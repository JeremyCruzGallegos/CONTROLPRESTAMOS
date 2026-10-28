package com.example.controlprestamos.data.google

import android.accounts.Account
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.example.controlprestamos.domain.today
import com.example.controlprestamos.notifications.CollectionNotifications
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import android.net.Network

internal object OfflineSync {
    private const val IMMEDIATE = 42100
    private const val PERIODIC = 42101
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val lock = ReentrantLock()
    private val foregroundQueued = AtomicBoolean(false)
    private val sessionLock = Any()
    private var sessionEpoch = 0
    private var networkCallbackInstalled = false
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var running = false
        private set
    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { listeners.forEach { it() } } }
    fun outbox(context: Context, subject: String, book: String) = PaymentOutbox(accountFile(context, subject, book, "outbox"), subject, book)
    fun operations(context: Context, subject: String, book: String) = LocalOperations(accountFile(context, subject, book, "operations"), subject, book)

    private fun capture(repository: SheetsRepository, local: OfflineBookStore, force: Set<String> = emptySet(), mutate: (() -> Unit) -> Unit): LocalBookView {
        val data = repository.preload(today().year, today()) { _, _ -> }
        val catalog = repository.searchLoans()
        mutate { local.saveCatalog(catalog); local.saveRows(data.todayPayments) }
        for (loan in catalog) {
            if (repository.isSimpleBook || loan.id in force || (loan.active && !local.calendarFresh(loan))) {
                val calendar = repository.calendar(loan, refresh = loan.id in force)
                mutate { local.saveCalendar(calendar, repository.offlineCalendarRows(loan, calendar)) }
            }
        }
        val cash = runCatching { repository.cash() }
        if (cash.isSuccess) mutate { local.saveCash(cash.getOrThrow()) }
        val savedCash = if (cash.isSuccess) local.cashSnapshot() else CashSnapshot(emptyList(), 0, false)
        val contributions = repository.loanContributions()
        mutate { local.saveContributions(catalog.associate { it.id to (contributions[it.id] ?: 0) }) }
        return LocalBookView(data, catalog, local.allRows(), savedCash.rows, local.contributions(), savedCash.actualPaymentDates, repository.cashMovements().also { values -> mutate { local.saveMovements(values) } },
            repository.cashEventTimes().also { values -> mutate { local.saveCashEventTimes(values) } })
    }
    fun repository(context: Context, subject: String, book: String, tokens: GoogleTokenProvider, guard: () -> Unit = {}): SheetsRepository {
        val fallback = accountFile(context, subject, book, "cache")
        val cache = if (SheetsIdentity.isSimpleBook(book)) SqliteSheetCache(context, subject, book, fallback) else fallback
        val guardedCache = object : SheetCache {
            override fun read() = cache.read()
            override fun write(value: org.json.JSONObject) = synchronized(sessionLock) { guard(); cache.write(value) }
            override fun delete() = synchronized(sessionLock) { guard(); cache.delete() }
        }
        return SheetsRepository(book, subject, GoogleHttp(tokens), guardedCache, GoogleConnection.load(context), tokens, SheetsIdentity.isSimpleBook(book))
    }
    @Synchronized private fun watchNetwork(context: Context) {
        if (networkCallbackInstalled) return
        networkCallbackInstalled = true
        val app = context.applicationContext
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var available = false
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !available) request(app)
                available = validated
            }
            override fun onLost(network: Network) { available = false; changed() }
        })
    }
    fun schedule(context: Context) {
        if (context.getSharedPreferences("google-session", 0).getString("subject", null) == null) return
        watchNetwork(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, SheetSyncJob::class.java)
        if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15)).build())
        CollectionNotifications.schedule(context)
    }
    fun request(context: Context) {
        val app = context.applicationContext
        if (app.getSharedPreferences("google-session", 0).getString("subject", null) == null) return
        schedule(app)
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(IMMEDIATE) == null) scheduler.schedule(JobInfo.Builder(IMMEDIATE, ComponentName(app, SheetSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        if (foregroundQueued.compareAndSet(false, true)) executor.schedule({
            try { sync(app) } finally { foregroundQueued.set(false) }
        }, 800, TimeUnit.MILLISECONDS)
    }
    fun connected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    /** Returns true for transient failures. All network access is off the main thread. */
    fun sync(context: Context): Boolean {
        if (!lock.tryLock()) return true
        try {
            val prefs = context.getSharedPreferences("google-session", 0)
            val subject = prefs.getString("subject", null) ?: return false
            val email = prefs.getString("email", null) ?: return false
            val connection = GoogleConnection.load(context)
            val copy = connection.effectiveCopy(prefs.getBoolean("copy", false))
            val epoch = synchronized(sessionLock) { sessionEpoch }
            fun active() = synchronized(sessionLock) { epoch == sessionEpoch && prefs.getString("subject", null) == subject && connection.effectiveCopy(prefs.getBoolean("copy", false)) == copy }
            fun ensureActive() { check(active()) { "La sesión cambió" } }
            fun localChange(action: () -> Unit) = synchronized(sessionLock) { ensureActive(); action() }
            val book = if (copy) connection.testBookId else connection.productionBookId
            val info = context.getSharedPreferences("sync-info", 0)
            val statusKey = remoteCacheKey(subject, book)
            val operations = operations(context, subject, book)
            if (!connected(context)) {
                if (operations.read().operations.isNotEmpty() || outbox(context, subject, book).read().changes.isNotEmpty())
                    SyncFailureNotifications.failure(context, subject, book)
                return true
            }
            running = true; changed()
            try {
                val scopes = connection.scopes(copy)
                val authorization = Tasks.await(Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
                    .setAccount(Account(email, "com.google")).setRequestedScopes(scopes.map(::Scope)).build()), 30, TimeUnit.SECONDS)
                check(!authorization.hasResolution() && authorization.grantedScopes.containsAll(scopes)) { "Abre la app y reconecta Google para continuar sincronizando." }
                val access = requireNotNull(authorization.accessToken)
                val tokens = GoogleTokenProvider { access }
                val user = GoogleHttp(tokens).get("https://www.googleapis.com/oauth2/v3/userinfo")
                check(user.optBoolean("email_verified") && user.getString("sub") == subject) { "La cuenta de Google cambió. Reconecta desde la app." }
                val repository = repository(context, subject, book, tokens, ::ensureActive)
                repository.cached(today().year)
                val local = OfflineBookStore(context, subject, book)
                // Older app versions already obtained device approval before persisting these requests.
                val legacyFile = accountFile(context, subject, book, "pending")
                legacyFile.read()?.let { pending ->
                    ensureActive()
                    repository.write(PendingRemoteWrite(pending.getString("id"), pending.getString("subject"), pending.getString("book"), pending.getString("payload")))
                    localChange { legacyFile.deleteIf("id", pending.getString("id")) }
                }
                val textFile = accountFile(context, subject, book, "loan-edit")
                textFile.read()?.let { pending ->
                    require(pending.getString("subject") == subject && pending.getString("book") == book)
                    repository.editLoanText(LoanTextEdit(pending.getString("loanId"), pending.getString("beforeClient"), pending.getString("beforeGuarantee"), pending.getString("client"), pending.getString("guarantee")))
                    localChange { textFile.delete() }
                }
                val queue = outbox(context, subject, book)
                var processed = 0
                while (queue.read().conflict == null && processed < 10) {
                    ensureActive()
                    val flight = synchronized(sessionLock) { ensureActive(); queue.begin(email, connection.usesDirectPayments(book)) } ?: break
                    try { repository.write(flight.pending, firstAttempt = false) }
                    catch (error: DirectPaymentRejected) {
                        localChange { queue.reject(flight, error.message ?: "La cuota cambió en Google. Revisa el cambio local.") }
                        changed(); break
                    }
                    localChange { queue.acknowledge(flight) }
                    val data = repository.preload(today().year, today()) { _, _ -> }
                    val catalog = repository.searchLoans()
                    localChange { local.saveCatalog(catalog); local.saveRows(data.todayPayments) }
                    val affected = flight.changes.values.map { it.first.loan.id }.toSet()
                    val fresh = catalog.filter { it.id in affected }.flatMap { loan ->
                        val calendar = repository.calendar(loan, refresh = true)
                        val rows = repository.offlineCalendarRows(loan, calendar)
                        localChange { local.saveCalendar(calendar, rows) }; rows
                    }
                    localChange {
                        local.confirmCash(requireNotNull(queue.read().flight), true, today())
                        queue.confirmed(flight, fresh)
                    }
                    info.edit().remove(statusKey).apply(); processed++; changed()
                }
                var commandCount = 0
                while (operations.read().operations.isNotEmpty() && operations.read().conflict == null && commandCount < 20) {
                    ensureActive()
                    val queued = operations.read()
                    val ids = queued.operations.flatMap { it.expectedLoans.map { loan -> queued.aliases[loan.id] ?: loan.id } +
                        it.expectedRows.map { row -> queued.aliases[row.loan.id] ?: row.loan.id } }.toSet()
                    val fresh = capture(repository, local, ids, ::localChange)
                    val pending = operations.prepare(fresh, repository) ?: break
                    val receipt = try {
                        val body = org.json.JSONObject(pending.payload)
                        if (body.getString("action") == "editLoanText") {
                            val before = queued.operations.first().expectedLoans.single()
                            repository.editLoanText(rebasedLoanTextEdit(before, fresh.loan(body.getString("loanId")), body.getString("client"), body.getString("guarantee")), operationId = pending.operationId)
                            org.json.JSONObject().put("operationId", pending.operationId).put("loanId", body.getString("loanId"))
                        } else repository.write(pending)
                    } catch (failure: Exception) {
                        if (failure is DirectPaymentRejected || failure is DirectLoanRejected || failure is LoanTermsRejected ||
                            failure is LoanDeletionRejected || failure is CapitalEditRejected ||
                            org.json.JSONObject(pending.payload).optString("action") == "editLoanText" && failure is IllegalArgumentException) {
                            localChange { operations.reject(failure.message ?: "La hoja cambió. Revisa tus datos locales.") }
                            changed(); break
                        }
                        throw failure
                    }
                    val confirmed = capture(repository, local, ids + receipt.optString("loanId"), ::localChange)
                    localChange { operations.confirmed(pending, receipt, confirmed) }
                    commandCount++; changed()
                }
                val data = repository.preload(today().year, today()) { _, _ -> }
                val catalog = repository.searchLoans()
                localChange { local.saveCatalog(catalog); local.saveRows(data.todayPayments) }; changed()
                // Download schedules for local detail, today's payments after midnight, and reminders.
                for (loan in catalog) {
                    if (queue.read().changes.isNotEmpty() && queue.read().conflict == null) break
                    if (local.calendarFresh(loan)) continue
                    val calendar = repository.calendar(loan)
                    localChange { local.saveCalendar(calendar, repository.offlineCalendarRows(loan, calendar)) }
                    changed()
                }
                if (queue.read().changes.isEmpty()) {
                    val cashReadAt = System.currentTimeMillis()
                    val cash = runCatching { repository.cash() }
                    if (cash.isSuccess) localChange { local.saveCash(cash.getOrThrow(), cashReadAt) }
                }
                val contributions = repository.loanContributions()
                localChange { local.saveContributions(catalog.associate { it.id to (contributions[it.id] ?: 0) }) }
                val cash = runCatching { repository.cash() }
                val snapshot = LocalBookView(data, catalog, local.allRows(), cash.getOrDefault(emptyList()), local.contributions(), cash.isSuccess && local.cashSnapshot().actualPaymentDates, repository.cashMovements().also { values -> localChange { local.saveMovements(values) } },
                    repository.cashEventTimes().also { values -> localChange { local.saveCashEventTimes(values) } })
                localChange { operations.refresh(snapshot) }
                // Presentation retries never reject or resend an acknowledged financial operation.
                // The periodic job also advances the calendar after midnight without a new payment.
                val presentationRetry = if (repository.isSimpleBook) runCatching {
                    ensureActive(); repository.syncBookPresentation()
                }.isFailure else false
                if (operations.read().conflict != null || queue.read().conflict != null) SyncFailureNotifications.failure(context, subject, book)
                else if (operations.read().operations.isEmpty() && queue.read().changes.isEmpty()) SyncFailureNotifications.recovered(context, subject, book)
                info.edit().remove(statusKey).apply(); changed()
                return presentationRetry || operations.read().operations.isNotEmpty() || queue.read().changes.isNotEmpty() && queue.read().conflict == null
            } catch (failure: Exception) {
                if (!active()) return false
                info.edit().putString(statusKey, if (failure is GoogleHttpException && failure.status in listOf(401, 403))
                    "Reconecta Google para continuar. Tus cambios siguen guardados en el teléfono."
                    else failure.message ?: "Sin conexión estable. Se reintentará automáticamente.").apply()
                SyncFailureNotifications.failure(context, subject, book)
                changed(); return true
            } finally { running = false; changed() }
        } finally { lock.unlock() }
    }
    fun cancel(context: Context) {
        synchronized(sessionLock) { sessionEpoch++ }
        context.getSystemService(JobScheduler::class.java).cancel(IMMEDIATE)
        context.getSystemService(JobScheduler::class.java).cancel(PERIODIC)
        CollectionNotifications.cancel(context)
    }
}

class SheetSyncJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute { val retry = OfflineSync.sync(applicationContext); jobFinished(params, retry) }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}

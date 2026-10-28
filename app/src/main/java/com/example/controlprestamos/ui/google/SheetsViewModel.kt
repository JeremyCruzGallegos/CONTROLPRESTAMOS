package com.example.controlprestamos.ui.google

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID
import com.example.controlprestamos.security.SensitiveActionGate
import com.example.controlprestamos.security.SensitiveRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.net.SocketTimeoutException

data class SheetsState(val exporting: Boolean = false, val data: SheetsPreload? = null, val cache: SheetsPreload? = null,
    val sensitiveRequest: SensitiveRequest? = null, val ready: Boolean = false, val syncing: Boolean = false, val busy: Boolean = false, val saving: Boolean = false, val progress: Float = 0.05f,
    val status: String = "Preparando conexión con Google…", val error: String? = null,
    val email: String? = null, val subject: String? = null, val copy: Boolean = false,
    val backgroundSyncing: Boolean = false, val cachedOnly: Boolean = false, val localAvailable: Boolean = false, val year: Int = today().year, val loans: List<SheetLoan> = emptyList(),
    val payments: List<SheetPayment> = emptyList(), val cash: List<BookCashDay> = emptyList(),
    val forecastPayments: List<SheetPayment> = emptyList(), val cashActualDates: Boolean = false,
    val cashMovements: List<CashMovement> = emptyList(), val cashEventTimes: Map<String, java.time.Instant> = emptyMap(), val loanContributions: Map<String, Long> = emptyMap(),
    val history: List<List<Any?>> = emptyList(), val edits: Map<String, Pair<SheetPayment, PaymentEdit>> = emptyMap(),
    val financialEdits: Map<String, Pair<SheetPayment, PaymentEdit>> = emptyMap(),
    val pending: PendingRemoteWrite? = null, val notice: String? = null, val authEpoch: Int = 0,
    val unconfirmedFees: Set<String> = emptySet(), val searchLoans: List<SheetLoan> = emptyList(),
    val searchLoaded: Boolean = false, val calendar: LoanCalendar? = null, val calendarRequestedId: String? = null,
    val calendarError: String? = null, val calendarRequestedKey: String? = null, val paymentFrom: LocalDate = today(), val paymentTo: LocalDate = today(),
    val paymentLoanId: String? = null, val cashLoaded: Boolean = false,
    val paymentServiceError: String? = null, val bookPresentationError: String? = null,
    val pendingPaymentIds: Set<String> = emptySet(),
    val autoPaymentCount: Int = 0, val syncMessage: String? = null, val paymentConflict: String? = null,
    val localOperationCount: Int = 0, val syncOverdue: Boolean = false,
    val pendingLoanEdit: PendingLoanTextEdit? = null, val completedOperationId: String? = null,
    val createdLoanId: String? = null, val deletedLoanId: String? = null)

class SheetsViewModel(application: Application) : AndroidViewModel(application) {
    val connection = GoogleConnection.load(application)
    private val prefs = application.getSharedPreferences("google-session", 0)
    private val mutable = MutableStateFlow(SheetsState(email = prefs.getString("email", null), subject = prefs.getString("subject", null), copy = connection.effectiveCopy(prefs.getBoolean("copy", false))))
    val state = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val disk = Executors.newSingleThreadExecutor()
    private val queries = Executors.newSingleThreadExecutor()
    private var localEpoch = 0
    private val pendingLock = Any()
    private val draftLock = Any()
    private val autoRetries = mutableMapOf<String, Int>()
    private val protection = SensitiveActionGate()
    private val approvedOperations = ConcurrentHashMap.newKeySet<String>()
    private fun protectionOwner() = "$generation|${mutable.value.subject}|$bookId"
    private fun protect(id: String = UUID.randomUUID().toString(), purpose: String, action: () -> Unit) {
        val request = protection.request(id, purpose, protectionOwner(), action)
        mutable.value = mutable.value.copy(sensitiveRequest = request, error = null)
    }
    fun deviceAuthenticationFinished(id: String, authenticated: Boolean, error: String?) {
        if (mutable.value.sensitiveRequest?.id != id) return
        mutable.value = mutable.value.copy(sensitiveRequest = null)
        runCatching { protection.resolve(id, protectionOwner(), authenticated) }.fold({ accepted ->
            if (!accepted) mutable.value = mutable.value.copy(notice = error ?: "La verificación venció. Vuelve a confirmar el cambio.")
        }, { mutable.value = mutable.value.copy(error = it.message ?: "No se pudo iniciar el cambio") })
    }
    private var token: String? = null
    private var repo: SheetsRepository? = null
    private var requestedAuthorizationEmail: String? = null
    private var authInFlight = false
    private var generation = 0
    private var calendarRequest = 0
    private val tokens = GoogleTokenProvider { token ?: error("Conecta Google para actualizar") }
    val bookId: String get() = if (mutable.value.copy) connection.testBookId else connection.productionBookId
    /** Export reads disk only; it never refreshes or downloads the Google book. */
    fun exportLocalExcel(uri: android.net.Uri) {
        val subject=mutable.value.subject ?: return
        val book=bookId
        val ownerGeneration=generation
        mutable.value=mutable.value.copy(exporting=true,error=null)
        disk.execute {
            val result=runCatching {
                val app=getApplication<Application>()
                val journal=OfflineSync.operations(app,subject,book).read()
                val local=OfflineBookStore(app,subject,book)
                val cache=snapshotStore(subject,book).read()
                val data=journal.view?.data ?: newRepo(book,subject).cached(today().year)
                    ?: error("No hay información local guardada para exportar")
                val view=journal.view ?: LocalBookView(data,(local.loans()+data.loans).distinctBy{it.id},
                    (local.allRows()+data.todayPayments).distinctBy{it.id},local.cash(),local.contributions(),
                    local.cashSnapshot().actualPaymentDates,local.movements(),local.cashEventTimes())
                val queue=OfflineSync.outbox(app,subject,book).read()
                val changes=financialPaymentChanges(queue,view.data.syncedAt.toEpochMilli(),today())
                val saved=linkedMapOf<String,String>()
                val prefix="sheets-${remoteCacheKey(subject,book)}-"
                app.filesDir.listFiles().orEmpty().filter{it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json")}
                    .sortedBy{it.name}.forEach{saved[it.name]=it.readText()}
                cache?.let{saved["snapshot-local.json"]=it.toString()}
                saved["vista-local.json"]=view.json(subject,book).toString()
                val sheets=localExcelReport(view,changes,saved)
                // Build completely before opening the destination so generation errors cannot leave a partial workbook.
                val temporary=File.createTempFile("respaldo-", ".xlsx",app.cacheDir)
                try {
                    temporary.outputStream().use{com.example.controlprestamos.data.ExcelBackup.write(it,sheets)}
                    require(generation==ownerGeneration && mutable.value.subject==subject && bookId==book){"La cuenta cambió; vuelve a exportar"}
                    requireNotNull(app.contentResolver.openOutputStream(uri,"wt")){"No se pudo abrir el archivo seleccionado"}.use{output->temporary.inputStream().use{it.copyTo(output)}}
                } finally {temporary.delete()}
            }
            main.post {
                if(generation==ownerGeneration && mutable.value.subject==subject && bookId==book) {
                    mutable.value=result.fold({mutable.value.copy(exporting=false,notice="Respaldo Excel guardado con los datos locales del teléfono.")},
                        {mutable.value.copy(exporting=false,error="No se pudo exportar el respaldo: ${it.message}")})
                }else mutable.value=mutable.value.copy(exporting=false)
            }
        }
    }
    fun scopes() = connection.scopes(mutable.value.copy)
    private fun file(subject: String, book: String, suffix: String = "cache") = RemoteJsonFile(File(getApplication<Application>().filesDir,
        "sheets-${remoteCacheKey(subject, book)}-$suffix.json"))
    private val offlineListener: () -> Unit = { reloadOffline() }
    private val automaticSync = object : Runnable {
        override fun run() {
            if (mutable.value.subject != null) OfflineSync.request(getApplication())
            main.postDelayed(this, 60_000)
        }
    }
    init {
        OfflineSync.listeners.add(offlineListener)
        loadCachedAccount()
        main.postDelayed(automaticSync, 5_000)
    }
    private data class OfflineView(val queue: PaymentQueue, val data: SheetsPreload?, val catalog: List<SheetLoan>, val catalogLoaded: Boolean,
        val rows: List<SheetPayment>, val calendar: LoanCalendar?, val cash: List<BookCashDay>, val forecastPayments: List<SheetPayment>, val cashActualDates: Boolean, val message: String?, val financialEdits: PaymentChanges,
        val operationCount: Int, val operationConflict: String?, val overdue: Boolean, val legacyPending: Boolean, val legacyText: Boolean, val movements: List<CashMovement>, val contributions: Map<String, Long>, val cashEventTimes: Map<String, java.time.Instant>)
    private fun reloadOffline() {
        val subject = mutable.value.subject ?: return
        val book = bookId; val run = generation; val scope = mutable.value
        disk.execute {
            val result = runCatching {
                val localRepo = newRepo(book, subject)
                val journal = OfflineSync.operations(getApplication(), subject, book).read()
                val view = journal.view
                val cached = view?.data ?: localRepo.cached(today().year)
                val local = OfflineBookStore(getApplication<Application>(), subject, book)
                val catalog = view?.catalog ?: (cached?.loans.orEmpty() + local.loans()).distinctBy { it.id }
                val todayRows = view?.rows?.filter { it.due == today() } ?: (local.payments(today(), today()) + cached?.todayPayments.orEmpty()).distinctBy { it.id }
                val rows = if (scope.paymentFrom == today() && scope.paymentTo == today() && scope.paymentLoanId == null) todayRows
                    else view?.rows?.filter { it.due in scope.paymentFrom..scope.paymentTo && it.due <= today() && (scope.paymentLoanId == null || it.loan.id == scope.paymentLoanId) }
                        ?: local.payments(scope.paymentFrom, scope.paymentTo, scope.paymentLoanId)
                val queue = OfflineSync.outbox(getApplication(), subject, book).read()
                val cash = view?.let { CashSnapshot(it.cash, it.data.syncedAt.toEpochMilli(), it.actualDates) } ?: local.cashSnapshot()
                OfflineView(queue, cached?.copy(todayPayments = todayRows), catalog, view != null || local.hasCatalog(), rows,
                    scope.calendarRequestedId?.let { id -> catalog.firstOrNull { it.id == id }?.let { view?.calendar(it.id) ?: local.calendar(it) } },
                    localCashRows(cash.rows, financialPaymentChanges(queue, cash.syncedAtMillis, today()),
                        cash.actualPaymentDates, today()).let { if (SheetsIdentity.isSimpleBook(book)) reconcileLegacyFunding(it) else it },
                    view?.rows?.filter { it.due <= today() } ?: local.payments(SIMPLE_START, today()), cash.actualPaymentDates,
                    if (!OfflineSync.connected(getApplication())) "Sin conexión estable. Se reintentará automáticamente."
                    else getApplication<Application>().getSharedPreferences("sync-info", 0).getString(remoteCacheKey(subject, book), null),
                    financialPaymentChanges(queue, cached?.syncedAt?.toEpochMilli() ?: 0, today()), journal.operations.size, journal.conflict,
                    SyncFailureNotifications.overdue(getApplication(), subject, book), file(subject, book, "pending").read() != null,
                    file(subject, book, "loan-edit").read() != null, view?.movements ?: local.movements(), view?.contributions ?: local.contributions(), view?.cashEventTimes ?: local.cashEventTimes())
            }
            main.post { if (run == generation && subject == mutable.value.subject && book == bookId) result.fold({ view ->
                val current = mutable.value
                val base = view.data ?: current.data
                mutable.value = current.copy(data = base, cache = base, ready = base != null || current.ready,
                    backgroundSyncing = OfflineSync.running, localAvailable = base != null, edits = view.queue.changes, financialEdits = view.financialEdits, autoPaymentCount = view.queue.changes.size,
                    paymentConflict = if (view.overdue) view.queue.conflict ?: view.operationConflict else null, syncMessage = if (view.overdue) view.message else null,
                    localOperationCount = view.operationCount, syncOverdue = view.overdue,
                    pending = if (!view.legacyPending) null else current.pending,
                    pendingLoanEdit = if (!view.legacyText) null else current.pendingLoanEdit,
                    loans = if (view.catalogLoaded || view.catalog.isNotEmpty()) view.catalog.filter { it.year == current.year } else base?.loans ?: current.loans,
                    searchLoans = if (view.catalogLoaded) view.catalog else view.catalog.ifEmpty { current.searchLoans },
                    searchLoaded = view.catalogLoaded || current.searchLoaded,
                    payments = if (current.paymentFrom == scope.paymentFrom && current.paymentTo == scope.paymentTo && current.paymentLoanId == scope.paymentLoanId)
                        view.rows else current.payments,
                    calendar = if (current.calendarRequestedId == scope.calendarRequestedId) view.calendar ?: current.calendar else current.calendar,
                    forecastPayments = view.forecastPayments, cashActualDates = view.cashActualDates, cashMovements = view.movements, loanContributions = view.contributions, cashEventTimes = view.cashEventTimes,
                    cash = view.cash, cashLoaded = base != null || current.cashLoaded)
            }, { mutable.value = mutable.value.copy(error = "No se pudo leer el almacenamiento local: ${it.message}") }) }
        }
    }
    fun discardPaymentConflict() {
        val subject = mutable.value.subject ?: return
        val book = bookId
        disk.execute {
            runCatching {
                val commands = OfflineSync.operations(getApplication(), subject, book)
                if (commands.read().conflict != null) commands.discardRejected()
                else OfflineSync.outbox(getApplication(), subject, book).discardRejected()
            }
                .onSuccess { OfflineSync.changed(); OfflineSync.request(getApplication()) }
                .onFailure { main.post { mutable.value = mutable.value.copy(error = it.message) } }
        }
    }
    private fun loadCachedAccount() {
        val subject = mutable.value.subject ?: return
        val book = bookId; val run = generation
        disk.execute {
            val result = runCatching {
                val repository = newRepo(book, subject)
                val cached = repository.cached(today().year)
                CachedSession(OfflineSync.operations(getApplication(), subject, book).read().view?.data ?: cached,
                    readPending(subject, book), readDraft(subject, book), readLoanEdit(subject, book), repository)
            }
            main.post { if (generation == run && mutable.value.subject == subject && bookId == book && (mutable.value.data == null || mutable.value.cachedOnly)) result.fold({ (cached, pending, edits, loanEdit, repository) ->
                val local = cached != null
                if (local) { repo = repository; localEpoch++ }
                mutable.value = mutable.value.copy(cache = cached, pending = pending, pendingPaymentIds = paymentIds(pending), edits = edits, pendingLoanEdit = loanEdit,
                    data = if (local) cached else mutable.value.data, ready = local || mutable.value.ready,
                    localAvailable = local, cachedOnly = local || mutable.value.cachedOnly, busy = if (local) false else mutable.value.busy,
                    syncing = local && (authInFlight || mutable.value.busy || mutable.value.syncing), loans = cached?.loans ?: mutable.value.loans,
                    payments = cached?.todayPayments ?: mutable.value.payments)
                reloadOffline()
            }, {
                mutable.value = mutable.value.copy(error = "La caché guardada no pudo abrirse. Actualiza desde Google.")
            }) }
        }
    }
    private fun snapshotStore(subject: String, book: String): SheetCache = if (SheetsIdentity.isSimpleBook(book))
        SqliteSheetCache(getApplication(), subject, book, file(subject, book)) else file(subject, book)
    private fun newRepo(book: String, subject: String) = SheetsRepository(book, subject, GoogleHttp(tokens), snapshotStore(subject, book), connection, tokens, isSimpleBook = SheetsIdentity.isSimpleBook(book))
    private data class CachedSession(val cached: SheetsPreload?, val pending: PendingRemoteWrite?,
        val edits: Map<String, Pair<SheetPayment, PaymentEdit>>, val loanEdit: PendingLoanTextEdit?, val repository: SheetsRepository)
    fun beginAuthorization(): Boolean {
        if (authInFlight || mutable.value.busy || mutable.value.saving || mutable.value.syncing || mutable.value.sensitiveRequest != null) return false
        authInFlight = true
        mutable.value = mutable.value.copy(busy = mutable.value.data == null, syncing = mutable.value.data != null, cachedOnly = mutable.value.data != null, status = "Actualizando desde Google…", progress = 0.1f, error = null)
        return true
    }
    fun authorizationFailed(message: String) {
        authInFlight = false; token = null
        mutable.value = mutable.value.copy(busy = false, syncing = false, cachedOnly = mutable.value.data != null,
            error = if (mutable.value.data == null || mutable.value.syncOverdue) message else null, status = "No se pudo conectar Google")
    }
    fun retryAuthorization() { if (!mutable.value.busy && !mutable.value.syncing) mutable.value = mutable.value.copy(authEpoch = mutable.value.authEpoch + 1) }
    fun authorizationEmail(): String? = requestedAuthorizationEmail ?: mutable.value.email
    fun requestAccountChange(email: String): Boolean {
        val current = mutable.value
        if (email.isBlank() || current.sensitiveRequest != null || authInFlight || current.busy || current.syncing || current.saving || current.pending != null ||
            current.pendingLoanEdit != null || current.edits.isNotEmpty() || current.localOperationCount > 0) return false
        generation++; token = null; repo = null
        requestedAuthorizationEmail = email
        mutable.value = current.copy(cachedOnly = true, authEpoch = current.authEpoch + 1, error = null, notice = null)
        return true
    }
    private data class Loaded(val repository: SheetsRepository, val data: SheetsPreload, val subject: String, val email: String, val pending: PendingRemoteWrite?,
        val edits: Map<String, Pair<SheetPayment, PaymentEdit>>, val loanEdit: PendingLoanTextEdit?)
    fun authorized(accessToken: String) {
        authInFlight = false; token = accessToken
        val book = bookId; val run = generation
        executor.execute {
            val result = runCatching {
                val user = GoogleHttp(tokens).get("https://www.googleapis.com/oauth2/v3/userinfo")
                require(user.optBoolean("email_verified")) { "Google no confirmó el correo de la cuenta" }
                val subject = user.getString("sub"); val email = user.getString("email")
                requestedAuthorizationEmail?.let { requested -> require(email.equals(requested, ignoreCase = true)) {
                    "Google autorizó una cuenta distinta. Vuelve a elegir la cuenta"
                } }
                val repository = newRepo(book, subject)
                val cachedRemote = runCatching { repository.cached(today().year) }.getOrNull()
                val cached = OfflineSync.operations(getApplication(), subject, book).read().view?.data ?: cachedRemote
                main.post { if (generation == run) {
                    if (mutable.value.subject != subject) {
                        repo = null
                        mutable.value = SheetsState(copy = mutable.value.copy, busy = true, authEpoch = mutable.value.authEpoch)
                    }
                    prefs.edit().putString("subject", subject).putString("email", email).apply()
                    mutable.value = mutable.value.copy(subject = subject, email = email, cache = cached)
                } }
                val preload = cached ?: repository.preload(today().year, today()) { progress, message ->
                    main.post { if (generation == run) mutable.value = mutable.value.copy(progress = progress, status = message) }
                }
                Loaded(repository, preload, subject, email, readPending(subject, book), readDraft(subject, book), readLoanEdit(subject, book))
            }
            main.post { if (generation == run) result.fold({ (repository, data, subject, email, pending, edits, loanEdit) ->
                repo = repository; localEpoch++
                requestedAuthorizationEmail = null
                prefs.edit().putString("subject", subject).putString("email", email).apply()
                mutable.value = mutable.value.copy(data = data, cache = data, ready = true, localAvailable = true, cachedOnly = false,
                    busy = false, syncing = false, saving = false, progress = 1f, status = "Información actualizada", error = null, year = today().year,
                    loans = data.loans, payments = data.todayPayments, paymentServiceError = data.paymentServiceError, bookPresentationError = null, cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false, history = emptyList(), pending = pending, pendingPaymentIds = paymentIds(pending), edits = edits, pendingLoanEdit = loanEdit,
                    searchLoans = emptyList(), searchLoaded = false, calendar = null, calendarRequestedId = null, calendarRequestedKey = null, calendarError = null,
                    paymentFrom = today(), paymentTo = today(), paymentLoanId = null,
                    notice = null)
                if (pending != null) scheduleRetry(pending)
                // Migrate old saved drafts into the automatic outbox before allowing further taps.
                disk.execute {
                    val local = OfflineBookStore(getApplication<Application>(), subject, book)
                    if (local.payments(today(), today()).isEmpty()) local.saveRows(data.todayPayments)
                    if (local.loans().isEmpty()) local.saveCatalog(data.loans)
                    if (edits.isNotEmpty() && pending == null) {
                        OfflineSync.outbox(getApplication(), subject, book).importDraft(edits)
                        synchronized(draftLock) { file(subject, book, "draft").delete() }
                    }
                    OfflineSync.changed(); OfflineSync.request(getApplication())
                }
            }, { mutable.value = mutable.value.copy(busy = false, syncing = false, cachedOnly = mutable.value.data != null,
                error = if (mutable.value.data == null || mutable.value.syncOverdue) it.message ?: "No se pudo actualizar desde Google" else null, status = "No se completó la carga") }) }
        }
    }
    private fun readPending(subject: String, book: String): PendingRemoteWrite? {
        val obj = synchronized(pendingLock) { file(subject, book, "pending").read() } ?: return null
        return PendingRemoteWrite(obj.getString("id"), obj.getString("subject"), obj.getString("book"), obj.getString("payload")).also { it.requireOwner(subject, book) }
    }
    private fun persistPending(pending: PendingRemoteWrite) = synchronized(pendingLock) {
        file(pending.subject, pending.bookId, "pending").write(JSONObject().put("id", pending.operationId)
            .put("subject", pending.subject).put("book", pending.bookId).put("payload", pending.payload))
    }
    private fun clearPending(pending: PendingRemoteWrite) = synchronized(pendingLock) {
        val outbox = file(pending.subject, pending.bookId, "pending")
        outbox.deleteIf("id", pending.operationId)
    }
    private fun versionPaymentId(version: JSONObject): String = if (version.optString("transport") == "sheets")
        "${version.getString("loanId")}:${version.getString("due")}" else {
            val year = version.getString("anio").toInt()
            "${SheetsIdentity.canonical(year, version.getString("id"))}:${sheetDate(version.get("dia"))}"
        }
    private fun paymentIds(pending: PendingRemoteWrite?): Set<String> = pending?.let {
        val array = JSONObject(it.payload).optJSONArray("edits") ?: return@let emptySet()
        (0 until array.length()).map { index ->
            versionPaymentId(array.getJSONObject(index).getJSONObject("version"))
        }.toSet()
    }.orEmpty()
    private fun persistDraft(edits: Map<String, Pair<SheetPayment, PaymentEdit>>) {
        val subject = mutable.value.subject ?: return
        val book = bookId; val run = generation
        val packet = draftPacket(subject, book, edits)
        disk.execute {
            val result = runCatching { synchronized(draftLock) {
                if (edits.isEmpty()) file(subject, book, "draft").delete() else file(subject, book, "draft").write(packet)
            } }
            main.post { if (run == generation && result.isFailure) mutable.value = mutable.value.copy(error = "No se pudo conservar el borrador: ${result.exceptionOrNull()?.message}") }
        }
    }
    private fun persistConfirmedDraft(edits: Map<String, Pair<SheetPayment, PaymentEdit>>, pending: PendingRemoteWrite) {
        val packet = draftPacket(pending.subject, pending.bookId, edits)
        val run = generation
        disk.execute {
            val result = runCatching {
                synchronized(draftLock) {
                    val draft = file(pending.subject, pending.bookId, "draft")
                    if (edits.isEmpty()) draft.delete() else draft.write(packet)
                }
                clearPending(pending)
            }
            main.post { if (run == generation && result.isFailure) mutable.value = mutable.value.copy(error = "El pago se guardó en Google, pero falta actualizar el registro local. Reconecta para comprobarlo.") }
        }
    }
    fun dismissMessages() { mutable.value = mutable.value.copy(error = null, notice = null) }
    fun dismissError(message: String) { if (mutable.value.error == message) mutable.value = mutable.value.copy(error = null) }
    fun dismissNotice(message: String) { if (mutable.value.notice == message) mutable.value = mutable.value.copy(notice = null) }
    private fun failureMessage(failure: Throwable): String = if (failure is SocketTimeoutException)
        if (mutable.value.pending?.payload?.let { JSONObject(it).optString("action") == "deleteLoan" } == true)
            "Google tardó en confirmar la eliminación. Comprueba el mismo envío; no se repetirá una eliminación ya confirmada."
        else if (mutable.value.pending?.payload?.let { JSONObject(it).optString("action") == "setCapital" } == true)
            "Google tardó en confirmar el capital. Comprueba el mismo envío antes de hacer otro cambio."
        else if (mutable.value.pending?.payload?.let { JSONObject(it).optString("action") == "editLoanTerms" } == true)
            "Google tardó en confirmar el acuerdo. Comprueba el mismo envío antes de preparar pagos."
        else if (mutable.value.pending != null) "Google tardó en responder. Puedes seguir navegando y preparar otros pagos; el envío pendiente se comprobará sin duplicarlo."
        else "Google tardó en actualizar la consulta. Puedes reintentar sin perder tus cambios preparados."
        else failure.message ?: "No se pudo completar la operación"
    private fun scheduleRetry(pending: PendingRemoteWrite) {
        if (pending.operationId !in approvedOperations) return
        val attempts = autoRetries[pending.operationId] ?: 0
        autoRetries[pending.operationId] = attempts + 1
        val run = generation
        main.postDelayed({
            if (run == generation && mutable.value.pending?.operationId == pending.operationId && !mutable.value.cachedOnly) {
                if (!mutable.value.busy && !mutable.value.saving) retryWrite()
                else { autoRetries[pending.operationId] = attempts; scheduleRetry(pending) }
            }
        }, (2500L * (1L shl attempts.coerceAtMost(7))).coerceAtMost(300_000L))
    }
    private fun readLoanEdit(subject: String, book: String): PendingLoanTextEdit? {
        val obj = file(subject, book, "loan-edit").read() ?: return null
        require(obj.getInt("schema") == 1)
        return PendingLoanTextEdit(obj.getString("subject"), obj.getString("book"), LoanTextEdit(
            obj.getString("loanId"), obj.getString("beforeClient"), obj.getString("beforeGuarantee"), obj.getString("client"), obj.getString("guarantee")))
            .also { it.requireOwner(subject, book) }
    }
    fun editLoan(loan: SheetLoan, client: String, guarantee: String) {
        LoanTextEdit(loan.id, loan.client, loan.guarantee, client.trim(), guarantee.trim())
        enqueueLocal(JSONObject().put("action", "editLoanText").put("loanId", loan.id)
            .put("client", client.trim()).put("guarantee", guarantee.trim()), true)
    }
    internal fun groupLoans(ids: List<String>, name: String): String {
        val current = mutable.value
        require(current.ready && !current.saving) { "Espera a que termine el guardado actual" }
        val catalog = current.searchLoans + current.loans + current.data?.loans.orEmpty()
        val plan = planLoanGrouping(catalog, ids, name, today())
        return requireNotNull(enqueueLocal(JSONObject().put("action", "groupLoans")
            .put("loanIds", JSONArray(ids)).put("client", plan.client), true)) {
            "No se pudo guardar la agrupación en este teléfono"
        }
    }
    fun retryLoanEdit() {
        val pending = mutable.value.pendingLoanEdit ?: return
        pending.requireOwner(mutable.value.subject ?: return, bookId)
        if (mutable.value.edits.isNotEmpty() || mutable.value.pending != null) return
        protect(purpose = "Confirmar la edición del préstamo ${pending.edit.loanId}") {
            operation { finishLoanEdit(it, pending) }
        }
    }
    private fun finishLoanEdit(repository: SheetsRepository, pending: PendingLoanTextEdit) {
        pending.requireOwner(mutable.value.subject ?: error("Conecta Google"), bookId)
        repository.editLoanText(pending.edit)
        reloadAfterLoanEdit(repository, pending, "Cambios guardados y verificados en Google Sheets")
    }
    fun reviewLoanEdit() {
        val pending = mutable.value.pendingLoanEdit ?: return
        pending.requireOwner(mutable.value.subject ?: return, bookId)
        operation {
            if (it.isSimpleBook) it.preload(today().year, today()) { _, _ -> }
            reloadAfterLoanEdit(it, pending, "Consulta actualizada. Revisa los datos actuales del préstamo")
        }
    }
    private fun reloadAfterLoanEdit(repository: SheetsRepository, pending: PendingLoanTextEdit, notice: String) {
        val data = if (repository.isSimpleBook) repository.refreshAfterPayments(emptyList()) else repository.preload(today().year, today()) { _, _ -> }
        val all = repository.searchLoans()
        file(pending.subject, pending.bookId, "loan-edit").delete()
        main.post { mutable.value = mutable.value.copy(data = data, cache = data, loans = data.loans, year = today().year,
            payments = data.todayPayments, paymentFrom = today(), paymentTo = today(), paymentLoanId = null,
            searchLoans = all, searchLoaded = true, calendar = null, calendarRequestedId = null, calendarRequestedKey = null, calendarError = null,
            cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false, history = emptyList(), pendingLoanEdit = null, notice = notice) }
    }
    private fun readDraft(subject: String, book: String): Map<String, Pair<SheetPayment, PaymentEdit>> {
        val packet = synchronized(draftLock) { file(subject, book, "draft").read() } ?: return emptyMap()
        return decodeDraft(packet, subject, book)
    }
    fun useCache() {
        val cached = mutable.value.cache ?: return
        mutable.value = mutable.value.copy(data = cached, ready = true, cachedOnly = true, busy = false,
            loans = cached.loans, payments = cached.todayPayments, notice = null)
    }
    fun switchBook(copy: Boolean) {
        if (copy && connection.clientInstallation) return
        if (mutable.value.sensitiveRequest != null || mutable.value.busy || mutable.value.saving || mutable.value.pending != null || mutable.value.pendingLoanEdit != null || mutable.value.edits.isNotEmpty() || mutable.value.localOperationCount > 0) return
        generation++; token = null; repo = null; authInFlight = false
        OfflineSync.cancel(getApplication())
        prefs.edit().putBoolean("copy", copy).apply()
        mutable.value = SheetsState(email = mutable.value.email, subject = mutable.value.subject, copy = copy, authEpoch = mutable.value.authEpoch + 1)
        loadCachedAccount()
    }
    private fun operation(write: Boolean = false, action: (SheetsRepository) -> Unit) {
        if (mutable.value.busy || mutable.value.saving) return
        val repository = repo
        if (repository == null || mutable.value.cachedOnly) { mutable.value = mutable.value.copy(error = "Conecta Google para consultar información actualizada"); return }
        val run = generation
        if (repository.isSimpleBook) localEpoch++
        mutable.value = mutable.value.copy(busy = !write, saving = write, error = null,
            status = if (write) "Guardando cambios en Google Sheets…" else "Actualizando la consulta…")
        executor.execute {
            val result = runCatching { action(repository) }
            main.post { if (generation == run) {
                mutable.value = if (write) mutable.value.copy(saving = false) else mutable.value.copy(busy = false)
                result.exceptionOrNull()?.let { failure ->
                    val networkFailure = failure is java.io.IOException || failure is GoogleHttpException
                    mutable.value = mutable.value.copy(error = if (networkFailure && mutable.value.data != null && !mutable.value.syncOverdue) null else failureMessage(failure))
                    if (write) mutable.value.pending?.let(::scheduleRetry)
                }
            } }
        }
    }
    /** Local queries never queue behind a slow HTTP request. Ignore results from an old account/snapshot. */
    private fun <T> readLocal(query: (SheetsRepository) -> T, accept: (T) -> Unit): Boolean {
        val repository = repo?.takeIf { it.isSimpleBook } ?: return false
        if (mutable.value.saving || mutable.value.busy) return true
        val run = generation; val epoch = localEpoch
        queries.execute {
            val result = runCatching { query(repository) }
            main.post {
                if (generation != run || repo !== repository || localEpoch != epoch) return@post
                result.fold(accept) { mutable.value = mutable.value.copy(error = it.message ?: "No se pudo consultar la base local") }
            }
        }
        return true
    }
    fun year(year: Int) {
        val subject = mutable.value.subject ?: return
        val book = bookId; val run = generation
        queries.execute {
            val rows = (OfflineSync.operations(getApplication(), subject, book).read().view?.catalog
                ?: OfflineBookStore(getApplication<Application>(), subject, book).loans()).filter { it.year == year }
            main.post { if (run == generation) mutable.value = mutable.value.copy(year = year, loans = rows) }
        }
    }
    fun searchAll() { reloadOffline() }
    fun calendar(loan: SheetLoan, refresh: Boolean = false) {
        val subject = mutable.value.subject ?: return
        val book = bookId; val run = generation
        val selected = refreshedLoanSelection(loan, mutable.value.searchLoans + mutable.value.loans)
        mutable.value = mutable.value.copy(calendarRequestedId = selected.id, calendarRequestedKey = paymentTerms(selected), calendarError = null)
        queries.execute {
            val local = OfflineSync.operations(getApplication(), subject, book)
            val calendar = local.read().view?.let { view -> runCatching { view.calendar(local.resolveId(selected.id)) }.getOrNull() }
                ?: OfflineBookStore(getApplication<Application>(), subject, book).calendar(selected)
            main.post { if (run == generation && mutable.value.calendarRequestedId == selected.id) mutable.value = mutable.value.copy(
                calendar = calendar, calendarError = if (calendar == null) "El calendario aún no se descargó. Se completará al conectar." else null) }
        }
        if (refresh) OfflineSync.request(getApplication())
    }
    fun payments(from: LocalDate, to: LocalDate, loanId: String? = null) {
        mutable.value = mutable.value.copy(paymentFrom = from, paymentTo = to, paymentLoanId = loanId, payments = emptyList())
        reloadOffline()
    }
    private fun synchronizePresentation(repository: SheetsRepository) {
        if (repository.isSimpleBook) return
        val run = generation
        executor.execute {
            if (generation != run) return@execute
            val error = runCatching { repository.syncBookPresentation() }.exceptionOrNull()?.message
            main.post { if (generation == run) mutable.value = mutable.value.copy(bookPresentationError = error) }
        }
    }
    fun updateBookPresentation() = operation { repository ->
        val error = runCatching { repository.syncBookPresentation() }.exceptionOrNull()?.message
        main.post { mutable.value = mutable.value.copy(bookPresentationError = error) }
    }
    fun refreshHome() {
        if (mutable.value.subject == null) { retryAuthorization(); return }
        OfflineSync.request(getApplication())
        reloadOffline()
    }
    fun charts() { mutable.value = mutable.value.copy(cashLoaded = true); reloadOffline() }
    fun history() = operation { repository ->
        val rows = repository.history(mutable.value.history.size)
        main.post { mutable.value = mutable.value.copy(history = mutable.value.history + rows) }
    }
    fun stage(row: SheetPayment, unpaid: Boolean, late: Long, paidOn: LocalDate? = row.paidOn) {
        if (!connection.canWrite(bookId) || row.version.isNullOrBlank()) return
        val date = if (unpaid) null else paidOn ?: today()
        validateRemotePaymentEdit(row, unpaid, late, today(), date)
        val edit = JSONObject().put("version", JSONObject(row.version)).put("unpaid", unpaid)
            .put("collectedLate", late / 100.0).put("paidOn", date?.toString() ?: "")
        enqueueLocal(JSONObject().put("action", "pay").put("edits", JSONArray().put(edit)), false)
    }
    fun stageAll(rows: List<SheetPayment>, unpaid: Boolean, paidOn: LocalDate? = null) {
        val changes = stageBulkPayments(emptyMap(), rows, unpaid, today(), paidOn)
        if (changes.isEmpty()) return
        val edits = changes.values.map { (row, change) ->
            JSONObject().put("version", JSONObject(requireNotNull(row.version))).put("unpaid", change.unpaid)
                .put("collectedLate", change.collectedLate / 100.0).put("paidOn", change.paidOn?.toString() ?: "")
        }
        enqueueLocal(JSONObject().put("action", "pay").put("edits", JSONArray(edits)), false)
    }
    internal fun amountPayment(loan: SheetLoan, amount: Long, waiveLate: Boolean) {
        require(!mutable.value.saving && connection.canWrite(bookId)) { "Espera a que se guarde el cambio anterior" }
        val calendar=mutable.value.calendar?.takeIf { it.loanId==loan.id } ?: error("Carga el calendario del préstamo")
        val plan=planAmountPayment(recordForAmount(loan,calendar,today()),amount,today(),waiveLate)
        requireNotNull(enqueueLocal(JSONObject().put("action","amountPayment").put("loanId",loan.id).put("year",loan.year)
            .put("version",loanDeletionVersion(loan)).put("amountCents",amount).put("waiveLate",waiveLate)
            .put("paidOn",today().toString())
            .put("confirmation",plan.confirmation()),false)) { "No se pudo guardar el monto en el teléfono" }
    }
    fun feeChanged(id: String, changed: Boolean) {
        mutable.value = mutable.value.copy(unconfirmedFees = if (changed) mutable.value.unconfirmedFees + id else mutable.value.unconfirmedFees - id)
    }
    fun discard() {
        val retained = mutable.value.edits.filterKeys { it in mutable.value.pendingPaymentIds }
        mutable.value = mutable.value.copy(edits = retained, unconfirmedFees = emptySet(), error = null)
        persistDraft(retained)
    }
    fun create(request: LoanRequest, disbursedOn: LocalDate = today(), pastPaymentsPaid: Boolean = false): String {
        require(connection.canCreateLoan(bookId)) { "No está configurado el registro de préstamos para este libro" }
        require(!mutable.value.saving) { "Espera a que termine la operación actual" }
        val data = mutable.value.data ?: error("Actualiza la caja antes de registrar un préstamo")
        previewDatedLoan(request, disbursedOn, today())
        require(!pastPaymentsPaid || connection.usesDirectPayments(bookId)) { "El registro de pagos iniciales requiere el guardado directo" }
        val existingClient = (mutable.value.searchLoans + mutable.value.loans).firstOrNull {
            normalizedClient(it.client) == normalizedClient(request.client)
        }?.client ?: request.client.trim()
        val loan = JSONObject().put("client", existingClient).put("principal", request.principal / 100.0)
            .put("rate", request.ratePercent.movePointLeft(2)).put("months", request.months).put("guarantee", request.guarantee)
            .put("origin", if (request.funding == Funding.EXTERNAL) "Capital nuevo" else "Del capital").put("contribution", request.contribution / 100.0)
        val available = cashForNewLoan(mutable.value.cash, disbursedOn, data.summary.cash)
        val body = JSONObject().put("action", "createLoan").put("loan", loan).put("disbursedOn", disbursedOn.toString()).put("pastPaymentsPaid", pastPaymentsPaid)
            .put("maxContributionCents", Rules.fundLoan(request, available).contribution)
        if (connection.usesDirectPayments(bookId)) body.put("transport", "sheets")
        return requireNotNull(submit(body)) { "Espera a que termine el envío actual" }
    }
    fun savePayments() { OfflineSync.request(getApplication()) }
    internal fun editLoanTerms(loan: SheetLoan, input: LoanTermsInput, confirmation: String): String {
        val current = mutable.value
        require(connection.usesDirectPayments(bookId) && current.ready && !current.saving && current.unconfirmedFees.isEmpty()) {
            "Conecta Google y guarda o descarta los cambios pendientes antes de editar"
        }
        val calendar = current.calendar?.takeIf { it.loanId == loan.id } ?: error("Carga el calendario antes de editar")
        val summary = current.data?.summary ?: error("Actualiza la caja antes de editar")
        require(planLoanTerms(loan, calendar, input, today(), summary.cash).confirmation() == confirmation) { "La vista previa cambió; revisa otra vez" }
        return requireNotNull(submit(JSONObject().put("action", "editLoanTerms").put("transport", "sheets")
            .put("loanId", loan.id).put("year", loan.year).put("version", loanDeletionVersion(loan))
            .put("terms", input.json()).put("confirmation", confirmation).put("actor", current.email ?: current.subject.orEmpty()))) {
            "No se pudo iniciar la edición"
        }
    }
    fun deleteLoan(loan: SheetLoan): String {
        val current = mutable.value
        require(connection.canDeleteLoan(bookId)) { "Este libro no está configurado para eliminar préstamos" }
        require(current.ready && !current.saving) { "Conecta Google y espera a que termine la operación actual" }
        return requireNotNull(submit(JSONObject().put("action", "deleteLoan").put("transport", "sheets")
            .put("loanId", loan.id).put("year", loan.year).put("version", loanDeletionVersion(loan))
            .put("actor", current.email ?: current.subject.orEmpty()))) { "No se pudo iniciar la eliminación" }
    }
    internal fun deleteLoans(loans: List<SheetLoan>): String {
        val current = mutable.value
        require(connection.canDeleteLoan(bookId) && current.ready && !current.saving && current.unconfirmedFees.isEmpty()) {
            "Espera a que termine el guardado actual"
        }
        require(loans.isNotEmpty() && loans.map { it.id }.distinct().size == loans.size) { "Revisa los préstamos seleccionados" }
        val rows = loans.map { loan -> JSONObject().put("loanId", loan.id).put("year", loan.year)
            .put("version", loanDeletionVersion(loan)) }
        return requireNotNull(submit(JSONObject().put("action", "deleteLoans").put("loans", JSONArray(rows)))) {
            "No se pudo iniciar la eliminación"
        }
    }
    internal fun editCapital(change: CapitalChange): String {
        val current = mutable.value
        require(connection.canEditCapital(bookId)) { "Este libro no está configurado para editar el capital" }
        require(current.ready && !current.saving) {
            "Espera a que termine el guardado actual"
        }
        val summary = current.data?.summary ?: error("Abre Inicio una vez para disponer del capital en el teléfono")
        require(summary.year == change.year && summary.initial == change.before && summary.cash == change.cashBefore) {
            "Los importes de Inicio cambiaron. Revisa de nuevo el capital"
        }
        return requireNotNull(submit(JSONObject().put("action", "setCapital").put("transport", "sheets")
            .put("year", change.year).put("beforeCents", change.before).put("afterCents", change.after).put("cashBeforeCents", change.cashBefore)
            .put("actor", current.email ?: current.subject.orEmpty()))) { "No se pudo iniciar el cambio de capital" }
    }
    internal fun moveCash(type: CashMovementType, amount: Long, date: LocalDate, description: String): String {
        require(mutable.value.ready && !mutable.value.saving) { "Espera a que termine el guardado actual" }
        return requireNotNull(enqueueLocal(JSONObject().put("action", "cashMovement").put("transport", "sheets")
            .put("type", type.name).put("amountCents", amount).put("date", date.toString()).put("description", description), false)) {
            "Espera a que termine el guardado actual"
        }
    }
    private fun submit(body: JSONObject): String? = enqueueLocal(body, true)

    private fun enqueueLocal(input: JSONObject, protected: Boolean): String? {
        val current = mutable.value
        val subject = current.subject ?: return null
        if (!connection.canWrite(bookId) || current.data == null || current.sensitiveRequest != null) return null
        val book = bookId; val run = generation
        val body = JSONObject(input.toString()).put("operationId", UUID.randomUUID().toString())
        val id = body.getString("operationId")
        val commit = {
            mutable.value = mutable.value.copy(saving = true, error = null)
            disk.execute {
                val result = runCatching {
                    check(run == generation && mutable.value.subject == subject && bookId == book) { "La sesión cambió" }
                    val local = OfflineBookStore(getApplication<Application>(), subject, book)
                    val catalog = canonicalLoanViews(current.searchLoans + current.loans + current.data.loans, today().year)
                    val known = (local.allRows() + current.data.todayPayments + current.payments + current.calendar?.days.orEmpty().mapNotNull { it.payment })
                        .associateBy { it.id }.values.toList()
                    val simple = snapshotStore(subject, book).read()?.optJSONObject("simple")?.let(::simpleBookFromJson)
                    val contributions = simple?.active?.associate { it.loan.id to it.loan.contribution } ?: local.contributions()
                    val fallback = LocalBookView(current.data, catalog, known, current.cash.ifEmpty { local.cash() }, contributions, current.cashActualDates, current.cashMovements, current.cashEventTimes)
                    OfflineSync.operations(getApplication(), subject, book).enqueue(body, current.email ?: subject, fallback)
                }
                main.post { if (run == generation && mutable.value.subject == subject && bookId == book) {
                    result.fold({ operation ->
                        mutable.value = mutable.value.copy(saving = false, completedOperationId = operation.id, localOperationCount = mutable.value.localOperationCount + 1,
                            createdLoanId = if (body.getString("action") == "createLoan") operation.body.getString("localLoanId") else mutable.value.createdLoanId,
                            deletedLoanId = if (body.getString("action") == "deleteLoan") body.getString("loanId") else mutable.value.deletedLoanId,
                            notice = null, error = null)
                        reloadOffline(); OfflineSync.request(getApplication())
                    }, { mutable.value = mutable.value.copy(saving = false, error = it.message ?: "No se pudo guardar en el teléfono") })
                } }
            }
        }
        if (protected) protect(id, when (body.getString("action")) {
            "deleteLoan" -> "Eliminar este préstamo"
            "deleteLoans" -> "Eliminar los préstamos seleccionados"
            "setCapital" -> "Cambiar el capital inicial"
            "editLoanTerms", "editLoanText" -> "Actualizar este préstamo"
            "groupLoans" -> "Agrupar los préstamos con el nombre elegido"
            else -> "Registrar un préstamo"
        }, commit) else commit()
        return id
    }
    fun retryWrite() {
        val pending = mutable.value.pending ?: return
        if (mutable.value.busy || mutable.value.saving || mutable.value.cachedOnly || mutable.value.sensitiveRequest != null) return
        fun retry() = operation(write = true) { repository ->
            persistPending(pending)
            completeWrite(repository, pending)
        }
        if (pending.operationId in approvedOperations) retry() else protect(purpose = "Comprobar y completar el envío pendiente") {
            check(mutable.value.pending == pending && !mutable.value.cachedOnly) { "El envío pendiente cambió" }
            approvedOperations += pending.operationId
            retry()
        }
    }
    private fun completeWrite(repository: SheetsRepository, pending: PendingRemoteWrite, firstAttempt: Boolean = false) {
        check(pending.operationId in approvedOperations) { "Verifica tu identidad antes de guardar" }
        val run = generation
        val ids = paymentIds(pending)
        val sent = JSONObject(pending.payload).optJSONArray("edits")
        val dates = if (sent == null) emptyMap() else (0 until sent.length()).associate { index ->
            val value = sent.getJSONObject(index)
            versionPaymentId(value.getJSONObject("version")) to value.optString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        }
        val submitted = mutable.value.edits.filterKeys { it in ids }.mapValues { (id, pair) ->
            pair.first to pair.second.copy(paidOn = dates[id])
        }
        val affected = submitted.values.map { it.first }
        val receipt = try { repository.write(pending, firstAttempt) } catch (failure: Exception) {
            if (failure is DirectPaymentRejected || failure is DirectLoanRejected || failure is LoanDeletionRejected || failure is CapitalEditRejected || failure is LoanTermsRejected) {
                clearPending(pending)
                main.post { mutable.value = mutable.value.copy(pending = null, pendingPaymentIds = emptySet()) }
            }
            throw failure
        }
        val created = JSONObject(pending.payload).optString("action") == "createLoan"
        val termsChanged = JSONObject(pending.payload).optString("action") == "editLoanTerms"
        val deleted = JSONObject(pending.payload).optString("action") == "deleteLoan"
        val capitalChanged = JSONObject(pending.payload).optString("action") == "setCapital"
        main.post {
            if (generation != run) return@post
            localEpoch++
            autoRetries.remove(pending.operationId)
            approvedOperations.remove(pending.operationId)
            val remaining = remainingPaymentDrafts(mutable.value.edits, ids)
            mutable.value = mutable.value.copy(edits = remaining, pending = null, pendingPaymentIds = emptySet(), saving = false,
                payments = confirmedPaymentRows(mutable.value.payments, submitted, today()),
                calendar = mutable.value.calendar?.let { calendar -> calendarWithPayments(calendar,
                    confirmedPaymentRows(calendar.days.mapNotNull { it.payment }, submitted, today())) },
                data = mutable.value.data?.let { it.copy(todayPayments = confirmedPaymentRows(it.todayPayments, submitted, today())) },
                completedOperationId = pending.operationId, createdLoanId = if (created) receipt.getString("loanId") else mutable.value.createdLoanId,
                deletedLoanId = if (deleted) receipt.getString("loanId") else mutable.value.deletedLoanId,
                loans = if (deleted) mutable.value.loans.filterNot { it.id == receipt.getString("loanId") } else mutable.value.loans,
                searchLoans = if (deleted) mutable.value.searchLoans.filterNot { it.id == receipt.getString("loanId") } else mutable.value.searchLoans,
                error = null, notice = if (created) "Préstamo ${receipt.getString("loanId")} registrado." +
                    if (receipt.optLong("contributionCents") > 0) " Se ingresaron ${soles(receipt.getLong("contributionCents"))} a caja como aporte de capital." else ""
                    else if (deleted) "Préstamo ${receipt.getString("loanId")} eliminado. Respaldo conservado en la hoja"
                    else if (termsChanged) "Préstamo actualizado. Cronograma y saldos recalculados"
                    else if (capitalChanged) "Capital actualizado a ${soles(receipt.getLong("capitalAfterCents"))}"
                    else "${receipt.optInt("updated", 1)} cambio(s) guardado(s) en la hoja")
            persistConfirmedDraft(remaining, pending)
            if (deleted) {
                val id = receipt.getString("loanId")
                mutable.value = mutable.value.copy(payments = mutable.value.payments.filterNot { it.loan.id == id },
                    calendar = null, calendarRequestedId = null, calendarRequestedKey = null,
                    data = null, cache = null,
                    cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false, history = emptyList())
            }
            if (termsChanged) {
                calendarRequest++
                mutable.value = mutable.value.copy(calendar = null, calendarRequestedId = null, calendarRequestedKey = null, calendarError = null)
            }
            if (capitalChanged || termsChanged) mutable.value = mutable.value.copy(data = null, cache = null, cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false)
            if (created || deleted || capitalChanged || termsChanged) refreshCreatedLoan(repository, termsChanged,
                if (termsChanged) receipt.getString("loanId") else null, JSONObject(pending.payload).optInt("year", today().year)) else refreshSavedPayments(repository, affected, ids)
            // Queue independently of reload success; a failed read must not skip refreshing the book's views.
            synchronizePresentation(repository)
        }
    }
    private fun refreshCreatedLoan(repository: SheetsRepository, reloadSearch: Boolean = false, calendarLoanId: String? = null, calendarYear: Int = today().year) {
        operation {
            val data = if (repository.isSimpleBook) repository.refreshAfterPayments(emptyList()) else repository.preload(today().year, today()) { _, _ -> }
            val editedViews = if (reloadSearch) repository.loanViews().sortedWith(compareBy<SheetLoan> { if (it.year == today().year) 0 else 1 }.thenByDescending { it.year }) else emptyList()
            val selected = calendarLoanId?.let { id -> (editedViews + data.loans).firstOrNull { it.id == id && it.year == calendarYear }
                ?: (editedViews + data.loans).firstOrNull { it.id == id } }
            val calendarResult = selected?.let { runCatching { repository.calendar(it, refresh = true) } }
            main.post { mutable.value = mutable.value.copy(data = data, cache = data, year = today().year,
                loans = data.loans, payments = data.todayPayments, searchLoans = editedViews, searchLoaded = reloadSearch,
                paymentFrom = today(), paymentTo = today(), paymentLoanId = null,
                cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false, history = emptyList(), calendar = calendarResult?.getOrNull(),
                calendarRequestedId = selected?.id, calendarRequestedKey = selected?.let(::paymentTerms),
                calendarError = calendarResult?.exceptionOrNull()?.message)
            }
        }
    }
    private fun refreshSavedPayments(repository: SheetsRepository, affected: List<SheetPayment>, ids: Set<String>) {
        val scope = mutable.value
        operation {
            val data = if (connection.usesDirectPayments(bookId) && affected.isNotEmpty()) repository.refreshAfterPayments(affected)
                else repository.preload(today().year, today()) { _, _ -> }
            main.post {
                val views = data.loans.associateBy { it.id }
                mutable.value = mutable.value.copy(data = data, cache = data,
                    loans = if (mutable.value.year == today().year) data.loans else mutable.value.loans,
                    searchLoans = mutable.value.searchLoans.map { views[it.id] ?: it },
                    cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false, history = emptyList())
            }
            val loanIds = affected.map { it.loan.id }.toSet()
            val refreshed = if (loanIds.isEmpty()) data.todayPayments else if (scope.paymentFrom == today() && scope.paymentTo == today() && scope.paymentLoanId == null)
                data.todayPayments.filter { it.loan.id in loanIds } else paymentWindows(scope.paymentFrom, scope.paymentTo, today()).flatMap {
                repository.payments(it.from.year, it.from, it.to, scope.paymentLoanId, loanIds)
            }
            val calendarResult = runCatching { scope.calendar?.let { repository.refreshCalendar(it, affected, refreshed) } }
            val calendar = calendarResult.getOrNull()
            val localRows = if (repository.isSimpleBook) loanIds.flatMap { id ->
                repository.calendar(repository.searchLoans().single { it.id == id }).days.mapNotNull { it.payment }
            } else refreshed + calendar?.days.orEmpty().mapNotNull { it.payment }
            val yearLoans = if (repository.isSimpleBook && scope.year != today().year) repository.loans(scope.year) else null
            val allLoans = if (repository.isSimpleBook) repository.searchLoans() else null
            main.post {
                val views = data.loans.associateBy { it.id }
                mutable.value = mutable.value.copy(data = data, cache = data,
                    loans = if (mutable.value.year == today().year) data.loans else yearLoans ?: mutable.value.loans,
                    payments = mutable.value.payments.filterNot { it.id in ids || it.loan.id in loanIds } + refreshed,
                    searchLoans = allLoans ?: mutable.value.searchLoans.map { views[it.id] ?: it },
                    searchLoaded = allLoans != null || mutable.value.searchLoaded,
                    cash = emptyList(), cashLoaded = false, forecastPayments = emptyList(), cashActualDates = false, history = emptyList(),
                    edits = rebasePaymentDrafts(mutable.value.edits, localRows),
                    calendarRequestedKey = calendar?.terms ?: mutable.value.calendarRequestedKey,
                    calendarError = calendarResult.exceptionOrNull()?.message,
                    error = calendarResult.exceptionOrNull()?.let { "Los pagos y saldos se actualizaron. Falta recargar el calendario: ${it.message}" },
                    calendar = if (mutable.value.calendar?.loanId == calendar?.loanId) calendar else mutable.value.calendar)
                persistDraft(mutable.value.edits)
            }
        }
    }
    fun signOut(): Boolean {
        if (authInFlight || mutable.value.syncing || mutable.value.sensitiveRequest != null || mutable.value.busy || mutable.value.saving || mutable.value.pending != null || mutable.value.pendingLoanEdit != null || mutable.value.edits.isNotEmpty() || mutable.value.localOperationCount > 0) return false
        generation++; token = null; repo = null; authInFlight = false; requestedAuthorizationEmail = null
        OfflineSync.cancel(getApplication())
        mutable.value.subject?.let { subject -> for (book in listOf(connection.productionBookId, connection.testBookId)) {
            snapshotStore(subject, book).delete()
            file(subject, book, "local-book").delete()
            file(subject, book, "outbox").delete()
            file(subject, book, "operations").delete()
            file(subject, book, "sync-health").delete()
        } }
        prefs.edit().clear().apply()
        mutable.value = SheetsState(status = "Conecta tu cuenta de Google")
        return true
    }
    override fun onCleared() { OfflineSync.listeners.remove(offlineListener); protection.invalidate(); approvedOperations.clear(); generation++; main.removeCallbacksAndMessages(null); token = null; executor.shutdown(); disk.shutdown(); queries.shutdown(); super.onCleared() }
}

package com.example.controlprestamos.ui.google

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.controlprestamos.data.ExcelBackup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import com.example.controlprestamos.consultas.LoanCustomerAccessDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.controlprestamos.R
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.controlprestamos.notifications.NotificationSettingsDialog
import com.example.controlprestamos.notifications.RequestCollectionNotificationPermission
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsApp(model: SheetsViewModel, dark: Boolean, accentIndex: Int, toggleTheme: () -> Unit,
    selectAccent: (Int) -> Unit, notificationEntry: Int = 0, onReconnect: () -> Unit, onChangeAccount: () -> Unit, onSignOut: () -> Unit) {
    val state by model.state.collectAsState()
    RequestCollectionNotificationPermission(state.ready, state.syncOverdue)
    val context = LocalContext.current
    val exportExcel=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExcelBackup.MIME)){uri->
        if(uri!=null)model.exportLocalExcel(uri)
    }
    var notifications by rememberSaveable { mutableStateOf(false) }
    var testNoticeDeadline by rememberSaveable { mutableLongStateOf(0L) }
    var showTestNotice by remember { mutableStateOf(false) }
    LaunchedEffect(state.copy) {
        showTestNotice = false
        if (state.copy) {
            if (testNoticeDeadline == 0L) testNoticeDeadline = SystemClock.elapsedRealtime() + 4_000L
            val remaining = (testNoticeDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remaining > 0) {
                showTestNotice = true
                delay(remaining)
                showTestNotice = false
            }
        }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<SheetLoan?>(null) }
    LaunchedEffect(state.deletedLoanId) { if (detail?.id == state.deletedLoanId) detail = null }
    var cashMenu by rememberSaveable(state.subject, state.copy) { mutableStateOf<String?>(null) }
    var create by rememberSaveable { mutableStateOf(false) }
    var creationSession by rememberSaveable { mutableIntStateOf(0) }
    var creationClient by rememberSaveable { mutableStateOf("") }

    var submittedCreation by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(create, state.pending?.operationId, state.completedOperationId, submittedCreation) {
        if (create && submittedCreation == null && state.pending?.payload?.let { JSONObject(it).optString("action") == "createLoan" } == true)
            submittedCreation = state.pending?.operationId
        if (submittedCreation != null && state.completedOperationId == submittedCreation) { create = false; submittedCreation = null }
    }
    cashMenu?.let { type -> CashMovementMenu(state, model, if (type == "HISTORY") null else CashMovementType.valueOf(type), dismiss = { cashMenu = null }) }
    val settings = rememberDrawerState(DrawerValue.Closed)
    val settingsScope = rememberCoroutineScope()
    var accent by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val names = listOf("Inicio", "Préstamos", "Pagos")
    val menuOrder = listOf(0, 1, 2)
    var paymentEntry by rememberSaveable { mutableIntStateOf(0) }
    val screens = rememberSaveableStateHolder()
    var menuHistory by rememberSaveable { mutableStateOf(listOf(page)) }
    val exitGuard = LocalExitBackGuard.current
    LaunchedEffect(page, detail?.id) { exitGuard.reset() }
    LaunchedEffect(page) { if (page !in names.indices) page = 0 }
    val currentDetail = detail?.let { selected ->
        localLoanView(refreshedLoanSelection(selected, state.searchLoans + state.loans), state.financialEdits, today())
    }
    val writable = model.connection.canWrite(model.bookId) && state.paymentConflict == null
    var showPending by rememberSaveable(state.pending?.operationId) { mutableStateOf(true) }
    var pendingNoticeEntry by rememberSaveable { mutableIntStateOf(0) }
    fun navigate(index: Int, record: Boolean = true, resetPayments: Boolean = true) {
        exitGuard.reset()
        settingsScope.launch { settings.close() }
        if (record) menuHistory = recordMenu(menuHistory, index)
        if (record && resetPayments && index == 2 && state.edits.isEmpty() && state.pending == null && page != 2 && !state.busy && !state.saving) {
            paymentEntry++
            if ((!state.cachedOnly || state.localAvailable) && (state.paymentFrom != today() || state.paymentTo != today() || state.paymentLoanId != null)) model.payments(today(), today())
        }
        page = index; detail = null
    }
    LaunchedEffect(notificationEntry) {
        if (notificationEntry > 0) { navigate(2); model.payments(today(), today()) }
    }
    BackHandler(enabled = detail != null || menuHistory.size > 1) {
        exitGuard.reset()
        if (detail != null) detail = null else {
            menuHistory = menuHistory.dropLast(1)
            navigate(menuHistory.last(), record = false)
        }
    }
    val canChangeAccount = !state.busy && !state.saving && state.pending == null && state.pendingLoanEdit == null && state.edits.isEmpty() && state.localOperationCount == 0
    SettingsDrawer(settings, canOpen = page == 0 && detail == null && !create && cashMenu == null && !notifications && !accent && !about,
        drawerContent = {
            SettingsPanel(account = state.email ?: "Sin cuenta", dark = dark, toggleTheme = toggleTheme,
                changeAccount = onChangeAccount, canChangeAccount = canChangeAccount,
                accountRestriction = if (state.busy || state.saving) "Espera a que termine la operación para cambiar de cuenta."
                    else "Guarda o descarta los cambios pendientes antes de cambiar de cuenta.",
                chooseColor = { accent = true }, about = { about = true },
                signOut = { onSignOut(); settingsScope.launch { settings.close() } }, extra = {
                    SettingsAction(R.drawable.ic_history, if(state.exporting)"Creando respaldo Excel…" else "Exportar respaldo en Excel",
                        { exportExcel.launch("ControlPrestamos-Respaldo-${today()}.xlsx"); settingsScope.launch{settings.close()} },
                        enabled=state.localAvailable && !state.exporting && !state.saving, order=4)
                    SettingsAction(R.drawable.ic_notifications, "Notificaciones", { notifications = true }, order = 2)
                    if (!model.connection.clientInstallation) SettingsAction(R.drawable.ic_loans, if (state.copy) "Usar libro oficial"
                        else if (model.connection.testBookId == SheetsIdentity.CLIENT_ID) "Abrir libro del cliente" else "Abrir pruebas vacías",
                        { model.switchBook(!state.copy); settingsScope.launch { settings.close() } }, enabled = canChangeAccount, order = 3)
                }, active = settings.isOpen, dismiss = { settingsScope.launch { settings.close() } })
        }) {
        Scaffold(bottomBar = {
            AppNavigation(page, names,
                listOf(R.drawable.ic_home, R.drawable.ic_loans, R.drawable.ic_payments),
                { navigate(it) },
                paymentsBadge = state.autoPaymentCount)
        }, floatingActionButton = {
            if (page == 1 && detail == null && state.edits.isEmpty() && state.pendingLoanEdit == null) ExtendedFloatingActionButton(onClick = { creationSession++; creationClient = ""; create = true }, text = { Text("Nuevo préstamo", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(painterResource(R.drawable.ic_add), null) }, containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary, elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp))
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.data?.summary?.let {
                    CashBalanceHeader(effectiveSheetSummary(it, state.financialEdits, state.cash).cash,
                        enabled = state.ready && !state.saving && model.connection.canWrite(model.bookId),
                        deposit = { cashMenu = CashMovementType.DEPOSIT.name },
                        withdraw = { cashMenu = CashMovementType.WITHDRAWAL.name },
                        history = { cashMenu = "HISTORY" }, testBook = state.copy)
                }
                if (state.data == null && (state.busy || state.saving || state.syncing)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
                if (state.syncMessage?.contains("Reconecta", ignoreCase = true) == true) TextButton(onClick = onReconnect) { Text("Reconectar Google") }
                state.paymentConflict?.let { conflict ->
                    var reviewConflict by remember { mutableStateOf(false) }
                    Notice("Un cambio local necesita revisión después de 24 horas sin sincronizar.") {
                        TextButton(onClick = { reviewConflict = true }) { Text("Revisar conflicto") }
                    }
                    if (reviewConflict) AlertDialog(onDismissRequest = { reviewConflict = false }, title = { Text("Cambio pendiente de revisión") },
                        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(conflict)
                            state.edits.values.forEach { (row, edit) -> Text("${row.loan.client} · ${dateLabel(row.due)}: ${if (edit.unpaid) "No pagó" else "Pagado"}") }
                            Text("El envío fue rechazado y no modificó la hoja. Puedes conservarlo para revisarlo o descartar ese envío y volver a los datos de Google.")
                        } }, confirmButton = { TextButton(onClick = { model.discardPaymentConflict(); reviewConflict = false }) { Text("Usar datos de Google") } },
                        dismissButton = { TextButton(onClick = { reviewConflict = false }) { Text("Conservar cambio local") } })
                }
                AnimatedVisibility(visible = state.copy && showTestNotice, enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(350)) + shrinkVertically(tween(350))) {
                    Notice("MODO PRUEBAS · Los cambios se guardan en el libro de pruebas separado. El libro oficial se conserva.")
                }
                state.bookPresentationError?.takeIf { state.syncOverdue }?.let {
                    Notice("No se pudo actualizar la vista del libro. Los movimientos confirmados siguen guardados.") {
                        TextButton(onClick = model::updateBookPresentation, enabled = !state.busy && !state.saving && !state.cachedOnly) { Text("Actualizar vista del libro") }
                    }
                }
                state.pendingLoanEdit?.takeIf { state.syncOverdue }?.let { pending -> Notice("Edición del préstamo ${pending.edit.loanId} pendiente de confirmar en Google.") {
                    TextButton(onClick = model::retryLoanEdit, enabled = !state.busy && !state.saving && !state.cachedOnly) { Text("Reintentar guardado") }
                    TextButton(onClick = model::reviewLoanEdit, enabled = !state.busy && !state.saving && !state.cachedOnly) { Text("Actualizar y cerrar intento") }
                } }
                val pendingPayment = state.pending
                if (pendingPayment != null && state.syncOverdue) {
                    val deleting = JSONObject(pendingPayment.payload).optString("action") == "deleteLoan"
                    val changingCapital = JSONObject(pendingPayment.payload).optString("action") == "setCapital"
                    val changingTerms = JSONObject(pendingPayment.payload).optString("action") == "editLoanTerms"
                    if (showPending) TimedNotice("${pendingPayment.operationId}:$pendingNoticeEntry", if (deleting) {
                        if (state.saving) "Eliminando préstamo y guardando su respaldo…" else "Eliminación pendiente de comprobar. Comprueba el mismo envío antes de editar pagos."
                    } else if (changingTerms) {
                        if (state.saving) "Guardando el nuevo acuerdo y recalculando las cuotas…" else "Edición del acuerdo pendiente de comprobar. Comprueba el mismo envío antes de preparar pagos."
                    } else if (changingCapital) {
                        if (state.saving) "Guardando el cambio de capital…" else "Cambio de capital pendiente de comprobar. Comprueba el mismo envío antes de editar pagos."
                    } else if (state.saving) "Enviando ${state.pendingPaymentIds.size} cambio(s). Puedes navegar y preparar otros pagos."
                        else "Envío pendiente de comprobar. Puedes seguir con otros pagos.", onDismiss = { showPending = false }) { close ->
                        Row {
                            TextButton(onClick = model::retryWrite, enabled = !state.busy && !state.saving && !state.cachedOnly) { Text("Comprobar envío") }
                            TextButton(onClick = close) { Text("Cerrar aviso") }
                        }
                    } else TextButton(onClick = { pendingNoticeEntry++; showPending = true }) { Text("Ver envío pendiente") }
                }
                state.error?.let { message -> TimedNotice(message, message, onDismiss = { model.dismissError(message) }) { close -> Row {
                    TextButton(onClick = onReconnect, enabled = !state.busy && !state.saving) { Text("Reconectar") }
                    TextButton(onClick = close) { Text("Cerrar") }
                } } }
                state.notice?.let { message -> TimedNotice(message, message, onDismiss = { model.dismissNotice(message) }) { close ->
                    TextButton(onClick = close) { Text("Cerrar") }
                } }
                if (detail != null) screens.SaveableStateProvider("loan:${detail!!.id}") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        LoanDetail(currentDetail!!, state, model, onBack = { detail = null; exitGuard.reset() }, onPayments = { loan ->
                            navigate(2, resetPayments = false); paymentEntry++
                            val end = minOf(today(), loan.lastDue)
                            if (loan.firstDue <= end) model.payments(maxOf(loan.firstDue, end.minusDays(30)), end, loan.id)
                            else model.payments(today(), today(), loan.id)
                        }, onDayPayment = { loan, day ->
                            navigate(2, resetPayments = false); paymentEntry++
                            model.payments(day, day, loan.id)
                        })
                        Spacer(Modifier.height(80.dp))
                    }
                } else MenuPager(page, menuOrder, { navigate(it) }, Modifier.weight(1f)) { menu ->
                    screens.SaveableStateProvider("page:$menu") {
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxSize().then(if (menu == 0) Modifier.verticalScroll(scroll) else Modifier)
                            .padding(horizontal = 12.dp).padding(top = if (menu == 2) 2.dp else 8.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (menu) {
                                0 -> Dashboard(state, model, model::refreshHome, active = page == menu)
                                1 -> Loans(state, model, onOpen = { if (!state.busy) detail = it },
                                    onCreateLoan = { creationSession++; creationClient = it; create = true }, active = page == menu)
                                2 -> PaymentsScreen(state, model, writable, paymentEntry,
                                    onOpen = { if (!state.busy) detail = it }, active = page == menu)
                            }
                            if (menu == 0) Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
    if (create) key(creationSession) { CreateLoan(state, model.connection.canCreateLoan(model.bookId) && state.ready && !state.saving, initialClient = creationClient, onDismiss = { create = false; submittedCreation = null }, retry = model::retryWrite, onCreate = { request, disbursedOn, paid ->
        submittedCreation = model.create(request, disbursedOn, paid)
    }) }
    if (notifications) NotificationSettingsDialog { notifications = false }
    if (accent) AccentPicker(accentIndex, selectAccent) { accent = false }
    if (about) BrandAboutDialog(demonstration = false) { about = false }
    FirstUseGuides(book = model.bookId,
        catalogLoaded = state.ready && state.searchLoaded && !state.busy && !state.syncing && !state.backgroundSyncing && !state.cachedOnly,
        hasLoans = (state.searchLoans + state.loans + state.data?.loans.orEmpty()).isNotEmpty(),
        topic = when {
            create -> FirstUseTopic.CREATE_LOAN
            cashMenu == "HISTORY" -> FirstUseTopic.HISTORY
            cashMenu != null -> FirstUseTopic.CASH
            settings.isOpen -> FirstUseTopic.SETTINGS
            detail != null -> FirstUseTopic.DETAIL
            page == 1 -> FirstUseTopic.LOANS
            page == 2 -> FirstUseTopic.PAYMENTS
            else -> FirstUseTopic.HOME
        },
        interactive = state.ready && !state.busy && !state.saving && !state.syncing && !state.backgroundSyncing &&
            !notifications && !accent && !about && state.sensitiveRequest == null && state.paymentConflict == null &&
            state.pendingLoanEdit == null && state.pending == null && state.error == null)
}

@Composable internal fun Notice(message: String, action: @Composable () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text(message, style = MaterialTheme.typography.bodySmall); action() }
    }
}
@Composable private fun TimedNotice(identity: String, message: String, onDismiss: () -> Unit,
    action: @Composable (() -> Unit) -> Unit) {
    var visible by rememberSaveable(identity) { mutableStateOf(true) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(identity) { delay(8_000); visible = false }
    LaunchedEffect(identity, visible) { if (!visible) { delay(300); dismiss() } }
    AnimatedVisibility(visible, exit = slideOutVertically(tween(250)) { -it } +
        shrinkVertically(tween(250), shrinkTowards = Alignment.Top) + fadeOut(tween(200))) {
        Notice(message) { action { visible = false } }
    }
}
@Composable internal fun Metric(title: String, amount: Long, caption: String? = null) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(soles(amount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    } }
}
@Composable internal fun Years(selected: Int, busy: Boolean, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (2026..2028).forEach { year ->
        FilterChip(selected = selected == year, onClick = { onSelect(year) }, enabled = !busy, label = { Text(year.toString()) })
    } }
}
@Composable private fun LoanDetail(loan: SheetLoan, state: SheetsState, model: SheetsViewModel,
    onBack: () -> Unit, onPayments: (SheetLoan) -> Unit, onDayPayment: (SheetLoan, LocalDate) -> Unit) {
    var customerAccess by rememberSaveable(loan.id) { mutableStateOf(false) }
    var editing by rememberSaveable(loan.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(loan.id) { mutableStateOf(false) }
    val editable = state.ready && !state.saving && state.unconfirmedFees.isEmpty()
    val progress = rememberChartAnimation(listOf("loan-detail-progress", loan.id, loan.paid, loan.total),
        durationMillis = LOAN_PROGRESS_DURATION_MILLIS, accelerating = true)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Volver a préstamos") }
        Column(Modifier.weight(1f)) {
            Text(loan.client, Modifier.clickable(enabled = editable) { customerAccess = true }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${dateLabel(loan.firstDue.minusDays(1))} — ${dateLabel(loan.lastDue)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    StatusBadge(if (loan.active) "Préstamo activo" else "Préstamo concluido", positive = !loan.active)
    FinancialHero("Saldo del préstamo", loan.debt, "${loan.paid}/${loan.total} cuotas cobradas · ${soles(loan.dailyLate)} de mora por día de atraso") {
        LinearProgressIndicator(progress = { progress.progress * loan.paid.toFloat() / loan.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().height(5.dp).then(progress.viewport), color = androidx.compose.ui.graphics.Color(0xFF9CE8CB),
            trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f))
    }
    OutlinedButton(onClick = { editing = true; model.calendar(loan, refresh = true) }, enabled = editable,
        modifier = Modifier.fillMaxWidth()) { Text("Editar acuerdo y mora") }
    if (editing) EditLoanTermsDialog(loan, state.calendar?.takeIf { calendarMatchesLoan(it, loan) }, state.data?.summary?.cash,
        editable, copy = state.copy, dismiss = { editing = false }, reload = { model.calendar(loan, refresh = true) }) { input, confirmation ->
        model.editLoanTerms(loan, input, confirmation); editing = false
    }
    if (customerAccess) LoanCustomerAccessDialog(
        ((state.searchLoans + state.loans + state.data?.loans.orEmpty()).filter { normalizedClient(it.client) == normalizedClient(loan.client) } + loan).distinctBy { it.id },
        model.bookId) { customerAccess = false }
    LoanBookFields(loan, simple = state.copy)
    LoanEvolution(loan, state, model, onDayPayment = { onDayPayment(loan, it) })
    OutlinedButton(onClick = { onPayments(loan) }, enabled = !state.busy && !state.saving && (!state.cachedOnly || state.localAvailable)) {
        Text("Ver pagos de este préstamo")
    }
    if (model.connection.canDeleteLoan(model.bookId)) {
        OutlinedButton(onClick = { deleting = true }, enabled = editable && state.unconfirmedFees.isEmpty(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Eliminar préstamo") }
        if (!editable) Text("Conecta Google y guarda o descarta los cambios pendientes para eliminar.", style = MaterialTheme.typography.bodySmall)
    }
    if (deleting) DeleteLoanDialog(loan, editable && state.unconfirmedFees.isEmpty(), copy = state.copy, dismiss = { deleting = false }) {
        model.deleteLoan(loan); deleting = false
    }
}
@Composable internal fun PaymentCard(row: SheetPayment, state: SheetsState, model: SheetsViewModel, writable: Boolean, grouped: Boolean = false, showDueDate: Boolean = true, framed: Boolean = true, compact: Boolean = false) {
    val edit = state.edits[row.id]?.second
    val unpaid = edit?.unpaid ?: row.unpaid
    val fee = edit?.collectedLate ?: row.late
    val pendingLate = row.loan.lateAt(row.due, today())
    val canEdit = writable && !row.version.isNullOrBlank() && row.due <= today() && row.id !in state.pendingPaymentIds && state.pendingLoanEdit == null &&
        !pendingLocksPayments(state.pending) && state.paymentConflict == null
    var details by rememberSaveable(row.id) { mutableStateOf(false) }
    var adjustRecordedFee by rememberSaveable(row.id) { mutableStateOf(false) }
    val paymentContent: @Composable () -> Unit = {
        if (compact) CompactInstallment(row, unpaid, if (unpaid) pendingLate else fee, canEdit,
            sending = row.id in state.pendingPaymentIds,
            paidOn = edit?.paidOn ?: row.paidOn,
            bookDifference = if (edit == null) row.bookAmount?.takeIf { it != row.base + row.late } else null,
            edit = { adjustRecordedFee = true }, reverse = { model.stage(row, true, 0) })
        else Column(Modifier.fillMaxWidth().padding(if (framed) 14.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (grouped) "Cuota ${row.installment}/${row.loan.total}" else row.loan.client,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    if (showDueDate && grouped) Text(dateLabel(row.due), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(if (unpaid) "No pagó" else "Pagado", positive = !unpaid)
            }
            if (row.id in state.pendingPaymentIds) Text("Envío pendiente de confirmar", style = MaterialTheme.typography.labelMedium)
            if (!grouped) Text("Cuota ${row.installment}/${row.loan.total}", style = MaterialTheme.typography.labelMedium)
            val visibleLate = if (unpaid) pendingLate else fee
            Text(if (visibleLate == 0L) "Sin mora" else
                "Cuota ${soles(row.base)} · ${if (unpaid) "Mora pendiente" else "Mora cobrada"} ${soles(visibleLate)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(soles(row.base + if (unpaid) pendingLate else fee), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (edit == null) row.bookAmount?.takeIf { it != row.base + row.late }?.let { Text("Monto en el libro: ${soles(it)}", style = MaterialTheme.typography.bodySmall) }
            if (!unpaid && edit != null && row.unpaid) Text("Cobro: ${dateLabel(edit.paidOn ?: today())}", style = MaterialTheme.typography.bodySmall)
            else if (!unpaid) (edit?.paidOn ?: row.paidOn)?.takeIf { it != row.due }?.let {
                Text("Cobro: ${dateLabel(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (!grouped) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar datos del préstamo" else "Datos del préstamo") }
                if (details) LoanBookFields(row.loan)
            }
            if (canEdit) {
                if (unpaid) {
                    Button(onClick = { adjustRecordedFee = true }, modifier = Modifier.fillMaxWidth()) { Text("Marcar pagado") }
                    if (pendingLate > 0) TextButton(onClick = { adjustRecordedFee = true }) { Text("Ajustar mora o fecha") }
                }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { model.stage(row, true, 0) }) { Text("Marcar no pagó") }
                    TextButton(onClick = { adjustRecordedFee = true }) { Text("Editar mora") }
                }

            }
        }
    }
    if (framed) Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        border = if (grouped && row.due == today()) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (compact) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)) { paymentContent() }
    else paymentContent()
    if (adjustRecordedFee) RecordedLateDialog(row, fee, edit?.paidOn, canEdit, dismiss = { adjustRecordedFee = false }) { amount, paidOn ->
        model.stage(row, false, amount, paidOn)
        adjustRecordedFee = false
    }
}
@Composable internal fun RecordedLateDialog(row: SheetPayment, currentFee: Long, stagedDate: LocalDate?, enabled: Boolean,
    dismiss: () -> Unit, apply: (Long, LocalDate?) -> Unit) {
    var amount by rememberSaveable(row.id) { mutableStateOf(BigDecimal.valueOf(currentFee, 2).toPlainString()) }
    var dateText by rememberSaveable(row.id) { mutableStateOf(stagedDate?.toString().orEmpty()) }
    var chooseDate by remember { mutableStateOf(false) }
    val missingDate = !row.unpaid && row.paidOn == null
    val paidOn = row.paidOn ?: runCatching { LocalDate.parse(dateText.trim()) }.getOrNull() ?: if (row.unpaid) today() else null
    val validDate = paidOn != null && paidOn in row.due..today()
    val cents = parseMoney(amount)
    val maximum = paidOn?.let { maxOf(row.loan.lateAt(row.due, it), if (row.unpaid || row.loan.termsRevision.isBlank()) 0 else row.late) } ?: 0
    val valid = validDate && cents != null && cents in 0..maximum
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (row.unpaid) "Registrar pago con mora" else "Editar mora cobrada") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${row.loan.client} · Cuota del ${dateLabel(row.due)}")
            row.paidOn?.let { Text("Fecha de cobro: ${dateLabel(it)}") }
            if (missingDate || row.unpaid) {
                OutlinedButton(onClick = { chooseDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(paidOn?.let { "Fecha de cobro: ${dateLabel(it)}" } ?: "Elegir fecha real de cobro")
                }
                Text("Indica cuándo cobraste esta cuota: entre ${dateLabel(row.due)} y ${dateLabel(today())}. La fecha quedará en el historial.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("Mora cobrada · S/") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !valid, modifier = Modifier.fillMaxWidth())
            Text("Ingresa entre S/ 0.00 y ${soles(maximum)}. El máximo se calcula a la fecha de cobro.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { amount = "0" }) { Text("Sin mora") }
                TextButton(onClick = { amount = BigDecimal.valueOf(maximum, 2).toPlainString() }, enabled = validDate) { Text("Mora completa") }
            }
        }
    }, confirmButton = { TextButton(onClick = { cents?.let { apply(it, paidOn) } }, enabled = enabled && valid) { Text(if (row.unpaid) "Aplicar pago" else "Aplicar ajuste") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } })
    if (chooseDate) ChoosePaymentDate(row.due, paidOn, dismiss = { chooseDate = false }) {
        dateText = it.toString(); chooseDate = false
    }
}
@Composable internal fun LoadSearch(enabled: Boolean, state: SheetsState, model: SheetsViewModel) {
    LaunchedEffect(enabled, state.busy, state.saving, state.cachedOnly, state.searchLoaded, state.error) {
        if (enabled && !state.busy && !state.saving && (!state.cachedOnly || state.localAvailable) && !state.searchLoaded && (state.error == null || state.localAvailable)) model.searchAll()
    }
}
@Composable private fun CreateLoan(state: SheetsState, enabled: Boolean, initialClient: String = "", onDismiss: () -> Unit, retry: () -> Unit,
    onCreate: (LoanRequest, LocalDate, Boolean) -> Unit) {
    val pendingCreation = state.pending?.takeIf { JSONObject(it.payload).optString("action") == "createLoan" }
    val initial = remember { pendingCreation?.let { runCatching { directLoanRequest(JSONObject(it.payload).getJSONObject("loan")) }.getOrNull() } }
    fun money(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
    var client by rememberSaveable { mutableStateOf(initial?.client ?: initialClient) }; var amount by rememberSaveable { mutableStateOf(initial?.principal?.let(::money).orEmpty()) }
    var rate by rememberSaveable { mutableStateOf(initial?.ratePercent?.toPlainString() ?: "2") }; var months by rememberSaveable { mutableStateOf(initial?.months?.toString() ?: "1") }
    var guarantee by rememberSaveable { mutableStateOf(initial?.guarantee.orEmpty()) }; var external by rememberSaveable { mutableStateOf(initial?.funding == Funding.EXTERNAL) }
    var contribution by rememberSaveable { mutableStateOf(initial?.contribution?.let(::money) ?: "0") }; var error by remember { mutableStateOf<String?>(null) }
    var dateText by rememberSaveable { mutableStateOf(pendingCreation?.let { JSONObject(it.payload).getString("disbursedOn") } ?: today().toString()) }
    var chooseDate by remember { mutableStateOf(false) }
    var chooseExistingClient by rememberSaveable { mutableStateOf(false) }
    val disbursedOn = LocalDate.parse(dateText)
    var pastPaymentsPaid by rememberSaveable { mutableStateOf(pendingCreation?.let { JSONObject(it.payload).optBoolean("pastPaymentsPaid", false) } ?: false) }
    val editable = !state.saving
    val cash = state.data?.summary?.cash

    val principal = parseMoney(amount); val added = if (external) parseMoney(contribution) else 0L
    val fundingCash = cash?.let { cashForNewLoan(state.cash, disbursedOn, it) }
    val effectiveAdded = if (principal != null && principal > 0 && added != null && fundingCash != null)
        maxOf(added, principal - fundingCash) else null
    val expected = remember(principal, rate, months, disbursedOn, external, added) {
        runCatching {
            Rules.createLoan("preview", LoanRequest("Vista previa", requireNotNull(principal), BigDecimal(rate.replace(',', '.')),
                months.toInt(), "", if (external) Funding.EXTERNAL else Funding.CAPITAL, requireNotNull(added)), disbursedOn)
        }.getOrNull()
    }
    val preview = if (principal != null && principal > 0 && effectiveAdded != null) runCatching { Rules.cashAfter(requireNotNull(cash) { "Actualiza la caja" }, principal, effectiveAdded) }.getOrNull() else null
    val initialCollections = if (pastPaymentsPaid && disbursedOn < today() && principal != null && added != null) runCatching {
        val request = LoanRequest(client, principal, BigDecimal(rate.replace(',', '.')), months.toInt(), guarantee,
            if (external) Funding.EXTERNAL else Funding.CAPITAL, added)
        withInitialPastPayments(previewDatedLoan(request, disbursedOn, today()), today(), true).receipts(today())
    }.getOrNull() else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nuevo préstamo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pendingCreation != null && state.syncOverdue) {
                Text(if (state.saving) "Registrando el préstamo…" else "Registro pendiente de comprobar. Reintenta el mismo envío.")
                TextButton(onClick = retry, enabled = !state.busy && !state.saving && !state.cachedOnly) { Text("Comprobar registro") }
            } else if (!enabled) Text("Espera a que termine el guardado en el teléfono.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { chooseExistingClient = true }, enabled = editable) { Text("Seleccionar cliente existente") }
            OutlinedTextField(client, { client = it }, label = { Text("Cliente") }, singleLine = true, enabled = editable)
            (state.searchLoans + state.loans).map { it.client }.distinctBy(::normalizedClient).filter { client.isNotBlank() && it.startsWith(client, true) && it != client }.take(4).forEach { name -> TextButton(onClick = { client = name }, enabled = editable) { Text(name) } }
            OutlinedButton(onClick = { chooseDate = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                Text("Desembolso · ${dateLabel(disbursedOn)}")
            }
            Text("Elige la fecha en que entregaste el dinero, aunque lo registres hoy.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(amount, { amount = it }, label = { Text("Monto · S/") }, singleLine = true, enabled = editable)
            OutlinedTextField(rate, { rate = it }, label = { Text("Interés mensual · %") }, singleLine = true, enabled = editable)
            OutlinedTextField(months, { months = it }, label = { Text("Plazo · meses") }, singleLine = true, enabled = editable)
            OutlinedTextField(guarantee, { guarantee = it }, label = { Text("Garantía") }, enabled = editable)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!external, { external = false }, enabled = editable, label = { Text("Del capital") })
                FilterChip(external, { external = true }, enabled = editable, label = { Text("Capital nuevo") })
            }
            if (external) OutlinedTextField(contribution, { contribution = it }, label = { Text("Aporte externo · S/") }, singleLine = true, enabled = editable)
            expected?.let {
                FinancialLine("Monto prestado", soles(it.principal))
                FinancialLine("Ganancia esperada", soles(it.agreed - it.principal))
                FinancialLine("Total a recibir", soles(it.agreed), emphasized = true)
                Text("Capital + interés pactado, sin mora.", style = MaterialTheme.typography.bodySmall)
            }
            cash?.let { Text("Caja actual: ${soles(it)}") }
            if (effectiveAdded != null && effectiveAdded > (added ?: 0)) {
                Text("Se ingresarán ${soles(effectiveAdded)} a caja como aporte de capital para completar este préstamo.",
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            preview?.let { Text("Caja después del desembolso: ${soles(it)}", color = MaterialTheme.colorScheme.primary) }
            if (initialCollections != null && preview != null) {
                Text("Cobros iniciales: ${soles(initialCollections)}")
                Text("Caja tras registrar préstamo y cobros: ${soles(preview + initialCollections)}")
            }
            Text("Primera cuota: ${dateLabel(disbursedOn.plusDays(1))}. El aporte nunca se registra como ganancia.", style = MaterialTheme.typography.bodySmall)
            if (disbursedOn < today()) {
                Text("Cuotas vencidas hasta hoy", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(!pastPaymentsPaid, { pastPaymentsPaid = false }, enabled = editable, label = { Text("Todas no pagadas") })
                    FilterChip(pastPaymentsPaid, { pastPaymentsPaid = true }, enabled = editable, label = { Text("Todas pagadas") })
                }
                Text(if (pastPaymentsPaid) "Confirmas que cada cuota vencida se cobró en su vencimiento, sin mora. Los cobros se sumarán a caja y los intereses a las ganancias al registrar. Las cuotas futuras quedan pendientes."
                    else "Las cuotas vencidas quedarán pendientes. Después puedes cambiar todas juntas o registrar cada pago con su fecha real.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { require(preview != null && preview >= 0) { "Revisa el monto, el aporte y los fondos disponibles" }
            val request = LoanRequest(client, principal!!, BigDecimal(rate.replace(',', '.')), months.toInt(), guarantee, if (external) Funding.EXTERNAL else Funding.CAPITAL, added!!)
            previewDatedLoan(request, disbursedOn, today()); onCreate(request, disbursedOn, pastPaymentsPaid && disbursedOn < today())
        }.onFailure { error = it.message ?: "Revisa los datos" }
    }, enabled = enabled && preview != null && preview >= 0) { Text(if (state.copy) "Guardar en la copia" else "Registrar préstamo") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
    if (chooseExistingClient) ClientPicker("Seleccionar cliente existente",
        (state.searchLoans + state.loans).map { it.client }.distinctBy(::normalizedClient).sortedBy(::normalizedClient),
        !state.searchLoaded, client, allowAll = false, dismiss = { chooseExistingClient = false }) {
        client = it; chooseExistingClient = false
    }
    if (chooseDate) ChooseDisbursementDate(disbursedOn, dismiss = { chooseDate = false }) {
        dateText = it.toString(); chooseDate = false; error = null
    }
}

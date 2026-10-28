package com.example.controlprestamos.ui.google

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.example.controlprestamos.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.example.controlprestamos.ui.FittedSingleLine
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.FinancialBreakdown
import com.example.controlprestamos.ui.FinancialLine
import com.example.controlprestamos.ui.CashChart
import com.example.controlprestamos.ui.MovementChart
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable internal fun StatusBadge(label: String, positive: Boolean) {
    Surface(shape = MaterialTheme.shapes.small,
        color = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable internal fun ClientAvatar(name: String, size: Dp = 42.dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString(""), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable internal fun Dashboard(state: SheetsState, model: SheetsViewModel, refresh: () -> Unit, active: Boolean = true) {
    val data = state.data
    if (data == null) {
        Text("Actualiza Inicio para consultar tus datos.")
        Button(onClick = refresh, enabled = !state.backgroundSyncing) { Text("Actualizar Inicio") }
        return
    }
    val summary = effectiveSheetSummary(data.summary, state.financialEdits, state.cash)
    val day by produceState(initialValue = today()) { while (true) { delay(60_000); value = today() } }
    var editingCapital by rememberSaveable(state.copy) { mutableStateOf(false) }
    var year by rememberSaveable { mutableIntStateOf(today().year) }
    var month by rememberSaveable { mutableIntStateOf(0) }
    var chooseMonth by remember { mutableStateOf(false) }
    val canEditCapital = model.connection.canEditCapital(model.bookId) && state.ready && !state.saving && state.unconfirmedFees.isEmpty()
    LaunchedEffect(active, state.cashLoaded) { if (active && !state.cashLoaded) model.charts() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Tu negocio, al día", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (state.backgroundSyncing) "Actualizando todo Inicio…" else
                "Actualizado ${DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(LIMA).format(data.syncedAt)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = refresh, enabled = !state.backgroundSyncing && !state.busy && !state.saving) {
            if (state.backgroundSyncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_refresh), "Actualizar todo Inicio")
        }
    }
    LoadSearch(active, state, model)
    val catalog = (state.searchLoans + data.loans).distinctBy { it.id }
    val forecast = remember(catalog, state.forecastPayments, state.financialEdits, day, state.searchLoaded) {
        if (!state.searchLoaded) null else portfolioForecast(catalog, state.forecastPayments, state.financialEdits, day)
    }
    DashboardOverview(summary, forecast, canEditCapital,
        cachedOnly = state.cachedOnly, pendingChanges = state.edits.isNotEmpty(), editCapital = { editingCapital = true },
        moved = if(state.searchLoaded)canonicalLoanViews(catalog, day.year).filter { it.firstDue.minusDays(1)<=day }.sumOf { it.principal }else null)
    EarningsChart(data.summary, active, pendingChanges = state.edits.isNotEmpty())
    if (editingCapital) CapitalEditor(summary, state.copy, canEditCapital, dismiss = { editingCapital = false }) {
        model.editCapital(it); editingCapital = false
    }
    val result = remember(state.cash, year, month, day) {
        runCatching { if (state.cash.isEmpty()) null else bookCashReport(state.cash, day, year, month) }
    }
    val report = result.getOrNull()
    // Keep the selected dates and the cash movements together.
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Crecimiento de tu dinero", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("El capital crece con los intereses y las moras que realmente cobraste.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val months = listOf("Todo el año", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
            Years(year, false) { year = it }
            Box {
                OutlinedButton(onClick = { chooseMonth = true }, modifier = Modifier.fillMaxWidth()) { Text("${months[month]} · Elegir período ▾") }
                DropdownMenu(expanded = chooseMonth, onDismissRequest = { chooseMonth = false }) {
                    months.forEachIndexed { index, title -> DropdownMenuItem(text = { Text(title) }, onClick = { month = index; chooseMonth = false }) }
                }
            }
            if (state.cash.isEmpty()) Text("La evolución se descargará al conectar. Puedes seguir usando la app.", style = MaterialTheme.typography.bodyMedium)
            else {
                result.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (report != null) {
                    if (report.cashEvolution.any { it < 0 }) {
                        Text("Este período tiene un saldo sin conciliar. Revisa las fechas de los pagos y los aportes antes de consultar el gráfico.",
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        val growth = capitalGrowthPoints(summary.initial, catalog, state.forecastPayments, day, year, month)
                        if (growth != null) CapitalGrowthChart(growth)
                        else Text("Descargando las fechas de cobro para calcular el crecimiento real…", style = MaterialTheme.typography.bodySmall)
                        val change = kotlin.math.abs(report.cashChange)
                        Text(when {
                            report.cashChange > 0 -> "La caja aumentó ${soles(change)} en este período."
                            report.cashChange < 0 -> "La caja disminuyó ${soles(change)} en este período."
                            else -> "La caja terminó con el mismo saldo con el que empezó."
                        }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    CashFlowSummary(report)
                    Text(if (SheetsIdentity.isSimpleBook(model.bookId)) "Los cobros se muestran en su fecha real de pago."
                        else if (state.cashActualDates) "Los cobros con fecha confirmada se muestran en su día real; los antiguos sin fecha conservan el vencimiento registrado."
                        else "Actualizando las fechas de cobro del historial…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text("No hay días registrados para este período.")
            }
        }
    }
}

/** A compact cash bridge explains reinvestment without another dense set of bars. */
@Composable private fun CashFlowSummary(report: PeriodReport) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "Ocultar entradas y salidas ▴" else "Ver entradas y salidas ▾")
    }
    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${dateLabel(report.start)} — ${dateLabel(report.cutoff)}", style = MaterialTheme.typography.labelMedium)
            if (report.openingCash >= 0) FinancialLine("Caja al empezar", soles(report.openingCash))
            FinancialLine("Entró por pagos", soles(report.receipts))
            FinancialLine("Abonos menos desembolsos de capital", soles(report.contribution))
            FinancialLine("Salió en préstamos", soles(report.disbursed))
            if (report.closingCash >= 0) FinancialLine("Caja al terminar", soles(report.closingCash), emphasized = true)
            Text("Volver a prestar dinero cobrado reduce la caja; no aumenta tu capital inicial ni cuenta como un aporte nuevo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ClientPicker(title: String, clients: List<String>, loading: Boolean, selected: String,
    allowAll: Boolean = true, dismiss: () -> Unit, select: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Buscar por nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando clientes de todos los años…", style = MaterialTheme.typography.bodySmall) }
            val visible = clients.filter { normalizedClient(it).contains(normalizedClient(query)) }
            Text("${visible.size} clientes", style = MaterialTheme.typography.labelMedium)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (allowAll) TextButton(onClick = { select("") }, modifier = Modifier.fillMaxWidth()) { Text("Todos los clientes") }
                visible.forEach { name ->
                    OutlinedCard(onClick = { select(name) }, modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(containerColor = if (name == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ClientAvatar(name)
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (visible.isEmpty() && !loading) Text("No hay clientes que coincidan.")
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Cerrar") } })
}

private fun dateMillis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun dateAt(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChooseDisbursementDate(selected: LocalDate, dismiss: () -> Unit, allowedYear: Int? = null, firstAllowed: LocalDate = FIRST_LOAN_DISBURSEMENT, title: String = "Fecha de desembolso", apply: (LocalDate) -> Unit) {
    val first = maxOf(firstAllowed, LocalDate.of(allowedYear ?: 2026, 1, 1))
    val last = minOf(today(), LocalDate.of(allowedYear ?: 2028, 12, 31))
    val picker = rememberDatePickerState(initialSelectedDateMillis = dateMillis(selected),
        initialDisplayedMonthMillis = dateMillis(selected), yearRange = first.year..last.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = dateAt(utcTimeMillis) in first..last
            override fun isSelectableYear(year: Int) = year in first.year..last.year
        })
    DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
        TextButton(onClick = { picker.selectedDateMillis?.let { apply(dateAt(it)) } },
            enabled = picker.selectedDateMillis?.let { dateAt(it) in first..last } == true) { Text("Elegir fecha") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }) {
        DatePicker(picker, title = { Text(title, Modifier.padding(24.dp)) }, showModeToggle = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChoosePaymentDate(due: LocalDate, selected: LocalDate?, dismiss: () -> Unit, apply: (LocalDate) -> Unit) {
    val last = today()
    val picker = rememberDatePickerState(initialSelectedDateMillis = selected?.let(::dateMillis),
        initialDisplayedMonthMillis = dateMillis(selected ?: due), yearRange = due.year..last.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = dateAt(utcTimeMillis) in due..last
            override fun isSelectableYear(year: Int) = year in due.year..last.year
        })
    DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
        TextButton(onClick = { picker.selectedDateMillis?.let { apply(dateAt(it)) } }, enabled = picker.selectedDateMillis != null) { Text("Elegir fecha") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }) {
        DatePicker(picker, title = { Text("Fecha real de cobro", Modifier.padding(24.dp)) }, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PaymentDates(from: LocalDate, to: LocalDate, dismiss: () -> Unit, apply: (LocalDate, LocalDate) -> Unit) {
    val last = today()
    val calendarHeight = (LocalConfiguration.current.screenHeightDp - 340).coerceIn(220, 420).dp
    val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = dateMillis(from), initialSelectedEndDateMillis = dateMillis(to),
        initialDisplayedMonthMillis = dateMillis(from), yearRange = 2026..minOf(last.year, 2028),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = dateAt(utcTimeMillis) in LocalDate.of(2026, 1, 1)..last
            override fun isSelectableYear(year: Int): Boolean = year in 2026..minOf(last.year, 2028)
        })
    DatePickerDialog(onDismissRequest = dismiss, confirmButton = {
        TextButton(onClick = { apply(dateAt(picker.selectedStartDateMillis!!), dateAt(picker.selectedEndDateMillis!!)) },
            enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) { Text("Aplicar rango") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }) {
        Text("Rango de cuotas", Modifier.padding(start = 24.dp, top = 20.dp), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Hoy" to 0L, "7 días" to 6L, "30 días" to 29L).forEach { (label, days) ->
                TextButton(onClick = { picker.setSelection(dateMillis(maxOf(last.minusDays(days), LocalDate.of(2026, 1, 1))), dateMillis(last)) }) { Text(label) }
            }
        }
        Text("Selecciona inicio y fin. Puedes cambiar el mes o el año.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
        DateRangePicker(state = picker, modifier = Modifier.fillMaxWidth().height(calendarHeight), title = null, showModeToggle = true)
    }
}

@Composable internal fun ColumnScope.PaymentsScreen(state: SheetsState, model: SheetsViewModel, writable: Boolean, entry: Int,
    onOpen: (SheetLoan) -> Unit, active: Boolean = true) {
    val day by produceState(initialValue = today()) { while (true) { delay(30_000); value = today() } }
    LaunchedEffect(active, day, entry) { if (active) model.payments(day, day) }
    LoadSearch(active, state, model)
    val catalog = state.searchLoans + state.loans + state.data?.loans.orEmpty()
    val groups = clientLoanGroups(catalog, day).map { loans ->
        loans to loans.filter { localLoanView(it, state.financialEdits, day).active }
    }.filter { it.second.isNotEmpty() }
    if (groups.isEmpty()) {
        EmptyLoansMessage(if (!state.searchLoaded) "Cargando clientes y préstamos…"
            else "No hay préstamos activos para cobrar.", Modifier.weight(1f))
    } else {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (loans, visible) -> key(normalizedClient(loans.first().client)) {
                ClientPayments(loans, visible, state, model, writable, day, onOpen)
            } }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable internal fun SelectedClientChip(client: String, clear: () -> Unit, open: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = open, label = { Text(client) })
        TextButton(onClick = clear) { Text("Quitar filtro") }
    }
}

package com.example.controlprestamos.ui.google

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.today
import com.example.controlprestamos.domain.dateLabel
import com.example.controlprestamos.domain.soles
import kotlinx.coroutines.launch
import com.example.controlprestamos.ui.FinancialBreakdown
import com.example.controlprestamos.ui.FinancialLine
import java.math.RoundingMode
import java.time.LocalDate

@Composable internal fun LoanBookFields(loan: SheetLoan, simple: Boolean = false) {
    FinancialBreakdown("Acuerdo del préstamo", "${dateLabel(loan.firstDue.minusDays(1))} — ${dateLabel(loan.lastDue)}") {
        FinancialLine("Capital entregado", soles(loan.principal), true)
        FinancialLine("Tasa mensual", "${loan.ratePercent.stripTrailingZeros().toPlainString()} %")
        FinancialLine("Ganancia esperada · interés pactado", soles(loan.agreed - loan.principal))
        FinancialLine("Total a recibir · capital + interés", soles(loan.agreed), true)
        FinancialLine("Mora por día de atraso", soles(loan.dailyLate))
    }
    FinancialBreakdown("Calendario", "Meses de 30 días · ${loan.total} cuotas") {
        FinancialLine("Fecha de desembolso", dateLabel(loan.firstDue.minusDays(1)))
        FinancialLine("Plazo", "${loan.months} meses")
        FinancialLine("Primera cuota", dateLabel(loan.firstDue))
        FinancialLine("Última cuota", dateLabel(loan.lastDue))
        FinancialLine("Base de la primera cuota", soles(loan.baseOn(loan.firstDue)))
        FinancialLine("Cuota base habitual", soles(loan.daily))
        if (loan.scheduledBases.isNotEmpty()) Text("El cronograma conserva las cuotas cobradas. Consulta el importe exacto de cada día en Evolución.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FinancialBreakdown("Cobros y respaldo", if (simple) "Resumen del préstamo" else "Resumen del libro de ${loan.year}") {
        FinancialLine(if (simple) "Total cobrado" else "Cobrado en ${loan.year}", soles(loan.collected))
        FinancialLine("Cuotas cobradas", "${loan.paid} de ${loan.total}")
        FinancialLine("Saldo pendiente", soles(loan.debt), true)
        Text("Garantía y observaciones", style = MaterialTheme.typography.labelLarge)
        Text(loan.guarantee.ifBlank { "Sin garantía registrada" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun LoanEvolution(loan: SheetLoan, state: SheetsState, model: SheetsViewModel, onDayPayment: (LocalDate) -> Unit) {
    var shown by rememberSaveable(loan.id) { mutableStateOf(true) }
    var hidePaid by rememberSaveable(loan.id) { mutableStateOf(false) }
    val scheduleKey = paymentTerms(loan)
    var selected by rememberSaveable(loan.id, scheduleKey) { mutableStateOf<String?>(null) }
    var collecting by rememberSaveable(loan.id, scheduleKey) { mutableStateOf<String?>(null) }
    val originalCalendar = state.calendar?.takeIf { calendarMatchesLoan(it, loan) }
    val currentDay = today()
    val calendar = remember(originalCalendar, state.edits, currentDay) {
        originalCalendar?.let { calendarWithDrafts(it, state.edits, currentDay) }
    }
    val writable = model.connection.canWrite(model.bookId) && state.paymentConflict == null &&
        !pendingLocksPayments(state.pending)
    fun originalPayment(day: SheetCalendarDay) = state.edits[day.payment?.id]?.first
        ?: originalCalendar?.days?.find { it.date == day.date }?.payment
    fun canEdit(day: SheetCalendarDay): Boolean {
        val row = originalPayment(day) ?: return false
        return writable && !row.version.isNullOrBlank() && day.date <= today() &&
            row.id !in state.pendingPaymentIds && state.pendingLoanEdit == null
    }
    val error = state.calendarError.takeIf { state.calendarRequestedId == loan.id }
    LaunchedEffect(scheduleKey, shown, state.busy, state.saving, state.cachedOnly, state.calendarRequestedKey, calendar) {
        if (shown && calendar == null && state.calendarRequestedKey != scheduleKey && !state.busy && !state.saving && (!state.cachedOnly || state.localAvailable)) model.calendar(loan)
    }
    Text("Evolución del préstamo", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { shown = !shown }) { Text(if (shown) "Ocultar calendario" else "Mostrar todos los días") }
        if (shown && calendar != null) TextButton(onClick = { model.calendar(loan, refresh = true) },
            enabled = !state.busy && !state.saving && !state.cachedOnly) { Text("Actualizar") }
    }
    if (!shown) return
    Text("Toca el estado de un día para cambiarlo.", style = MaterialTheme.typography.bodySmall)
    if (calendar == null) {
        Text(when { error != null -> error; state.cachedOnly && !state.localAvailable -> "Conecta Google para cargar todos los días de este préstamo."; else -> "Cargando los días del préstamo…" })
        if (!state.busy && !state.saving && (!state.cachedOnly || state.localAvailable)) TextButton(onClick = { model.calendar(loan) }) { Text("Cargar calendario") }
        return
    }
    BulkPaymentSelector(originalCalendar?.days.orEmpty().mapNotNull { it.payment },
        writable && !state.busy && !state.saving && state.pending == null && state.pendingLoanEdit == null, model)
    if (calendar.days.any { it.payment?.version == null && it.payment != null })
        Text("Recargando las cuotas guardadas. Si siguen deshabilitadas, pulsa Actualizar para recuperar su versión editable.", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val paid = calendar.days.count { it.status == CalendarStatus.PAID }
    val pending = calendar.days.count { it.status == CalendarStatus.PENDING }
    val future = calendar.days.count { it.status == CalendarStatus.FUTURE }
    Text("${calendar.days.size} días · $paid pagadas · $pending pendientes · $future futuras", style = MaterialTheme.typography.labelLarge)
    if (calendar.days.any { it.status == CalendarStatus.UNAVAILABLE || it.status == CalendarStatus.INVALID })
        Text("Hay días sin una cuota válida disponible en las hojas del libro. Se muestran sin atribuirles un pago.", style = MaterialTheme.typography.bodySmall)
    FilterChip(hidePaid, onClick = { hidePaid = !hidePaid }, label = { Text("Ocultar pagadas") })
    val visible = remember(calendar, hidePaid) { calendar.days.filter { !hidePaid || it.status != CalendarStatus.PAID } }
    if (visible.isEmpty()) { Text("Todas las cuotas están pagadas. Desactiva Ocultar pagadas para verlas."); return }
    key(loan.id, scheduleKey, hidePaid) {
        val initial = visible.indexOfFirst { it.date == today() }.takeIf { it >= 0 }
            ?: visible.indexOfFirst { it.status == CalendarStatus.PENDING }.coerceAtLeast(0)
        val scroll = rememberLazyListState(initialFirstVisibleItemIndex = initial)
        val scope = rememberCoroutineScope()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope.launch { scroll.animateScrollToItem(0) } }) { Text("Inicio") }
            TextButton(onClick = {
                val index = visible.indexOfFirst { it.date == today() }
                if (index >= 0) scope.launch { scroll.animateScrollToItem(index) }
            }, enabled = visible.any { it.date == today() }) { Text("Hoy") }
            TextButton(onClick = {
                val index = visible.indexOfFirst { it.status == CalendarStatus.PENDING }
                if (index >= 0) scope.launch { scroll.animateScrollToItem(index) }
            }, enabled = visible.any { it.status == CalendarStatus.PENDING }) { Text("Primera pendiente") }
            TextButton(onClick = { scope.launch { scroll.animateScrollToItem(visible.lastIndex) } }) { Text("Final") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(78.dp)) {
                CalendarCell("Día", header = true)
                CalendarCell("Válida")
                CalendarCell("Cuota")
                CalendarCell("Pago")
                CalendarCell("Monto")
                CalendarCell("Mora")
                CalendarCell("Estado")
            }
            LazyRow(state = scroll, modifier = Modifier.weight(1f).height(316.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.date.toString() }) { day ->
                    CalendarDayColumn(day, selected == day.date.toString(), editable = canEdit(day),
                        onSelect = { selected = day.date.toString() }, onPayment = {
                            val row = originalPayment(day) ?: return@CalendarDayColumn
                            if (day.status == CalendarStatus.PAID) model.stage(row, true, 0)
                            else if (!row.unpaid) model.stage(row, false, row.late, row.paidOn)
                            else collecting = day.date.toString()
                        })
                }
            }
        }
        Text("Desliza los días hacia los lados. Toca uno para ver el detalle.", style = MaterialTheme.typography.bodySmall)
    }
    val chosen = calendar.days.find { it.date.toString() == selected }
    if (chosen != null) {
        originalPayment(chosen)?.let { PaymentCard(it, state, model, writable = writable) }
            ?: Text("${dateLabel(chosen.date)} · ${calendarStatusLabel(chosen.status)} · Cuota ${chosen.installment}/${loan.total}")
        if (chosen.status == CalendarStatus.FUTURE) Text("Cuota prevista: ${soles(loan.baseOn(chosen.date))}", style = MaterialTheme.typography.bodySmall)
        if (chosen.payment != null && chosen.date <= today() && !canEdit(chosen)) OutlinedButton(onClick = { onDayPayment(chosen.date) },
            enabled = !state.busy && !state.saving && !state.cachedOnly && state.pendingLoanEdit == null) {
            Text("Abrir este día en Pagos")
        }
        TextButton(onClick = { selected = null }) { Text("Ocultar detalle del día") }
    }
    val collectingDay = calendar.days.find { it.date.toString() == collecting }
    val collectingRow = collectingDay?.let(::originalPayment)
    if (collectingDay != null && collectingRow != null) RecordedLateDialog(collectingRow,
        collectingDay.bookLate ?: 0, state.edits[collectingRow.id]?.second?.paidOn, canEdit(collectingDay),
        dismiss = { collecting = null }) { fee, date ->
        model.stage(collectingRow, false, fee, date); collecting = null
    }
}

@Composable private fun CalendarCell(text: String, header: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(text, style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun calendarStatusLabel(status: CalendarStatus) = when (status) {
    CalendarStatus.PAID -> "Pagó"
    CalendarStatus.PENDING -> "No pagó"
    CalendarStatus.FUTURE -> "Futura"
    CalendarStatus.INVALID -> "No válida"
    CalendarStatus.UNAVAILABLE -> "Sin datos"
}

@Composable private fun CalendarDayColumn(day: SheetCalendarDay, selected: Boolean, editable: Boolean,
    onSelect: () -> Unit, onPayment: () -> Unit) {
    val color = when (day.status) {
        CalendarStatus.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
        CalendarStatus.PAID -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(onClick = onSelect, modifier = Modifier.width(118.dp), colors = CardDefaults.cardColors(containerColor = color),
        border = if (day.date == today() || selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Column {
            CalendarCell("${if (day.date == today()) "HOY · " else ""}${day.date.dayOfMonth}/${day.date.monthValue}/${day.date.year}", header = true)
            CalendarCell(when (day.valid) { true -> "Sí"; false -> "No"; null -> "—" })
            CalendarCell(day.recordedInstallment?.toString() ?: "—")
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                if (day.payment != null) TextButton(onClick = onPayment, enabled = editable,
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (day.status == CalendarStatus.PENDING) "No pagó" else "Pagado", fontWeight = FontWeight.Bold)
                } else Text("—")
            }
            CalendarCell(day.bookAmount?.let(::soles) ?: "—")
            CalendarCell(day.bookLate?.let(::soles) ?: "—")
            CalendarCell(calendarStatusLabel(day.status))
        }
    }
}

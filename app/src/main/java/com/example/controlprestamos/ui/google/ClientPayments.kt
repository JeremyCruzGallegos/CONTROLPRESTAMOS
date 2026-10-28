package com.example.controlprestamos.ui.google

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.FittedSingleLine
import java.time.LocalDate

@Composable internal fun ClientPayments(allLoans: List<SheetLoan>, loans: List<SheetLoan>, state: SheetsState, model: SheetsViewModel,
    writable: Boolean, day: LocalDate, onOpen: (SheetLoan) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf(loans.last().id) }
    val selected = loans.firstOrNull { it.id == selectedId } ?: loans.last()
    var amountRequested by rememberSaveable(selected.id) { mutableStateOf(false) }
    val effective = localLoanView(selected, state.financialEdits, day)
    val rows = (state.forecastPayments + state.data?.todayPayments.orEmpty()).distinctBy { it.id }
    val balances = loans.map { loanBalances(it, rows, state.financialEdits, day) }
    val complete = balances.all { it != null }
    val breakdown = loanDebtBreakdown(selected, rows, state.financialEdits, day)
    val calendar = state.calendar?.takeIf { calendarMatchesLoan(it, selected) }
    val colors = MaterialTheme.colorScheme
    fun toggle() { expanded = !expanded; if (!expanded) amountRequested = false }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                onClickLabel = if (expanded) "Resumir pagos del cliente" else "Desplegar pagos del cliente", onClick = ::toggle),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClientAvatar(selected.client, size = 34.dp)
                Text(selected.client, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${loans.size} préstamo${if (loans.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
                DisclosureButton(expanded, if (expanded) "Resumir pagos de ${selected.client}" else "Desplegar pagos de ${selected.client}", ::toggle)
            }
            Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BalanceAmount("Por cobrar a hoy", if (complete) balances.sumOf { it!!.dueToday } else null,
                        Modifier.weight(1f), prominent = true)
                    BalanceAmount("Deuda total", if (complete) balances.sumOf { it!!.pending } else null, Modifier.weight(1f))
                }
            }
            if (expanded) {
                LoanNumberPicker(allLoans, loans, selected.id, state, day, select = { selectedId = it; amountRequested = false })
                LoanDebtBar(breakdown, effective.paid, effective.total)
                val todayRows = rows.filter { it.loan.id == selected.id && it.due == day }
                todayRows.forEach { row -> key(row.id) {
                    PaymentCard(row, state, model, writable, grouped = true, showDueDate = false, compact = true)
                } }
                if (todayRows.isEmpty()) Text(if (!effective.active) "Préstamo concluido. No tiene cuotas pendientes."
                    else "No hay cuota para hoy. Consulta los días anteriores en el calendario.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedButton(onClick = { amountRequested = true; model.calendar(selected) }, modifier = Modifier.fillMaxWidth(),
                    enabled = writable && effective.active && !state.saving && !pendingLocksPayments(state.pending) &&
                        state.financialEdits.values.none { it.first.loan.id == selected.id }) { Text("Pago múltiple") }
                if (amountRequested && calendar == null) {
                    Text(state.calendarError?.takeIf { state.calendarRequestedId == selected.id } ?: "Cargando cuotas del préstamo…",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { amountRequested = false }) { Text("Cancelar") }
                }
                TextButton(onClick = { onOpen(selected) }, enabled = !state.busy, modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Calendario y ficha →") }
            }
        }
    }
    if (expanded && amountRequested && calendar != null) AmountPaymentDialog(selected, calendar, dismiss = { amountRequested = false }) { amount, waive ->
        model.amountPayment(selected, amount, waive); amountRequested = false
    }
}

@Composable private fun LoanNumberPicker(allLoans: List<SheetLoan>, loans: List<SheetLoan>, selectedId: String, state: SheetsState, day: LocalDate, select: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val index = loans.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = index)
    LaunchedEffect(selectedId, loans.map { it.id }) {
        if (scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Elegir préstamo", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        LazyRow(Modifier.fillMaxWidth(), state = scroll, horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp)) {
            itemsIndexed(loans, key = { _, loan -> loan.id }) { _, loan ->
                val number = allLoans.indexOfFirst { it.id == loan.id } + 1
                val standing = loanStanding(loan, state.forecastPayments + state.data?.todayPayments.orEmpty(), state.financialEdits, day)
                LoanNumberBadge(number, standing, chosen = loan.id == selectedId, click = { select(loan.id) })
            }
        }
    }
}

@Composable private fun BalanceAmount(label: String, amount: Long?, modifier: Modifier, prominent: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        FittedSingleLine(amount?.let(::soles) ?: "Calculando…", style = (if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
            .copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center,
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

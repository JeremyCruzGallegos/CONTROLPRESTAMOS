package com.example.controlprestamos.ui.google

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlprestamos.R
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.FittedSingleLine
import com.example.controlprestamos.ui.portfolioRowPalette
import com.example.controlprestamos.ui.rememberChartAnimation
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal const val LOAN_PROGRESS_DURATION_MILLIS = 933

@Composable internal fun PortfolioClientTable(allLoans: List<SheetLoan>, visible: List<SheetLoan>, payments: List<SheetPayment>,
    changes: PaymentChanges, day: LocalDate, active: Boolean, selecting: Boolean, selectedIds: List<String>,
    toggle: (SheetLoan) -> Unit, open: (SheetLoan) -> Unit, selectAll: () -> Unit,
    canCreate: Boolean, create: () -> Unit, onClient: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val moneyStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val headerStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val loanPayments = remember(payments, allLoans) {
        val ids = allLoans.map { it.id }.toSet()
        payments.filter { it.loan.id in ids }.groupBy { it.loan.id }
    }
    val balances = remember(visible, loanPayments, changes, day) {
        visible.associate { it.id to loanBalances(it, loanPayments[it.id].orEmpty(), changes, day) }
    }
    fun amount(value: Long?) = value?.let { soles(it).removePrefix("S/ ") } ?: "—"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (selecting) Checkbox(checked = visible.all { it.id in selectedIds }, onCheckedChange = { selectAll() },
                    modifier = Modifier.semantics { contentDescription = "Seleccionar préstamos de ${allLoans.first().client}" })
                ClientAvatar(allLoans.first().client, size = 30.dp)
                Text(allLoans.first().client, Modifier.weight(1f).clickable(enabled = !selecting, onClick = onClient), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${visible.size} préstamo${if (visible.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, color = colors.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val numberWidth = if (selecting) 64.dp else 48.dp
                val moneyWidth = ((maxWidth - numberWidth) / 3).coerceAtLeast(1.dp)
                val dateWidth = numberWidth + moneyWidth
                val progressWidth = moneyWidth * 2
                Column {
                    Surface(color = colors.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("N.º", Modifier.width(numberWidth), textAlign = TextAlign.Center, style = headerStyle)
                            listOf("Prestado", "Pendiente", "A hoy").forEach { label ->
                                FittedSingleLine(label, Modifier.width(moneyWidth).padding(horizontal = 4.dp), style = headerStyle,
                                    color = colors.onSurfaceVariant, minimumSp = 8f, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    visible.forEach { original -> key(original.id) {
                        val loan = localLoanView(original, changes, day)
                        val number = allLoans.indexOfFirst { it.id == loan.id } + 1
                        val standing = loanStanding(original, loanPayments[original.id].orEmpty(), changes, day)
                        val closed = standing == LoanStanding.CLOSED
                        var expanded by rememberSaveable(loan.id, loan.active) { mutableStateOf(false) }
                        val collapsed = closed && !expanded && !selecting
                        val rowColors = portfolioRowPalette(colors, closed)
                        val checked = original.id in selectedIds
                        val click = { if (selecting) toggle(original) else if (collapsed) expanded = true else open(loan) }
                        val interaction = if (selecting) Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { toggle(original) })
                            else Modifier.clickable(role = Role.Button,
                                onClickLabel = if (collapsed) "Desplegar préstamo concluido $number" else "Ver ficha del préstamo $number", onClick = click)
                        Column(Modifier.fillMaxWidth().background(rowColors.background).then(interaction)
                            .heightIn(min = if (collapsed) 36.dp else 48.dp).padding(vertical = 2.dp), verticalArrangement = Arrangement.Center) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val numberAction = if (closed && !selecting && !collapsed) Modifier.clickable(role = Role.Button,
                                    onClickLabel = "Resumir préstamo concluido $number", onClick = { expanded = false }) else Modifier
                                Row(Modifier.width(numberWidth).then(numberAction), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center) {
                                    if (selecting) Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(20.dp))
                                    LoanNumberBadge(number, standing, chosen = selecting && checked, compact = true, click = click)
                                    if (closed && !selecting) DisclosureChevron(!collapsed, rowColors.foreground)
                                }
                                if (collapsed) {
                                    FittedSingleLine(amount(loan.principal), Modifier.width(moneyWidth).padding(horizontal = 4.dp),
                                        style = moneyStyle, color = rowColors.foreground, textAlign = TextAlign.Center)
                                    Text("Concluido", Modifier.width(moneyWidth * 2), style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center, color = rowColors.foreground)
                                } else {
                                    listOf(amount(loan.principal), amount(balances[loan.id]?.pending), amount(balances[loan.id]?.dueToday)).forEach { value ->
                                        FittedSingleLine(value, Modifier.width(moneyWidth).padding(horizontal = 4.dp), style = moneyStyle,
                                            color = rowColors.foreground, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            if (!collapsed) {
                                val animation = rememberChartAnimation(listOf("portfolio-progress", loan.id, loan.paid, loan.total), active,
                                    durationMillis = LOAN_PROGRESS_DURATION_MILLIS, accelerating = true)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    PortfolioDates(loan.firstDue.minusDays(1), loan.lastDue, rowColors.secondary,
                                        Modifier.width(dateWidth).padding(start = 6.dp, end = 4.dp))
                                    LoanProgressBar(loan.paid, loan.total, standing, animation.progress,
                                        Modifier.width(progressWidth).padding(start = 4.dp, end = 6.dp).then(animation.viewport))
                                }
                                if (standing == LoanStanding.UNVERIFIED) Text("Detalle de pagos pendiente de actualizar", Modifier.fillMaxWidth().padding(top = 3.dp),
                                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = rowColors.secondary)
                            }
                        }
                        if (original.id != visible.last().id) HorizontalDivider(color = colors.outlineVariant)
                    } }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            TextButton(onClick = create, enabled = canCreate, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                Text("Nuevo préstamo", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Icon(painterResource(R.drawable.ic_add), "Añadir préstamo para ${allLoans.first().client}", Modifier.size(20.dp))
            }
        }
    }
}

@Composable private fun PortfolioDates(start: LocalDate, end: LocalDate, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val text = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.semantics { contentDescription = "Desembolso ${dateLabel(start)}, vencimiento ${dateLabel(end)}" }) {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yy")
        val range = "${start.format(formatter)} — ${end.format(formatter)}"
        val fits = text.measure(range, style).size.width <= with(density) { maxWidth.toPx() }
        if (fits) Text(range, Modifier.fillMaxWidth(), style = style, textAlign = TextAlign.Center, color = color, maxLines = 1, softWrap = false)
        else Column {
            FittedSingleLine(start.format(formatter), style = style, color = color, textAlign = TextAlign.Center)
            FittedSingleLine(end.format(formatter), style = style, color = color, textAlign = TextAlign.Center)
        }
    }
}

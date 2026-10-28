package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.FinancialBreakdown
import com.example.controlprestamos.ui.FinancialHero
import com.example.controlprestamos.ui.FinancialChangeLine
import com.example.controlprestamos.ui.FinancialLine
import java.math.BigDecimal
import java.time.LocalDate

@Composable internal fun EditLoanTermsDialog(loan: SheetLoan, calendar: LoanCalendar?, cash: Long?, enabled: Boolean,
    copy: Boolean, dismiss: () -> Unit, reload: () -> Unit, save: (LoanTermsInput, String) -> Unit) {
    var client by rememberSaveable(loan.id) { mutableStateOf(loan.client) }
    var guarantee by rememberSaveable(loan.id) { mutableStateOf(loan.guarantee) }
    var principal by rememberSaveable(loan.id) { mutableStateOf(decimalMoney(loan.principal)) }
    var rate by rememberSaveable(loan.id) { mutableStateOf(loan.ratePercent.stripTrailingZeros().toPlainString()) }
    var months by rememberSaveable(loan.id) { mutableStateOf(loan.months.toString()) }
    var date by rememberSaveable(loan.id) { mutableStateOf(loan.firstDue.minusDays(1).toString()) }
    var late by rememberSaveable(loan.id) { mutableStateOf(decimalMoney(loan.dailyLate)) }
    var chooseDate by remember { mutableStateOf(false) }
    var review by rememberSaveable(loan.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val input = runCatching { LoanTermsInput(client.trim(), guarantee.trim(), parseMoney(principal) ?: error("Revisa el monto"),
        BigDecimal(rate.replace(',', '.')), months.toInt(), LocalDate.parse(date), parseMoney(late) ?: error("Revisa la mora diaria")) }
    val plan = runCatching {
        planLoanTerms(loan, requireNotNull(calendar) { "Carga el calendario completo antes de editar" }, input.getOrThrow(), today(), requireNotNull(cash) { "Actualiza Inicio para consultar la caja" })
    }
    val preview = plan.getOrNull()
    val changed = input.getOrNull()?.let { it.client != loan.client || it.guarantee != loan.guarantee || it.principal != loan.principal ||
        it.rate.compareTo(loan.ratePercent) != 0 || it.months != loan.months || it.disbursedOn != loan.firstDue.minusDays(1) || it.dailyLate != loan.dailyLate } == true
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (review) "Revisar nuevo acuerdo" else "Editar préstamo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusBadge(if (copy) "Libro de PRUEBAS" else "Libro OFICIAL", !copy)
            Text("${loan.client} · ${loan.id}", style = MaterialTheme.typography.labelLarge)
            if (!review) {
                TermField("Cliente", client, { client = it; error = null })
                TermField("Monto prestado · S/", principal, { principal = it; error = null }, numeric = true)
                OutlinedButton(onClick = { chooseDate = true }, modifier = Modifier.fillMaxWidth()) { Text("Inicio · ${dateLabel(LocalDate.parse(date))}") }
                Text("Fecha en que se entregó el dinero. La primera cuota vence al día siguiente.", style = MaterialTheme.typography.bodySmall)
                TermField("Tasa mensual · %", rate, { rate = it; error = null }, numeric = true)
                TermField("Plazo · meses de 30 días", months, { months = it; error = null }, numeric = true)
                TermField("Mora por día de atraso · S/", late, { late = it; error = null }, numeric = true)
                Text("S/ 1.00 por defecto. Puedes reducirla o poner cero; lo ya cobrado conserva su importe.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { late = "0" }) { Text("Sin mora") }
                    TextButton(onClick = { late = "1.00" }) { Text("S/ 1 al día") }
                }
                OutlinedTextField(guarantee, { guarantee = it; error = null }, label = { Text("Garantía / observaciones") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                if (preview != null) FinancialHero("Saldo del nuevo acuerdo", preview.remaining + preview.pendingLate,
                    "${preview.pendingCount} cuotas pendientes · incluye mora acumulada a hoy")
                if (calendar == null) TextButton(onClick = reload, enabled = enabled) { Text("Cargar calendario para calcular") }
                Text("Los cobros conservan su importe y fecha de cuota. Al cambiar el acuerdo se reparte el saldo entre las cuotas pendientes. La fecha de inicio conserva el año ${loan.id.substringBefore('-')}.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (preview != null) {
                FinancialHero("Saldo por cobrar", preview.remaining + preview.pendingLate,
                    "${preview.pendingCount} cuotas pendientes después del cambio")
                FinancialBreakdown("Acuerdo", "Anterior → nuevo") {
                    FinancialChangeLine("Monto", soles(loan.principal), soles(preview.input.principal))
                    FinancialChangeLine("Tasa mensual", "${loan.ratePercent.stripTrailingZeros().toPlainString()}%", "${preview.input.rate.stripTrailingZeros().toPlainString()}%")
                    FinancialChangeLine("Plazo", "${loan.months} meses", "${preview.input.months} meses")
                    FinancialChangeLine("Desembolso", dateLabel(loan.firstDue.minusDays(1)), dateLabel(preview.input.disbursedOn))
                    FinancialChangeLine("Total pactado", soles(loan.agreed), soles(preview.agreed))
                }
                FinancialBreakdown("Cronograma y cobros") {
                    FinancialLine("Base ya cobrada", soles(preview.collectedBase))
                    FinancialLine("Mora ya cobrada", soles(preview.collectedLate))
                    FinancialLine("Saldo sin mora", soles(preview.remaining), true)
                    FinancialLine("Primera cuota pendiente", soles(preview.nextBase))
                    FinancialLine("Primera cuota", dateLabel(preview.firstDue))
                    FinancialLine("Última cuota", dateLabel(preview.lastDue))
                    FinancialChangeLine("Mora diaria", soles(loan.dailyLate), soles(preview.input.dailyLate))
                    FinancialLine("Mora pendiente a hoy", soles(preview.pendingLate))
                    Text("Al cambiar el acuerdo, el redondeo del saldo se reparte entre las cuotas pendientes, con una diferencia máxima de un céntimo.", style = MaterialTheme.typography.bodySmall)
                }
                FinancialBreakdown("Efecto en caja") {
                    FinancialLine("Caja actual", soles(cash ?: 0))
                    FinancialLine("Caja corregida", soles(preview.cashAfter), true)
                    Text("Corregir monto o fecha actualiza el desembolso y los reportes históricos. Los aportes externos conservan su monto.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Se guardará el respaldo del acuerdo anterior y de las celdas modificadas.", style = MaterialTheme.typography.bodySmall)
            }
            if (changed && preview == null) Text(plan.exceptionOrNull()?.message ?: "Revisa los campos", color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (!review) review = true else runCatching { val result = plan.getOrThrow(); save(result.input, result.confirmation()) }
            .onFailure { error = it.message }
    }, enabled = enabled && changed && preview != null) { Text(if (review) "Confirmar y guardar" else "Revisar cambios") } },
        dismissButton = { TextButton(onClick = { if (review) review = false else dismiss() }) { Text(if (review) "Corregir" else "Cancelar") } })
    if (chooseDate) ChooseDisbursementDate(LocalDate.parse(date), dismiss = { chooseDate = false }, allowedYear = loan.id.substringBefore('-').toInt()) {
        date = it.toString(); chooseDate = false; error = null
    }
}

@Composable private fun TermField(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

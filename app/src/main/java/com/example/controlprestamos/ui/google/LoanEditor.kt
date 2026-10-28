package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*

@Composable internal fun DeleteLoanDialog(loan: SheetLoan, enabled: Boolean, copy: Boolean, dismiss: () -> Unit, delete: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("¿Eliminar este préstamo?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (copy) "Libro de PRUEBAS" else "Libro OFICIAL", fontWeight = FontWeight.Bold)
            Text(loan.client, fontWeight = FontWeight.Bold)
            Text("Préstamo ${loan.id} · ${soles(loan.principal)}")
            Text("Se eliminará este préstamo de todos los años donde aparece, junto con sus cuotas y moras. También se anulará el aporte de capital vinculado, si existe.")
            Text("Los saldos, la caja y los reportes se recalcularán como si este préstamo no se hubiera registrado. Los otros préstamos de este cliente se conservan.")
            Text("Se guardará un respaldo en la hoja. Esta acción no se puede deshacer desde la app.", fontWeight = FontWeight.SemiBold)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching(delete).onFailure { error = it.message ?: "No se pudo iniciar la eliminación" } },
        enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Sí, eliminar préstamo") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } })
}

@Composable internal fun EditLoanDialog(loan: SheetLoan, enabled: Boolean, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var client by rememberSaveable(loan.id) { mutableStateOf(loan.client) }
    var guarantee by rememberSaveable(loan.id) { mutableStateOf(loan.guarantee) }
    var error by remember { mutableStateOf<String?>(null) }
    val changed = client.trim() != loan.client || guarantee.trim() != loan.guarantee
    AlertDialog(onDismissRequest = dismiss, title = { Text("Editar préstamo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${dateLabel(loan.firstDue.minusDays(1))} — ${dateLabel(loan.lastDue)}", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(client, { client = it; error = null }, label = { Text("Nombre del cliente") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), supportingText = { Text("Se cambia solo este préstamo") })
            OutlinedTextField(guarantee, { guarantee = it; error = null }, label = { Text("Garantía") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
            Text("Los cambios se guardan en la hoja y se reflejan en los años donde aparece este préstamo.", style = MaterialTheme.typography.bodySmall)
            Text("Monto, tasa y plazo: ${soles(loan.principal)} · ${loan.ratePercent.stripTrailingZeros().toPlainString()}% · ${loan.months} meses", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { LoanTextEdit(loan.id, loan.client, loan.guarantee, client.trim(), guarantee.trim()); save(client.trim(), guarantee.trim()) }
            .onFailure { error = it.message ?: "Revisa los datos" }
    }, enabled = enabled && changed && client.isNotBlank()) { Text("Guardar en la hoja") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } })
}

@Composable internal fun LoanHistory(state: SheetsState, model: SheetsViewModel, source: List<SheetLoan>, filtered: List<SheetLoan>) {
    var shown by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { shown = !shown }, modifier = Modifier.fillMaxWidth()) {
        Text(if (shown) "Ocultar historial de cambios ▴" else "Ver historial de cambios de pagos ▾")
    }
    if (!shown) return
    OutlinedButton(onClick = model::history, enabled = !state.busy && !state.cachedOnly) { Text(if (state.history.isEmpty()) "Consultar historial" else "Cargar más registros") }
    if (state.history.isEmpty()) Text("Sin registros cargados. Los pagos antiguos pueden no tener fecha real verificable.", style = MaterialTheme.typography.bodySmall)
    val ids = filtered.map { it.id }.toSet()
    state.history.filter { historyLoanId(it) in ids }.asReversed().forEach { row ->
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val loan = source.find { it.id == historyLoanId(row) }
            Text(loan?.client ?: row.getOrNull(10)?.toString() ?: "Préstamo", fontWeight = FontWeight.Bold)
            Text("Préstamo ${historyLoanId(row) ?: row.getOrNull(3)}", style = MaterialTheme.typography.labelMedium)
            Text("Cuota: ${runCatching { dateLabel(sheetDate(row.getOrNull(4))) }.getOrDefault("Sin fecha válida")}")
            Text("${if (row.getOrNull(6) == true) "No pagó" else "Pagó"} · Mora: ${runCatching { soles(sheetCents(row.getOrNull(8))) }.getOrDefault("No disponible")}")
            Text("Registrado: ${row.getOrNull(0)}", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

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
import com.example.controlprestamos.data.google.SheetLoan
import com.example.controlprestamos.domain.*

@Composable internal fun DeleteSelectedLoansDialog(loans: List<SheetLoan>, state: SheetsState, model: SheetsViewModel,
    dismiss: () -> Unit, deleted: () -> Unit) {
    val selected = remember { loans.toList() }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = selected.isNotEmpty() && state.ready && !state.saving && state.sensitiveRequest == null &&
        state.paymentConflict == null && state.financialEdits.isEmpty() && state.unconfirmedFees.isEmpty()
    LaunchedEffect(state.completedOperationId, submitted) {
        if (submitted != null && submitted == state.completedOperationId) deleted()
    }
    AlertDialog(onDismissRequest = { if (!state.saving) dismiss() }, title = { Text("Eliminar préstamos seleccionados") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (state.copy) "Libro de PRUEBAS" else "Libro OFICIAL", fontWeight = FontWeight.Bold)
            Text("${selected.size} préstamo${if (selected.size == 1) "" else "s"} · ${soles(selected.sumOf { it.principal })} prestados", fontWeight = FontWeight.SemiBold)
            selected.forEach { loan ->
                Text("${loan.client} · ${loan.id}\n${dateLabel(loan.firstDue.minusDays(1))} · ${soles(loan.principal)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Se eliminarán estos préstamos con sus cuotas, cobros, moras y aportes vinculados. La caja y los reportes se recalcularán.")
            Text("Los demás préstamos se conservan. Al sincronizar se guardará un respaldo en la hoja. No se puede deshacer desde la app.", style = MaterialTheme.typography.bodySmall)
            (error ?: if (submitted != null) state.error else null)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = enabled, onClick = {
        runCatching { submitted = model.deleteLoans(selected) }.onFailure { error = it.message }
    }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
        Text(if (state.saving) "Guardando…" else "Eliminar ${selected.size}")
    } }, dismissButton = { TextButton(onClick = dismiss, enabled = !state.saving) { Text("Cancelar") } })
}

package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*

/** Selection happens on the portfolio table; this dialog reviews its exact loan IDs and common name. */
@Composable internal fun GroupLoansDialog(state: SheetsState, model: SheetsViewModel, catalog: List<SheetLoan>, selectedIds: List<String>,
    dismiss: () -> Unit, grouped: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val loans = canonicalLoanViews(catalog, today().year)
    val chosen = chronologicalLoans(loans.filter { it.id in selectedIds })
    val plan = runCatching { planLoanGrouping(loans, selectedIds, name, today()) }
    val writable = state.ready && !state.saving && state.sensitiveRequest == null && state.paymentConflict == null
    LaunchedEffect(state.completedOperationId, submitted) {
        if (submitted != null && submitted == state.completedOperationId) grouped()
    }
    AlertDialog(onDismissRequest = { if (!state.saving) dismiss() }, title = { Text("Nombre único del cliente") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Nombre para todos los seleccionados") },
                singleLine = true, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
            Text("${chosen.size} préstamos · del más antiguo al más reciente", style = MaterialTheme.typography.labelLarge)
            chosen.forEachIndexed { index, loan ->
                Text("${index + 1}. ${loan.client} · ${dateLabel(loan.firstDue.minusDays(1))} · ${loan.id}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Cada préstamo conserva sus cuotas, pagos e historial. Todos usarán el nombre elegido.", style = MaterialTheme.typography.bodySmall)
            Text("Se guardará en el teléfono y se enviará a Google al recuperar la conexión.", style = MaterialTheme.typography.bodySmall)
            if (name.isNotBlank()) plan.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            (error ?: if (submitted != null) state.error else null)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = writable && plan.isSuccess,
        onClick = { runCatching { submitted = model.groupLoans(selectedIds, name) }.onFailure { error = it.message } }) {
        Text(if (state.saving) "Guardando…" else "Agrupar con este nombre")
    } }, dismissButton = { TextButton(onClick = dismiss, enabled = !state.saving) { Text("Cambiar selección") } })
}

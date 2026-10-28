package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.SheetPayment
import com.example.controlprestamos.domain.*
import java.time.LocalDate

@Composable internal fun BulkPaymentSelector(rows: List<SheetPayment>, enabled: Boolean, model: SheetsViewModel) {
    val due = rows.filter { it.due <= today() }.distinctBy { it.id }
    if (due.isEmpty()) return
    var choice by remember { mutableStateOf<Boolean?>(null) }
    var onDueDate by rememberSaveable { mutableStateOf(true) }
    var date by rememberSaveable { mutableStateOf(today().toString()) }
    var chooseDate by remember { mutableStateOf(false) }
    Text("Cambiar todas las cuotas vencidas (${due.size})", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { choice = false }, enabled = enabled && due.all { !it.version.isNullOrBlank() },
            modifier = Modifier.weight(1f)) { Text("Todos pagados") }
        OutlinedButton(onClick = { choice = true }, enabled = enabled && due.all { !it.version.isNullOrBlank() },
            modifier = Modifier.weight(1f)) { Text("Todos no pagados") }
    }
    val unpaid = choice ?: return
    AlertDialog(onDismissRequest = { choice = null }, title = { Text(if (unpaid) "Todos no pagados" else "Todos pagados") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Se cambiarán ${due.size} cuotas de esta selección. Las cuotas futuras se conservan.")
            if (unpaid) Text("Se anularán los cobros y las moras cobradas de estas cuotas automáticamente. La caja y las ganancias se recalcularán.")
            else {
                Text("Para las cuotas pendientes, confirma la fecha real de cobro. Se registrarán sin mora; podrás ajustar cada día después.")
                FilterChip(onDueDate, { onDueDate = true }, label = { Text("Cada una en su vencimiento") })
                FilterChip(!onDueDate, { onDueDate = false }, label = { Text("Todas en una misma fecha") })
                if (!onDueDate) OutlinedButton(onClick = { chooseDate = true }) { Text("Cobro: ${dateLabel(LocalDate.parse(date))}") }
                Text("Las cuotas ya pagadas conservan su fecha y su mora cobrada.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = {
        model.stageAll(due, unpaid, if (onDueDate) null else LocalDate.parse(date)); choice = null
    }, enabled = enabled) { Text("Aplicar cambios") } }, dismissButton = { TextButton(onClick = { choice = null }) { Text("Cancelar") } })
    if (chooseDate) ChoosePaymentDate(due.filter { it.unpaid }.maxOfOrNull { it.due } ?: due.maxOf { it.due },
        LocalDate.parse(date), dismiss = { chooseDate = false }) { date = it.toString(); chooseDate = false }
}

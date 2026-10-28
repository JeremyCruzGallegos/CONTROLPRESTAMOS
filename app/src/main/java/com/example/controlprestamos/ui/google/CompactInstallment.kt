package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.SheetPayment
import com.example.controlprestamos.domain.dateLabel
import com.example.controlprestamos.domain.soles
import java.time.LocalDate

/** The collection task gets one amount and one primary action; corrections stay secondary. */
@Composable internal fun CompactInstallment(row: SheetPayment, unpaid: Boolean, late: Long,
    canEdit: Boolean, sending: Boolean, paidOn: LocalDate?, bookDifference: Long?, edit: () -> Unit, reverse: () -> Unit) {
    var correcting by rememberSaveable(row.id, unpaid) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cuota de hoy · ${row.installment}/${row.loan.total}", Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            StatusBadge(if (unpaid) "Pendiente" else "Pagada", positive = !unpaid)
        }
        val amount: @Composable () -> Unit = {
            Text(soles(row.base + late), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        val action: @Composable () -> Unit = {
            if (canEdit) {
                if (unpaid) Button(onClick = edit, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Cobrar cuota") }
                else TextButton(onClick = { correcting = !correcting }) { Text(if (correcting) "Cerrar" else "Corregir pago") }
            }
        }
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 260.dp || fontScale > 1.2f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { amount(); action() }
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                amount(); action()
            }
        }
        Text(if (late > 0) "Base ${soles(row.base)} + mora ${soles(late)}" else "Sin mora",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sending) Text("Envío pendiente de confirmar", style = MaterialTheme.typography.labelSmall)
        bookDifference?.let { Text("Monto en el libro: ${soles(it)}", style = MaterialTheme.typography.bodySmall) }
        if (!unpaid && paidOn != null && paidOn != row.due)
            Text("Cobrado el ${dateLabel(paidOn)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (correcting && canEdit && !unpaid) {
            TextButton(onClick = reverse) { Text("Marcar pendiente") }
            TextButton(onClick = edit) { Text("Editar mora") }
        }
    }
}

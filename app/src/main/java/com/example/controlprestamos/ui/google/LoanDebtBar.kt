package com.example.controlprestamos.ui.google

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.LoanDebtBreakdown
import com.example.controlprestamos.domain.soles
import com.example.controlprestamos.ui.chartPalette

@Composable internal fun LoanDebtBar(debt: LoanDebtBreakdown?, paid: Int, total: Int) {
    val colors = MaterialTheme.colorScheme
    val palette = chartPalette(colors.surface)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Deuda de este préstamo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("$paid/$total pagadas", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (debt == null) {
            Text("Esperando el detalle de cuotas para calcular la deuda…", style = MaterialTheme.typography.bodySmall)
            return
        }
        Text(soles(debt.pending), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (debt.pending == 0L) {
            Text("Sin deuda pendiente", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            return
        }
        val parts = listOf(debt.overdueBase to palette.outflows, debt.late to palette.fees, debt.futureBase to palette.interest)
        Canvas(Modifier.fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHighest)
            .semantics { contentDescription = "Deuda pendiente ${soles(debt.pending)}: cuotas hasta hoy ${soles(debt.overdueBase)}, mora ${soles(debt.late)}, cuotas futuras ${soles(debt.futureBase)}" }) {
            var start = 0f
            parts.forEach { (amount, color) ->
                val width = (size.width * amount.toDouble() / debt.pending).toFloat()
                if (amount > 0) drawRect(color, topLeft = Offset(start, 0f), size = Size(width, size.height))
                start += width
            }
        }
        DebtPart("Hasta hoy", debt.dueToday, null, emphasized = true)
        DebtPart("Cuotas pendientes hasta hoy", debt.overdueBase, palette.outflows, indent = true)
        DebtPart("Mora acumulada", debt.late, palette.fees, indent = true)
        DebtPart("Cuotas futuras", debt.futureBase, palette.interest)
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Sin mora", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(soles(debt.withoutLate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f)) {
                Text("Con mora", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(soles(debt.pending), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        val percent = debt.latePercent?.let { " · +${it.toPlainString()}% sobre el saldo sin mora" }.orEmpty()
        Text(if (debt.late == 0L) "La mora no está aumentando esta deuda."
            else "Aumento por mora: ${soles(debt.late)}$percent", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable private fun DebtPart(label: String, amount: Long, color: Color?, emphasized: Boolean = false, indent: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (color != null) Box(Modifier.size(8.dp).clip(MaterialTheme.shapes.small).background(color))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(soles(amount), style = MaterialTheme.typography.bodySmall, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium)
    }
}

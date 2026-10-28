package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.SheetSummary
import com.example.controlprestamos.domain.soles
import com.example.controlprestamos.ui.ChartSlice
import com.example.controlprestamos.ui.DonutChart
import com.example.controlprestamos.ui.FinancialLine
import com.example.controlprestamos.ui.chartPalette

/** Collected profit only; recycled principal and external contributions are not earnings. */
@Composable internal fun EarningsChart(summary: SheetSummary, active: Boolean, pendingChanges: Boolean = false) {
    val palette = chartPalette(MaterialTheme.colorScheme.surfaceContainerLow)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ganancias cobradas · ${summary.year}", style = MaterialTheme.typography.titleMedium)
            DonutChart(listOf(ChartSlice("Intereses cobrados", summary.interest, palette.interest),
                ChartSlice("Mora cobrada", summary.late, palette.fees)), "Interés + mora", active)
            if (pendingChanges) Text("Ganancias al último corte disponible.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (summary.earnings <= 0) "Aún no hay intereses ni mora cobrados este año."
                else "Lo que ya ganaste. El capital devuelto vuelve a caja y puede prestarse otra vez.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

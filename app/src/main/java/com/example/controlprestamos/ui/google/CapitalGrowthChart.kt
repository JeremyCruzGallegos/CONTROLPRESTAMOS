package com.example.controlprestamos.ui.google

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlprestamos.data.google.CapitalGrowthPoint
import com.example.controlprestamos.domain.moneyAxis
import com.example.controlprestamos.domain.soles
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.controlprestamos.ui.rememberChartAnimation

@Composable internal fun CapitalGrowthChart(points: List<CapitalGrowthPoint>) {
    if (points.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val text = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = muted)
    val amounts = points.map { text.measure(soles(it.capital), style) }
    val percentages = points.map { text.measure(it.growthPercent?.let { percent -> "+${percent.toPlainString()}%" } ?: "Sin capital base", style) }
    val dates = points.mapIndexed { i, point -> text.measure(if (i == 0) {
        if (point.interest + point.late == 0L) "Inicial" else "Apertura"
    } else point.date.format(DateTimeFormatter.ofPattern("dd/MM", Locale.forLanguageTag("es-PE"))), style) }
    val density = LocalDensity.current
    val widest = with(density) { (amounts + percentages + dates).maxOf { it.size.width }.toDp() }
    val edge = maxOf(36.dp, widest / 2 + 10.dp)
    val spacing = maxOf(80.dp, widest + 18.dp)
    val axis = moneyAxis(points.map { it.capital })
    val animation = rememberChartAnimation(listOf("capital-growth", points), true, durationMillis = 1000, linear = true)
    Text("Crecimiento del capital", style = MaterialTheme.typography.titleMedium)
    Text(soles(points.last().capital), style = MaterialTheme.typography.headlineMedium)
    Text(points.last().growthPercent?.let { "+${it.toPlainString()}% respecto al capital inicial" }
        ?: "Porcentaje no disponible: capital inicial en cero", style = MaterialTheme.typography.bodyMedium, color = accent)
    Text("Capital inicial + intereses y mora cobrados. Volver a prestar el mismo dinero no aumenta este gráfico.", style = MaterialTheme.typography.bodySmall, color = muted)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, spacing * (points.size - 1) + edge * 2)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(Modifier.width(width).height(250.dp).then(animation.viewport)) {
                val left = edge.toPx(); val right = size.width - edge.toPx()
                // Amounts occupy their own band above the plot, irrespective of the slope.
                val labelY = 4.dp.toPx()
                val top = labelY + amounts.maxOf { it.size.height } + percentages.maxOf { it.size.height } + 26.dp.toPx()
                val bottom = size.height - dates.maxOf { it.size.height } - 20.dp.toPx()
                fun point(i: Int) = Offset(left + (right - left) * i / (points.size - 1).coerceAtLeast(1),
                    bottom - axis.fraction(points[i].capital) * (bottom - top))
                fun showLabel(i: Int) = points.size <= 13 || i == 0 || i == points.lastIndex || i % (points.size / 5).coerceAtLeast(1) == 0
                val line = Path()
                points.indices.forEach { i -> val p = point(i)
                    if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                    if (showLabel(i)) drawLine(muted.copy(alpha = .10f), Offset(p.x, top), Offset(p.x, bottom), strokeWidth = 1.dp.toPx())
                }
                val visibleEnd = left + (right - left) * animation.progress
                clipRect(top = top, bottom = bottom + 6.dp.toPx(), right = visibleEnd) {
                    drawPath(line, accent, style = Stroke(2.5.dp.toPx()))
                }
                points.indices.forEach { i ->
                    val p = point(i)
                    if (p.x > visibleEnd) return@forEach
                    drawCircle(surface, 5.dp.toPx(), p); drawCircle(accent, 4.dp.toPx(), p)
                    if (showLabel(i)) {
                        drawText(amounts[i], topLeft = Offset(p.x - amounts[i].size.width / 2f, labelY))
                        drawText(percentages[i], topLeft = Offset(p.x - percentages[i].size.width / 2f, labelY + amounts[i].size.height + 3.dp.toPx()))
                        drawText(dates[i], topLeft = Offset(p.x - dates[i].size.width / 2f, bottom + 12.dp.toPx()))
                    }
                }
            }
        }
    }
    Text("Intereses: ${soles(points.last().interest)} · Mora: ${soles(points.last().late)}", style = MaterialTheme.typography.bodySmall)
}

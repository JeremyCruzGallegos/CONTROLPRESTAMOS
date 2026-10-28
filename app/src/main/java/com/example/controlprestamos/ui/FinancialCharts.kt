package com.example.controlprestamos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlprestamos.R
import com.example.controlprestamos.domain.*
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

private val Spanish = Locale.forLanguageTag("es-PE")
private val ReceiptColor = Color(0xFF34D399)
private val OutflowColor = Color(0xFFF59E0B)
private val InterestColor = Color(0xFF60A5FA)
private val FeeColor = Color(0xFFA78BFA)
private fun monthName(month: Int) = Month.of(month).getDisplayName(DateTextStyle.FULL, Spanish).replaceFirstChar { it.titlecase(Spanish) }
private fun shortDate(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("d MMM", Spanish))
private fun signedMoney(value: Long) = if (value > 0) "+${soles(value)}" else soles(value)

@Composable
fun ChartsScreen(snapshot: Snapshot, day: LocalDate) {
    var year by rememberSaveable { mutableIntStateOf(day.year) }
    var month by rememberSaveable { mutableIntStateOf(0) }
    val report = remember(snapshot, day, year, month) { periodReport(snapshot, day, year, month) }
    val years = (listOf(day.year, 2026, 2027, 2028) + snapshot.loans.flatMap {
        listOf(it.disbursed.year, it.installments.last().due.year)
    }).distinct().sortedDescending()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Análisis financiero", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Tu caja, tus cobros y tu cartera", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PeriodPicker("Año", year.toString(), years.map(Int::toString), Modifier.weight(0.8f)) { year = it.toInt() }
                PeriodPicker("Período", if (month == 0) "Todo el año" else monthName(month), listOf("Todo el año") + (1..12).map(::monthName), Modifier.weight(1.2f)) {
                    month = if (it == "Todo el año") 0 else (1..12).first { number -> monthName(number) == it }
                }
            }
        }
        if (report == null) item {
            FinancialCard("Período futuro", "Todavía no hay movimientos registrados para esta selección.") {
                Text("Selecciona un año o mes anterior para consultar tus resultados.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            item { CashOverview(report) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi("Cobros registrados", report.receipts, "Capital + intereses + mora", ReceiptColor, Modifier.weight(1f).fillMaxHeight())
                        Kpi("Desembolsos", report.disbursed, "Préstamos entregados", OutflowColor, Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi("Ganancias cobradas", report.earnings, "Intereses + mora", InterestColor, Modifier.weight(1f).fillMaxHeight())
                        Kpi("Deuda pendiente", report.debt, "Al ${dateLabel(report.cutoff)}", FeeColor, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            item { CashChart(report) }
            item { MovementChart(report, earnings = false) }
            item { MovementChart(report, earnings = true) }
            item { CompositionChart(report) }
            item {
                FinancialCard("Aportes externos", "Capital incorporado en este período") {
                    Text(soles(report.contribution), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Los aportes aumentan la caja y no se incluyen en las ganancias.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("PROYECCIÓN · SIN COBRAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(soles(report.projected), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Cuotas futuras del período, sin mora. Este importe no forma parte de los cobros ni de la caja actual.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text("Los movimientos se muestran por fecha de registro. Caja y deuda acumulan todos los años hasta el corte. Importes en soles (S/).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Actualización local: " + (snapshot.refreshedAt?.atZone(LIMA)?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) ?: "Sin actualizar"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun PeriodPicker(label: String, value: String, options: List<String>, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option, fontWeight = if (option == value) FontWeight.Bold else FontWeight.Normal) },
                onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable private fun FinancialCard(title: String, subtitle: String, framed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val body: @Composable ColumnScope.() -> Unit = {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
    if (framed) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = body)
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = body)
}

@Composable private fun Kpi(label: String, value: Long, caption: String, color: Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(24.dp).height(3.dp).background(color, RoundedCornerShape(2.dp)))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(soles(value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun CashOverview(report: PeriodReport) {
    FinancialHero("Caja al cierre", report.closingCash,
        "${dateLabel(report.start)} — ${dateLabel(report.cutoff)}") {
        Text("${signedMoney(report.cashChange)} en el período", style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFD1E2E7))
        Text("Apertura ${soles(report.openingCash)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1E2E7))
    }
}

private fun Modifier.chartTouch(key: Any, count: Int, left: Float, right: Float, bars: Boolean, drag: Boolean = true,
    select: (Int) -> Unit): Modifier {
    fun index(x: Float, width: Float): Int {
        val fraction = ((x - left) / (width - left - right).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return if (bars) floor(fraction * count).toInt().coerceIn(0, count - 1) else nearestChartIndex(fraction, count)
    }
    val taps = pointerInput(key, count, left, right) { detectTapGestures { select(index(it.x, size.width.toFloat())) } }
    if (!drag) return taps
    return taps.pointerInput(key, count, left, right) {
            detectHorizontalDragGestures(onDragStart = { select(index(it.x, size.width.toFloat())) }) { change, _ ->
                change.consume(); select(index(change.position.x, size.width.toFloat()))
            }
        }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.moneyGrid(axis: MoneyAxis, left: Float, right: Float,
    top: Float, bottom: Float, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, color: Color, lines: Boolean = true) {
    axis.ticks.forEach { value ->
        val y = bottom - axis.fraction(value.roundToLong()) * (bottom - top)
        if (lines) drawLine(color.copy(alpha = 0.6f), Offset(left, y), Offset(size.width - right, y), strokeWidth = 1.dp.toPx())
        val text = measurer.measure(soles(value.roundToLong()), style)
        drawText(text, topLeft = Offset((left - text.size.width - 6.dp.toPx()).coerceAtLeast(0f), y - text.size.height / 2))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dateTicks(count: Int, left: Float, right: Float, bottom: Float,
    bars: Boolean, measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, label: (Int) -> String) {
    val width = (size.width - left - right).coerceAtLeast(1f)
    val slots = (width / 62.dp.toPx()).toInt().coerceIn(1, 5)
    (0..slots).map { nearestChartIndex(it.toFloat() / slots, count) }.distinct().forEach { index ->
        val x = left + width * if (bars) (index + 0.5f) / count else index.toFloat() / (count - 1).coerceAtLeast(1)
        val text = measurer.measure(label(index), style)
        drawText(text, topLeft = Offset((x - text.size.width / 2).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f)), bottom + 9.dp.toPx()))
    }
}

@Composable private fun PointSelector(index: Int, count: Int, label: String, onSelect: (Int) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        IconButton(onClick = { onSelect(index - 1) }, enabled = index > 0) { Icon(painterResource(R.drawable.ic_back), "Dato anterior", Modifier.size(20.dp)) }
        IconButton(onClick = { onSelect(index + 1) }, enabled = index < count - 1) {
            // Mirrored arrow without changing its accessible label.
            Icon(painterResource(R.drawable.ic_back), "Dato siguiente", Modifier.size(20.dp).rotate(180f))
        }
    }
}

@Composable internal fun CashChart(report: PeriodReport, active: Boolean = true, framed: Boolean = true) {
    val animation = rememberChartAnimation(listOf("cash", report.start, report.cutoff, report.cashEvolution), active, durationMillis = 2800, linear = true)
    val values = report.cashEvolution
    var selected by remember(report.start, report.cutoff, values) { mutableIntStateOf(values.lastIndex) }
    val axis = remember(values) { moneyAxis(values, includeZero = true) }
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = muted, fontSize = 9.sp)
    val padding = with(LocalDensity.current) { 12.dp.toPx() }
    val left = axis.ticks.maxOf { measurer.measure(soles(it.roundToLong()), style).size.width }.toFloat() + padding
    val selectedDate = report.start.minusDays(1).plusDays(selected.toLong())
    FinancialCard("Saldo día a día", "Fechas de movimiento · ${dateLabel(report.start)} — ${dateLabel(report.cutoff)}", framed = framed) {
        PointSelector(selected, values.size, if (selected == 0) "Apertura · ${dateLabel(selectedDate)}" else dateLabel(selectedDate), { selected = it }) {
            Text(soles(values[selected]), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
        }
        Canvas(Modifier.fillMaxWidth().height(260.dp).then(animation.viewport).chartTouch(values, values.size, left, padding, false) { selected = it }
            .semantics { contentDescription = "Evolución de caja. ${dateLabel(selectedDate)}: ${soles(values[selected])}. Usa anterior o siguiente para explorar." }) {
            val top = 12.dp.toPx(); val bottom = size.height - 32.dp.toPx()
            val width = (size.width - left - padding).coerceAtLeast(1f)
            moneyGrid(axis, left, padding, top, bottom, measurer, style, grid)
            fun point(index: Int) = Offset(left + width * index / (values.size - 1).coerceAtLeast(1), bottom - axis.fraction(values[index]) * (bottom - top))
            val line = Path().apply {
                values.indices.forEach { index ->
                    val p = point(index)
                    if (index == 0) moveTo(p.x, p.y) else {
                        lineTo(p.x, point(index - 1).y)
                        lineTo(p.x, p.y)
                    }
                }
            }
            val measure = PathMeasure().apply { setPath(line, false) }
            val trace = Path()
            if (animation.progress > 0 && measure.length > 0) {
                measure.getSegment(0f, measure.length * animation.progress, trace, startWithMoveTo = true)
                val tip = measure.getPosition(measure.length * animation.progress)
                val area = Path().apply {
                    addPath(trace); lineTo(tip.x, bottom); lineTo(point(0).x, bottom); close()
                }
                drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f * animation.progress),
                    accent.copy(alpha = 0.01f * animation.progress)), top, bottom))
                drawPath(trace, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                val highlighted = point(selected)
                if (highlighted.x <= tip.x) {
                    drawLine(accent.copy(alpha = 0.45f), Offset(highlighted.x, top), Offset(highlighted.x, bottom),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                    drawCircle(background, 5.dp.toPx(), highlighted)
                    drawCircle(accent, 4.dp.toPx(), highlighted, style = Stroke(2.dp.toPx()))
                }
                if (animation.progress < 1f) drawCircle(accent, 3.dp.toPx(), tip)
            } else if (animation.progress > 0 && values.isNotEmpty()) drawCircle(accent, 3.dp.toPx(), point(0))
            dateTicks(values.size, left, padding, bottom, false, measurer, style) { shortDate(report.start.minusDays(1).plusDays(it.toLong())) }
        }
        Text("Toca o desliza horizontalmente para consultar cada día", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

@Composable private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun MovementChart(report: PeriodReport, earnings: Boolean, active: Boolean = true, framed: Boolean = true,
    summary: @Composable ColumnScope.() -> Unit = {}) {
    val animation = rememberChartAnimation(listOf("bars", earnings, report.start, report.cutoff, report.movements), active)
    val periods = report.movements
    var selected by remember(periods, earnings) { mutableIntStateOf(periods.lastIndex) }
    val monthly = report.monthly
    val axis = remember(periods, earnings) { moneyAxis(if (earnings) periods.map { it.earnings } else periods.flatMap { listOf(it.receipts, it.disbursed) }, includeZero = true) }
    val palette = chartPalette(MaterialTheme.colorScheme.surfaceContainerLow)
    val firstColor = if (earnings) palette.interest else palette.receipts
    val secondColor = if (earnings) palette.fees else palette.outflows
    val firstLabel = if (earnings) "Intereses" else "Cobros"
    val secondLabel = if (earnings) "Mora" else "Desembolsos"
    val grid = MaterialTheme.colorScheme.outlineVariant
    val selectionColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = muted, fontSize = 9.sp)
    val padding = with(LocalDensity.current) { 12.dp.toPx() }
    val left = axis.ticks.maxOf { measurer.measure(soles(it.roundToLong()), style).size.width }.toFloat() + padding
    val picked = periods[selected]
    val first = if (earnings) picked.interest else picked.receipts
    val second = if (earnings) picked.late else picked.disbursed
    val hasData = periods.any { if (earnings) it.earnings > 0 else it.receipts > 0 || it.disbursed > 0 }
    val dateLabels = remember(periods, monthly, measurer, style) {
        periods.map { item ->
            val label = if (monthly) item.start.format(DateTimeFormatter.ofPattern("MMM", Spanish)) else shortDate(item.start)
            measurer.measure(label, style)
        }
    }
    FinancialCard(if (earnings) "Ganancias cobradas" else "Cobros y desembolsos",
        "Barras ${if (monthly) "por mes" else "por día"} · ${dateLabel(report.start)} — ${dateLabel(report.cutoff)}", framed = framed) {
        summary()
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { LegendDot(firstLabel, firstColor); LegendDot(secondLabel, secondColor) }
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        Row(Modifier.fillMaxWidth()) {
            // The scale stays outside the scrolling plot, aligned to the same top and baseline.
            Canvas(Modifier.width(with(density) { left.toDp() }).height(220.dp)) {
                val top = 12.dp.toPx(); val bottom = size.height - 32.dp.toPx()
                moneyGrid(axis, size.width, 0f, top, bottom, measurer, style, grid, lines = false)
                val x = size.width - 1.dp.toPx()
                drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                axis.ticks.forEach { value ->
                    val y = bottom - axis.fraction(value.roundToLong()) * (bottom - top)
                    drawLine(grid, Offset(x - 3.dp.toPx(), y), Offset(x, y), strokeWidth = 1.dp.toPx())
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                // Keep paired columns wide even for 31 daily groups; only days and bars scroll.
                val chartWidth = maxOf(maxWidth, 48.dp * periods.size + 12.dp)
                val viewportWidth = maxWidth
                LaunchedEffect(selected, periods, scroll.maxValue, chartWidth, viewportWidth) {
                    val group = (with(density) { chartWidth.toPx() } - padding) / periods.size
                    val center = (selected + 0.5f) * group
                    scroll.scrollTo((center - with(density) { viewportWidth.toPx() } / 2).toInt().coerceIn(0, scroll.maxValue))
                }
                Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                    Canvas(Modifier.width(chartWidth).height(220.dp).then(animation.viewport)
                        .chartTouch(periods, periods.size, 0f, padding, true, drag = false) { selected = it }
                        .semantics { contentDescription = "Gráfico de barras con escala vertical fija. ${dateLabel(picked.start)} a ${dateLabel(picked.end)}. $firstLabel ${soles(first)}. $secondLabel ${soles(second)}. Desliza a izquierda o derecha para ver más períodos o usa las flechas." }) {
                        val top = 12.dp.toPx(); val bottom = size.height - 32.dp.toPx()
                        val plotWidth = (size.width - padding).coerceAtLeast(1f)
                        val groupWidth = plotWidth / periods.size
                        fun y(value: Long) = bottom - axis.fraction(value) * (bottom - top) * animation.progress
                        periods.forEachIndexed { index, item ->
                            val center = (index + 0.5f) * groupWidth
                            val width = minOf(24.dp.toPx(), groupWidth * if (earnings) 0.60f else 0.34f)
                            fun column(value: Long, color: Color, x: Float) {
                                if (value <= 0 || animation.progress <= 0) return
                                val position = Offset(x, y(value))
                                val barSize = androidx.compose.ui.geometry.Size(width, bottom - y(value))
                                drawRoundRect(color, position, barSize, CornerRadius(3.dp.toPx()))
                                if (index == selected) drawRoundRect(selectionColor, position, barSize,
                                    CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                            }
                            if (earnings) {
                                if (item.interest > 0) drawRect(firstColor, Offset(center - width / 2, y(item.interest)),
                                    androidx.compose.ui.geometry.Size(width, bottom - y(item.interest)))
                                if (item.late > 0) drawRect(secondColor, Offset(center - width / 2, y(item.earnings)),
                                    androidx.compose.ui.geometry.Size(width, y(item.interest) - y(item.earnings)))
                                if (index == selected && item.earnings > 0 && animation.progress > 0)
                                    drawRect(selectionColor, Offset(center - width / 2, y(item.earnings)),
                                        androidx.compose.ui.geometry.Size(width, bottom - y(item.earnings)), style = Stroke(1.5.dp.toPx()))
                            } else {
                                column(item.receipts, firstColor, center - width - groupWidth * 0.03f)
                                column(item.disbursed, secondColor, center + groupWidth * 0.03f)
                            }
                            val text = dateLabels[index]
                            drawText(text, topLeft = Offset(center - text.size.width / 2, bottom + 9.dp.toPx()))
                        }
                    }
                }
            }
        }
        if (!hasData) Text("Sin ${if (earnings) "ganancias cobradas" else "movimientos"} en este período", style = MaterialTheme.typography.bodySmall, color = muted)
        PointSelector(selected, periods.size,
            if (monthly) "${monthName(picked.start.monthValue)} · ${dateLabel(picked.start)} — ${dateLabel(picked.end)}" else dateLabel(picked.start), { selected = it }) {
            Text("$firstLabel ${soles(first)}", style = MaterialTheme.typography.labelLarge)
            Text("$secondLabel ${soles(second)}", style = MaterialTheme.typography.labelLarge)
        }
        Text("Desliza a izquierda o derecha para ver más ${if (monthly) "meses" else "días"}. Toca una barra para consultar sus importes.", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

internal data class ChartSlice(val label: String, val value: Long, val color: Color)
@Composable private fun CompositionChart(report: PeriodReport) {
    var pending by rememberSaveable { mutableStateOf(false) }
    val palette = chartPalette(MaterialTheme.colorScheme.surfaceContainerLow)
    val slices = if (pending) listOf(ChartSlice("Capital por recuperar", report.pendingCapital, palette.outflows),
        ChartSlice("Intereses pendientes", report.pendingInterest, palette.interest), ChartSlice("Mora pendiente", report.pendingLate, palette.fees))
    else listOf(ChartSlice("Capital recuperado", report.recovered, palette.receipts), ChartSlice("Intereses cobrados", report.interest, palette.interest), ChartSlice("Mora cobrada", report.late, palette.fees))
    val total = slices.sumOf { it.value }
    FinancialCard("Composición de la cartera", if (pending) "Deuda al ${dateLabel(report.cutoff)} · Todos los años" else "Cobros del período seleccionado") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !pending, onClick = { pending = false }, label = { Text("Cobrado") })
            FilterChip(selected = pending, onClick = { pending = true }, label = { Text("Por cobrar") })
        }
        DonutChart(slices, if (pending) "Por cobrar" else "Cobrado")
        if (total == 0L) Text(if (pending) "No hay deuda pendiente al corte." else "No hay cobros registrados en este período.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Animate the sector sweep; the track, total and legend do not move. */
@Composable internal fun DonutChart(slices: List<ChartSlice>, centerLabel: String, active: Boolean = true) {
    val animation = rememberChartAnimation(listOf(centerLabel, slices), active)
    val total = slices.sumOf { it.value }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val textColor = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(224.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp).then(animation.viewport).semantics {
                contentDescription = "$centerLabel: ${soles(total)}. " + slices.joinToString { "${it.label}: ${soles(it.value)}" }
            }) {
                val stroke = Stroke(30.dp.toPx())
                val inset = stroke.width / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                if (total > 0) {
                    var start = -90f
                    slices.forEach { slice ->
                        val sweep = (slice.value.toDouble() / total * 360).toFloat()
                        val gap = if (slices.count { it.value > 0 } > 1) minOf(4f, sweep * 0.20f) else 0f
                        val shown = (360f * animation.progress - (start + 90f) - gap / 2).coerceIn(0f, sweep - gap)
                        if (shown > 0) drawArc(slice.color, start + gap / 2, shown, false, Offset(inset, inset), arcSize, style = stroke)
                        start += sweep
                    }
                }
            }
            Column(Modifier.width(125.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(soles(total), fontSize = if (soles(total).length > 14) 13.sp else 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = textColor)
            }
        }
        slices.forEach { slice ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(slice.color, RoundedCornerShape(3.dp)))
                Column(Modifier.weight(1f)) {
                    Text(slice.label, style = MaterialTheme.typography.bodyMedium)
                    Text(String.format(Spanish, "%.1f%% del total", if (total == 0L) 0.0 else slice.value.toDouble() / total * 100),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(soles(slice.value), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

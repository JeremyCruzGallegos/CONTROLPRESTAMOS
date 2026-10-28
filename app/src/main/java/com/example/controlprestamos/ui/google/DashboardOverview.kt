package com.example.controlprestamos.ui.google

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.example.controlprestamos.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.dateLabel
import com.example.controlprestamos.domain.soles
import com.example.controlprestamos.domain.today
import com.example.controlprestamos.ui.FinancialLine
import com.example.controlprestamos.ui.FittedSingleLine
import com.example.controlprestamos.ui.cashActionPalette
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Pinned above every scrolling menu; its amount follows the durable local payment state. */
@Composable internal fun CashBalanceHeader(cash: Long, enabled: Boolean = false, deposit: (() -> Unit)? = null,
    withdraw: (() -> Unit)? = null, history: (() -> Unit)? = null, testBook: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val actions = cashActionPalette(colors.surface)
    var actionsOpen by remember { mutableStateOf(false) }
    val day by produceState(initialValue = today()) { while (true) { delay(30_000); value = today() } }
    val balance = if (cash >= 0) soles(cash) else "Caja por conciliar"
    Surface(onClick = { history?.invoke() }, enabled = history != null,
        color = Color.Transparent, contentColor = colors.onSurface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .4f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).semantics {
            contentDescription = "Caja, $balance, ${dateLabel(day)}" + if (history != null) ". Abrir historial de caja" else ""
        }) {
        Column(Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = .78f),
            colors.secondaryContainer.copy(alpha = .38f), colors.surface.copy(alpha = .55f))))
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CashTitleDate(if (testBook) "CAJA · PRUEBAS" else "CAJA", dateLabel(day))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FittedSingleLine(balance, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    color = colors.onSurface, minimumSp = 10f)
                if (history != null) Icon(painterResource(R.drawable.ic_history), null, Modifier.size(14.dp), tint = actions.history.foreground)
                if (deposit != null || withdraw != null) Box {
                    IconButton(onClick = { actionsOpen = true }, enabled = enabled,
                        modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_edit), "Editar caja: abonos y desembolsos", Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                        if (deposit != null) DropdownMenuItem(text = { Text("Abonar a caja") },
                            leadingIcon = { Text("+", style = MaterialTheme.typography.titleLarge, color = actions.deposit.foreground) },
                            enabled = enabled, onClick = { actionsOpen = false; deposit() })
                        if (withdraw != null) DropdownMenuItem(text = { Text("Desembolsar de caja") },
                            leadingIcon = { Text("−", style = MaterialTheme.typography.titleLarge, color = actions.withdrawal.foreground) },
                            enabled = enabled, onClick = { actionsOpen = false; withdraw() })
                    }
                }
            }
        }
    }
}

/** Both labels use one fitted size, so the date stays level with CAJA on narrow screens. */
@Composable private fun CashTitleDate(title: String, date: String) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = with(density) { (maxWidth - 12.dp).toPx() }
        val font = remember(title, date, available, style, density.fontScale) {
            (64 downTo 32).firstOrNull { quarter ->
                val fitted = style.copy(fontSize = (quarter / 4f).sp)
                measurer.measure(title, fitted, maxLines = 1, softWrap = false).size.width +
                    measurer.measure(date, fitted, maxLines = 1, softWrap = false).size.width <= available
            }?.let { it / 4f } ?: 8f
        }
        val fitted = style.copy(fontSize = font.sp, lineHeight = (font * 1.2f).sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = fitted, maxLines = 1, softWrap = false)
            Spacer(Modifier.weight(1f))
            Text(date, style = fitted, maxLines = 1, softWrap = false)
        }
    }
}

@Composable internal fun DashboardOverview(summary: SheetSummary, forecast: PortfolioForecast?, canEditCapital: Boolean,
    cachedOnly: Boolean, pendingChanges: Boolean, editCapital: () -> Unit, moved: Long? = summary.disbursed) {
    val colors = MaterialTheme.colorScheme
    var details by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color(0xFF122C3A), contentColor = Color.White) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF122C3A),
            lerp(Color(0xFF163B43), colors.primary, 0.18f)))).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Dinero esperado al final de todas las deudas", style = MaterialTheme.typography.titleMedium)
            val total = forecast?.cashAfterCollection(summary.cash)
            Text(total?.takeIf { it >= 0 }?.let(::soles) ?: "Calculando…",
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Si todos pagan: caja actual + capital e intereses pendientes + mora acumulada a hoy.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1E2E7))
            if (forecast != null && total != null && summary.cash >= 0) {
                if (total > 0) Row(Modifier.fillMaxWidth().height(12.dp)) {
                    if (summary.cash > 0) Box(Modifier.weight(summary.cash.toFloat()).fillMaxHeight().background(Color(0xFF8AE2BB)))
                    if (forecast.pending > 0) Box(Modifier.weight(forecast.pending.toFloat()).fillMaxHeight().background(Color(0xFF91C7FA)))
                    if (forecast.late > 0) Box(Modifier.weight(forecast.late.toFloat()).fillMaxHeight().background(Color(0xFFFFA64D)))
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun share(value: Long) = if (total > 0) "%.1f %%".format(java.util.Locale.forLanguageTag("es-PE"), value.toDouble() * 100 / total) else "0.0 %"
                    ForecastBreakdownRow("En caja", soles(summary.cash), share(summary.cash), Color(0xFF8AE2BB))
                    ForecastBreakdownRow("Por cobrar", soles(forecast.pending), share(forecast.pending), Color(0xFF91C7FA))
                    ForecastBreakdownRow("Mora", soles(forecast.late), share(forecast.late), Color(0xFFFFA64D))
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Dinero movido entre todos los préstamos", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD1E2E7))
                Text(moved?.let(::soles)?:"Calculando…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Suma de los montos entregados, incluidos los préstamos concluidos y el dinero vuelto a prestar.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1E2E7))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Intereses que vas a ganar", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD1E2E7))
                Text(forecast?.let { soles(it.interest) } ?: "Calculando…", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = Color(0xFF8AE2BB))
                Text("Solo el interés que falta cobrar de tus préstamos actuales.", style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD1E2E7))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Capital inicial", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD1E2E7))
                    Text(soles(summary.initial), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                if (canEditCapital) TextButton(onClick = editCapital, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF9CE8CB))) { Text("Editar") }
            }
            if (forecast == null) Text("Estamos reuniendo los pagos para calcular lo que falta cobrar.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1E2E7))
            if (summary.cash < 0) Text("Revisa las fechas de cobro y los aportes del historial para conciliar la caja.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1E2E7))
            if (summary.assumedContribution > 0) Text("Incluye ${soles(summary.assumedContribution)} de aporte considerado para financiar préstamos anteriores.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFD1E2E7))
        }
    }
    TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) {
        Text(if (details) "Ocultar detalle del capital ▴" else "Ver detalle del capital ▾")
    }
    AnimatedVisibility(details) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Movimiento del capital · ${summary.year}", style = MaterialTheme.typography.titleMedium)
                FinancialLine("Capital por recuperar", soles(summary.pendingCapital))
                FinancialLine("Abonos menos desembolsos de capital", soles(summary.contribution))
                FinancialLine("Dinero prestado", soles(summary.disbursed))
                FinancialLine("Capital recuperado", soles(summary.recovered))
                Text("Corte: ${dateLabel(summary.cutoff)}", style = MaterialTheme.typography.bodySmall)
                if (cachedOnly) Text("Últimos datos guardados en el teléfono.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Symmetric side columns keep the amount centered in the full row. */
@Composable private fun ForecastBreakdownRow(label: String, amount: String, percentage: String, color: Color) {
    val style=MaterialTheme.typography.labelMedium
    Row(Modifier.fillMaxWidth().heightIn(min=30.dp),verticalAlignment=Alignment.CenterVertically){
        FittedSingleLine("● $label",Modifier.weight(1.3f),style=style,color=color,minimumSp=6f)
        FittedSingleLine(amount,Modifier.weight(2f).padding(horizontal=4.dp),style=style.copy(fontWeight=FontWeight.SemiBold),color=color,minimumSp=6f,textAlign=TextAlign.Center)
        FittedSingleLine(percentage,Modifier.weight(1.3f),style=style,color=color,minimumSp=6f,textAlign=TextAlign.End)
    }
}

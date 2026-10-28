package com.example.controlprestamos.ui.google

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable private fun movementColor(type: CashMovementType): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    return when (type) {
    CashMovementType.DEPOSIT -> if (dark) Color(0xFF5DD3D8) else Color(0xFF007F87)
    CashMovementType.WITHDRAWAL -> if (dark) Color(0xFFFFB77E) else Color(0xFFC45114)
    CashMovementType.LOAN -> if (dark) Color(0xFFA8C1FF) else Color(0xFF426BD1)
    CashMovementType.PAYMENT -> if (dark) Color(0xFF83D69F) else Color(0xFF23834D)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CashMovementMenu(state: SheetsState, model: SheetsViewModel, type: CashMovementType?, dismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !state.saving })
    val historyMode = type == null
    val contentModifier = if (historyMode) Modifier.fillMaxHeight(.85f) else
        Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f).verticalScroll(rememberScrollState())
    ModalBottomSheet(onDismissRequest = { if (!state.saving) dismiss() }, sheetState = sheet,
        sheetGesturesEnabled = !historyMode) {
        Column(Modifier.fillMaxWidth().then(contentModifier).imePadding()
            .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(when (type) {
                    CashMovementType.DEPOSIT -> "Abonar a caja"
                    CashMovementType.WITHDRAWAL -> "Desembolsar de caja"
                    else -> "Historial de caja"
                }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = dismiss, enabled = !state.saving) { Text("Cerrar") }
            }
            if (type != null) {
                key(type) {
                    CashMovementForm(type, state, SheetsIdentity.isSimpleBook(model.bookId) && state.cashActualDates, dismiss) { movement, amount, date, description ->
                        model.moveCash(movement, amount, date, description)
                    }
                }
            } else CashHistoryList(state, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun CashHistoryList(state: SheetsState, modifier: Modifier) {
    var filter by rememberSaveable(state.subject, state.copy) { mutableStateOf("ALL") }
    val day = today()
    val catalog = (state.searchLoans + state.loans + state.data?.loans.orEmpty()).distinctBy { it.id }
    val history = remember(catalog, state.forecastPayments, state.cashMovements, state.loanContributions, state.cashEventTimes, day) {
        cashHistory(catalog, state.forecastPayments, state.cashMovements, state.loanContributions, day, state.cashEventTimes)
    }
    val filtered = remember(history, filter) { history.filter { filter == "ALL" || it.type.name == filter } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("ALL" to "Todos") + CashMovementType.entries.map { it.name to when(it) {
                CashMovementType.DEPOSIT -> "Abonos"; CashMovementType.WITHDRAWAL -> "Desembolsos"
                CashMovementType.LOAN -> "Préstamos"; CashMovementType.PAYMENT -> "Pagos"
            } }).forEach { (value, label) -> FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) }) }
        }
        Text("${filtered.size} movimientos · Más recientes primero", style = MaterialTheme.typography.labelSmall)
        // Only the rows scroll vertically. The sheet, filters and table height stay fixed.
        key(filter) {
            val vertical = rememberLazyListState()
            val horizontal = rememberScrollState()
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val tableWidth = maxOf(maxWidth, 640.dp)
                Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                    LazyColumn(Modifier.width(tableWidth).fillMaxHeight(), state = vertical,
                        overscrollEffect = null, contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        stickyHeader(key = "heading") {
                            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(8.dp)) {
                                    Text("Fecha / hora de registro", Modifier.width(160.dp), fontWeight = FontWeight.Bold)
                                    Text("Tipo", Modifier.width(110.dp), fontWeight = FontWeight.Bold)
                                    Text("Monto", Modifier.width(115.dp), fontWeight = FontWeight.Bold)
                                    Text("Descripción", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (!state.searchLoaded) item(key = "loading") {
                            Text("Descargando el historial de préstamos y pagos…", Modifier.padding(8.dp))
                        }
                        if (filtered.isEmpty()) item(key = "empty") {
                            Text("No hay movimientos de este tipo.", Modifier.padding(8.dp))
                        }
                        items(filtered, key = { "movement:${it.id}" }, contentType = { "movement" }) { row ->
                            val color = movementColor(row.type)
                            Surface(color = color.copy(alpha = .10f), modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                                Row(Modifier.padding(8.dp)) {
                                    Text(row.recordedAt?.let { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(LIMA).format(it) }
                                        ?: "${row.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} · Sin hora", Modifier.width(160.dp), style = MaterialTheme.typography.bodySmall)
                                    Text(row.type.label, Modifier.width(110.dp), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    Text((if (row.type in listOf(CashMovementType.LOAN, CashMovementType.WITHDRAWAL)) "− " else "+ ") + soles(row.amount),
                                        Modifier.width(115.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                    Text(row.description.ifBlank { "—" } +
                                        if (row.recordedAt != null && row.recordedAt.atZone(LIMA).toLocalDate() != row.date) "\nFecha del movimiento: ${dateLabel(row.date)}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item(key = "end") {
                            Text(if (state.searchLoaded) "Fin del historial · ${filtered.size} movimientos" else "Cargando movimientos…",
                                Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CashMovementForm(type: CashMovementType, state: SheetsState, reconcileFunding: Boolean, dismiss: () -> Unit,
    save: (CashMovementType, Long, LocalDate, String) -> String) {
    var amount by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(today().toString()) }
    var chooseDate by remember { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val date = LocalDate.parse(dateText)
    val cents = parseMoney(amount)
    val pending = submitted != null && state.saving
    val preview = runCatching {
        val entry = CashMovement("preview", date, type, cents ?: error("Ingresa el monto"), description.trim())
        require(date in SIMPLE_START..today()) { "Revisa la fecha" }
        projectCashMovement(requireNotNull(state.data).summary, state.cash, entry, today(), reconcileFunding).first.cash
    }
    LaunchedEffect(state.completedOperationId, submitted, state.cashMovements) {
        if (submitted != null && (state.completedOperationId == submitted || state.cashMovements.any { it.id == submitted })) dismiss()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Caja actual: ${soles(state.data?.summary?.cash ?: 0)}", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Monto · S/") }, singleLine = true,
            enabled = !pending, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { chooseDate = true }, enabled = !pending, modifier = Modifier.fillMaxWidth()) { Text("Fecha: ${dateLabel(date)}") }
        OutlinedTextField(description, { if (it.length <= 500) description = it }, label = { Text("Descripción (opcional)") },
            enabled = !pending, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
        preview.getOrNull()?.let { Text("Caja después: ${soles(it)}", fontWeight = FontWeight.Bold) }
        if (amount.isNotBlank()) preview.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        (error ?: if (submitted != null) state.error else null)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = preview.isSuccess && !pending && !state.saving, modifier = Modifier.fillMaxWidth(), onClick = {
            runCatching { submitted = save(type, requireNotNull(cents), date, description.trim()) }.onFailure { error = it.message }
        }) { Text(if (pending) "Guardando…" else if (type == CashMovementType.DEPOSIT) "Guardar abono" else "Guardar desembolso") }
    }
    if (chooseDate) ChooseDisbursementDate(date, dismiss = { chooseDate = false }, firstAllowed = state.cash.firstOrNull()?.date ?: SIMPLE_START, title = "Fecha del movimiento") { dateText = it.toString(); chooseDate = false }
}

package com.example.controlprestamos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.R
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.data.google.SheetSummary
import com.example.controlprestamos.data.google.PortfolioForecast
import com.example.controlprestamos.ui.google.CashBalanceHeader
import com.example.controlprestamos.ui.google.DashboardOverview
import com.example.controlprestamos.ui.google.EarningsChart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Teal: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Yellow: Color @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.3f) Color(0xFF463A20) else Color(0xFFFFF0B6)
private val Grey: Color @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.3f) Color(0xFF303846) else Color(0xFFE8E9EB)
private val tabs = listOf("Inicio", "Préstamos", "Pagos", "Gráficos")
private val tabIcons = listOf(R.drawable.ic_home, R.drawable.ic_loans, R.drawable.ic_payments, R.drawable.ic_chart)

@Composable private fun AppIcon(resource: Int, description: String? = null) {
    Icon(painterResource(resource), description, Modifier.size(22.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrestamosApp(model: LoansViewModel, dark: Boolean, accentIndex: Int, onToggleTheme: () -> Unit, onSelectAccent: (Int) -> Unit, onGoogle: () -> Unit,
    onChangeAccount: () -> Unit = onGoogle, canChangeAccount: Boolean = true) {
    val state by model.state.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(1) }
    var newLoan by rememberSaveable { mutableStateOf(false) }
    var selectedLoan by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentLoanId by rememberSaveable { mutableStateOf<String?>(null) }
    var connection by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var palette by rememberSaveable { mutableStateOf(false) }
    val settings = rememberDrawerState(DrawerValue.Closed)
    val settingsScope = rememberCoroutineScope()
    var menuHistory by rememberSaveable { mutableStateOf(listOf(page)) }
    val exitGuard = LocalExitBackGuard.current
    fun navigate(index: Int, record: Boolean = true) {
        exitGuard.reset()
        settingsScope.launch { settings.close() }
        if (record) menuHistory = recordMenu(menuHistory, index)
        page = index; newLoan = false; selectedLoan = null; connection = false
        if (record) paymentLoanId = null
    }
    LaunchedEffect(page, newLoan, selectedLoan, connection) { exitGuard.reset() }
    val snapshot = state.snapshot
    fun openPayments(id: String?) { navigate(2); paymentLoanId = id }
    LaunchedEffect(page) { if (page !in tabs.indices) page = 1 }
    LaunchedEffect(model) { while (true) { model.tick(); delay(30_000) } }
    BackHandler(newLoan || selectedLoan != null || connection || menuHistory.size > 1) {
        exitGuard.reset()
        when { newLoan -> newLoan = false; selectedLoan != null -> selectedLoan = null; connection -> connection = false
            else -> { menuHistory = menuHistory.dropLast(1); navigate(menuHistory.last(), record = false) } }
    }
    SettingsDrawer(settings, canOpen = page == 0 && !newLoan && selectedLoan == null && !connection && !about && !palette,
        drawerContent = {
            SettingsPanel("Demostración local", dark, onToggleTheme,
                changeAccount = onChangeAccount, canChangeAccount = canChangeAccount,
                accountRestriction = "Espera o resuelve los cambios de Google pendientes antes de cambiar de cuenta.",
                chooseColor = { palette = true }, about = { about = true },
                active = settings.isOpen, dismiss = { settingsScope.launch { settings.close() } })
        }) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    CenterAlignedTopAppBar(title = {
                        Text(when { newLoan -> "Nuevo préstamo"; selectedLoan != null -> "Detalle del préstamo"
                            connection -> "Conexión"; else -> tabs.getOrElse(page) { "Préstamos" } },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }, navigationIcon = {
                        if (newLoan || selectedLoan != null || connection) IconButton(onClick = {
                            newLoan = false; selectedLoan = null; connection = false; exitGuard.reset()
                        }) { AppIcon(R.drawable.ic_back, "Volver") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("DEMOSTRACIÓN · Datos ficticios · Guardado local", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            bottomBar = {
                AppNavigation(page, tabs, tabIcons, { navigate(it) })
            },
            floatingActionButton = {
                if (snapshot != null && page == 1 && !newLoan && selectedLoan == null && !connection) {
                    ExtendedFloatingActionButton(onClick = { newLoan = true }, icon = { AppIcon(R.drawable.ic_add) }, text = { Text("Nuevo préstamo") },
                        containerColor = Teal, contentColor = MaterialTheme.colorScheme.onPrimary)
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(error)
                            TextButton(onClick = { if (snapshot == null) model.load() else model.refresh() }, enabled = !state.busy,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                Text("Actualizar / reintentar lectura")
                            }
                        }
                    }
                }
                state.notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Teal, style = MaterialTheme.typography.bodySmall) }
                when {
                    snapshot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.busy) "Abriendo datos de demostración…" else "No se pudieron cargar los datos") }
                    connection -> ConnectionScreen(onBack = { connection = false }, onGoogle = onGoogle)
                    newLoan -> NewLoanScreen(snapshot, state, model) { navigate(1) }
                    selectedLoan != null -> snapshot.loans.find { it.id == selectedLoan }?.let { loan ->
                        key(loan.id) { LoanDetail(loan, state.day, onBack = { selectedLoan = null }, onPayments = { openPayments(loan.id) }) }
                    }
                    else -> MenuPager(page, tabs.indices.toList(), { navigate(it) },
                        Modifier.weight(1f)) { menu ->
                        when (menu) {
                            0 -> HomeScreen(snapshot, state, onRefresh = model::refresh, onNew = { newLoan = true }, onConnect = { connection = true })
                            1 -> LoansScreen(snapshot, state.day, state.busy, onOpen = { selectedLoan = it }, onNew = { newLoan = true },
                                onPayments = { openPayments(it) }, onRefresh = model::refresh)
                            2 -> key(paymentLoanId) { PaymentsScreen(snapshot, state, model, paymentLoanId) { paymentLoanId = null } }
                            else -> ChartsScreen(snapshot, state.day)
                        }
                    }
                }
            }
        }
    }
    if (about) BrandAboutDialog { about = false }
    if (palette) AccentPicker(accentIndex, onSelectAccent) { palette = false }
}

@Composable private fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun Metric(label: String, amount: Long, prominent: Boolean = false) {
    if (prominent) { FinancialHero(label, amount, "Resumen de la demostración"); return }
    Card(colors = CardDefaults.cardColors(containerColor = if (prominent) Teal else MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(label, color = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(soles(amount), style = if (prominent) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                color = if (prominent) MaterialTheme.colorScheme.onPrimary else Teal, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable private fun HomeScreen(snapshot: Snapshot, state: AppState, onRefresh: () -> Unit, onNew: () -> Unit, onConnect: () -> Unit) {
    val summary = Rules.summary(snapshot, state.day)
    val annual = periodReport(snapshot, state.day, state.day.year, 0)!!
    val active = snapshot.loans.filter { it.disbursed <= state.day && it.active(state.day) }
    val forecast = PortfolioForecast(active.size,
        active.sumOf { loan -> loan.installments.filterNot { it.paid(state.day) }.sumOf { it.base } },
        active.sumOf { it.agreed - it.principal - it.interest(state.day) },
        active.sumOf { loan->loan.installments.filterNot { it.paid(state.day) }.sumOf { it.late(state.day) } })
    val display = SheetSummary(state.day.year, state.day, Rules.INITIAL_CAPITAL, annual.openingCash,
        annual.contribution, annual.disbursed, annual.receipts, annual.interest, annual.late,
        annual.recovered, summary.cash, summary.pendingCapital)
    Column(Modifier.fillMaxSize()) {
        CashBalanceHeader(summary.cash)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageTitle("Mi negocio", "${dateLabel(state.day)} · Demostración") }
            item { DashboardOverview(display, forecast, false, false, false, {},moved=snapshot.loans.filter { it.disbursed<=state.day }.sumOf { it.principal }) }
            item { EarningsChart(display, true) }
            item { CashChart(periodReport(snapshot, state.day, state.day.year, state.day.monthValue)!!) }
            item { OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Actualizar demostración") } }
            item { Button(onClick = onNew, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Nuevo préstamo") } }
            item { TextButton(onClick = onConnect) { Text("Conexión con Google Sheets") } }
        }
    }
}

@Composable private fun Choices(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { label -> FilterChip(selected = label == selected, onClick = { onSelect(label) }, label = { Text(label) }) }
    }
}
@Composable private fun YearSelector(years: List<Int>, year: Int, onSelect: (Int) -> Unit) =
    Choices(years.map(Int::toString), year.toString()) { onSelect(it.toInt()) }
private fun years(snapshot: Snapshot, day: LocalDate) = (listOf(day.year, 2026, 2027, 2028) +
    snapshot.loans.flatMap { listOf(it.disbursed.year, it.installments.last().due.year) }).distinct().sorted()
private fun matchesFilter(loan: Loan, filter: String, day: LocalDate) = when (filter) {
    "Activos" -> loan.active(day)
    "Concluidos" -> !loan.active(day)
    else -> true
}
@Composable private fun LoanCard(loan: Loan, day: LocalDate, onPayments: (() -> Unit)? = null, onOpen: () -> Unit) {
    val active = loan.active(day)
    val overdue = loan.installments.count { it.due < day && !it.paid(day) }
    val next = loan.installments.firstOrNull { !it.paid(day) }
    val status = if (!active) "Concluido" else if (overdue > 0) "Con atraso" else "Activo"
    val initials = loan.client.trim().split(Regex("\\s+")).take(2).joinToString("") { it.take(1) }.uppercase()
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(12.dp),
        border = if (overdue > 0) BorderStroke(1.dp, Color(0xFFC99B35)) else null,
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.surface else Grey)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(initials, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold) }
                }
                Column(Modifier.weight(1f)) {
                    Text(loan.client, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Prestado ${soles(loan.principal)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = if (overdue > 0) Yellow else MaterialTheme.colorScheme.surfaceVariant) {
                    Text(status, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("Saldo pendiente", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(soles(loan.balance(day)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text("${loan.paidCount(day)} / ${loan.installments.size} cuotas", style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { loan.paidCount(day).toFloat() / loan.installments.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            if (next != null) {
                val dueToday = loan.installments.firstOrNull { it.due == day && !it.paid(day) }
                val relevant = dueToday ?: next
                Text("${if (dueToday != null) "HOY" else if (next.due < day) "Pendiente desde" else "Próxima cuota"} · ${dateLabel(relevant.due)} · ${soles(relevant.base + relevant.late(day))}",
                    style = MaterialTheme.typography.bodySmall, color = if (dueToday != null) Teal else MaterialTheme.colorScheme.onSurface)
            }
            if (overdue > 0) Text("$overdue cuota(s) atrasada(s) · Saldo incluye mora pendiente", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Desde ${dateLabel(loan.disbursed)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Detalle") }
                    if (active && onPayments != null) TextButton(onClick = onPayments, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Pagos") }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LoansScreen(snapshot: Snapshot, day: LocalDate, busy: Boolean, onOpen: (String) -> Unit,
    onNew: () -> Unit, onPayments: (String?) -> Unit, onRefresh: () -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(day.year) }
    var filter by rememberSaveable { mutableStateOf("Activos") }
    var client by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val clients = snapshot.loans.map { it.client }.distinct().sortedBy { it.lowercase() }
    val loans = snapshot.loans.filter { (client.isNotBlank() || it.belongsToYear(year)) && matchesFilter(it, filter, day) && (client.isBlank() || it.client.equals(client, true)) }
        .sortedWith(compareByDescending<Loan> { it.installments.any { installment -> installment.due < day && !installment.paid(day) } }.thenBy { it.client.lowercase() })
    val todayPending = snapshot.loans.flatMap { it.installments }.filter { it.due == day && !it.paid(day) }
    val cash = Rules.summary(snapshot, day).cash
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PageTitle("Mis préstamos", "${dateLabel(day)} · America/Lima") }
                IconButton(onClick = onRefresh, enabled = !busy) { AppIcon(R.drawable.ic_refresh, "Actualizar datos locales") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Caja disponible", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(soles(cash), color = Teal, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Por cobrar hoy · ${todayPending.size} cuota(s)", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(soles(todayPending.sumOf { it.base }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { onPayments(null) }) { Text("Ver pagos de hoy") }
                    }
                    Text("Todos los préstamos · Importes pendientes, aún sin cobrar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy) expanded = it }) {
                OutlinedTextField(client.ifBlank { "Todos los clientes" }, {}, readOnly = true, label = { Text("Cliente") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text("Todos los clientes") }, onClick = { client = ""; filter = "Activos"; expanded = false })
                    clients.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { client = name; filter = "Todos"; expanded = false }) }
                }
            }
        }
        item { YearSelector(years(snapshot, day), year) { year = it } }
        item { Choices(listOf("Activos", "Todos", "Concluidos"), filter) { filter = it } }
        item {
            Text("${loans.size} préstamo(s) · $year", fontWeight = FontWeight.Medium)
            Text("Actualización local: " + (snapshot.refreshedAt?.atZone(LIMA)?.format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) ?: "Sin actualizar"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(loans, key = { it.id }) { loan -> LoanCard(loan, day, onPayments = { onPayments(loan.id) }) { onOpen(loan.id) } }
        if (loans.isEmpty()) item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No hay préstamos para esta búsqueda.", fontWeight = FontWeight.Bold)
                    Text("Puedes consultar otro año, incluir los concluidos o registrar un préstamo.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { client = ""; filter = "Todos" }) { Text("Ver todos los clientes") }
                    Button(onClick = onNew) { Text("Nuevo préstamo") }
                }
            }
        }
    }
}
@Composable private fun LoanDetail(loan: Loan, day: LocalDate, onBack: () -> Unit, onPayments: () -> Unit) {
    val todayIndex = loan.installments.indexOfFirst { it.due == day }
    val firstPendingIndex = loan.installments.indexOfFirst { !it.paid(day) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (todayIndex >= 0) todayIndex + 1 else (firstPendingIndex + 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack) { AppIcon(R.drawable.ic_back); Spacer(Modifier.width(6.dp)); Text("Volver") }
        PageTitle(loan.client, "${loan.paidCount(day)} / ${loan.installments.size} cuotas pagadas")
        Text(soles(loan.balance(day)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Teal)
        Text("Saldo pendiente · incluye mora", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { listState.animateScrollToItem(if (todayIndex >= 0) todayIndex + 1 else 0) } }) { Text("Hoy ${dateLabel(day)}") }
            Button(onClick = onPayments) { Text("Editar pagos") }
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text("Código: ${loan.id}\nPrestado ${soles(loan.principal)} · Pactado ${soles(loan.agreed)}\nTasa mensual ${loan.ratePercent.toPlainString()}% · ${loan.months} mes(es)\nGarantía: ${loan.guarantee.ifBlank { "Sin garantía" }}\nPrimera cuota: ${dateLabel(loan.installments.first().due)}")
                if (todayIndex < 0) Text("Hoy no corresponde una cuota de este préstamo.", color = Teal)
            }
            items(loan.installments, key = { it.id }) { cuota ->
                Card(border = if (cuota.due == day) BorderStroke(2.dp, Teal) else null,
                    colors = CardDefaults.cardColors(containerColor = when { cuota.paid(day) -> Grey; cuota.due <= day -> Yellow; else -> MaterialTheme.colorScheme.surface })) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${if (cuota.due == day) "HOY · " else ""}${dateLabel(cuota.due)} · ${if (cuota.paid(day)) "Pagó" else if (cuota.due > day) "Futura" else "Pendiente"}", fontWeight = FontWeight.Bold)
                        Text("Base ${soles(cuota.base)} · Mora ${soles(cuota.late(day))}")
                        Text("Total ${soles(cuota.base + cuota.late(day))}")
                        cuota.paidOn?.let { Text("Cobrado: ${dateLabel(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable private fun FormField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun NewLoanScreen(snapshot: Snapshot, state: AppState, model: LoansViewModel, onDone: () -> Unit) {
    var client by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("2") }
    var months by rememberSaveable { mutableStateOf("1") }
    var guarantee by rememberSaveable { mutableStateOf("") }
    var external by rememberSaveable { mutableStateOf(false) }
    var contribution by rememberSaveable { mutableStateOf("0") }
    var key by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    fun changed() { key = UUID.randomUUID().toString() }
    val rawRequest = runCatching {
        LoanRequest(client, parseMoney(amount) ?: error("Ingresa un monto con hasta dos decimales"),
            BigDecimal(rate.replace(',', '.')), months.toInt(), guarantee,
            if (external) Funding.EXTERNAL else Funding.CAPITAL,
            if (external) parseMoney(contribution) ?: error("Aporte inválido") else 0)
    }.getOrNull()
    val cash = Rules.summary(snapshot, state.day).cash
    val request = rawRequest?.let { runCatching { Rules.fundLoan(it, cash) }.getOrNull() }
    val preview = request?.let { runCatching { Rules.createLoan("preview", it, state.day) } }
    val after = request?.let { runCatching { Rules.cashAfter(cash, it.principal, it.contribution) }.getOrNull() }
    val valid = preview?.isSuccess == true && after != null && after >= 0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageTitle("Nuevo préstamo", "Guardado local de demostración") }
        item { FormField(client, { client = it; changed() }, "Cliente", enabled = !state.busy) }
        val suggestions = snapshot.loans.map { it.client }.distinct().filter { client.isNotBlank() && it.startsWith(client, true) && it != client }.take(5)
        if (suggestions.isNotEmpty()) item { Choices(suggestions, "") { client = it; changed() } }
        item { FormField(amount, { amount = it; changed() }, "Monto en soles", true, !state.busy) }
        item { FormField(rate, { rate = it; changed() }, "Tasa mensual (%)", true, !state.busy) }
        item { FormField(months, { months = it; changed() }, "Plazo en meses (1–24)", true, !state.busy) }
        item { FormField(guarantee, { guarantee = it; changed() }, "Garantía", enabled = !state.busy) }
        item { Choices(listOf("Del capital", "Capital nuevo"), if (external) "Capital nuevo" else "Del capital") { if (!state.busy) { external = it == "Capital nuevo"; changed() } } }
        if (external) item { FormField(contribution, { contribution = it; changed() }, "Aporte externo en soles", true, !state.busy) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Vista previa", fontWeight = FontWeight.Bold)
                    Text("Caja anterior: ${soles(cash)}")
                    request?.let {
                        Text("Aporte: ${soles(it.contribution)} · Préstamo: ${soles(it.principal)}")
                        Text("Sale de caja: ${soles((it.principal - it.contribution).coerceAtLeast(0))}")
                        Text("Excedente a caja: ${soles((it.contribution - it.principal).coerceAtLeast(0))}")
                    }
                    after?.let { Text("Caja después: ${soles(it)}", color = if (it < 0) MaterialTheme.colorScheme.error else Teal, fontWeight = FontWeight.Bold) }
                    preview?.getOrNull()?.let {
                        Text("Pactado: ${soles(it.agreed)} · ${it.installments.size} cuotas")
                        Text("Primera cuota ${dateLabel(it.installments.first().due)}: ${soles(it.installments.first().base)}")
                        Text("Siguientes: ${soles(it.installments[1].base)}")
                    }
                    if (request != null && request.contribution > (rawRequest?.contribution ?: 0))
                        Text("Se ingresarán ${soles(request.contribution)} a caja como aporte de capital.", color = MaterialTheme.colorScheme.primary)
                    preview?.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("El aporte externo no se cuenta como ganancia.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Button(onClick = { request?.let { model.create(key, it, onDone) } }, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Guardar préstamo de demostración") } }
        item { TextButton(onClick = onDone, enabled = !state.busy) { Text("Cancelar") } }
    }
}

@Composable private fun PaymentsScreen(snapshot: Snapshot, state: AppState, model: LoansViewModel, focusLoanId: String?, onClearFocus: () -> Unit) {
    val focusedLoan = snapshot.loans.find { it.id == focusLoanId && it.active(state.day) }
    val firstPending = focusedLoan?.installments?.firstOrNull { it.due <= state.day && !it.paid(state.day) }?.due ?: state.day
    var from by rememberSaveable { mutableStateOf(firstPending.toString()) }
    var to by rememberSaveable { mutableStateOf(state.day.toString()) }
    var client by rememberSaveable { mutableStateOf("") }
    var pending by rememberSaveable { mutableStateOf(false) }
    var review by rememberSaveable { mutableStateOf(false) }
    val start = runCatching { LocalDate.parse(from) }.getOrNull()
    val end = runCatching { LocalDate.parse(to) }.getOrNull()
    val valid = start != null && end != null && start <= end
    val rows = if (valid) snapshot.loans.filter { it.active(state.day) && (focusLoanId == null || it.id == focusLoanId) && it.client.startsWith(client.trim(), true) }
        .flatMap { loan -> loan.installments.filter { it.due >= start && it.due <= end && (!pending || !it.paid(state.day)) }.map { loan to it } }
        .sortedWith(compareBy({ it.second.due }, { it.first.client })) else emptyList()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PageTitle("Pagos", "✓ Marcado = NO PAGÓ · Sin marcar = PAGÓ")
        focusedLoan?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${it.client} · Préstamo seleccionado", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Teal)
                TextButton(onClick = onClearFocus) { Text("Ver todos") }
            }
        }
        OutlinedButton(onClick = { from = state.day.toString(); to = from }, enabled = !state.busy) { Text("Hoy · ${dateLabel(state.day)}") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { FormField(from, { from = it }, "Desde (aaaa-mm-dd)") }
            Box(Modifier.weight(1f)) { FormField(to, { to = it }, "Hasta (aaaa-mm-dd)") }
        }
        FormField(client, { client = it }, "Primeras letras del cliente")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pending, onCheckedChange = { pending = it })
            Text("Solo pendientes")
        }
        if (!valid) Text("Ingresa fechas válidas y un intervalo ordenado", color = MaterialTheme.colorScheme.error)
        if (state.edits.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { review = true }, enabled = !state.busy) { Text("Revisar ${state.edits.size} cambios") }
            TextButton(onClick = model::discard, enabled = !state.busy) { Text("Descartar") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.second.id }) { (loan, cuota) -> PaymentRow(loan, cuota, state, model) }
            if (rows.isEmpty()) item { Text("No hay cuotas para estos filtros.") }
        }
    }
    if (review) AlertDialog(onDismissRequest = { if (!state.busy) review = false },
        title = { Text("Revisar cambios") },
        text = {
            Column {
                Text("Se guardará todo el lote junto. Las cuotas futuras están excluidas.")
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.edits.values.toList(), key = { it.installmentId }) { edit ->
                        val loan = snapshot.loans.single { it.id == edit.loanId }
                        val cuota = loan.installments.single { it.id == edit.installmentId }
                        Column {
                            Text("${loan.client} · ${dateLabel(cuota.due)}", fontWeight = FontWeight.Bold)
                            Text("${if (cuota.paid(state.day)) "Pagó" else "No pagó"} → ${if (edit.unpaid) "No pagó" else "Pagó"}")
                            Text("Base ${soles(cuota.base)} · Mora cobrada ${soles(edit.collectedLate)}")
                            Text("Cobro: ${soles(if (edit.unpaid) 0 else cuota.base + edit.collectedLate)}")
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { model.savePayments { review = false } }, enabled = !state.busy && state.edits.isNotEmpty()) { Text(if (state.busy) "Guardando…" else "Guardar lote") } },
        dismissButton = { TextButton(onClick = { review = false }, enabled = !state.busy) { Text("Seguir editando") } })
}

@Composable private fun PaymentRow(loan: Loan, cuota: Installment, state: AppState, model: LoansViewModel) {
    val edit = state.edits[cuota.id]
    val unpaid = edit?.unpaid ?: !cuota.paid(state.day)
    val future = cuota.due > state.day
    val late = edit?.collectedLate ?: cuota.late(state.day)
    var lateText by remember(cuota.id, late) { mutableStateOf(BigDecimal.valueOf(late, 2).toPlainString()) }
    val parsed = parseMoney(lateText)
    Card(colors = CardDefaults.cardColors(containerColor = if (future) MaterialTheme.colorScheme.surface else if (unpaid) Yellow else Grey)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${loan.client} · ${if (cuota.due == state.day) "HOY " else ""}${dateLabel(cuota.due)}", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = unpaid, enabled = !future && !state.busy, onCheckedChange = { model.stage(loan, cuota, it) })
                Text(if (future) "Futura · No contabilizada" else if (unpaid) "No pagó" else "Pagó")
                if (edit != null) Text(" · Sin guardar", color = Teal, style = MaterialTheme.typography.labelSmall)
            }
            Text("Cuota base ${soles(cuota.base)} · Mora ${soles(cuota.late(state.day))}")
            Text("Total actualizado ${soles(cuota.base + cuota.late(state.day))}")
            if (!unpaid && !future) {
                FormField(lateText, { value ->
                    lateText = value
                    val amount = parseMoney(value)
                    if (amount != null && amount <= cuota.maximumLate(state.day)) model.stage(loan, cuota, false, amount)
                }, "Mora efectivamente cobrada (S/)", true, !state.busy)
                if (parsed == null || parsed > cuota.maximumLate(state.day)) Text("Mora inválida. Máximo: ${soles(cuota.maximumLate(state.day))}. El lote conserva ${soles(late)}.", color = MaterialTheme.colorScheme.error)
                Text("A guardar: base ${soles(cuota.base)} + mora ${soles(late)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun ConnectionScreen(onBack: () -> Unit, onGoogle: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { AppIcon(R.drawable.ic_back); Spacer(Modifier.width(6.dp)); Text("Volver") } }
        item { PageTitle("Google Sheets", "Autoriza tu cuenta para leer el libro") }
        item { Button(onClick = onGoogle) { Text("Conectar con Google") } }
        item { Text("La app trabaja actualmente con datos ficticios locales. No ha iniciado sesión en Google y no escribe en tu libro.") }
        item { Text("Puedes conectar Google para consultar datos reales. Las escrituras están bloqueadas hasta comprobar las operaciones en una copia del libro.") }
        item { Text("Para conectar tu cuenta se solicitarán permisos de Google. Cada usuario deberá tener acceso al libro y autorización para las operaciones.") }
        item { Text("Antes de habilitar operaciones reales se comprobarán en una copia del libro: alta, pagos por lote, mora cobrada, reintentos, conflictos e historial.") }
        item { Text("Podrás seguir usando esta demostración mientras se configura y verifica la conexión real.") }
    }
}

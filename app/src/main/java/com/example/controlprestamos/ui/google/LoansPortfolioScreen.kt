package com.example.controlprestamos.ui.google

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.R
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.consultas.LoanCustomerAccessDialog
import com.example.controlprestamos.consultas.LoanPaymentInstructionsDialog
import com.example.controlprestamos.domain.today
import kotlinx.coroutines.delay

@Composable internal fun ColumnScope.Loans(state: SheetsState, model: SheetsViewModel, onOpen: (SheetLoan) -> Unit, onCreateLoan: (String) -> Unit, active: Boolean = true) {
    var query by rememberSaveable { mutableStateOf("") }
    var chooseClient by rememberSaveable { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var accessClient by remember { mutableStateOf<List<SheetLoan>?>(null) }
    var paymentInstructions by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var review by rememberSaveable { mutableStateOf(false) }
    val day by produceState(initialValue = today()) { while (true) { delay(30_000); value = today() } }
    LoadSearch(active, state, model)
    val all = canonicalLoanViews(state.searchLoans + state.loans + state.data?.loans.orEmpty(), day.year)
    val groups = clientLoanGroups(all, day)
    val visibleGroups = groups.map { clientLoans ->
        clientLoans to clientLoans.filter { showClosed || localLoanView(it, state.financialEdits, day).active }
    }.filter { (_, visible) -> visible.isNotEmpty() }
    val visibleLoans = visibleGroups.flatMap { it.second }
    val matchingGroups = visibleGroups.filter { (clientLoans, _) -> selectedClientMatches(clientLoans.first().client, query) }
    val rows = (state.forecastPayments + state.data?.todayPayments.orEmpty()).distinctBy { it.id }
    val writable = state.ready && !state.saving && state.sensitiveRequest == null && state.paymentConflict == null
    val canDelete = writable && model.connection.canDeleteLoan(model.bookId) && state.unconfirmedFees.isEmpty() && state.financialEdits.isEmpty()
    LaunchedEffect(visibleLoans.map { it.id }, state.searchLoaded) {
        if (state.searchLoaded) selectedIds = selectedIds.filter { id -> visibleLoans.any { it.id == id } }
    }
    fun select(loan: SheetLoan) {
        selectedIds = if (loan.id in selectedIds) selectedIds - loan.id else selectedIds + loan.id
    }
    fun finishSelection() { selectionMode = null; selectedIds = emptyList(); review = false }
    BackHandler(enabled = active && selectionMode != null && !review) { if (!state.saving) finishSelection() }
    fun startSelection(mode: String) { menu = false; query = ""; selectedIds = emptyList(); selectionMode = mode }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${matchingGroups.size} clientes · ${matchingGroups.sumOf { it.second.size }} préstamos", style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !state.saving) {
                Icon(painterResource(R.drawable.ic_more), "Opciones de préstamos y búsqueda")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (model.bookId == SheetsIdentity.CLIENT_ID) DropdownMenuItem(text = { Text("Instrucciones de pago") },
                    enabled = writable && selectionMode == null, onClick = { menu = false; paymentInstructions = true })
                DropdownMenuItem(text = { Text("Buscar cliente") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    onClick = { menu = false; chooseClient = true })
                DropdownMenuItem(text = { Text(if (showClosed) "Ocultar préstamos concluidos" else "Mostrar préstamos concluidos") },
                    enabled = selectionMode == null,
                    onClick = { showClosed = !showClosed; menu = false })
                DropdownMenuItem(text = { Text("Agrupar préstamos") }, enabled = writable && selectionMode == null && visibleLoans.size >= 2,
                    onClick = { startSelection("GROUP") })
                DropdownMenuItem(text = { Text("Eliminar préstamos") }, enabled = canDelete && selectionMode == null && visibleLoans.isNotEmpty(),
                    onClick = { startSelection("DELETE") })
                DropdownMenuItem(text = { Text("Ver todos los clientes") }, enabled = query.isNotBlank(), onClick = { query = ""; menu = false })
            }
        }
    }
    if (chooseClient) ClientPicker("Elegir cliente", visibleGroups.map { (clientLoans, _) -> clientLoans.first().client }, !state.searchLoaded, query,
        dismiss = { chooseClient = false }) { query = it; chooseClient = false }
    if (query.isNotBlank()) SelectedClientChip(query, clear = { query = "" }, open = { chooseClient = true })
    if (selectionMode != null) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (selectionMode == "GROUP") "Marca los préstamos que quieres agrupar" else "Marca los préstamos que quieres eliminar",
                    style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selectedIds.size} seleccionados", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = ::finishSelection, enabled = !state.saving) { Text("Cancelar") }
                    TextButton(onClick = { review = true }, enabled = if (selectionMode == "GROUP") writable && selectedIds.size >= 2
                        else canDelete && selectedIds.isNotEmpty()) { Text(if (selectionMode == "GROUP") "Continuar" else "Revisar") }
                }
            }
        }
    }
    if (matchingGroups.isEmpty()) {
        EmptyLoansMessage(when {
            !state.searchLoaded -> "Cargando préstamos…"
            all.isEmpty() -> "Aún no hay préstamos.\nAñade el primero para comenzar."
            query.isNotBlank() || showClosed -> "No hay préstamos que coincidan con estos filtros."
            else -> "No hay préstamos activos.\nPuedes ver los concluidos desde los tres puntos."
        }, Modifier.weight(1f))
    } else {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            matchingGroups.forEach { (clientLoans, visible) ->
                key(normalizedClient(clientLoans.first().client)) {
                    PortfolioClientTable(clientLoans, visible, rows, state.financialEdits, day, active,
                        selectionMode != null, selectedIds, toggle = ::select, open = onOpen, selectAll = {
                            val ids = visible.map { it.id }
                            selectedIds = if (ids.all { it in selectedIds }) selectedIds - ids.toSet() else (selectedIds + ids).distinct()
                        }, canCreate = writable && selectionMode == null && model.connection.canCreateLoan(model.bookId),
                        create = { onCreateLoan(clientLoans.first().client) },
                        onClient = { if (writable && selectionMode == null) accessClient = clientLoans })
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
    accessClient?.let { loans -> LoanCustomerAccessDialog(loans, model.bookId) { accessClient = null } }
    if (paymentInstructions) LoanPaymentInstructionsDialog(model.bookId) { paymentInstructions = false }
    if (review && selectionMode == "GROUP") GroupLoansDialog(state, model, all, selectedIds,
        dismiss = { review = false }, grouped = { finishSelection(); query = "" })
    if (review && selectionMode == "DELETE") DeleteSelectedLoansDialog(all.filter { it.id in selectedIds }, state, model,
        dismiss = { review = false }, deleted = ::finishSelection)
}

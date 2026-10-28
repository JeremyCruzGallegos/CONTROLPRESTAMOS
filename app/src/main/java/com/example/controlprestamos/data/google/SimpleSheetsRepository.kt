package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The experimental two-tab format is accepted only on the explicitly authorized test book. */
internal class SimpleSheetsRepository(private val bookId: String, private val subject: String,
    private val http: SheetsHttp, private val cache: SheetCache) {
    init { require(SheetsIdentity.isSimpleBook(bookId)) { "El libro no está configurado para el formato simple" } }
    private val base = "https://sheets.googleapis.com/v4/spreadsheets/$bookId"
    private var meta: JSONObject? = null
    @Volatile private var state: SimpleBook? = null
    private var historyRows: List<List<Any?>>? = null
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun properties(name: String): JSONObject = requireNotNull(meta).getJSONArray("sheets").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getJSONObject("properties") }.single { it.getString("title") == name }
    }
    private fun batch(ranges: List<String>, formulas: Boolean = false): List<List<List<Any?>>> {
        val result = http.get("$base/values:batchGet?" + ranges.joinToString("&") { "ranges=${encode(it)}" } +
            "&valueRenderOption=${if (formulas) "FORMULA" else "UNFORMATTED_VALUE"}&dateTimeRenderOption=SERIAL_NUMBER")
        require(result.getString("spreadsheetId") == bookId)
        val values = result.getJSONArray("valueRanges"); require(values.length() == ranges.size)
        return ranges.indices.map { i -> values.getJSONObject(i).let {
            require(sameSheetRange(ranges[i], it.getString("range"))); it.values()
        } }
    }
    fun cached(year: Int): SheetsPreload? {
        val saved = cache.read() ?: return null
        if (saved.optInt("schema") != 2) return null
        require(saved.getString("bookId") == bookId && saved.getString("subject") == subject)
        state = simpleBookFromJson(saved.getJSONObject("simple"))
        return requireNotNull(state).preload(year, today(), bookId)
    }
    private fun persist() {
        cache.write(JSONObject().put("schema", 2).put("bookId", bookId).put("subject", subject)
            .put("simple", requireNotNull(state).json()))
    }
    private fun loadMetadata(names: Boolean = false) {
        val mask = "spreadsheetId,properties(title,timeZone),sheets(properties(sheetId,title,gridProperties))" +
            if (names) ",namedRanges(name,namedRangeId,range)" else ""
        val response = http.get("$base?fields=${encode(mask)}")
        require(response.getString("spreadsheetId") == bookId && response.getJSONObject("properties").getString("timeZone") == "America/Lima")
        val tabs = response.getJSONArray("sheets")
        val titles=(0 until tabs.length()).map { tabs.getJSONObject(it).getJSONObject("properties").getString("title") }.toSet()
        require(titles.containsAll(setOf("NUEVO","PRESTAMOS")) && titles.all{it in setOf("NUEVO","PRESTAMOS","CLIENTES")} && titles.size==tabs.length()) {
            "El libro debe conservar NUEVO y PRESTAMOS; CLIENTES es opcional"
        }
        meta = response
    }
    private fun loadValues(formulas: Boolean = false) {
        val grid = properties("PRESTAMOS").getJSONObject("gridProperties")
        val columns = grid.getInt("columnCount"); val rows = grid.getInt("rowCount")
        require(columns in 19..26_000 && rows in 2..100_000 && columns.toLong() * rows <= 10_000_000)
        val journalColumns = properties("NUEVO").getJSONObject("gridProperties").getInt("columnCount")
        val journalRows = properties("NUEVO").getJSONObject("gridProperties").getInt("rowCount")
        val result = batch(listOf("'NUEVO'!A1:B5", "'PRESTAMOS'!A1:${columnName(columns)}$rows") +
            listOf("'NUEVO'!A24:${if (journalColumns >= 11) "K" else "F"}$journalRows"), formulas)
        val eventTimes = if (result.size > 2) result[2].fold(emptyMap<String, Instant>()) { times, row ->
            val receipt = row.getOrNull(3)?.toString()?.takeIf { it.startsWith("{") }?.let(::JSONObject)
            times + (receipt?.let { receiptCashTimes(it, row.firstOrNull()) }.orEmpty())
        } else emptyMap()
        val movements = if (result.size > 2) result[2].mapNotNull { row ->
            val movement = row.getOrNull(3)?.toString()?.takeIf { it.startsWith("{") }?.let(::JSONObject)?.optJSONObject("cashMovement")
            movement?.let { value -> cashMovement(value).also { entry ->
                require(row.drop(6).take(5) == listOf(entry.signed / 100.0, entry.date.toString(), entry.type.label.uppercase(), entry.description, entry.id) ||
                    row.drop(6).take(5).let { cells -> cells.size == 5 && sheetCents(cells[0]) == entry.signed && cells.drop(1) == listOf(entry.date.toString(), entry.type.label.uppercase(), entry.description, entry.id) }) {
                    "El detalle del movimiento de caja cambió; revisa el comprobante"
                }
            } }
        } else emptyList()
        require(movements.map { it.id }.distinct().size == movements.size) { "Hay movimientos de caja duplicados" }
        val config = result[0]
        require(config.getOrNull(0)?.getOrNull(0) in listOf(SIMPLE_SCHEMA, SIMPLE_LEGACY_SCHEMA) && config.getOrNull(1)?.getOrNull(0) == "CAPITAL INICIAL" &&
            config.getOrNull(2)?.getOrNull(0) == "REVISION" && config.getOrNull(3)?.getOrNull(0) == "CALENDARIO DESDE" &&
            config.getOrNull(4)?.getOrNull(0) == "CALENDARIO HASTA") { "La configuración del libro simple cambió" }
        require(sheetDate(config[3][1]) == SIMPLE_START)
        val checkboxes = config[0][0] == SIMPLE_SCHEMA
        val until = sheetDate(config[4][1]); require(until >= SIMPLE_START && simpleDayColumn(until, checkboxes) + (if (checkboxes) 4 else 3) == columns)
        val initial = sheetCents(config[1][1]); require(initial in 0..MAX_INITIAL_CAPITAL)
        val revision = config[2][1].toString(); require(revision.isNotBlank())
        val header = result[1].indexOfFirst { it.take(SIMPLE_COLUMNS).map { cell -> cell?.toString() } == SIMPLE_HEADERS }
        require(header in 0..20 && result[1][header].map { it?.toString() } == simpleHeadings(until, checkboxes)) { "Los encabezados o las fechas de PRESTAMOS cambiaron" }
        val records = result[1].drop(header + 1).mapIndexedNotNull { index, row ->
            if (row.none(::hasLoanValue)) null else parseSimpleRecord(index + header + 2, row, until, checkboxes)
        }
        require(records.map { it.loan.id }.distinct().size == records.size) { "Hay préstamos con ID duplicado" }
        require(records.flatMap { it.loan.installments }.none { it.paidOn?.let { date -> date > today() } == true }) { "Hay cobros con fecha futura" }
        state = SimpleBook(requireNotNull(meta).getJSONObject("properties").getString("title"), initial, revision, until, records, Instant.now(), checkboxes, header + 1, movements, eventTimes)
    }
    fun preload(year: Int, progress: (Float, String) -> Unit): SheetsPreload {
        progress(0.3f, "Sincronizando el libro simple…")
        loadMetadata(); loadValues(); persist()
        progress(1f, "Información actualizada")
        return book().preload(year, today(), bookId)
    }
    private fun book() = requireNotNull(state) { "Carga el libro simple antes de consultar" }
    fun loans(year: Int) = book().loans(year, today())
    fun allLoans() = book().active.map { it.view(today()) }
    fun contributions() = book().active.associate { it.loan.id to it.loan.contribution }
    fun calendar(loan: SheetLoan, refresh: Boolean): LoanCalendar {
        if (refresh) { loadMetadata(); loadValues(); persist() }
        return book().calendar(loan.id, today(), bookId)
    }
    internal fun offlineCalendarRows(loan: SheetLoan): List<SheetPayment> {
        val state = book(); val record = state.record(loan.id)
        return record.loan.installments.map { state.payment(record, it, today(), bookId) }
    }
    fun payments(from: LocalDate, to: LocalDate, id: String?, ids: Set<String>?) = book().payments(from, to, id, ids, today(), bookId)
    fun cash() = book().cash(today())
    fun movements() = book().movements
    fun cashEventTimes() = book().cashEventTimes
    fun current(year: Int = today().year): SheetsPreload {
        if (state == null) { loadMetadata(); loadValues(); persist() }
        return book().preload(year, today(), bookId)
    }
    fun syncPresentation() {
        // A presentation retry always uses the latest remote rows, including manual edits.
        loadMetadata(); loadValues()
        val requests = simpleCalendarPresentationRequests(properties("PRESTAMOS").getInt("sheetId"), book(), today())
        if (requests.isEmpty()) return
        val result = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
        require(result.getString("spreadsheetId") == bookId)
    }
    fun history(offset: Int): List<List<Any?>> {
        require(offset >= 0)
        val all = historyRows ?: run {
            val max = properties("NUEVO").getJSONObject("gridProperties").getInt("rowCount")
            batch(listOf("'NUEVO'!A24:F$max")).single().flatMap { row ->
                val receipt = row.getOrNull(3)?.toString()?.takeIf { it.startsWith("{") }?.let(::JSONObject)
                receipt?.optJSONArray("history")?.rowValues().orEmpty()
            }.also { historyRows = it }
        }
        return all.drop(offset).take(200)
    }
    private fun cell(value: Any?): JSONObject = JSONObject().apply {
        if (value != null && value != "") put("userEnteredValue", JSONObject().apply {
            when (value) { is Boolean -> put("boolValue", value); is Number -> put("numberValue", value)
                else -> put("stringValue", value.toString()) }
        })
    }
    private fun writeCells(sheet: Int, row: Int, column: Int, values: List<Any?>): JSONObject = JSONObject().put("updateCells", JSONObject()
        .put("start", JSONObject().put("sheetId", sheet).put("rowIndex", row).put("columnIndex", column))
        .put("rows", JSONArray().put(JSONObject().put("values", JSONArray(values.map(::cell))))).put("fields", "userEnteredValue"))
    private fun named(name: String, sheet: Int, row: Int, column: Int = 0): JSONObject = JSONObject().put("addNamedRange", JSONObject()
        .put("namedRange", JSONObject().put("name", name).put("range", JSONObject().put("sheetId", sheet)
            .put("startRowIndex", row).put("endRowIndex", row + 1).put("startColumnIndex", column).put("endColumnIndex", column + 1))))
    private fun operationName(id: String) = "cp_simple_op_" + directPaymentHash(id).take(40)
    private fun receipt(body: JSONObject, hash: String): JSONObject? {
        val names = requireNotNull(meta).optJSONArray("namedRanges") ?: return null
        val marker = (0 until names.length()).map { names.getJSONObject(it) }.singleOrNull { it.getString("name") == operationName(body.getString("operationId")) } ?: return null
        val range = marker.getJSONObject("range")
        require(range.getInt("sheetId") == properties("NUEVO").getInt("sheetId") && range.getInt("startColumnIndex") == 3)
        val row = range.getInt("startRowIndex") + 1
        val saved = batch(listOf("'NUEVO'!B$row:D$row")).single().single()
        require(saved[0] == body.getString("operationId") && saved[1] == hash) { "La operación ya existe con otros datos" }
        return JSONObject(saved[2].toString())
    }
    fun write(pending: PendingRemoteWrite): JSONObject {
        pending.requireOwner(subject, bookId)
        val body = JSONObject(pending.payload)
        require(body.getString("bookId") == bookId && body.getString("operationId") == pending.operationId)
        loadMetadata(names = true)
        receipt(body, directPaymentHash(pending.payload))?.let {
            state = null; historyRows = null
            return it
        }
        // FORMULA refuses to overwrite unexpected formulas instead of converting their results to data.
        loadValues(formulas = true)
        val before = book(); val day = today(); val op = pending.operationId
        val updated = before.records.toMutableList(); var initial = before.initial
        var movement: CashMovement? = null
        var customerDirectory: ClientDirectoryPlan? = null
        val result = JSONObject().put("operationId", op).put("updated", 1)
        val history = mutableListOf<List<Any?>>()
        val changed = mutableSetOf<String>()
        fun replace(value: SimpleRecord) {
            val index = updated.indexOfFirst { it.loan.id == value.loan.id }
            if (index < 0) updated.add(value) else updated[index] = value
            changed += value.loan.id
        }
        try {
            when (body.getString("action")) {
                "editLoanText" -> {
                    val record = before.record(body.getString("loanId"))
                    val fields = body.getJSONArray("textFields").let { values -> List(values.length()) { index ->
                        val field = values.getJSONObject(index)
                        LoanTextField(field.getInt("column"), field.getString("before"), field.getString("after"))
                    } }
                    require(fields.isNotEmpty() && fields.all { it.columnIndex in listOf(1, 6) } && fields.distinctBy { it.columnIndex }.size == fields.size) { "Campos de texto inválidos" }
                    val clientField = fields.firstOrNull { it.columnIndex == 1 }
                    val guaranteeField = fields.firstOrNull { it.columnIndex == 6 }
                    val edit = LoanTextEdit(record.loan.id, clientField?.before ?: record.loan.client,
                        guaranteeField?.before ?: record.loan.guarantee, clientField?.after ?: record.loan.client,
                        guaranteeField?.after ?: record.loan.guarantee)
                    val plan = loanTextWritePlan(edit, mapOf(1 to LoanTextCell(record.loan.client), 6 to LoanTextCell(record.loan.guarantee)))
                    val client = plan.firstOrNull { it.columnIndex == 1 }?.after ?: record.loan.client
                    val guarantee = plan.firstOrNull { it.columnIndex == 6 }?.after ?: record.loan.guarantee
                    replace(record.copy(loan = record.loan.copy(client = client, guarantee = guarantee), revision = op))
                    result.put("loanId", record.loan.id)
                }
                "cashMovement" -> {
                    val entry = movementCommand(body, day)
                    validateCashMovement(entry, before.cash(day), before.summary(day.year, day).cash, day)
                    movement = entry; result.put("cashMovement", entry.json())
                }
                "amountPayment" -> {
                    require(before.checkboxes) { "Actualiza el formato de pruebas para usar Pago múltiple" }
                    val record=before.record(body.getString("loanId"))
                    require(loanDeletionVersion(record.view(day))==body.getString("version")) { "El préstamo cambió; revisa el monto" }
                    val paymentDate=amountPaymentDate(body,day)
                    val plan=planAmountPayment(record,body.getLong("amountCents"),paymentDate,body.optBoolean("waiveLate"))
                    require(plan.confirmation()==body.getString("confirmation")) { "El reparto del monto cambió. Revisa nuevamente" }
                    replace(plan.after.copy(revision=op))
                    plan.paidDates.forEach { due->val item=plan.after.loan.installments.single { it.due==due }
                        history+=listOf(cashRecordedAt(body),op,record.loan.disbursed.year,record.loan.id,due.toString(),true,false,0,
                            item.collectedLate/100.0,paymentDate.toString(),record.loan.client,body.optString("actor",subject)) }
                    result.put("loanId",record.loan.id).put("updated",plan.paidDates.size).put("amountCents",plan.amount).put("termsChanged",true)
                }
                "createLoan" -> {
                    val input = directLoanRequest(body.getJSONObject("loan")); val date = LocalDate.parse(body.getString("disbursedOn"))
                    require(date in SIMPLE_START..day) { "Revisa la fecha del desembolso" }
                    val currentCash = before.summary(day.year, day).cash
                    val available = if (date == day) currentCash else cashForNewLoan(before.cash(day), date, currentCash)
                    val request = Rules.fundLoan(input, available)
                    require(!body.has("maxContributionCents") || request.contribution <= body.getLong("maxContributionCents")) { "La caja cambió y se necesita un aporte mayor al confirmado" }
                    result.put("contributionCents", request.contribution)
                    val number = (before.records.filter { it.loan.disbursed.year == date.year }.maxOfOrNull { it.loan.id.substringAfter('-').toInt() } ?: 0) + 1
                    val id = "${date.year}-${number.toString().padStart(3, '0')}"
                    val loan = withInitialPastPayments(Rules.createLoan(id, request, date), day, body.optBoolean("pastPaymentsPaid", false))
                    val row = (before.records.maxOfOrNull { it.row } ?: before.headerRow) + 1
                    replace(SimpleRecord(row, loan, 100, false, op)); result.put("loanId", id)
                    if (bookId==SheetsIdentity.CLIENT_ID) {
                        val sheets=requireNotNull(meta).getJSONArray("sheets");val props=(0 until sheets.length()).map{sheets.getJSONObject(it).getJSONObject("properties")}
                        val directory=props.singleOrNull{it.getString("title")=="CLIENTES"}
                        val sheet=directory?.getInt("sheetId") ?: generateSequence(190027){it+1}.first{candidate->props.none{it.getInt("sheetId")==candidate}}
                        val values=directory?.let{batch(listOf("'CLIENTES'!A1:H${minOf(it.getJSONObject("gridProperties").getInt("rowCount"),5001)}"),true).single()}.orEmpty()
                        var legacy:JSONObject?=null
                        if(directory==null && properties("NUEVO").getJSONObject("gridProperties").getInt("columnCount")>=28) {
                            val cells=batch(listOf("'NUEVO'!AA1:AB32")).single()
                            if(cells.firstOrNull()?.firstOrNull()=="CP_CONSULTAS_V1") {
                                val count=(cells.getOrNull(1)?.getOrNull(1) as? Number)?.toInt() ?: 0
                                require(count in 1..30){"Revisa el registro de accesos antes de crear el código"}
                                legacy=JSONObject((0 until count).joinToString(""){cells.getOrNull(it+2)?.getOrNull(0)?.toString().orEmpty()})
                            }
                        }
                        customerDirectory=clientDirectoryPlan(updated,loan.client,directory,values,sheet,legacy)
                        result.put("customerCode",customerDirectory!!.code)
                    }
                    loan.installments.filter { it.paid(day) }.forEach { item ->
                        history += listOf(cashRecordedAt(body), op, date.year, id, item.due.toString(), true, false,
                            0, 0, item.paidOn.toString(), loan.client, body.optString("actor", subject))
                    }
                }
                "pay" -> {
                    val edits = body.getJSONArray("edits"); require(edits.length() in 1..100)
                    val seen = mutableSetOf<String>()
                    for (i in 0 until edits.length()) {
                        val edit = edits.getJSONObject(i); val version = directPaymentVersion(edit.getJSONObject("version"))
                        require(seen.add("${version.loanId}:${version.due}")) { "Cuota repetida" }
                        val record = before.record(version.loanId); val view = record.view(day)
                        val item = record.loan.installments.single { it.due == version.due }
                        val current = before.payment(record, item, day, bookId)
                        val paidOn = edit.optString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                        val fee = sheetCents(edit.get("collectedLate")); val unpaid = edit.getBoolean("unpaid")
                        validateDirectPaymentChange(bookId, DirectPaymentChange(version, unpaid, fee, paidOn),
                            DirectPaymentSnapshot(view, item.due, true, current.unpaid, if (current.unpaid) 0 else current.late,
                                current.paidOn, revision = record.revision), day)
                        val working = updated.single { it.loan.id == record.loan.id }
                        replace(working.copy(loan = working.loan.copy(installments = working.loan.installments.map {
                            if (it.due == item.due) it.copy(paidOn = if (unpaid) null else paidOn, collectedLate = if (unpaid) 0 else fee) else it
                        }), revision = op, unverifiedPaid = working.unverifiedPaid - item.due,
                            advances=working.advances.filterNot { unpaid && it.source==item.due }))
                        history += listOf(cashRecordedAt(body), op, view.year, view.id, item.due.toString(), current.unpaid, unpaid,
                            (if (current.unpaid) 0 else current.late) / 100.0, fee / 100.0, paidOn?.toString() ?: "", view.client, body.optString("actor"))
                    }
                    result.put("updated", edits.length())
                }
                "deleteLoan" -> {
                    val record = before.record(body.getString("loanId"))
                    require(loanDeletionVersion(record.view(day)) == body.getString("version")) { "El préstamo cambió; actualiza antes de eliminar" }
                    replace(record.copy(deleted = true, revision = op)); result.put("loanId", record.loan.id)
                }
                "setCapital" -> {
                    val change = capitalChange(body); val summary = before.summary(change.year, day)
                    require(summary.initial == change.before && summary.cash == change.cashBefore) { "El capital o la caja cambiaron; actualiza" }
                    initial = change.after; result.put("capitalAfterCents", initial)
                }
                "editLoanTerms" -> {
                    val record = before.record(body.getString("loanId")); val view = record.view(day)
                    require(record.unverifiedPaid.none { it <= day }) { "Confirma la fecha real de las cuotas cobradas antes de modificar el acuerdo" }
                    require(loanDeletionVersion(view) == body.getString("version")) { "El préstamo cambió; actualiza el acuerdo" }
                    val input = loanTermsInput(body.getJSONObject("terms"))
                    val plan = planLoanTerms(view, before.calendar(view.id, day, bookId), input, day, before.summary(day.year, day).cash)
                    require(record.advances.all { it.source in plan.bases && it.target in plan.bases }) { "Conserva las fechas de cuotas con abonos" }
                    require(plan.confirmation() == body.getString("confirmation")) { "La vista previa cambió; revisa otra vez" }
                    val old = record.loan.installments.associateBy { it.due }
                    val installments = plan.bases.entries.mapIndexed { index, (date, base) ->
                        val paid = old[date]?.takeIf { it.paid(day) }
                        Installment("${view.id}:${index + 1}", date, base, paid?.paidOn, paid?.collectedLate ?: 0)
                    }
                    replace(record.copy(loan = record.loan.copy(client = input.client, guarantee = input.guarantee,
                        principal = input.principal, ratePercent = input.rate, months = input.months, disbursed = input.disbursedOn,
                        installments = installments), dailyLate = input.dailyLate, revision = op))
                    result.put("loanId", view.id)
                }
                else -> error("Operación no admitida en el libro simple")
            }
        } catch (error: IllegalArgumentException) {
            throw DirectPaymentRejected(error.message ?: "Los datos cambiaron. Actualiza y revisa", error)
        }
        if (history.isNotEmpty()) result.put("history", JSONArray(history))
        val eventTimes = receiptCashTimes(result, cashRecordedAt(body))
        result.put("cashEventTimes", encodeCashTimes(eventTimes))
        val end = maxOf(before.until, updated.maxOfOrNull { LocalDate.of(it.loan.installments.last().due.year, 12, 31) } ?: before.until)
        require(simpleDayColumn(end, before.checkboxes) + (if (before.checkboxes) 4 else 3) <= 26_000) { "El calendario supera el límite de columnas de Sheets" }
        val after = before.copy(initial = initial, capitalRevision = op, until = end, records = updated, syncedAt = Instant.now(), movements = before.movements + listOfNotNull(movement), cashEventTimes = before.cashEventTimes + eventTimes)
        val loanProps = properties("PRESTAMOS"); val newProps = properties("NUEVO")
        val loanSheet = loanProps.getInt("sheetId"); val newSheet = newProps.getInt("sheetId")
        val newRows = newProps.getJSONObject("gridProperties").getInt("rowCount")
        val journal = batch(listOf("'NUEVO'!B24:B$newRows")).single()
        val journalRow = 24 + journal.size
        val requests = mutableListOf<JSONObject>()
        // Unique revision names make competing app/script batches fail atomically before overwriting each other.
        requests += named("cp_simple_rev_" + directPaymentHash(before.capitalRevision).take(40), newSheet, 2, 1)
        if (end != before.until || (updated.maxOfOrNull { it.row } ?: 2) > loanProps.getJSONObject("gridProperties").getInt("rowCount")) {
            val rows = maxOf(loanProps.getJSONObject("gridProperties").getInt("rowCount"), (updated.maxOfOrNull { it.row } ?: 2) + 19)
            require(rows.toLong() * (simpleDayColumn(end, before.checkboxes) + (if (before.checkboxes) 4 else 3)) <= 9_900_000) { "El libro alcanzó su capacidad de celdas" }
            requests += JSONObject().put("updateSheetProperties", JSONObject().put("properties", JSONObject().put("sheetId", loanSheet)
                .put("gridProperties", JSONObject().put("rowCount", rows).put("columnCount", simpleDayColumn(end, before.checkboxes) + (if (before.checkboxes) 4 else 3))))
                .put("fields", "gridProperties(rowCount,columnCount)"))
            if (end != before.until) {
                requests += writeCells(loanSheet, before.headerRow - 1, 0, simpleHeadings(end, before.checkboxes))
                if (before.checkboxes) requests += simpleCalendarGroups(loanSheet, before.until.plusDays(1), end)
            }
        }
        if (journalRow > newRows) requests += JSONObject().put("appendDimension", JSONObject().put("sheetId", newSheet).put("dimension", "ROWS").put("length", 100))
        changed.forEach { id -> val record = updated.single { it.loan.id == id }
            val request = writeCells(loanSheet, record.row - 1, 0, record.cells(end, before.checkboxes))
            if (before.checkboxes) {
                val update = request.getJSONObject("updateCells")
                val values = update.getJSONArray("rows").getJSONObject(0).getJSONArray("values")
                for (column in SIMPLE_COLUMNS until values.length() step 4) {
                    if (values.getJSONObject(column).optJSONObject("userEnteredValue")?.has("boolValue") == true)
                        values.getJSONObject(column).put("dataValidation", JSONObject().put("condition", JSONObject().put("type", "BOOLEAN")).put("strict", true).put("showCustomUi", true))
                }
                record.loan.installments.filter { !record.isPaid(it,day) }.forEach { item->
                    simpleAdvanceFormula(record.row,item.due,record.advances)?.let { formula->
                        values.getJSONObject(simpleDayColumn(item.due)+3).put("userEnteredValue",JSONObject().put("formulaValue",formula))
                    }
                }
                update.put("fields", "userEnteredValue,dataValidation")
            }
            requests += request
        }
        requests += customerDirectory?.requests.orEmpty()
        requests += writeCells(newSheet, 1, 1, listOf(initial / 100.0))
        requests += writeCells(newSheet, 2, 1, listOf(op))
        requests += writeCells(newSheet, 4, 1, listOf(end.toString()))
        if (movement != null && newProps.getJSONObject("gridProperties").getInt("columnCount") < 11)
            requests += JSONObject().put("appendDimension", JSONObject().put("sheetId", newSheet).put("dimension", "COLUMNS")
                .put("length", 11 - newProps.getJSONObject("gridProperties").getInt("columnCount")))
        if (movement != null) requests += writeCells(newSheet, 22, 6, listOf("MONTO NETO", "FECHA", "TIPO", "DESCRIPCIÓN", "REFERENCIA"))
        requests += writeCells(newSheet, journalRow - 1, 0, listOf(cashRecordedAt(body), op, directPaymentHash(pending.payload),
            result.toString(), body.getString("action"), body.optString("actor", subject)) +
            (movement?.let { listOf(it.signed / 100.0, it.date.toString(), it.type.label.uppercase(), it.description, it.id) } ?: emptyList()))
        requests += named(operationName(op), newSheet, journalRow - 1, 3)
        try {
            val response = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(response.getString("spreadsheetId") == bookId)
        } catch (error: Exception) {
            // Timeout may mean success. Only a matching receipt resolves it; never generate a second operation.
            val confirmed = runCatching { loadMetadata(names = true); receipt(body, directPaymentHash(pending.payload)) }.getOrNull()
            if (confirmed != null) { state = null; historyRows = null; return confirmed }
            if (error is GoogleHttpException && error.status in listOf(400, 403))
                throw DirectPaymentRejected("Google rechazó el cambio o alguien actualizó el libro. Actualiza y revisa antes de reenviar", error)
            throw error
        }
        state = after; historyRows = null
        // Remote acknowledgement is authoritative even if local storage is temporarily unavailable.
        runCatching { persist() }
        return result
    }
    fun editText(edit: LoanTextEdit, operationId: String? = null) {
        val fields = JSONArray(edit.fields.map { field -> JSONObject().put("column", field.columnIndex)
            .put("before", field.before).put("after", field.after) })
        val id = operationId ?: "text_" + directPaymentHash("${edit.loanId}|$fields").take(40)
        // Only changed fields enter the frozen receipt hash. Unrelated external fields can evolve during a retry.
        val body = JSONObject().put("bookId", bookId).put("operationId", id).put("action", "editLoanText")
            .put("loanId", edit.loanId).put("textFields", fields)
        write(PendingRemoteWrite(id, subject, bookId, body.toString()))
    }
}

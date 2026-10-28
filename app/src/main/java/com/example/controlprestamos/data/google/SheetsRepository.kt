package com.example.controlprestamos.data.google

import android.util.AtomicFile
import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

internal fun JSONArray.rowValues(): List<List<Any?>> = List(length()) { index ->
    val row = getJSONArray(index); List(row.length()) { column -> row.opt(column).takeUnless { it == JSONObject.NULL } }
}
internal fun JSONObject.values(): List<List<Any?>> = optJSONArray("values")?.rowValues() ?: emptyList()
interface SheetCache {
    fun read(): JSONObject?
    fun write(value: JSONObject)
    fun delete()
}
class RemoteJsonFile(file: File) : SheetCache {
    private val atomic = AtomicFile(file)
    private val lock = locks.computeIfAbsent(file.absolutePath) { Any() }
    override fun read(): JSONObject? = synchronized(lock) {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) null else JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
    }
    override fun write(value: JSONObject) = synchronized(lock) {
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    override fun delete() = synchronized(lock) { atomic.delete() }
    fun deleteIf(field: String, value: String) = synchronized(lock) {
        if (read()?.optString(field) == value) atomic.delete()
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
data class SheetsPreload(val title: String, val summary: SheetSummary, val loans: List<SheetLoan>,
    val todayPayments: List<SheetPayment>, val syncedAt: Instant, val paymentServiceError: String? = null)

/** Real ranges stay separate from UI models and from the fictitious demo Snapshot. */
class SheetsRepository(val bookId: String, val subject: String, private val http: SheetsHttp,
    private val cache: SheetCache, private val connection: GoogleConnection, private val tokens: GoogleTokenProvider,
    val isSimpleBook: Boolean = false) {
    private val simple by lazy { SimpleSheetsRepository(bookId, subject, http, cache) }
    private var metadata: JSONObject? = null
    private var capital: List<List<Any?>> = emptyList()
    private val catalogs = mutableMapOf<Int, List<SheetLoan>>()
    private val rawCatalogs = mutableMapOf<Int, JSONArray>()
    private var todayPayments = emptyList<SheetPayment>()
    private var syncedAt: Instant? = null
    private data class Headers(val calendar: Map<LocalDate, Int>, val fees: Map<LocalDate, Int>, val feeRows: Map<String, Int>)
    private val headers = mutableMapOf<Int, Headers>()
    private var cashCache: List<BookCashDay>? = null
    private val calendars = mutableMapOf<String, LoanCalendar>()
    private val offlineCalendars = mutableMapOf<String, List<SheetPayment>>()
    private var paymentHistory: PaymentHistoryIndex? = null
    private val conditions = mutableMapOf<String, LoanConditions>()
    private var conditionsLoaded = false
    private fun configuredLoans(year: Int, rows: List<List<Any?>>, firstRow: Int = 6) =
        parseSheetLoans(year, rows, firstRow).map { it.withConditions(conditions[it.id]) }
    private fun loadConditions(force: Boolean = false) {
        if (conditionsLoaded && !force) return
        conditions.clear()
        optionalProperties("_PRESTAMOS_CONDICIONES")?.let {
            val values = batch(listOf("'_PRESTAMOS_CONDICIONES'!A1:C${rowsIn("_PRESTAMOS_CONDICIONES")}")).single().values()
            require(values.firstOrNull() == listOf("PRESTAMO ID", "CONDICIONES JSON", "OPERACION")) { "Los encabezados de condiciones cambiaron" }
            values.drop(1).filter { it.firstOrNull()?.toString()?.isNotBlank() == true }.forEach { row ->
                val config = loanConditions(JSONObject(row[1].toString()))
                require(config.revision == row[2]?.toString()) { "La revisión del préstamo no coincide" }
                conditions[row[0].toString()] = config
            }
        }
        val references=conditions.values.flatMap { it.advances }.map { it.sourceRef }.filter { it.isNotBlank() }.distinct()
        if(references.isNotEmpty()) {
            require(references.all { it.matches(Regex("'20\\d{2}'![A-Z]+[0-9]+")) }) { "Referencia de abono inválida" }
            val flags=batch(references).map { it.values().firstOrNull()?.firstOrNull() }
            val paid=references.zip(flags).associate { it.first to (it.second!=true) }
            conditions.replaceAll { _,config->config.copy(advances=config.advances.map { it.copy(sourcePaid=paid[it.sourceRef]?:it.sourcePaid) }) }
        }
        conditionsLoaded = true
    }
    var paymentServiceError: String? = null
        private set
    private val base = "https://sheets.googleapis.com/v4/spreadsheets/$bookId"
    init {
        require(bookId.matches(Regex("[A-Za-z0-9_-]{20,}")))
        require(!isSimpleBook || SheetsIdentity.isSimpleBook(bookId))
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun rowsIn(sheet: String): Int = properties(sheet).getJSONObject("gridProperties").getInt("rowCount")
    private fun columnsIn(sheet: String): Int = properties(sheet).getJSONObject("gridProperties").getInt("columnCount")
    private fun properties(sheet: String): JSONObject {
        val sheets = requireNotNull(metadata).getJSONArray("sheets")
        return (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }
            .singleOrNull { it.getString("title") == sheet } ?: error("Falta la hoja $sheet")
    }
    fun batch(ranges: List<String>): List<JSONObject> {
        require(ranges.isNotEmpty())
        if (ranges.size > 50) return ranges.chunked(50).flatMap(::batch)
        val query = ranges.joinToString("&") { "ranges=${encode(it)}" }
        val result = http.get("$base/values:batchGet?$query&valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER&majorDimension=ROWS")
        require(result.getString("spreadsheetId") == bookId) { "Google respondió desde otro libro" }
        val values = result.getJSONArray("valueRanges")
        require(values.length() == ranges.size) { "Respuesta de rangos incompleta" }
        return ranges.indices.map { index -> values.getJSONObject(index).also {
            val returned = it.getString("range")
            require(sameSheetRange(ranges[index], returned)) {
                "Respuesta de un rango inesperado: se solicitó ${ranges[index]} y Google devolvió $returned"
            }
        } }
    }
    fun summary(year: Int): SheetSummary = if (isSimpleBook) simple.current(year).summary else parseSheetSummary(year,
        capital.singleOrNull { it.getOrNull(0)?.toString()?.toDoubleOrNull()?.toInt() == year } ?: error("No hay resumen de $year"),
        capital.firstOrNull()?.getOrNull(1))
    fun cached(year: Int): SheetsPreload? {
        if (isSimpleBook) return simple.cached(year)
        val saved = cache.read() ?: return null
        require(saved.getInt("schema") == 1 && saved.getString("bookId") == bookId && saved.getString("subject") == subject) { "Caché de otra cuenta o libro" }
        metadata = saved.getJSONObject("metadata")
        capital = saved.getJSONArray("capital").rowValues()
        conditions.clear()
        saved.optJSONObject("loanConditions")?.let { configs -> configs.keys().forEach { id -> conditions[id] = loanConditions(configs.getJSONObject(id)) } }
        conditionsLoaded = true
        val all = saved.getJSONObject("catalogs")
        all.keys().forEach { key ->
            val raw = all.getJSONArray(key); rawCatalogs[key.toInt()] = raw; catalogs[key.toInt()] = configuredLoans(key.toInt(), raw.rowValues())
        }
        syncedAt = Instant.parse(saved.getString("syncedAt"))
        val loans = catalogs[year] ?: return null
        val map = catalogs.values.flatten().associateBy { "${it.year}:${it.id}" }
        val date = LocalDate.parse(saved.getString("day"))
        todayPayments = saved.getJSONArray("todayPayments").let { array -> List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val due = LocalDate.parse(obj.getString("due"))
            SheetPayment(map.getValue("${due.year}:${obj.getString("loanId")}"), due, obj.getLong("base"), obj.getBoolean("unpaid"), obj.getLong("late"),
                obj.optString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                version = obj.optString("version").takeIf { it.isNotBlank() },
                bookAmount = obj.opt("bookAmount").takeUnless { it == null || it == JSONObject.NULL }?.let { (it as Number).toLong() })
        } }
        // A different day must not display yesterday's payments as today's collection.
        return SheetsPreload(metadata!!.getJSONObject("properties").getString("title"), summary(year), loans,
            if (date == today()) todayPayments else emptyList(), syncedAt!!)
    }
    private fun save(day: LocalDate) {
        val packet = JSONObject().put("schema", 1).put("subject", subject).put("bookId", bookId).put("metadata", metadata)
            .put("loanConditions", JSONObject(conditions.mapValues { it.value.json() })).put("capital", JSONArray(capital)).put("syncedAt", syncedAt?.toString()).put("catalogs", JSONObject(rawCatalogs.mapKeys { it.key.toString() }))
            .put("day", day.toString()).put("todayPayments", JSONArray(todayPayments.map {
                JSONObject().put("loanId", it.loan.id).put("version", it.version ?: "").put("due", it.due.toString()).put("base", it.base).put("unpaid", it.unpaid).put("late", it.late)
                    .put("paidOn", it.paidOn?.toString() ?: "")
                    .put("bookAmount", it.bookAmount ?: JSONObject.NULL)
            }))
        cache.write(packet)
    }
    fun preload(year: Int, day: LocalDate, progress: (Float, String) -> Unit): SheetsPreload {
        if (isSimpleBook) return simple.preload(year, progress)
        progress(0.25f, "Verificando el libro y sus hojas…")
        val meta = http.get("$base?fields=${encode("spreadsheetId,properties(title,timeZone),sheets(properties(sheetId,title,gridProperties))")}")
        require(meta.getString("spreadsheetId") == bookId)
        require(meta.getJSONObject("properties").getString("timeZone") == "America/Lima") { "El libro debe usar America/Lima" }
        metadata = meta
        loadConditions(force = true)
        headers.clear(); catalogs.clear(); rawCatalogs.clear(); cashCache = null
        calendars.clear(); paymentHistory = null
        val max = rowsIn(year.toString())
        require(max in 6..10_005) { "Estructura anual fuera del rango auditado" }
        val chunks = (6..max step 200).map { "'$year'!A$it:Q${minOf(it + 199, max)}" }
        progress(0.4f, "Cargando capital y préstamos de $year…")
        val result = batch(listOf("'_CAPITAL'!A1:M8") + chunks)
        capital = result.first().values()
        require(capital.getOrNull(3)?.getOrNull(9) == "CAJA") { "La estructura de capital cambió" }
        val data = chunks.indices.flatMap { index ->
            val rows = result[index + 1].values()
            val length = minOf(200, max - (6 + index * 200) + 1)
            rows + List((length - rows.size).coerceAtLeast(0)) { emptyList<Any?>() }
        }
        rawCatalogs[year] = JSONArray(data); catalogs[year] = configuredLoans(year, data)
        val summary = summary(year)
        progress(0.7f, "Cargando las cuotas de hoy…")
        todayPayments = payments(year, day, day, null)
        progress(0.9f, "Guardando la información actualizada…")
        val stamp = Instant.now(); syncedAt = stamp
        save(day)
        return SheetsPreload(meta.getJSONObject("properties").getString("title"), summary, catalogs.getValue(year), todayPayments, stamp)
            .copy(paymentServiceError = paymentServiceError)
    }
    fun loans(year: Int): List<SheetLoan> {
        if (isSimpleBook) return simple.loans(year)
        loadConditions()
        catalogs[year]?.let { return it }
        val max = rowsIn(year.toString()); require(max in 6..10_005)
        val chunks = (6..max step 200).map { "'$year'!A$it:Q${minOf(it + 199, max)}" }
        val response = batch(chunks)
        val data = chunks.indices.flatMap { index ->
            val rows = response[index].values(); val length = minOf(200, max - (6 + index * 200) + 1)
            rows + List((length - rows.size).coerceAtLeast(0)) { emptyList<Any?>() }
        }
        rawCatalogs[year] = JSONArray(data)
        return configuredLoans(year, data).also { catalogs[year] = it }
    }
    private fun availableYears(): List<Int> {
        val sheets = requireNotNull(metadata).getJSONArray("sheets")
        return (0 until sheets.length()).mapNotNull {
            sheets.getJSONObject(it).getJSONObject("properties").getString("title").toIntOrNull()
                ?.takeIf { year -> year in 2026..2028 }
        }.sorted()
    }
    fun loanViews(): List<SheetLoan> = if (isSimpleBook) simple.allLoans() else availableYears().flatMap(::loans)
    fun searchLoans(): List<SheetLoan> = canonicalLoanViews(loanViews(), today().year)

    internal fun loanContributions(): Map<String, Long> {
        if (isSimpleBook) return simple.contributions()
        if (optionalProperties("_APORTES") == null) return emptyMap()
        return batch(listOf("'_APORTES'!A2:F${rowsIn("_APORTES")}")).single().values()
            .filter { it.getOrNull(1)?.toString()?.isNotBlank() == true }
            .groupBy { it[1].toString() }.mapValues { (_, rows) -> rows.sumOf { sheetCents(it.getOrNull(3)) } }
    }

    fun editLoanText(edit: LoanTextEdit, operationId: String? = null) {
        if (isSimpleBook) return simple.editText(edit, operationId)
        require(connection.canEditLoanText(bookId)) { "Libro no configurado para editar" }
        // Resolve by ID afresh: a stored row number may have changed since opening the editor.
        val year = edit.originYear
        val max = rowsIn(year.toString())
        require(max in 6..10_005)
        val read = batch(listOf("'$year'!A5:G5", "'$year'!A6:A$max"))
        val labels = read.first().values().single()
        require(normalizedClient(labels.getOrNull(1)?.toString().orEmpty()) == "cliente" &&
            normalizedClient(labels.getOrNull(6)?.toString().orEmpty()) == "garantia") { "La estructura de los campos del préstamo cambió" }
        val ids = read.last().values()
        val rows = ids.mapIndexedNotNull { index, values ->
            val raw = values.firstOrNull()?.toString()?.removeSuffix(".0") ?: return@mapIndexedNotNull null
            if (runCatching { SheetsIdentity.canonical(year, raw) }.getOrNull() == edit.loanId) index + 6 else null
        }
        val row = rows.singleOrNull() ?: error("No se encontró una única fila original del préstamo")
        val sheetId = properties(year.toString()).getInt("sheetId")
        fun cells(): Map<Int, LoanTextCell> {
            val mask = "spreadsheetId,sheets(properties(sheetId),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue,dataValidation))))"
            val response = http.get("$base?ranges=${encode("'$year'!A$row:G$row")}&fields=${encode(mask)}")
            require(response.getString("spreadsheetId") == bookId)
            val sheets = response.getJSONArray("sheets")
            val sheet = (0 until sheets.length()).map { sheets.getJSONObject(it) }
                .singleOrNull { it.getJSONObject("properties").getInt("sheetId") == sheetId }
                ?: error("Google devolvió otra hoja al comprobar la edición")
            val grid = sheet.getJSONArray("data").getJSONObject(0)
            require(grid.optInt("startRow", 0) == row - 1 && grid.optInt("startColumn", 0) == 0)
            val values = grid.getJSONArray("rowData").getJSONObject(0).getJSONArray("values")
            val idCell = values.getJSONObject(0)
            require(idCell.optJSONObject("userEnteredValue")?.has("formulaValue") != true) { "La identidad original contiene una fórmula" }
            val idValue = idCell.getJSONObject("effectiveValue")
            val rawId = (idValue.opt("numberValue") ?: idValue.get("stringValue")).toString().removeSuffix(".0")
            require(SheetsIdentity.canonical(year, rawId) == edit.loanId) { "La fila original cambió; actualiza el préstamo" }
            return listOf(1, 6).associateWith { column ->
                val cell = values.optJSONObject(column) ?: JSONObject()
                val entered = cell.optJSONObject("userEnteredValue") ?: JSONObject()
                val effective = cell.optJSONObject("effectiveValue") ?: JSONObject()
                LoanTextCell(effective.opt("stringValue")?.toString() ?: effective.opt("numberValue")?.toString().orEmpty(),
                    entered.has("formulaValue"), cell.has("dataValidation"))
            }
        }
        val plan = loanTextWritePlan(edit, cells())
        if (plan.isNotEmpty()) {
            val requests = plan.map { field -> JSONObject().put("updateCells", JSONObject()
                .put("range", JSONObject().put("sheetId", sheetId).put("startRowIndex", row - 1).put("endRowIndex", row)
                    .put("startColumnIndex", field.columnIndex).put("endColumnIndex", field.columnIndex + 1))
                .put("rows", JSONArray().put(JSONObject().put("values", JSONArray().put(JSONObject()
                    .put("userEnteredValue", JSONObject().put("stringValue", field.after))))))
                .put("fields", "userEnteredValue")) }
            val response = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(response.getString("spreadsheetId") == bookId)
        }
        val saved = cells()
        require(edit.fields.all { saved.getValue(it.columnIndex).value == it.after && !saved.getValue(it.columnIndex).formula }) {
            "No se confirmó el cambio en Google. Reintenta o actualiza para revisar la hoja"
        }
    }

    private fun historyIndex(): PaymentHistoryIndex {
        paymentHistory?.let { return it }
        val sheets = requireNotNull(metadata).getJSONArray("sheets")
        val exists = (0 until sheets.length()).any {
            sheets.getJSONObject(it).getJSONObject("properties").getString("title") == "_PAGOS_HISTORIAL"
        }
        val rows = mutableListOf<List<Any?>>()
        if (exists) {
            var offset = 0
            while (offset + 2 <= rowsIn("_PAGOS_HISTORIAL")) {
                val page = history(offset)
                rows.addAll(page)
                if (page.size < 200) break
                offset += 200
            }
        }
        return PaymentHistoryIndex(rows).also { paymentHistory = it }
    }

    /** Read just this loan's row, including future checks, in bounded 31-day slices across years. */
    fun calendar(loan: SheetLoan, refresh: Boolean = false): LoanCalendar {
        if (isSimpleBook) return simple.calendar(loan, refresh)
        if (refresh) { calendars.remove(loan.id); paymentHistory = null }
        calendars[loan.id]?.takeIf { calendarMatchesLoan(it, loan) }?.let { return it }
        calendars.remove(loan.id)
        val days = loanDates(loan)
        val loaded = mutableMapOf<LocalDate, SheetCalendarDay>()
        val storedFees = mutableMapOf<LocalDate, Long>()
        val years = availableYears().toSet()
        days.groupBy { it.year }.forEach { (year, contractualDates) ->
            if (year !in years) return@forEach
            val position = loans(year).singleOrNull { it.id == loan.id } ?: return@forEach
            val view = loan.copy(year = year, row = position.row, rawId = position.rawId)
            val h = headers(year)
            val feeRow = h.feeRows[view.rawId] ?: error("No existe el ID en mora cobrada")
            val slices = contractualDates.filter { it in h.calendar }.chunked(31)
            if (slices.isEmpty()) return@forEach
            val ranges = slices.flatMap { slice ->
                val first = h.calendar.getValue(slice.first())
                val last = h.calendar.getValue(slice.last()) + 4
                val feeFirst = h.fees[slice.first()] ?: error("Calendario de mora incompleto")
                val feeLast = h.fees[slice.last()] ?: error("Calendario de mora incompleto")
                listOf("'$year'!${columnName(first)}${view.row}:${columnName(last)}${view.row}",
                    "'_MORA_COBRADA_$year'!${columnName(feeFirst)}$feeRow:${columnName(feeLast)}$feeRow")
            }
            val response = batch(ranges)
            slices.forEachIndexed { index, slice ->
                val main = response[index * 2].values().firstOrNull().orEmpty()
                val fees = response[index * 2 + 1].values().firstOrNull().orEmpty()
                slice.forEach { date ->
                    val start = h.calendar.getValue(date) - h.calendar.getValue(slice.first())
                    val fee = fees.getOrNull(h.fees.getValue(date) - h.fees.getValue(slice.first()))
                    loaded[date] = parseCalendarDay(view, date, (0..4).map { main.getOrNull(start + it) }, fee, today())
                    storedFees[date] = if (fee == null || fee == "") 0 else sheetCents(fee)
                }
            }
        }
        val direct = connection.usesDirectPayments(bookId)
        val history = if (direct || loaded.values.any { it.status == CalendarStatus.PAID }) historyIndex() else null
        val calendar = completeLoanCalendar(loan, loaded.mapValues { (_, day) ->
            day.copy(payment = day.payment?.let { row ->
                val payment = row.copy(paidOn = history?.paidOn(row, today()))
                if (direct) payment.copy(version = directPaymentVersion(bookId, payment, storedFees.getValue(day.date),
                    history?.revision(row).orEmpty()).toJson().toString()) else payment
            })
        }, today())
        offlineCalendars[loan.id] = calendar.days.mapNotNull { entry ->
            entry.payment ?: if (entry.status == CalendarStatus.FUTURE && entry.valid == true && direct) {
                val view = loans(entry.date.year).singleOrNull { it.id == loan.id } ?: return@mapNotNull null
                val row = SheetPayment(view, entry.date, view.baseOn(entry.date), entry.checked == true,
                    if (entry.checked == true) 0 else storedFees[entry.date] ?: 0, bookAmount = entry.bookAmount)
                row.copy(version = directPaymentVersion(bookId, row, storedFees[entry.date] ?: 0, history?.revision(row).orEmpty()).toJson().toString())
            } else null
        }
        return calendar.also { calendars[loan.id] = it }
    }
    internal fun offlineCalendarRows(loan: SheetLoan, calendar: LoanCalendar): List<SheetPayment> =
        if (isSimpleBook) simple.offlineCalendarRows(loan) else offlineCalendars[loan.id] ?: calendar.days.mapNotNull { it.payment }
    /** Refresh only edited calendar cells, retaining the scroll position and all other days. */
    fun refreshCalendar(calendar: LoanCalendar, affected: List<SheetPayment>, refreshed: List<SheetPayment>): LoanCalendar {
        if (isSimpleBook) return simple.calendar(simple.allLoans().single { it.id == calendar.loanId }, false)
        val known = refreshed.associateBy { it.id }
        val targets = affected.filter { it.loan.id == calendar.loanId && it.id !in known }.distinctBy { it.id }
        val updated = refreshed.toMutableList()
        if (!connection.usesDirectPayments(bookId)) {
            targets.forEach { updated.addAll(payments(it.due.year, it.due, it.due, it.loan.id)) }
        } else if (targets.isNotEmpty()) {
            val ranges = targets.flatMap { row ->
                val loan = loans(row.due.year).single { it.id == row.loan.id }
                val h = headers(row.due.year); val col = h.calendar.getValue(row.due)
                val feeCol = h.fees.getValue(row.due); val feeRow = h.feeRows.getValue(loan.rawId)
                listOf("'${loan.year}'!${columnName(col)}${loan.row}:${columnName(col + 4)}${loan.row}",
                    "'_MORA_COBRADA_${loan.year}'!${columnName(feeCol)}$feeRow")
            }
            val values = batch(ranges)
            val history = historyIndex()
            targets.forEachIndexed { index, row ->
                val loan = loans(row.due.year).single { it.id == row.loan.id }
                val fee = values[index * 2 + 1].values().firstOrNull()?.firstOrNull()
                val stored = if (fee == null || fee == "") 0 else sheetCents(fee)
                val payment = parsePayment(loan, row.due, values[index * 2].values().firstOrNull().orEmpty(), fee, today())
                    ?: error("La cuota cambió; vuelve a cargar el calendario")
                val dated = payment.copy(paidOn = history.paidOn(payment, today()))
                updated.add(dated.copy(version = directPaymentVersion(bookId, dated, stored, history.revision(payment)).toJson().toString()))
            }
        }
        return calendarWithPayments(calendar, updated).also { calendars[calendar.loanId] = it }
    }

    private fun headers(year: Int): Headers = headers.getOrPut(year) {
        require(year in 2026..2028) { "Año sin reglas de calendario auditadas" }
        val begin = if (year == 2026) 18 else 22
        val days = if (year == 2026) 169 else if (year == 2028) 366 else 365
        val helper = "_MORA_COBRADA_$year"
        val ranges = listOf("'$year'!${columnName(begin)}5:${columnName(begin + days * 5 - 1)}5",
            "'$helper'!B5:${columnName(columnsIn(helper))}5", "'$helper'!A6:A${rowsIn(helper)}")
        val result = batch(ranges)
        val calendar = result[0].values().firstOrNull().orEmpty()
        val fees = result[1].values().firstOrNull().orEmpty()
        val ids = result[2].values()
        val calendarMap = (0 until days).associate { index -> sheetDate(calendar.getOrNull(index * 5)) to begin + index * 5 }
        val feesMap = fees.mapIndexedNotNull { index, cell -> if (cell == null || cell == "") null else sheetDate(cell) to index + 2 }.toMap()
        val feeRows = ids.mapIndexedNotNull { index, row -> row.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }?.let {
            (if (it.endsWith(".0")) it.dropLast(2) else it) to index + 6
        } }
        require(feeRows.map { it.first }.distinct().size == feeRows.size) { "IDs de mora repetidos" }
        Headers(calendarMap, feesMap, feeRows.toMap())
    }
    fun payments(year: Int, from: LocalDate, to: LocalDate, loanId: String?, loanIds: Set<String>? = null): List<SheetPayment> {
        if (isSimpleBook) return simple.payments(from, to, loanId, loanIds)
        require(from <= to && to <= today() && java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 30) { "Consulta de hasta 31 días vencidos o de hoy" }
        val candidates = loans(year).filter { (loanId == null || it.id == loanId) && (loanIds == null || it.id in loanIds) && it.firstDue <= to && it.lastDue >= from }
        if (candidates.isEmpty()) return emptyList()
        if (connection.canWrite(bookId) && !connection.usesDirectPayments(bookId)) {
            val api = AppsScriptClient(connection.deploymentId, bookId, tokens)
            val remote = runCatching { candidates.flatMap { loan ->
                val result = api.execute(JSONObject().put("bookId", bookId).put("action", "payments").put("year", year).put("rawId", loan.rawId).put("from", from.toString()).put("to", to.toString()))
                val payments = result.getJSONArray("payments")
                List(payments.length()) { index -> val value = payments.getJSONObject(index)
                    require(value.getString("loanId") == loan.id)
                    SheetPayment(loan, sheetDate(value.get("due")), sheetCents(value.get("base")), value.getBoolean("unpaid"),
                        sheetCents(value.get("late")), value.opt("paidOn").takeUnless { it == null || it == JSONObject.NULL || it == "" }?.let(::sheetDate), value.getJSONObject("version").toString())
                }
            } }
            if (remote.isSuccess) {
                paymentServiceError = null
                return remote.getOrThrow()
            }
            paymentServiceError = remote.exceptionOrNull()?.message ?: "No se pudo conectar el servicio de pagos y moras"
        }
        val h = headers(year)
        val dates = h.calendar.keys.filter { it in from..to }.sorted()
        if (dates.isEmpty()) return emptyList()
        val result = mutableListOf<SheetPayment>()
        val storedFees = mutableMapOf<String, Long>()
        candidates.chunked(25).forEach { group ->
            val ranges = group.flatMap { loan ->
                val row = h.feeRows[loan.rawId] ?: error("No existe el ID en mora cobrada")
                val start = h.calendar.getValue(dates.first()); val end = h.calendar.getValue(dates.last()) + 4
                val feeStart = h.fees[dates.first()] ?: error("Calendario de mora incompleto")
                val feeEnd = h.fees[dates.last()] ?: error("Calendario de mora incompleto")
                listOf("'$year'!${columnName(start)}${loan.row}:${columnName(end)}${loan.row}",
                    "'_MORA_COBRADA_$year'!${columnName(feeStart)}$row:${columnName(feeEnd)}$row")
            }
            val values = batch(ranges)
            group.forEachIndexed { index, loan ->
                val main = values[index * 2].values().firstOrNull().orEmpty()
                val fees = values[index * 2 + 1].values().firstOrNull().orEmpty()
                dates.forEach { date ->
                    val offset = h.calendar.getValue(date) - h.calendar.getValue(dates.first())
                    val cells = (0..4).map { main.getOrNull(offset + it) }
                    val fee = fees.getOrNull(h.fees.getValue(date) - h.fees.getValue(dates.first()))
                    parsePayment(loan, date, cells, fee, today())?.let {
                        result.add(it)
                        storedFees[it.id] = if (fee == null || fee == "") 0 else sheetCents(fee)
                    }
                }
            }
        }
        val direct = connection.usesDirectPayments(bookId)
        val history = if (direct || result.any { !it.unpaid }) historyIndex() else null
        if (direct) paymentServiceError = null
        return result.map { row ->
            val payment = row.copy(paidOn = history?.paidOn(row, today()))
            if (direct) payment.copy(version = directPaymentVersion(bookId, payment, storedFees.getValue(row.id),
                history?.revision(row).orEmpty()).toJson().toString()) else payment
        }
            .sortedWith(compareBy({ it.due }, { it.loan.client }))
    }
    fun cash(): List<BookCashDay> = if (isSimpleBook) simple.cash() else cashCache ?: run {
        val ledger = parseBookCash(batch(listOf("'CAJA'!A6:E${rowsIn("CAJA")}"))[0].values())
        val history = historyIndex()
        val payments = searchLoans().filter { it.collected > 0 && history.hasDateCorrection(it.id, today()) }.flatMap { loan ->
            calendar(loan).days.mapNotNull { it.payment }
        }
        cashByPaymentDates(ledger, payments, today())
    }.also { cashCache = it }
    internal fun cashEventTimes(): Map<String, Instant> {
        if (isSimpleBook) return simple.cashEventTimes()
        val times = mutableMapOf<String, Instant>()
        if (optionalProperties("_APORTES") != null) {
            batch(listOf("'_APORTES'!A2:F${rowsIn("_APORTES")}")).single().values().forEach { row ->
                val id = row.getOrNull(1)?.toString().orEmpty()
                val stamp = cashTimestamp(row.firstOrNull())
                if (id.isNotBlank() && stamp != null) { times["loan:$id"] = stamp; times["fund:$id"] = stamp }
            }
        }
        if (optionalProperties("_PAGOS_HISTORIAL") != null) {
            var offset = 0
            while (true) {
                val rows = history(offset)
                rows.forEach { row ->
                    if (row.getOrNull(6) == false) {
                        val id = historyLoanId(row)
                        val stamp = cashTimestamp(row.firstOrNull())
                        val due = runCatching { sheetDate(row.getOrNull(4)) }.getOrNull()
                        if (id != null && stamp != null && due != null) times["pay:$id:$due"] = stamp
                    }
                }
                if (rows.size < 200) break
                offset += rows.size
            }
        }
        val props = optionalProperties("_APP_TRANSACCIONES") ?: return times
        val count = props.getJSONObject("gridProperties").getInt("rowCount")
        for (start in 2..count step 400) {
            val rows = batch(listOf("'_APP_TRANSACCIONES'!A$start:D${minOf(start + 399, count)}")).single().values()
            rows.forEach { row ->
                val receipt = row.getOrNull(3)?.toString()?.takeIf { it.startsWith("{") }?.let(::JSONObject)
                if (receipt != null) times.putAll(receiptCashTimes(receipt, row.getOrNull(2)))
            }
            if (rows.size < minOf(400, count - start + 1)) break
        }
        return times
    }
    internal fun cashMovements(): List<CashMovement> {
        if (isSimpleBook) return simple.movements()
        freshMetadata()
        val props = optionalProperties("_APP_TRANSACCIONES") ?: return emptyList()
        val count = props.getJSONObject("gridProperties").getInt("rowCount")
        val result = mutableListOf<CashMovement>()
        for (start in 2..count step 400) {
            val rows = batch(listOf("'_APP_TRANSACCIONES'!A$start:D${minOf(start + 399, count)}")).single().values()
            rows.forEach { row -> row.getOrNull(3)?.toString()?.takeIf { it.startsWith("{") }?.let(::JSONObject)
                ?.optJSONObject("cashMovement")?.let { result += cashMovement(it) } }
            if (rows.size < minOf(400, count - start + 1)) break
        }
        require(result.map { it.id }.distinct().size == result.size) { "Hay movimientos de caja duplicados" }
        if (result.isNotEmpty()) {
            val entries = batch(listOf("'_APORTES'!A2:F${rowsIn("_APORTES")}")).single().values()
            result.forEach { movement ->
                val entry = entries.singleOrNull { it.getOrNull(4) == "${movement.type.label} de caja · ${movement.id}" }
                    ?: error("Falta el movimiento de caja en el registro de capital")
                require((entry.getOrNull(1) == null || entry.getOrNull(1) == "") && sheetDate(entry.getOrNull(2)) == movement.date &&
                    sheetCents(entry.getOrNull(3)) == movement.signed && entry.getOrNull(5)?.toString().orEmpty() == movement.description) {
                    "El movimiento de caja cambió; revisa su comprobante y el registro de capital"
                }
            }
        }
        return result
    }
    fun history(offset: Int): List<List<Any?>> {
        if (isSimpleBook) return simple.history(offset)
        require(offset >= 0)
        val start = offset + 2; val end = minOf(offset + 201, rowsIn("_PAGOS_HISTORIAL"))
        if (start > end) return emptyList()
        return batch(listOf("'_PAGOS_HISTORIAL'!A$start:L$end"))[0].values().takeWhile { it.any { cell -> cell != null && cell != "" } }
    }
    fun write(pending: PendingRemoteWrite, firstAttempt: Boolean = false): JSONObject {
        if (isSimpleBook) return simple.write(pending)
        pending.requireOwner(subject, bookId)
        require(connection.canWrite(bookId)) { "El servicio de pagos no está configurado para este libro" }
        val body = JSONObject(pending.payload)
        require(body.getString("bookId") == bookId && body.getString("operationId") == pending.operationId)
        if (body.optString("transport") == "sheets") {
            require(connection.usesDirectPayments(bookId))
            return when (body.getString("action")) {
                "pay" -> writeDirectPayments(pending, body, firstAttempt)
                "createLoan" -> writeDirectLoan(pending, body, firstAttempt)
                "deleteLoan" -> writeDirectLoanDeletion(pending, body)
                "setCapital" -> writeDirectCapital(pending, body)
                "cashMovement" -> writeDirectCashMovement(pending, body)
                "editLoanTerms", "amountPayment" -> writeDirectLoanTerms(pending, body)
                else -> error("Operación directa no admitida")
            }
        }
        require(body.getString("action") != "createLoan" || connection.canCreateLoan(bookId)) { "El alta móvil en la hoja principal no está habilitada" }
        return AppsScriptClient(connection.deploymentId, bookId, tokens).execute(body)
    }

    /** Presentation is a separate retryable operation; it never changes loan or payment values. */
    fun syncBookPresentation() {
        if (isSimpleBook) return simple.syncPresentation()
        require(connection.usesDirectPayments(bookId) && connection.canWrite(bookId))
        val fields = "spreadsheetId,properties(title,timeZone),sheets(properties(sheetId,title,gridProperties),basicFilter)"
        val meta = http.get("$base?fields=${encode(fields)}")
        require(meta.getString("spreadsheetId") == bookId && meta.getJSONObject("properties").getString("timeZone") == "America/Lima")
        metadata = meta
        val entries = meta.getJSONArray("sheets")
        val sheets = availableYears().map { year ->
            val entry = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getJSONObject("properties").getString("title") == year.toString() }
            val props = entry.getJSONObject("properties")
            PresentationSheet(props.getInt("sheetId"), year, rowsIn(year.toString()), headers(year).calendar, entry.optJSONObject("basicFilter"))
        }
        val count = sheets.sumOf { it.calendar.size }
        val summary = batch(listOf("'_RESUMEN_DIARIO'!A1:F${count + 1}")).single().values()
        require(summary.firstOrNull()?.map(::sheetHeading)?.take(2) == listOf("ano", "fecha")) { "Los encabezados del resumen diario cambiaron" }
        val requests = bookPresentationRequests(sheets, summary.drop(1), today())
        val result = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
        require(result.getString("spreadsheetId") == bookId)
    }

    private fun freshMetadata(includeNames: Boolean = false) {
        val fields = "spreadsheetId,properties(title,timeZone),sheets(properties(sheetId,title,gridProperties))" +
            if (includeNames) ",namedRanges(namedRangeId,name)" else ""
        val meta = http.get("$base?fields=${encode(fields)}")
        require(meta.getString("spreadsheetId") == bookId)
        require(meta.getJSONObject("properties").getString("timeZone") == "America/Lima")
        metadata = meta
    }
    private fun optionalProperties(title: String): JSONObject? = requireNotNull(metadata).getJSONArray("sheets").let { sheets ->
        (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }
            .singleOrNull { it.getString("title") == title }
    }
    private fun directReceipt(pending: PendingRemoteWrite): JSONObject? {
        val journal = optionalProperties("_APP_TRANSACCIONES") ?: return null
        val count = journal.getJSONObject("gridProperties").getInt("rowCount")
        val hash = directPaymentHash(pending.payload)
        val matches = mutableListOf<List<Any?>>()
        for (start in 2..count step 400) {
            val rows = batch(listOf("'_APP_TRANSACCIONES'!A$start:D${minOf(start + 399, count)}")).single().values()
            matches.addAll(rows.filter { it.firstOrNull() == pending.operationId })
            if (rows.size < minOf(400, count - start + 1)) break
        }
        return directPaymentReceipt(matches, pending.operationId, hash)?.let { JSONObject(it).also { result ->
            require(result.getString("bookId") == bookId && result.getString("operationId") == pending.operationId)
        } }
    }
    private data class PaymentCell(val sheet: String, val sheetId: Int, val row: Int, val column: Int) {
        val range: String get() = "'$sheet'!${columnName(column)}$row"
    }
    private data class PaymentCellValue(val value: Any?, val formula: Boolean)
    private fun paymentCells(addresses: List<PaymentCell>): Map<PaymentCell, PaymentCellValue> {
        val output = mutableMapOf<PaymentCell, PaymentCellValue>()
        addresses.distinct().chunked(60).forEach { group ->
            val query = group.joinToString("&") { "ranges=${encode(it.range)}" }
            val fields = "spreadsheetId,sheets(properties(sheetId),data(startRow,startColumn,rowData(values(effectiveValue,userEnteredValue))))"
            val response = http.get("$base?$query&fields=${encode(fields)}")
            require(response.getString("spreadsheetId") == bookId)
            val sheets = response.getJSONArray("sheets")
            for (s in 0 until sheets.length()) {
                val sheet = sheets.getJSONObject(s)
                val id = sheet.getJSONObject("properties").getInt("sheetId")
                val grids = sheet.optJSONArray("data") ?: continue
                for (g in 0 until grids.length()) {
                    val grid = grids.getJSONObject(g)
                    val address = group.singleOrNull { it.sheetId == id && it.row - 1 == grid.optInt("startRow", 0) &&
                        it.column - 1 == grid.optInt("startColumn", 0) } ?: error("Google devolvió una celda inesperada")
                    val cell = grid.optJSONArray("rowData")?.optJSONObject(0)?.optJSONArray("values")?.optJSONObject(0) ?: JSONObject()
                    val effective = cell.optJSONObject("effectiveValue")
                    require(effective?.has("errorValue") != true) { "La celda de pago tiene un error en la hoja" }
                    val value = effective?.let { it.opt("boolValue") ?: it.opt("numberValue") ?: it.opt("stringValue") }
                    output[address] = PaymentCellValue(value, cell.optJSONObject("userEnteredValue")?.has("formulaValue") == true)
                }
            }
            require(group.all { it in output }) { "No se pudieron comprobar todas las celdas del pago" }
        }
        return output
    }
    private fun enteredCell(value: Any): JSONObject = JSONObject().put("userEnteredValue", JSONObject().put(when (value) {
        is Boolean -> "boolValue"
        is Number -> "numberValue"
        else -> "stringValue"
    }, value))
    private fun updatePaymentCell(address: PaymentCell, value: Any): JSONObject = JSONObject().put("updateCells", JSONObject()
        .put("range", JSONObject().put("sheetId", address.sheetId).put("startRowIndex", address.row - 1).put("endRowIndex", address.row)
            .put("startColumnIndex", address.column - 1).put("endColumnIndex", address.column))
        .put("rows", JSONArray().put(JSONObject().put("values", JSONArray().put(enteredCell(value)))))
        .put("fields", "userEnteredValue"))
    private fun appendPaymentRows(sheetId: Int, rows: List<List<Any>>): JSONObject = JSONObject().put("appendCells", JSONObject()
        .put("sheetId", sheetId).put("rows", JSONArray(rows.map { row -> JSONObject().put("values", JSONArray(row.map(::enteredCell))) }))
        .put("fields", "userEnteredValue"))

    private fun directLoanCells(ranges: List<String>): Map<PaymentCell, DirectLoanCell> {
        val query = ranges.joinToString("&") { "ranges=${encode(it)}" }
        val fields = "spreadsheetId,sheets(properties(sheetId,title),data(startRow,startColumn,rowData(values(effectiveValue,userEnteredValue,dataValidation))))"
        val response = http.get("$base?$query&fields=${encode(fields)}")
        require(response.getString("spreadsheetId") == bookId)
        val result = mutableMapOf<PaymentCell, DirectLoanCell>()
        val sheets = response.getJSONArray("sheets")
        for (index in 0 until sheets.length()) {
            val sheet = sheets.getJSONObject(index); val props = sheet.getJSONObject("properties")
            val grids = sheet.optJSONArray("data") ?: continue
            for (g in 0 until grids.length()) {
                val grid = grids.getJSONObject(g); val rows = grid.optJSONArray("rowData") ?: continue
                for (r in 0 until rows.length()) {
                    val values = rows.getJSONObject(r).optJSONArray("values") ?: continue
                    for (c in 0 until values.length()) {
                        val cell = values.getJSONObject(c); val effective = cell.optJSONObject("effectiveValue")
                        require(effective?.has("errorValue") != true) { "Hay un error en la plantilla del préstamo" }
                        val validation = cell.optJSONObject("dataValidation")?.optJSONObject("condition")
                        val choices = validation?.optJSONArray("values")
                        result[PaymentCell(props.getString("title"), props.getInt("sheetId"), grid.optInt("startRow") + r + 1,
                            grid.optInt("startColumn") + c + 1)] = DirectLoanCell(
                            effective?.let { it.opt("boolValue") ?: it.opt("numberValue") ?: it.opt("stringValue") },
                            cell.optJSONObject("userEnteredValue")?.optString("formulaValue")?.takeIf { it.isNotBlank() },
                            validation?.optString("type"), choices?.let { array -> List(array.length()) {
                                array.getJSONObject(it).optString("userEnteredValue")
                            } }.orEmpty())
                    }
                }
            }
        }
        return result
    }

    private fun writeDirectLoan(pending: PendingRemoteWrite, body: JSONObject, firstAttempt: Boolean): JSONObject {
        freshMetadata(includeNames = true)
        // A receipt takes precedence over date validation or a now-occupied row.
        if (!firstAttempt) directReceipt(pending)?.let { return it }
        val names = requireNotNull(metadata).optJSONArray("namedRanges")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("namedRangeId") }.toSet()
        }.orEmpty()
        val operationKey = loanOperationKey(pending.operationId)
        check(operationKey !in names) { "El registro ya se envió, pero falta su comprobante. Revisa el diario antes de continuar" }
        val requests = mutableListOf<JSONObject>()
        val journalLabels = listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON")
        val plan: DirectLoanPlan
        val journalId: Int
        val registeredOn = today()
        try {
            val request = directLoanRequest(body.getJSONObject("loan"))
            val day = LocalDate.parse(body.getString("disbursedOn"))
            require(day.year in 2026..2028 && rowsIn(day.year.toString()) >= 405)
            require(rowsIn("_APORTES") == 1201 && columnsIn("_APORTES") == 6) { "La estructura del registro de aportes cambió" }
            val journal = optionalProperties("_APP_TRANSACCIONES")
            val ranges = listOf("'_CAPITAL'!A1:M8", "'${day.year}'!A5:Q405", "'_APORTES'!A1:F1201") +
                if (journal != null) listOf("'_APP_TRANSACCIONES'!A1:D1") else emptyList()
            val read = batch(ranges)
            val capital = read[0].values()
            require(capital.getOrNull(3)?.getOrNull(9) == "CAJA") { "La estructura de capital cambió" }
            val summary = parseSheetSummary(registeredOn.year, capital.single { it.firstOrNull()?.toString()?.removeSuffix(".0") == registeredOn.year.toString() },
                capital.firstOrNull()?.getOrNull(1))
            val annual = read[1].values(); validateLoanHeadings(annual.firstOrNull().orEmpty())
            val contributions = read[2].values()
            require(contributions.firstOrNull()?.map(::sheetHeading) == listOf("registrado", "prestamo id", "fecha aporte", "monto aporte", "origen", "cliente")) {
                "Los encabezados de aportes cambiaron"
            }
            val recycled = reusableLoanSlots(day.year, annual.drop(1), names)
            val reservedRows = names.mapNotNull { Regex("cp_newslot_${day.year}_(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet() - recycled.keys
            val reservedItems = names.mapNotNull { Regex("cp_newloan_${day.year}_(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
            if (day != registeredOn) {
                // Funding must use a new cash/calendar read, not a chart cached before this operation.
                catalogs.clear(); rawCatalogs.clear(); headers.clear(); calendars.clear(); cashCache = null; paymentHistory = null
            }
            val fundingCash = if (day == registeredOn) summary.cash else cashForNewLoan(cash(), day, summary.cash)
            plan = planDirectLoan(bookId, request, day, registeredOn, fundingCash, annual.drop(1), contributions.drop(1), reservedRows, reservedItems)
                .let { it.copy(cashAfter = Rules.cashAfter(summary.cash, it.loan.principal, it.loan.contribution)) }
            require(!body.has("maxContributionCents") || plan.loan.contribution <= body.getLong("maxContributionCents")) { "La caja cambió y se necesita un aporte mayor al confirmado" }
            headers.clear()
            val years = (plan.year..plan.lastDue.year).toList()
            val calendars = years.associateWith(::headers)
            val dates = plan.loan.installments.groupBy { it.due.year }.mapValues { it.value.map { installment -> installment.due } }
            val auxiliaryRows = years.associateWith { year ->
                val h = calendars.getValue(year)
                h.feeRows[plan.rawId(year)] ?: (6..rowsIn("_MORA_COBRADA_$year")).firstOrNull {
                    (it !in h.feeRows.values && loanAuxSlotKey(year, it) !in names) ||
                        retiredAuxIdentity(year, h.feeRows.entries.firstOrNull { entry -> entry.value == it }?.key, names) != null
                } ?: error("No quedan filas libres en mora cobrada")
            }
            val nativeRanges = years.flatMap { year ->
                val last = calendars.getValue(year).calendar.values.max() + 4
                listOf("'$year'!A${plan.rowIn(year)}:${columnName(last)}${plan.rowIn(year)}",
                    "'_MORA_COBRADA_$year'!A${auxiliaryRows.getValue(year)}:${columnName(columnsIn("_MORA_COBRADA_$year"))}${auxiliaryRows.getValue(year)}")
            }
            val cells = directLoanCells(nativeRanges)
            fun cell(sheet: String, row: Int, col: Int) = cells[PaymentCell(sheet, properties(sheet).getInt("sheetId"), row, col)] ?: DirectLoanCell()
            years.forEach { year ->
                val row = plan.rowIn(year); require(row <= rowsIn(year.toString())) { "No existe la posición de traslado" }
                val template = (1..17).associateWith { cell(year.toString(), row, it) }
                val reusing = plan.row in recycled
                validateLoanTemplate(plan, if (reusing && year == plan.year) recycledLoanTemplate(row, template) else template, year)
                if (reusing && year == plan.year) nativeRepartitionFormulas(row).forEach { (col, formula) ->
                    requests.add(updateFormula(PaymentCell(year.toString(), properties(year.toString()).getInt("sheetId"), row, col), formula))
                }
                val h = calendars.getValue(year)
                dates[year].orEmpty().forEach { date ->
                    val col = h.calendar[date] ?: error("El plazo incluye fechas sin calendario")
                    require(date in h.fees) { "El calendario de mora no contiene todas las fechas" }
                    (0..4).forEach { offset -> validateLoanCalendarCell(cell(year.toString(), row, col + offset), check = offset == 2) }
                    // Apply explicitly confirmed past receipts; preserve future defaults.
                    requests.add(updatePaymentCell(PaymentCell(year.toString(), properties(year.toString()).getInt("sheetId"), row, col + 2),
                        initialLoanUnpaid(date, registeredOn) && !body.optBoolean("pastPaymentsPaid", false)))
                    if (reusing) {
                        val valid = "${columnName(col)}$row"; val check = "${columnName(col + 2)}$row"; val fee = "${columnName(col + 4)}$row"
                        val helper = "_MORA_COBRADA_$year"; val feeRow = auxiliaryRows.getValue(year); val feeCol = h.fees.getValue(date)
                        requests.add(updateFormula(PaymentCell(year.toString(), properties(year.toString()).getInt("sheetId"), row, col + 3),
                            "=IF($valid,ROUND(IF(${columnName(col)}\u00245=\u0024P$row,\u0024N$row,\u0024M$row)+$fee,2),0)"))
                        requests.add(updateFormula(PaymentCell(year.toString(), properties(year.toString()).getInt("sheetId"), row, col + 4),
                            editedLateFormula(valid, check, "${columnName(col)}\u00245", "'$helper'!\u0024${columnName(feeCol)}\u0024$feeRow", 100)))
                    }
                }
                if (year > plan.year) carryBoundaryFormula(plan, year, cell(year.toString(), row, 2).formula!!)?.let { formula ->
                    val target = PaymentCell(year.toString(), properties(year.toString()).getInt("sheetId"), row, 2)
                    requests.add(updatePaymentCell(target, "").also { it.getJSONObject("updateCells").getJSONArray("rows").getJSONObject(0)
                        .getJSONArray("values").getJSONObject(0).put("userEnteredValue", JSONObject().put("formulaValue", formula)) })
                }
                val helper = "_MORA_COBRADA_$year"; val auxiliary = auxiliaryRows.getValue(year)
                val identity = cell(helper, auxiliary, 1)
                require(identity.formula == null && (!hasLoanValue(identity.value) || identity.value.toString().removeSuffix(".0") == plan.rawId(year) ||
                    retiredAuxIdentity(year, identity.value.toString().removeSuffix(".0"), names) != null)) {
                    "La fila de mora ya pertenece a otro préstamo"
                }
                (2..columnsIn(helper)).forEach { col -> val fee = cell(helper, auxiliary, col)
                    require(fee.formula == null && (!hasLoanValue(fee.value) || sheetCents(fee.value) == 0L)) { "La fila de mora contiene cobros previos" }
                }
                requests.add(updatePaymentCell(PaymentCell(helper, properties(helper).getInt("sheetId"), auxiliary, 1),
                    if (year == plan.year) plan.item else plan.loan.id))
            }
            val inputs = listOf(1 to plan.item, 2 to plan.loan.client, 4 to plan.loan.principal / 100.0,
                5 to plan.loan.ratePercent.movePointLeft(2), 6 to plan.loan.months, 7 to plan.loan.guarantee, 16 to sheetSerial(plan.firstDue))
            inputs.forEach { (col, value) ->
                val update = updatePaymentCell(PaymentCell(plan.year.toString(), properties(plan.year.toString()).getInt("sheetId"), plan.row, col), value)
                if (col == 1 || col == 16) {
                    val operation = update.getJSONObject("updateCells")
                    operation.getJSONArray("rows").getJSONObject(0).getJSONArray("values").getJSONObject(0)
                        .put("userEnteredFormat", JSONObject().put("numberFormat", JSONObject()
                            .put("type", if (col == 16) "DATE" else "NUMBER").put("pattern", if (col == 16) "dd-mm-yyyy" else "000")))
                    operation.put("fields", "userEnteredValue,userEnteredFormat.numberFormat")
                }
                requests.add(update)
            }
            if (journal != null) {
                require(read[3].values().firstOrNull() == journalLabels) { "Los encabezados del diario cambiaron" }
                journalId = journal.getInt("sheetId")
            } else {
                val used = requireNotNull(metadata).getJSONArray("sheets").let { sheets -> (0 until sheets.length()).map {
                    sheets.getJSONObject(it).getJSONObject("properties").getInt("sheetId")
                }.toSet() }
                journalId = generateSequence(1) { it + 1 }.first { it !in used }
                requests.add(0, JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", journalId)
                    .put("title", "_APP_TRANSACCIONES").put("hidden", true).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", 4)))))
                requests.add(1, appendPaymentRows(journalId, listOf(journalLabels)))
            }
            fun marker(key: String) = JSONObject().put("addNamedRange", JSONObject().put("namedRange", JSONObject()
                .put("namedRangeId", key).put("name", key.uppercase()).put("range", JSONObject().put("sheetId", journalId)
                    .put("startRowIndex", 0).put("endRowIndex", 1).put("startColumnIndex", 0).put("endColumnIndex", 1))))
            requests.add(marker(operationKey))
            requests.add(marker(cashLedgerRevisionKey(contributions.drop(1))))
            if (loanSlotKey(plan.year, plan.row) !in names) requests.add(marker(loanSlotKey(plan.year, plan.row)))
            requests.add(marker(loanSlotRevisionKey(plan.year, plan.row, recycled[plan.row])))
            requests.add(marker(loanItemKey(plan.year, plan.item)))
            years.forEach { year ->
                val auxRow = auxiliaryRows.getValue(year)
                val prior = calendars.getValue(year).feeRows.entries.firstOrNull { it.value == auxRow }?.key
                if (loanAuxSlotKey(year, auxRow) !in names) requests.add(marker(loanAuxSlotKey(year, auxRow)))
                requests.add(marker(loanAuxRevisionKey(year, auxRow, retiredAuxIdentity(year, prior, names))))
            }
            val contribution = appendPaymentRows(properties("_APORTES").getInt("sheetId"), listOf(listOf(
                cashRecordedAt(body), plan.loan.id,
                sheetSerial(plan.loan.disbursed), plan.loan.contribution / 100.0,
                if (plan.loan.funding == Funding.EXTERNAL) "Capital nuevo" else "Del capital", plan.loan.client)))
            val contributionCells = contribution.getJSONObject("appendCells").getJSONArray("rows").getJSONObject(0).getJSONArray("values")
            listOf(2 to ("DATE" to "dd-mm-yyyy"), 3 to ("NUMBER" to "0.00")).forEach { (column, format) ->
                contributionCells.getJSONObject(column).put("userEnteredFormat", JSONObject().put("numberFormat", JSONObject()
                    .put("type", format.first).put("pattern", format.second)))
            }
            contribution.getJSONObject("appendCells").put("fields", "userEnteredValue,userEnteredFormat.numberFormat")
            requests.add(contribution)
            if (body.optBoolean("pastPaymentsPaid", false)) {
                val paid = withInitialPastPayments(plan.loan, registeredOn, true).installments.filter { it.paid(registeredOn) }
                if (paid.isNotEmpty()) {
                    val existingHistory = optionalProperties("_PAGOS_HISTORIAL")
                    val auditId = if (existingHistory != null) {
                        require(batch(listOf("'_PAGOS_HISTORIAL'!A1:L1")).single().values().single() == PAYMENT_HISTORY_LABELS) {
                            "Los encabezados del historial cambiaron"
                        }
                        existingHistory.getInt("sheetId")
                    } else {
                        val sheets = requireNotNull(metadata).getJSONArray("sheets")
                        val used = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }.toSet() + journalId
                        val id = generateSequence(1) { it + 1 }.first { it !in used }
                        requests.add(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", id)
                            .put("title", "_PAGOS_HISTORIAL").put("hidden", true).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", 12)))))
                        requests.add(appendPaymentRows(id, listOf(PAYMENT_HISTORY_LABELS)))
                        id
                    }
                    val registered = cashRecordedAt(body)
                    requests.add(appendPaymentRows(auditId, paid.map { item ->
                        listOf(registered, pending.operationId, item.due.year, plan.rawId(item.due.year), sheetSerial(item.due),
                            true, false, 0, 0, sheetSerial(item.due), plan.loan.client, body.optString("actor", subject))
                    }))
                }
            }
        } catch (failure: Exception) {
            throw DirectLoanRejected(failure.message ?: "No se pudo preparar el registro del préstamo", failure)
        }
        val receipt = JSONObject().put("bookId", bookId).put("operationId", pending.operationId).put("loanId", plan.loan.id)
            .put("contributionCents", plan.loan.contribution)
            .put("cashAfter", (plan.cashAfter + withInitialPastPayments(plan.loan, registeredOn, body.optBoolean("pastPaymentsPaid", false)).receipts(registeredOn)) / 100.0).put("firstDue", plan.firstDue.toString()).put("confirmed", true).put("transport", "sheets")
        requests.add(appendPaymentRows(journalId, listOf(listOf(pending.operationId, directPaymentHash(pending.payload),
            cashRecordedAt(body), receipt.toString()))))
        try {
            val result = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(result.getString("spreadsheetId") == bookId)
        } catch (failure: Exception) {
            val verification = runCatching {
                freshMetadata(includeNames = true)
                val confirmed = directReceipt(pending)
                val markers = requireNotNull(metadata).optJSONArray("namedRanges")
                val alreadySent = markers?.let { list -> (0 until list.length()).any {
                    list.getJSONObject(it).getString("namedRangeId") == operationKey
                } } == true
                confirmed to alreadySent
            }
            verification.getOrNull()?.first?.let { return it }
            // Failed verification is still an unknown outcome: retain the immutable pending operation.
            if (verification.isSuccess && verification.getOrThrow().second == false &&
                failure is GoogleHttpException && failure.status in listOf(400, 403))
                throw DirectLoanRejected(if (failure.status == 403) failure.message ?: "Google rechazó la edición"
                    else "No se registró el préstamo: otra operación ocupó la fila o cambió la estructura. Revisa y vuelve a guardar", failure)
            throw failure
        }
        catalogs.clear(); rawCatalogs.clear(); headers.clear(); calendars.clear(); cashCache = null; paymentHistory = null
        return receipt
    }

    private fun directLogSheet(title: String, labels: List<String>, requests: MutableList<JSONObject>): Int {
        optionalProperties(title)?.let { props ->
            require(batch(listOf("'$title'!A1:${columnName(labels.size)}1")).single().values().single() == labels) { "Los encabezados de $title cambiaron" }
            return props.getInt("sheetId")
        }
        val sheets = requireNotNull(metadata).getJSONArray("sheets")
        val ids = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }
        val id = generateSequence(1) { it + 1 }.first { it !in ids }
        val props = JSONObject().put("sheetId", id).put("title", title).put("hidden", true)
            .put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", labels.size))
        requests.add(JSONObject().put("addSheet", JSONObject().put("properties", props)))
        requests.add(appendPaymentRows(id, listOf(labels)))
        sheets.put(JSONObject().put("properties", props))
        return id
    }

    private fun updateFormula(address: PaymentCell, formula: String): JSONObject = updatePaymentCell(address, "").also {
        it.getJSONObject("updateCells").getJSONArray("rows").getJSONObject(0).getJSONArray("values").getJSONObject(0)
            .put("userEnteredValue", JSONObject().put("formulaValue", formula))
    }

    private fun writeDirectLoanTerms(pending: PendingRemoteWrite, body: JSONObject): JSONObject {
        freshMetadata(includeNames = true)
        directReceipt(pending)?.let { invalidateFinancialCache(); return it }
        val requests = mutableListOf<JSONObject>()
        val receipt: JSONObject
        val operationKey = "cp_edit_" + directPaymentHash(pending.operationId).take(40)
        val names = requireNotNull(metadata).optJSONArray("namedRanges")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("namedRangeId") }.toSet()
        }.orEmpty()
        check(operationKey !in names) { "La edición figura enviada, pero falta el comprobante. Comprueba el mismo envío" }
        try {
            loadConditions(force = true)
            catalogs.clear(); rawCatalogs.clear(); headers.clear(); calendars.clear(); paymentHistory = null
            val id = body.getString("loanId")
            val views = availableYears().flatMap(::loans).filter { it.id == id }
            val selected = views.singleOrNull { it.year == body.getInt("year") } ?: error("El préstamo ya no está disponible")
            require(loanDeletionVersion(selected) == body.getString("version")) { "El préstamo cambió. Actualiza y revisa de nuevo la edición" }
            val origin = views.singleOrNull { it.year == id.substringBefore('-').toInt() } ?: error("No se encontró la fila original")
            require(origin.row in 6..405) { "La fila original está fuera del bloque disponible" }
            val sourceRange = "'${origin.year}'!A${origin.row}:Q${origin.row}"
            val source = directLoanCells(listOf(sourceRange))
            fun address(sheet: String, row: Int, col: Int) = PaymentCell(sheet, properties(sheet).getInt("sheetId"), row, col)
            fun original(col: Int) = address(origin.year.toString(), origin.row, col)
            loanDeletionInputs((1..17).associateWith { source[original(it)] ?: DirectLoanCell() })
            require(loanDeletionVersion(configuredLoans(origin.year, listOf((1..17).map { source[original(it)]?.value }), origin.row).single()) == loanDeletionVersion(origin)) {
                "La fila original cambió durante la consulta"
            }
            val calendar = calendar(origin, refresh = true)
            val capitalRows = batch(listOf("'_CAPITAL'!A1:M8")).single().values()
            val day = today()
            val summary = parseSheetSummary(day.year, capitalRows.single { it.firstOrNull()?.toString()?.removeSuffix(".0") == day.year.toString() }, capitalRows.firstOrNull()?.getOrNull(1))
            val amountPlan=if(body.getString("action")=="amountPayment") planAmountPayment(recordForAmount(origin,calendar,day),
                body.getLong("amountCents"),amountPaymentDate(body,day),body.optBoolean("waiveLate")) else null
            val input = if(amountPlan==null)loanTermsInput(body.getJSONObject("terms")) else
                LoanTermsInput(origin.client,origin.guarantee,origin.principal,origin.ratePercent,origin.months,origin.firstDue.minusDays(1),origin.dailyLate)
            val plan = if(amountPlan==null)planLoanTerms(origin,calendar,input,day,summary.cash) else {
                val after=amountPlan.after;val paid=after.loan.installments.filter { after.isPaid(it,day) }
                LoanTermsPlan(input,origin.agreed,after.loan.installments.associate { it.due to it.base },paid.sumOf { it.base },
                    paid.sumOf { it.collectedLate },paid.map { it.due }.toSet(),summary.cash+amountPlan.amount,
                    after.view(day).debt-(origin.agreed-paid.sumOf { it.base }),amountPlan.confirmation())
            }
            require((amountPlan?.confirmation()?:plan.confirmation()) == body.getString("confirmation")) { "La caja o las cuotas cambiaron desde la revisión. Actualiza y revisa otra vez" }
            val advances=(amountPlan?.after?.advances?:origin.advances).map { advance->
                require(advance.source in plan.bases && advance.target in plan.bases) { "El plazo debe conservar las fechas con abonos de mora" }
                val sourceRow=origin.row+(advance.source.year-origin.year)*400
                val checkColumn=headers(advance.source.year).calendar.getValue(advance.source)+2
                advance.copy(sourceRef="'${advance.source.year}'!${columnName(checkColumn)}$sourceRow")
            }
            val allDates = (loanDates(origin) + plan.bases.keys).distinct().sorted()
            val years = (origin.year..maxOf(origin.lastDue.year, plan.lastDue.year)).toList()
            val helperRows = years.associateWith { year ->
                val h = headers(year)
                val rawId = if (year == origin.year) origin.rawId else id
                h.feeRows[rawId] ?: (6..rowsIn("_MORA_COBRADA_$year")).firstOrNull {
                    it !in h.feeRows.values && loanAuxSlotKey(year, it) !in names
                } ?: error("No quedan filas para la mora de $year")
            }
            val ranges = years.flatMap { year ->
                val row = origin.row + (year - origin.year) * 400
                require(row <= rowsIn(year.toString())) { "No existe la posición de traslado para $year" }
                val helper = "_MORA_COBRADA_$year"; val feeRow = helperRows.getValue(year)
                listOf("'$year'!A$row:${columnName(headers(year).calendar.values.max() + 4)}$row",
                    "'$helper'!A$feeRow:${columnName(columnsIn(helper))}$feeRow")
            }
            val cells = directLoanCells(ranges)
            val backup = mutableListOf<List<Any>>()
            fun preserve(target: PaymentCell) {
                val old = cells[target] ?: source[target] ?: DirectLoanCell()
                backup.add(listOf(target.sheet, target.row, target.column, JSONObject().put("value", old.value ?: JSONObject.NULL)
                    .put("formula", old.formula ?: JSONObject.NULL).toString()))
            }
            val calendarByDate = calendar.days.associateBy { it.date }
            val paymentKeys = mutableSetOf<String>()
            years.forEach { year ->
                val row = origin.row + (year - origin.year) * 400
                val rawId = if (year == origin.year) origin.rawId else id
                if (year > origin.year) {
                    val clientCell = cells[address(year.toString(), row, 2)] ?: DirectLoanCell()
                    val existing = views.singleOrNull { it.year == year }
                    require(!hasLoanValue(clientCell.value) || existing?.row == row) { "La fila de traslado ya contiene otro préstamo" }
                    (1..17).forEach { require(!cells[address(year.toString(), row, it)]?.formula.isNullOrBlank()) { "Faltan fórmulas del traslado anual" } }
                    val previousYear = year - 1; val previousRow = row - 400
                    val expected = "=IF(AND('$previousYear'!\u0024B$previousRow<>\"\",ISNUMBER('$previousYear'!\u0024P$previousRow),'$previousYear'!\u0024P$previousRow<DATE($year,1,1),'$previousYear'!\u0024Q$previousRow>=DATE($year,1,1)),'$previousYear'!\u0024B$previousRow,\"\")"
                    require(clientCell.formula == expected || clientCell.formula == expected.replace("<DATE", "<=DATE")) { "La fórmula de traslado cambió" }
                    if (plan.firstDue == LocalDate.of(year, 1, 1) && clientCell.formula == expected) {
                        val target = address(year.toString(), row, 2); preserve(target)
                        requests.add(updateFormula(target, expected.replace("<DATE", "<=DATE")))
                    }
                }
                val helper = "_MORA_COBRADA_$year"; val feeRow = helperRows.getValue(year)
                val identity = address(helper, feeRow, 1)
                val identityCell = cells[identity] ?: DirectLoanCell()
                require(identityCell.formula == null && (!hasLoanValue(identityCell.value) || identityCell.value.toString().removeSuffix(".0") == rawId)) { "La mora pertenece a otro préstamo" }
                if (!hasLoanValue(identityCell.value)) {
                    require((2..columnsIn(helper)).all { col -> val c = cells[address(helper, feeRow, col)] ?: DirectLoanCell()
                        c.formula == null && (!hasLoanValue(c.value) || sheetCents(c.value) == 0L) }) { "La fila auxiliar contiene importes ajenos" }
                    preserve(identity); requests.add(updatePaymentCell(identity, if (year == origin.year) origin.rawId.toInt() else id))
                }
                allDates.filter { it.year == year }.forEach { due ->
                    val h = headers(year); val col = h.calendar[due] ?: error("La fecha no existe en el calendario")
                    val feeCol = h.fees[due] ?: error("La fecha no existe en el calendario de mora")
                    val valid = address(year.toString(), row, col)
                    val check = address(year.toString(), row, col + 2)
                    val amount = address(year.toString(), row, col + 3)
                    val late = address(year.toString(), row, col + 4)
                    val fee = address(helper, feeRow, feeCol)
                    val checkCell = cells[check] ?: DirectLoanCell(); val feeCell = cells[fee] ?: DirectLoanCell()
                    validateLoanCalendarCell(checkCell, true)
                    require(feeCell.formula == null && feeCell.validation == null) { "La mora cobrada contiene una fórmula o validación" }
                    require(!cells[valid]?.formula.isNullOrBlank() && !cells[amount]?.formula.isNullOrBlank() && !cells[late]?.formula.isNullOrBlank()) { "Faltan fórmulas del calendario" }
                    val before = calendarByDate[due]?.payment
                    if (before != null) {
                        require((checkCell.value == true) == before.unpaid) { "Un pago cambió durante la revisión" }
                        val stored = if (hasLoanValue(feeCell.value)) sheetCents(feeCell.value) else 0L
                        if (!before.unpaid) require(stored == before.late) { "Una mora cobrada cambió durante la revisión" }
                        val version = directPaymentVersion(JSONObject(requireNotNull(before.version)))
                        paymentKeys.add(paymentRevisionKey(version))
                    } else if (due !in calendarByDate) {
                        require(!hasLoanValue(feeCell.value) || sheetCents(feeCell.value) == 0L) { "La nueva fecha contiene una mora previa. Revisa el libro" }
                    }
                    if (due in plan.bases) {
                        listOf(check, amount, late).forEach(::preserve)
                        val unpaid = due !in plan.paidDates && due <= day
                        requests.add(updatePaymentCell(check, unpaid))
                        requests.add(updateFormula(amount, editedAmountFormula("${columnName(col)}$row", "${columnName(col + 4)}$row", plan.bases.getValue(due))))
                        requests.add(updateFormula(late, editedLateFormula("${columnName(col)}$row", "${columnName(col + 2)}$row",
                            "${columnName(col)}\u00245", "'$helper'!\u0024${columnName(feeCol)}\u0024$feeRow", input.dailyLate,
                            advances.filter { it.target==due }.joinToString("+" ) { "IF(${it.sourceRef}=TRUE,0,${decimalMoney(it.amount)})" })))
                        if(amountPlan!=null && due in amountPlan.paidDates) {
                            preserve(fee)
                            requests.add(updatePaymentCell(fee,amountPlan.after.loan.installments.single { it.due==due }.collectedLate/100.0))
                        }
                    } else {
                        require(before?.unpaid != false) { "No se puede quitar una fecha con un cobro" }
                        preserve(check); preserve(fee)
                        requests.add(updatePaymentCell(check, false)); requests.add(updatePaymentCell(fee, 0))
                    }
                }
            }
            listOf(2 to input.client, 4 to input.principal / 100.0, 5 to input.rate.movePointLeft(2), 6 to input.months,
                7 to input.guarantee, 16 to sheetSerial(plan.firstDue)).forEach { (col, value) ->
                val target = original(col); preserve(target); requests.add(updatePaymentCell(target, value))
            }
            // M/N remain useful for the displayed typical and first installment; each date has its exact base.
            val regular = plan.bases.entries.lastOrNull { it.key !in plan.paidDates }?.value ?: plan.bases.values.last()
            listOf(13 to "=${decimalMoney(regular)}", 14 to "=${decimalMoney(plan.bases.getValue(plan.firstDue))}",
                15 to "=ROUND(H${origin.row}-${decimalMoney(plan.bases.values.sum())},2)").forEach { (col, formula) ->
                val target = original(col); preserve(target); requests.add(updateFormula(target, formula))
            }
            val contributions = batch(listOf("'_APORTES'!A1:F1201")).single().values()
            require(contributions.firstOrNull()?.map(::sheetHeading) == listOf("registrado", "prestamo id", "fecha aporte", "monto aporte", "origen", "cliente")) { "Los encabezados de aportes cambiaron" }
            val linked = contributions.mapIndexedNotNull { i, row -> if (row.getOrNull(1)?.toString() == id) i + 1 else null }
            require(linked.size <= 1) { "Hay aportes duplicados para este préstamo" }
            if (linked.isNotEmpty()) {
                val row = linked.single()
                val contributionCells = directLoanCells(listOf("'_APORTES'!A$row:F$row"))
                listOf(3 to sheetSerial(input.disbursedOn), 6 to input.client).forEach { (col, value) ->
                    val target = address("_APORTES", row, col); val cell = contributionCells[target] ?: DirectLoanCell()
                    require(cell.formula == null && cell.validation == null) { "El aporte contiene una fórmula o validación" }
                    backup.add(listOf(target.sheet, row, col, JSONObject().put("value", cell.value ?: JSONObject.NULL).toString()))
                    requests.add(updatePaymentCell(target, value))
                }
            }
            val journalId = directLogSheet("_APP_TRANSACCIONES", listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON"), requests)
            val conditionsId = directLogSheet("_PRESTAMOS_CONDICIONES", listOf("PRESTAMO ID", "CONDICIONES JSON", "OPERACION"), requests)
            val auditId = directLogSheet("_PRESTAMOS_EDICIONES", listOf("REGISTRADO", "OPERACION", "PRESTAMO ID", "USUARIO", "HOJA", "FILA", "COLUMNA", "ANTES JSON", "DESPUES JSON"), requests)
            fun marker(key: String) { requests.add(JSONObject().put("addNamedRange", JSONObject().put("namedRange", JSONObject().put("namedRangeId", key).put("name", key.uppercase())
                .put("range", JSONObject().put("sheetId", journalId).put("startRowIndex", 0).put("endRowIndex", 1).put("startColumnIndex", 0).put("endColumnIndex", 1))))) }
            marker(operationKey); marker(loanTermsRevisionKey(bookId, origin)); paymentKeys.forEach(::marker)
            helperRows.forEach { (year, row) -> loanAuxSlotKey(year, row).takeIf { it !in names }?.let(::marker) }
            val config = LoanConditions(input.dailyLate, plan.bases, pending.operationId,advances)
            requests.add(appendPaymentRows(conditionsId, listOf(listOf(id, config.json().toString(), pending.operationId))))
            val registered = cashRecordedAt(body)
            val actor = body.optString("actor", subject)
            if(amountPlan!=null) {
                val historyId=directLogSheet("_PAGOS_HISTORIAL",PAYMENT_HISTORY_LABELS,requests)
                requests.add(appendPaymentRows(historyId,amountPlan.paidDates.map { due->val item=amountPlan.after.loan.installments.single { it.due==due }
                    listOf(registered,pending.operationId,due.year,if(due.year==origin.year)origin.rawId else id,sheetSerial(due),true,false,0,
                        item.collectedLate/100.0,sheetSerial(amountPlan.day),origin.client,actor) }))
            }
            val auditRows = listOf(listOf<Any>(registered, pending.operationId, id, actor, "PRESTAMO", 0, 0,
                LoanTermsInput(origin.client, origin.guarantee, origin.principal, origin.ratePercent, origin.months, origin.firstDue.minusDays(1), origin.dailyLate).json().toString(), input.json().toString())) +
                backup.map { listOf<Any>(registered, pending.operationId, id, actor) + it + "" }
            requests.add(appendPaymentRows(auditId, auditRows))
            receipt = JSONObject().put("bookId", bookId).put("operationId", pending.operationId).put("loanId", id)
                .put("termsChanged", true).put("confirmed", true).put("cashAfterCents", plan.cashAfter).put("transport", "sheets")
            if(amountPlan!=null)receipt.put("amountCents",amountPlan.amount).put("updated",amountPlan.paidDates.size)
            requests.add(appendPaymentRows(journalId, listOf(listOf(pending.operationId, directPaymentHash(pending.payload), registered, receipt.toString()))))
        } catch (failure: Exception) {
            if (failure is java.io.IOException || failure is GoogleHttpException) throw failure
            throw LoanTermsRejected(failure.message ?: "No se pudo preparar la edición", failure)
        }
        try {
            val result = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(result.getString("spreadsheetId") == bookId)
        } catch (failure: Exception) {
            val verification = runCatching { freshMetadata(includeNames = true); directReceipt(pending) to
                (requireNotNull(metadata).optJSONArray("namedRanges")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).getString("namedRangeId") == operationKey } } == true) }
            verification.getOrNull()?.first?.let { invalidateFinancialCache(); return it }
            if (verification.isSuccess && !verification.getOrThrow().second && failure is GoogleHttpException && failure.status in listOf(400, 403))
                throw LoanTermsRejected("No se guardó: cambiaron las cuotas, la estructura o los permisos. Actualiza y revisa", failure)
            throw failure
        }
        invalidateFinancialCache()
        return receipt
    }

    private fun writeDirectCashMovement(pending: PendingRemoteWrite, body: JSONObject): JSONObject {
        freshMetadata(includeNames = true)
        directReceipt(pending)?.let { invalidateFinancialCache(); return it }
        val requests = mutableListOf<JSONObject>()
        val result: JSONObject
        try {
            val movement = movementCommand(body)
            val labels = listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON")
            val ranges = listOf("'_CAPITAL'!A1:M8", "'_APORTES'!A1:F${rowsIn("_APORTES")}") +
                if (optionalProperties("_APP_TRANSACCIONES") != null) listOf("'_APP_TRANSACCIONES'!A1:D1") else emptyList()
            val read = batch(ranges)
            val capital = read[0].values()
            require(sheetHeading(capital.firstOrNull()?.firstOrNull()) == "capital inicial" && capital.getOrNull(3)?.getOrNull(9) == "CAJA")
            val summary = parseSheetSummary(today().year, capital.single { it.firstOrNull()?.toString()?.removeSuffix(".0") == today().year.toString() }, capital.first()[1])
            val contributions = read[1].values()
            require(contributions.firstOrNull()?.map(::sheetHeading) == listOf("registrado", "prestamo id", "fecha aporte", "monto aporte", "origen", "cliente")) { "La estructura de aportes cambió" }
            require(contributions.drop(1).count { it.any(::hasLoanValue) } < 1200) { "El registro de aportes alcanzó su capacidad" }
            invalidateFinancialCache()
            validateCashMovement(movement, cash(), summary.cash, today())
            val journalId = optionalProperties("_APP_TRANSACCIONES")?.let { props ->
                require(read[2].values().single() == labels) { "El diario cambió" }; props.getInt("sheetId")
            } ?: run {
                val sheets = requireNotNull(metadata).getJSONArray("sheets")
                val used = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }.toSet()
                val id = generateSequence(1) { it + 1 }.first { it !in used }
                requests += JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", id)
                    .put("title", "_APP_TRANSACCIONES").put("hidden", true).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", 4))))
                requests += appendPaymentRows(id, listOf(labels)); id
            }
            fun marker(key: String) = JSONObject().put("addNamedRange", JSONObject().put("namedRange", JSONObject()
                .put("namedRangeId", key).put("name", key.uppercase()).put("range", JSONObject().put("sheetId", journalId)
                    .put("startRowIndex", 0).put("endRowIndex", 1).put("startColumnIndex", 0).put("endColumnIndex", 1))))
            requests += marker(loanOperationKey(pending.operationId))
            requests += marker(cashLedgerRevisionKey(contributions.drop(1)))
            val registered = cashRecordedAt(body)
            // Signed external capital flows use the existing dated native capital ledger.
            // Empty loan identity prevents loan edits/deletion from touching these entries.
            val capitalEntry = appendPaymentRows(properties("_APORTES").getInt("sheetId"), listOf(listOf(registered, "",
                sheetSerial(movement.date), movement.signed / 100.0, "${movement.type.label} de caja · ${movement.id}", movement.description)))
            val entryCells = capitalEntry.getJSONObject("appendCells").getJSONArray("rows").getJSONObject(0).getJSONArray("values")
            listOf(2 to ("DATE" to "dd-mm-yyyy"), 3 to ("NUMBER" to "0.00")).forEach { (column, format) ->
                entryCells.getJSONObject(column).put("userEnteredFormat", JSONObject().put("numberFormat", JSONObject()
                    .put("type", format.first).put("pattern", format.second)))
            }
            capitalEntry.getJSONObject("appendCells").put("fields", "userEnteredValue,userEnteredFormat.numberFormat")
            requests += capitalEntry
            result = JSONObject().put("bookId", bookId).put("operationId", pending.operationId).put("confirmed", true)
                .put("transport", "sheets").put("cashMovement", movement.json()).put("cashAfterCents", summary.cash + movement.signed)
            requests += appendPaymentRows(journalId, listOf(listOf(pending.operationId, directPaymentHash(pending.payload), registered, result.toString())))
        } catch (failure: Exception) {
            if (failure is java.io.IOException || failure is GoogleHttpException) throw failure
            throw DirectPaymentRejected(failure.message ?: "Revisa el movimiento de caja", failure)
        }
        try {
            val response = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(response.getString("spreadsheetId") == bookId)
        } catch (failure: Exception) {
            val confirmed = runCatching { freshMetadata(includeNames = true); directReceipt(pending) }.getOrNull()
            if (confirmed != null) { invalidateFinancialCache(); return confirmed }
            if (failure is GoogleHttpException && failure.status in listOf(400, 403))
                throw DirectPaymentRejected("La caja cambió o Google rechazó el movimiento. Actualiza Inicio y revisa", failure)
            throw failure
        }
        invalidateFinancialCache()
        return result
    }

    private fun writeDirectCapital(pending: PendingRemoteWrite, body: JSONObject): JSONObject {
        require(connection.canEditCapital(bookId))
        freshMetadata(includeNames = true)
        directReceipt(pending)?.let { invalidateFinancialCache(); return it }
        val names = requireNotNull(metadata).optJSONArray("namedRanges")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("namedRangeId") }.toSet()
        }.orEmpty()
        val operationKey = capitalOperationKey(pending.operationId)
        check(operationKey !in names) { "El cambio de capital figura enviado pero falta su comprobante. Revisa el diario" }
        val requests = mutableListOf<JSONObject>()
        val receipt: JSONObject
        try {
            val change = capitalChange(body)
            require(change.year == today().year) { "Cambió el año. Actualiza Inicio antes de modificar el capital" }
            val logLabels = linkedMapOf("_APP_TRANSACCIONES" to listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON"),
                "_CAPITAL_HISTORIAL" to listOf("REGISTRADO", "OPERACION", "USUARIO", "CAPITAL ANTERIOR", "CAPITAL NUEVO",
                    "DIFERENCIA", "CAJA ANTES", "CAJA DESPUES", "AÑO"))
            val existing = logLabels.keys.filter { optionalProperties(it) != null }
            // Anchor the revision before reading mutable amounts: a later edit must conflict.
            var revision = "initial"
            optionalProperties("_CAPITAL_HISTORIAL")?.let { props ->
                val count = props.getJSONObject("gridProperties").getInt("rowCount")
                for (start in 2..count step 400) {
                    val rows = batch(listOf("'_CAPITAL_HISTORIAL'!A$start:B${minOf(start + 399, count)}")).single().values()
                    rows.filter { it.any(::hasLoanValue) }.forEach { row ->
                        revision = row.getOrNull(1)?.toString()?.takeIf(String::isNotBlank)
                            ?: error("Hay una operación de capital sin identidad. Revisa su historial")
                    }
                    if (rows.size < minOf(400, count - start + 1)) break
                }
            }
            val read = batch(listOf("'_CAPITAL'!A1:M8") + existing.map { "'$it'!A1:${columnName(logLabels.getValue(it).size)}1" })
            val values = read.first().values()
            require(sheetHeading(values.firstOrNull()?.firstOrNull()) == "capital inicial" &&
                values.getOrNull(3)?.getOrNull(9) == "CAJA") { "La estructura de capital cambió" }
            val summary = parseSheetSummary(change.year,
                values.single { it.firstOrNull()?.toString()?.removeSuffix(".0") == change.year.toString() }, values.first()[1])
            val address = PaymentCell("_CAPITAL", properties("_CAPITAL").getInt("sheetId"), 1, 2)
            val initialCell = directLoanCells(listOf("'_CAPITAL'!B1:B1"))[address] ?: DirectLoanCell()
            validateCapitalChange(change, summary, initialCell)
            val logHeaders = existing.mapIndexed { index, title -> title to read[index + 1].values().single() }.toMap()
            val usedIds = requireNotNull(metadata).getJSONArray("sheets").let { array -> (0 until array.length())
                .map { array.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }.toMutableSet() }
            fun logSheet(title: String): Int {
                val labels = logLabels.getValue(title)
                optionalProperties(title)?.let { props ->
                    require(logHeaders[title] == labels) { "Los encabezados de $title cambiaron" }
                    return props.getInt("sheetId")
                }
                val id = generateSequence(1) { it + 1 }.first { it !in usedIds }; usedIds.add(id)
                requests.add(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", id)
                    .put("title", title).put("hidden", true).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", labels.size)))))
                requests.add(appendPaymentRows(id, listOf(labels)))
                return id
            }
            val journalId = logSheet("_APP_TRANSACCIONES")
            val historyId = logSheet("_CAPITAL_HISTORIAL")
            fun marker(key: String) = JSONObject().put("addNamedRange", JSONObject().put("namedRange", JSONObject()
                .put("namedRangeId", key).put("name", key.uppercase()).put("range", JSONObject().put("sheetId", journalId)
                    .put("startRowIndex", 0).put("endRowIndex", 1).put("startColumnIndex", 0).put("endColumnIndex", 1))))
            requests.add(marker(operationKey)); requests.add(marker(capitalRevisionKey(bookId, change.before, revision)))
            requests.add(updatePaymentCell(address, java.math.BigDecimal.valueOf(change.after, 2)))
            val registered = cashRecordedAt(body)
            requests.add(appendPaymentRows(historyId, listOf(listOf(registered, pending.operationId, body.optString("actor", subject),
                java.math.BigDecimal.valueOf(change.before, 2), java.math.BigDecimal.valueOf(change.after, 2),
                java.math.BigDecimal.valueOf(change.difference, 2), java.math.BigDecimal.valueOf(change.cashBefore, 2),
                java.math.BigDecimal.valueOf(change.cashAfter, 2), change.year))))
            receipt = JSONObject().put("bookId", bookId).put("operationId", pending.operationId).put("confirmed", true)
                .put("transport", "sheets").put("capitalAfterCents", change.after).put("cashAfterCents", change.cashAfter)
            requests.add(appendPaymentRows(journalId, listOf(listOf(pending.operationId, directPaymentHash(pending.payload), registered, receipt.toString()))))
        } catch (failure: Exception) {
            if (failure is java.io.IOException || failure is GoogleHttpException) throw failure
            throw CapitalEditRejected(failure.message ?: "No se pudo preparar el cambio de capital", failure)
        }
        try {
            val result = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(result.getString("spreadsheetId") == bookId)
        } catch (failure: Exception) {
            val verification = runCatching {
                freshMetadata(includeNames = true)
                val confirmed = directReceipt(pending)
                val markers = requireNotNull(metadata).optJSONArray("namedRanges")
                val sent = markers?.let { list -> (0 until list.length()).any {
                    list.getJSONObject(it).getString("namedRangeId") == operationKey
                } } == true
                confirmed to sent
            }
            verification.getOrNull()?.first?.let { invalidateFinancialCache(); return it }
            if (verification.isSuccess && !verification.getOrThrow().second && failure is GoogleHttpException && failure.status in listOf(400, 403))
                throw CapitalEditRejected("No se cambió el capital: cambiaron los datos o Google rechazó la edición. Actualiza Inicio y revisa", failure)
            throw failure
        }
        invalidateFinancialCache()
        return receipt
    }

    /** Archive, reverse linked inputs, reserve identities and issue a receipt in one atomic batch. */
    private fun writeDirectLoanDeletion(pending: PendingRemoteWrite, body: JSONObject): JSONObject {
        require(connection.canDeleteLoan(bookId))
        freshMetadata(includeNames = true)
        directReceipt(pending)?.let { invalidateFinancialCache(); return it }
        val names = requireNotNull(metadata).optJSONArray("namedRanges")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("namedRangeId") }.toSet()
        }.orEmpty()
        val operationKey = "cp_deleteop_" + directPaymentHash(pending.operationId).take(40)
        check(operationKey !in names) { "La eliminación figura enviada pero falta su comprobante. Revisa el diario" }
        val requests = mutableListOf<JSONObject>()
        val receipt: JSONObject
        val journalId: Int
        val registered = cashRecordedAt(body)
        try {
            val id = body.getString("loanId")
            val deletionKey = deletedLoanKey(id)
            require(deletionKey !in names) { "Este préstamo ya fue eliminado. Actualiza la consulta" }
            loadConditions(force = true)
            catalogs.clear(); rawCatalogs.clear(); headers.clear(); paymentHistory = null
            val views = availableYears().flatMap(::loans).filter { it.id == id }
            val selected = views.singleOrNull { it.year == body.getInt("year") }
                ?: error("El préstamo ya no está disponible. Actualiza la consulta")
            require(loanDeletionVersion(selected) == body.getString("version")) {
                "El préstamo cambió desde que abriste el detalle. Actualiza y confirma de nuevo"
            }
            val origin = views.singleOrNull { it.year == id.substringBefore('-').toInt() }
                ?: error("No se encontró una única fila original del préstamo")
            require(origin.row in 6..405) { "La fila original está fuera del bloque de préstamos" }
            validateLoanHeadings(batch(listOf("'${origin.year}'!A5:Q5")).single().values().single())
            val originalCells = directLoanCells(listOf("'${origin.year}'!A${origin.row}:Q${origin.row}"))
            val originalSheetId = properties(origin.year.toString()).getInt("sheetId")
            fun originalCell(col: Int) = PaymentCell(origin.year.toString(), originalSheetId, origin.row, col)
            val inputs = loanDeletionInputs((1..17).associateWith { originalCells[originalCell(it)] ?: DirectLoanCell() })
            val sourceValues = (1..17).map { originalCells[originalCell(it)]?.value }
            require(loanDeletionVersion(configuredLoans(origin.year, listOf(sourceValues), origin.row).single()) == loanDeletionVersion(origin)) {
                "La fila original cambió. Actualiza antes de eliminar"
            }
            val changes = linkedMapOf<PaymentCell, Pair<Any?, Any?>>()
            inputs.forEach { col -> changes[originalCell(col)] = originalCells[originalCell(col)]?.value to null }
            val reservationKeys = mutableSetOf(deletionKey, operationKey,
                loanSlotKey(origin.year, origin.row), loanItemKey(origin.year, id.substringAfter('-').toInt()))
            val paymentMarkers = mutableSetOf<String>()
            val history = historyIndex()
            views.forEach { loan ->
                val h = headers(loan.year)
                val feeSheet = "_MORA_COBRADA_${loan.year}"
                val feeRow = h.feeRows[loan.rawId]
                val ranges = listOf("'${loan.year}'!A${loan.row}:${columnName(h.calendar.values.max() + 4)}${loan.row}") +
                    if (feeRow == null) emptyList() else listOf("'$feeSheet'!A$feeRow:${columnName(columnsIn(feeSheet))}$feeRow")
                val cells = directLoanCells(ranges)
                fun cell(sheet: String, row: Int, col: Int) = PaymentCell(sheet, properties(sheet).getInt("sheetId"), row, col)
                val viewValues = (1..17).map { cells[cell(loan.year.toString(), loan.row, it)]?.value }
                require(loanDeletionVersion(configuredLoans(loan.year, listOf(viewValues), loan.row).single()) == loanDeletionVersion(loan)) {
                    "El préstamo cambió durante la comprobación. Actualiza antes de eliminar"
                }
                if (feeRow != null) {
                    reservationKeys.add(loanAuxSlotKey(loan.year, feeRow))
                    (2..columnsIn(feeSheet)).forEach { col ->
                        val address = cell(feeSheet, feeRow, col)
                        val value = cells[address] ?: DirectLoanCell()
                        require(value.formula == null) { "La mora contiene una fórmula que no se puede eliminar" }
                        if (hasLoanValue(value.value)) {
                            require(sheetCents(value.value) >= 0) { "La mora contiene un valor inválido" }
                            changes[address] = value.value to null
                        }
                    }
                }
                h.calendar.forEach date@ { (due, col) ->
                    if (due !in loan.firstDue..loan.lastDue) return@date
                    val address = cell(loan.year.toString(), loan.row, col + 2)
                    val check = cells[address] ?: DirectLoanCell()
                    validateLoanCalendarCell(check, check = true)
                    if (hasLoanValue(check.value)) changes[address] = check.value to null
                    if (due <= today()) {
                        require(cells[cell(loan.year.toString(), loan.row, col)]?.value == true) { "El control de cuota cambió" }
                        val feeCol = h.fees[due] ?: error("Falta la fecha de mora")
                        val fee = feeRow?.let { cells[cell(feeSheet, it, feeCol)]?.value }
                        val storedLate = if (hasLoanValue(fee)) sheetCents(fee) else 0L
                        var payment = SheetPayment(loan, due, loan.baseOn(due),
                            check.value == true, if (check.value == true) loan.lateAt(due, today()) else storedLate)
                        payment = payment.copy(paidOn = history.paidOn(payment, today()))
                        paymentMarkers.add(paymentRevisionKey(directPaymentVersion(bookId, payment, storedLate, history.revision(payment))))
                    }
                }
            }
            require(rowsIn("_APORTES") == 1201 && columnsIn("_APORTES") == 6) { "La estructura de aportes cambió" }
            val contributions = batch(listOf("'_APORTES'!A1:F1201")).single().values()
            require(contributions.firstOrNull()?.map(::sheetHeading) == listOf("registrado", "prestamo id", "fecha aporte", "monto aporte", "origen", "cliente")) {
                "Los encabezados de aportes cambiaron"
            }
            val contributionRows = contributions.mapIndexedNotNull { index, row ->
                if (row.getOrNull(1)?.toString() == id) index + 1 else null
            }
            require(contributionRows.size <= 1) { "El préstamo tiene aportes duplicados. Revisa la hoja" }
            val contributionCells = if (contributionRows.isEmpty()) emptyMap() else directLoanCells(contributionRows.map { "'_APORTES'!A$it:F$it" })
            contributionRows.forEach { row ->
                val address = PaymentCell("_APORTES", properties("_APORTES").getInt("sheetId"), row, 4)
                val identity = contributionCells[address.copy(column = 2)] ?: DirectLoanCell()
                require(identity.value == id && identity.formula == null) { "El aporte cambió de préstamo" }
                val value = contributionCells[address] ?: DirectLoanCell()
                require(value.formula == null && value.validation == null && sheetCents(value.value) >= 0) { "El aporte dejó de ser editable" }
                changes[address] = value.value to 0
            }
            val usedIds = requireNotNull(metadata).getJSONArray("sheets").let { array -> (0 until array.length())
                .map { array.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }.toMutableSet() }
            fun logSheet(title: String, labels: List<String>): Int {
                optionalProperties(title)?.let { props ->
                    require(batch(listOf("'$title'!A1:${columnName(labels.size)}1")).single().values().single() == labels) {
                        "Los encabezados de $title cambiaron"
                    }
                    return props.getInt("sheetId")
                }
                val sheetId = generateSequence(1) { it + 1 }.first { it !in usedIds }; usedIds.add(sheetId)
                requests.add(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", sheetId)
                    .put("title", title).put("hidden", true).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", labels.size)))))
                requests.add(appendPaymentRows(sheetId, listOf(labels)))
                return sheetId
            }
            journalId = logSheet("_APP_TRANSACCIONES", listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON"))
            val archiveId = logSheet("_PRESTAMOS_ELIMINADOS", listOf("REGISTRADO", "OPERACION", "PRESTAMO ID", "USUARIO", "TIPO", "HOJA", "FILA", "COLUMNA", "VALOR JSON"))
            fun marker(key: String) = JSONObject().put("addNamedRange", JSONObject().put("namedRange", JSONObject()
                .put("namedRangeId", key).put("name", key.uppercase()).put("range", JSONObject().put("sheetId", journalId)
                    .put("startRowIndex", 0).put("endRowIndex", 1).put("startColumnIndex", 0).put("endColumnIndex", 1))))
            // Existing reservation keys remain; payment revision keys must be free or the entire batch fails.
            reservationKeys.filter { it !in names }.forEach { requests.add(marker(it)) }
            paymentMarkers.forEach { requests.add(marker(it)) }
            val actor = body.optString("actor", subject)
            val archive = mutableListOf<List<Any>>(listOf(registered, pending.operationId, id, actor, "PRESTAMO",
                origin.year.toString(), origin.row, 0, JSONArray(sourceValues).toString()))
            changes.forEach { (address, values) ->
                archive.add(listOf(registered, pending.operationId, id, actor, "CELDA", address.sheet, address.row, address.column,
                    JSONObject().put("value", values.first ?: JSONObject.NULL).toString()))
                requests.add(if (values.second == null) JSONObject().put("updateCells", JSONObject()
                    .put("range", JSONObject().put("sheetId", address.sheetId).put("startRowIndex", address.row - 1).put("endRowIndex", address.row)
                        .put("startColumnIndex", address.column - 1).put("endColumnIndex", address.column))
                    .put("fields", "userEnteredValue")) else updatePaymentCell(address, values.second!!))
            }
            requests.add(appendPaymentRows(archiveId, archive))
            receipt = JSONObject().put("bookId", bookId).put("operationId", pending.operationId).put("loanId", id)
                .put("deleted", true).put("confirmed", true).put("transport", "sheets")
            requests.add(appendPaymentRows(journalId, listOf(listOf(pending.operationId, directPaymentHash(pending.payload), registered, receipt.toString()))))
        } catch (failure: Exception) {
            if (failure is java.io.IOException || failure is GoogleHttpException) throw failure
            throw LoanDeletionRejected(failure.message ?: "No se pudo preparar la eliminación. Actualiza y revisa", failure)
        }
        try {
            val response = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(response.getString("spreadsheetId") == bookId)
        } catch (failure: Exception) {
            val verification = runCatching {
                freshMetadata(includeNames = true)
                val confirmed = directReceipt(pending)
                val markers = requireNotNull(metadata).optJSONArray("namedRanges")
                val sent = markers?.let { list -> (0 until list.length()).any {
                    list.getJSONObject(it).getString("namedRangeId") == operationKey
                } } == true
                confirmed to sent
            }
            verification.getOrNull()?.first?.let { invalidateFinancialCache(); return it }
            if (verification.isSuccess && !verification.getOrThrow().second && failure is GoogleHttpException && failure.status in listOf(400, 403))
                throw LoanDeletionRejected(failure.message ?: "No se eliminó: cambiaron los datos o los permisos. Actualiza y revisa", failure)
            throw failure // Unknown outcome: retain the durable operation and check its receipt on retry.
        }
        invalidateFinancialCache()
        return receipt
    }

    private fun invalidateFinancialCache() {
        conditionsLoaded = false
        catalogs.clear(); rawCatalogs.clear(); headers.clear(); calendars.clear(); cashCache = null; paymentHistory = null
        cache.delete()
    }

    private fun writeDirectPayments(pending: PendingRemoteWrite, body: JSONObject, firstAttempt: Boolean): JSONObject {
        freshMetadata()
        if (!firstAttempt) directReceipt(pending)?.let { return it }
        loadConditions(force = true)
        val rawEdits = body.getJSONArray("edits")
        require(rawEdits.length() in 1..100)
        val changes = (0 until rawEdits.length()).map { index ->
            val value = rawEdits.getJSONObject(index)
            DirectPaymentChange(directPaymentVersion(value.getJSONObject("version")), value.getBoolean("unpaid"),
                sheetCents(value.get("collectedLate")), value.optString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse))
        }
        require(changes.map { it.before.loanId to it.before.due }.distinct().size == changes.size) { "Cuotas repetidas en el lote" }
        paymentHistory = null
        val years = changes.map { it.before.year }.distinct()
        val logLabels = linkedMapOf(
            "_PAGOS_HISTORIAL" to PAYMENT_HISTORY_LABELS,
            "_APP_TRANSACCIONES" to listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON"))
        val existingLogs = logLabels.keys.filter { optionalProperties(it) != null }
        val identityRanges = years.flatMap { year -> listOf("'$year'!A6:A${rowsIn(year.toString())}",
            "'_MORA_COBRADA_$year'!A6:A${rowsIn("_MORA_COBRADA_$year")}") }
        val identities = batch(identityRanges + existingLogs.map { title ->
            "'$title'!A1:${columnName(logLabels.getValue(title).size)}1"
        })
        val logHeaders = existingLogs.mapIndexed { index, title ->
            title to identities[identityRanges.size + index].values().firstOrNull().orEmpty()
        }.toMap()
        val rowNumbers = years.mapIndexed { index, year -> year to identities[index * 2].values().mapIndexedNotNull { row, values ->
            values.firstOrNull()?.toString()?.removeSuffix(".0")?.takeIf { it.isNotBlank() }?.let { raw -> raw to row + 6 }
        } }.toMap()
        years.forEachIndexed { index, year ->
            val feeRows = identities[index * 2 + 1].values().mapIndexedNotNull { row, values ->
                values.firstOrNull()?.toString()?.removeSuffix(".0")?.takeIf { it.isNotBlank() }?.let { it to row + 6 }
            }
            require(feeRows.map { it.first }.distinct().size == feeRows.size) { "IDs de mora repetidos" }
            headers[year] = headers(year).copy(feeRows = feeRows.toMap())
        }
        val loanKeys = changes.map { it.before.year to it.before.loanId }.distinct()
        val resolved = loanKeys.associateWith { (year, id) -> rowNumbers.getValue(year).singleOrNull {
            runCatching { SheetsIdentity.canonical(year, it.first) }.getOrNull() == id
        }?.second ?: throw DirectPaymentRejected("El préstamo ya no está disponible. Descarta el borrador y actualiza") }
        val loanValues = batch(loanKeys.map { key -> "'${key.first}'!A${resolved.getValue(key)}:Q${resolved.getValue(key)}" })
        val currentLoans = loanKeys.mapIndexed { index, key -> key to configuredLoans(key.first,
            loanValues[index].values(), resolved.getValue(key)).singleOrNull().let { loan ->
                loan ?: throw DirectPaymentRejected("El préstamo fue eliminado o ya no está disponible. Descarta el borrador y actualiza")
            } }.toMap()
        data class Target(val change: DirectPaymentChange, val loan: SheetLoan, val control: PaymentCell, val check: PaymentCell, val fee: PaymentCell)
        val targets = changes.map { change ->
            val before = change.before
            val loan = currentLoans.getValue(before.year to before.loanId)
            val h = headers(before.year)
            val col = h.calendar[before.due] ?: throw DirectPaymentRejected("El calendario de la cuota cambió")
            val feeRow = h.feeRows[loan.rawId] ?: throw DirectPaymentRejected("La fila de mora cambió")
            val feeCol = h.fees[before.due] ?: throw DirectPaymentRejected("El calendario de mora cambió")
            val annualId = properties(before.year.toString()).getInt("sheetId")
            val feeSheet = "_MORA_COBRADA_${before.year}"
            Target(change, loan, PaymentCell(before.year.toString(), annualId, loan.row, col),
                PaymentCell(before.year.toString(), annualId, loan.row, col + 2),
                PaymentCell(feeSheet, properties(feeSheet).getInt("sheetId"), feeRow, feeCol))
        }
        val headerCells = targets.flatMap { listOf(it.control.copy(row = 5), it.fee.copy(row = 5)) }
        val cells = paymentCells(targets.flatMap { listOf(it.control, it.check, it.fee) } + headerCells)
        val history = historyIndex()
        try {
            targets.forEach { target ->
                require(sheetDate(cells.getValue(target.control.copy(row = 5)).value) == target.change.before.due &&
                    sheetDate(cells.getValue(target.fee.copy(row = 5)).value) == target.change.before.due) { "El calendario de la cuota cambió" }
                val check = cells.getValue(target.check); val fee = cells.getValue(target.fee)
                require(check.value == null || check.value == "" || check.value is Boolean) { "El estado del pago dejó de ser válido" }
                val stored = if (fee.value == null || fee.value == "") 0L else sheetCents(fee.value)
                val row = SheetPayment(target.loan, target.change.before.due,
                    target.loan.baseOn(target.change.before.due),
                    check.value == true, if (check.value == true) target.loan.lateAt(target.change.before.due, today()) else stored)
                val snapshot = DirectPaymentSnapshot(target.loan, row.due, cells.getValue(target.control).value == true,
                    row.unpaid, stored, history.paidOn(row, today()), check.formula, fee.formula, history.revision(row))
                validateDirectPaymentChange(bookId, target.change, snapshot, today())
            }
        } catch (failure: IllegalArgumentException) {
            throw DirectPaymentRejected(failure.message ?: "El pago cambió. Descarta el borrador y actualiza", failure)
        }
        val requests = mutableListOf<JSONObject>()
        val usedIds = requireNotNull(metadata).getJSONArray("sheets").let { array -> (0 until array.length())
            .map { array.getJSONObject(it).getJSONObject("properties").getInt("sheetId") }.toMutableSet() }
        fun logSheet(title: String, labels: List<String>): Int {
            optionalProperties(title)?.let { props ->
                val values = logHeaders.getValue(title)
                require(values == labels) { "Los encabezados de $title cambiaron; revisa la hoja antes de guardar" }
                return props.getInt("sheetId")
            }
            var id = 1; while (id in usedIds) id++
            usedIds.add(id)
            requests.add(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", id)
                .put("title", title).put("hidden", true).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", labels.size)))))
            requests.add(appendPaymentRows(id, listOf(labels)))
            requireNotNull(metadata).getJSONArray("sheets").put(JSONObject().put("properties", JSONObject().put("sheetId", id)
                .put("title", title).put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", labels.size))))
            return id
        }
        val auditId = logSheet("_PAGOS_HISTORIAL", logLabels.getValue("_PAGOS_HISTORIAL"))
        val journalId = logSheet("_APP_TRANSACCIONES", logLabels.getValue("_APP_TRANSACCIONES"))
        // Reopening an amount-payment source refunds its prepaid fees too. Remove those
        // credits permanently, so a later ordinary payment cannot reactivate them for free.
        targets.groupBy { it.loan.id }.values.forEach { group->
            val loan=group.first().loan
            if(loan.advances.isEmpty())return@forEach
            val revisionKey=loanTermsRevisionKey(bookId,loan)
            requests.add(JSONObject().put("addNamedRange",JSONObject().put("namedRange",JSONObject()
                .put("namedRangeId",revisionKey).put("name",revisionKey.uppercase()).put("range",JSONObject().put("sheetId",journalId)
                    .put("startRowIndex",0).put("endRowIndex",1).put("startColumnIndex",0).put("endColumnIndex",1)))))
            val reopened=group.filter { it.change.unpaid }.map { it.change.before.due }.toSet()
            val remaining=loan.advances.filterNot { it.source in reopened }
            val conditionsId=directLogSheet("_PRESTAMOS_CONDICIONES",listOf("PRESTAMO ID","CONDICIONES JSON","OPERACION"),requests)
            val config=LoanConditions(loan.dailyLate,loanDates(loan).associateWith(loan::baseOn),pending.operationId,remaining)
            requests.add(appendPaymentRows(conditionsId,listOf(listOf(loan.id,config.json().toString(),pending.operationId))))
            loan.advances.map { it.target }.distinct().forEach { due->
                val h=headers(due.year);val row=loan.row+(due.year-loan.year)*400;val col=h.calendar.getValue(due)
                val helper="_MORA_COBRADA_${due.year}";val raw=if(due.year==loan.id.substringBefore('-').toInt())loan.id.substringAfter('-').toInt().toString() else loan.id
                val feeRow=h.feeRows[raw]?:error("Falta la fila de mora del abono")
                val formula=editedLateFormula("${columnName(col)}$row","${columnName(col+2)}$row","${columnName(col)}\u00245",
                    "'$helper'!\u0024${columnName(h.fees.getValue(due))}\u0024$feeRow",loan.dailyLate,
                    remaining.filter { it.target==due }.joinToString("+") { "IF(${it.sourceRef}=TRUE,0,${decimalMoney(it.amount)})" })
                requests.add(updateFormula(PaymentCell(due.year.toString(),properties(due.year.toString()).getInt("sheetId"),row,col+4),formula))
            }
        }
        val registered = cashRecordedAt(body)
        val auditRows = targets.map { target ->
            val change = target.change; val before = change.before
            // The unique revision marker makes two direct writes against the same version mutually exclusive.
            val key = paymentRevisionKey(before)
            requests.add(JSONObject().put("addNamedRange", JSONObject().put("namedRange", JSONObject()
                .put("namedRangeId", key).put("name", key.uppercase()).put("range", JSONObject().put("sheetId", journalId)
                    .put("startRowIndex", 0).put("endRowIndex", 1).put("startColumnIndex", 0).put("endColumnIndex", 1)))))
            requests.add(updatePaymentCell(target.check, change.unpaid))
            requests.add(updatePaymentCell(target.fee, change.collectedLate / 100.0))
            listOf<Any>(registered, pending.operationId, before.year.toString(), target.loan.rawId, sheetSerial(before.due),
                before.unpaid, change.unpaid, before.collectedLate / 100.0, change.collectedLate / 100.0,
                change.paidOn?.let(::sheetSerial) ?: "", target.loan.client, body.optString("actor", subject))
        }
        val receipt = JSONObject().put("bookId", bookId).put("operationId", pending.operationId).put("updated", targets.size)
            .put("transport", "sheets").put("confirmed", true)
        requests.add(appendPaymentRows(auditId, auditRows))
        requests.add(appendPaymentRows(journalId, listOf(listOf(pending.operationId, directPaymentHash(pending.payload), registered, receipt.toString()))))
        try {
            val response = http.post("$base:batchUpdate", JSONObject().put("requests", JSONArray(requests)))
            require(response.getString("spreadsheetId") == bookId)
        } catch (failure: Exception) {
            // A timeout or duplicate marker can be an already committed retry. Read the receipt first.
            val confirmed = runCatching { freshMetadata(); directReceipt(pending) }.getOrNull()
            if (confirmed != null) return confirmed
            if (failure is GoogleHttpException && failure.status in listOf(400, 403))
                throw DirectPaymentRejected(if (failure.status == 403) failure.message ?: "Google rechazó el permiso de edición"
                    else "No se guardó el lote: una cuota o la estructura cambió. Descarta el borrador, actualiza y revisa", failure)
            throw failure
        }
        // batchUpdate's acknowledgement confirms the atomic write. Refresh is a separate read operation.
        cashCache = null; calendars.clear(); paymentHistory = null;conditionsLoaded=false
        return receipt
    }

    fun refreshAfterPayments(affected: List<SheetPayment>): SheetsPreload {
        if (isSimpleBook) return simple.current()
        val keys = affected.map { it.loan.year to it.loan.id }.distinct()
        val targets = keys.map { (year, id) -> loans(year).single { it.id == id } }
        val reads = batch(listOf("'_CAPITAL'!A1:M8") + targets.map { "'${it.year}'!A${it.row}:Q${it.row}" })
        capital = reads.first().values()
        targets.forEachIndexed { index, previous ->
            val rows = reads[index + 1].values()
            val updated = configuredLoans(previous.year, rows, previous.row).single()
            require(updated.id == previous.id) { "La fila del préstamo cambió; actualiza la consulta" }
            catalogs[previous.year] = loans(previous.year).map { if (it.id == updated.id) updated else it }
            rawCatalogs[previous.year]?.put(previous.row - 6, JSONArray(rows.single()))
        }
        val ids = keys.map { it.second }.toSet()
        paymentHistory = null; cashCache = null; calendars.clear()
        val refreshed = payments(today().year, today(), today(), null, ids)
        todayPayments = todayPayments.filterNot { it.loan.id in ids } + refreshed
        syncedAt = Instant.now()
        save(today())
        return SheetsPreload(requireNotNull(metadata).getJSONObject("properties").getString("title"), summary(today().year),
            loans(today().year), todayPayments, syncedAt!!, paymentServiceError)
    }
}

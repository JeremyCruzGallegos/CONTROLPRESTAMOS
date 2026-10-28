package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.calculatedLate
import com.example.controlprestamos.domain.today
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.time.LocalDate

/** Exercises the repository's real deletion path with an in-memory Sheets transport. */
class LoanDeletionTest {
    private class Cache : SheetCache {
        var deleted = false
        override fun read(): JSONObject? = null
        override fun write(value: JSONObject) = Unit
        override fun delete() { deleted = true }
    }
    internal class FakeSheets(private val first: LocalDate = LocalDate.of(2026, 12, 20), val book: String = SheetsIdentity.PRODUCTION_ID) : SheetsHttp {
        val id = "2026-001"
        val cells = mutableMapOf<Triple<String, Int, Int>, Pair<Any?, String?>>()
        val sheets = linkedMapOf("2026" to (1 to 405), "2027" to (2 to 805),
            "_MORA_COBRADA_2026" to (3 to 405), "_MORA_COBRADA_2027" to (4 to 805), "_APORTES" to (5 to 1201))
        val names = mutableSetOf<String>()
        var posted: JSONObject? = null
        var posts = 0
        var commitThenTimeout = false
        var rejectPost = false
        var failVerification = false
        var journal: List<Any> = emptyList()
        var afterCapitalRead: (() -> Unit)? = null
        init {
            val headings = listOf("ÍTEM", "CLIENTE", "CUOTAS", "PRÉSTAMO", "TASA mensual", "MESES", "GARANTÍA",
                "PACTADO", "COBRADO", "DEUDA", "INTERÉS REAL", "INTERÉS", "CUOTA", "PRIMERA", "CONTROL", "PRIMER PAGO", "ÚLTIMO PAGO")
            headings.forEachIndexed { col, v -> put("2026", 5, col + 1, v) }
            val original = listOf<Any>(1, "Ana", "0/30", 500, 0.02, 1, "Equipo", 510, 0, 510, 0.333333, 0.3, 16.9, 19.9, 0,
                sheetSerial(first), sheetSerial(first.plusDays(29)))
            original.forEachIndexed { col, value ->
                val input = col + 1 in listOf(1, 2, 4, 5, 6, 7, 16)
                put("2026", 6, col + 1, value, if (input) null else "=FORMULA")
                put("2027", 406, col + 1, if (col == 0) id else value, "=CARRY")
                put("2026", 7, col + 1, if (col == 0) 2 else value, if (input) null else "=FORMULA")
            }
            listOf(2026, 2027).forEach { year ->
                val days = if (year == 2026) 169 else 365
                val begin = if (year == 2026) 18 else 22
                val start = LocalDate.of(year, 12, 31).minusDays(days - 1L)
                val row = if (year == 2026) 6 else 406
                val helper = "_MORA_COBRADA_$year"
                put(helper, 6, 1, if (year == 2026) 1 else id)
                repeat(days) { index ->
                    val due = start.plusDays(index.toLong()); val col = begin + index * 5
                    put(year.toString(), 5, col, sheetSerial(due))
                    put(helper, 5, index + 2, sheetSerial(due))
                    if (due in first..first.plusDays(29)) {
                        put(year.toString(), row, col, true, "=VALID")
                        put(year.toString(), row, col + 2, due == first)
                    }
                }
                val index = java.time.temporal.ChronoUnit.DAYS.between(start, maxOf(first, start)).toInt()
                put(helper, 6, index + 2, 2.0)
            }
            listOf("REGISTRADO", "PRESTAMO ID", "FECHA APORTE", "MONTO APORTE", "ORIGEN", "CLIENTE")
                .forEachIndexed { col, value -> put("_APORTES", 1, col + 1, value) }
            listOf<Any>("2026-12-19 10:00:00", id, sheetSerial(first.minusDays(1)), 500, "Capital nuevo", "Ana")
                .forEachIndexed { col, value -> put("_APORTES", 2, col + 1, value) }
        }
        fun put(sheet: String, row: Int, col: Int, value: Any?, formula: String? = null) { cells[Triple(sheet, row, col)] = value to formula }
        fun loan(year: Int = 2026): SheetLoan {
            val row = if (year == 2026) 6 else 406
            return parseSheetLoans(year, listOf((1..17).map { cells[Triple(year.toString(), row, it)]?.first }), row).single()
        }
        private fun columns(title: String) = when {
            title == "_APORTES" -> 6
            title == "_APP_TRANSACCIONES" -> 4
            title == "_PRESTAMOS_ELIMINADOS" -> 9
            title.startsWith("_MORA") -> if (title.endsWith("2026")) 170 else 366
            else -> 2000
        }
        private fun properties(title: String) = JSONObject().put("sheetId", sheets.getValue(title).first).put("title", title)
            .put("gridProperties", JSONObject().put("rowCount", sheets.getValue(title).second).put("columnCount", columns(title)))
        private data class Range(val title: String, val row: Int, val col: Int, val endRow: Int, val endCol: Int)
        private fun range(a1: String): Range {
            val m = Regex("'([^']+)'!([A-Z]+)([0-9]+)(?::([A-Z]+)([0-9]+))?").matchEntire(a1) ?: error(a1)
            fun column(s: String) = s.fold(0) { n, c -> n * 26 + c.code - 'A'.code + 1 }
            return Range(m.groupValues[1], m.groupValues[3].toInt(), column(m.groupValues[2]),
                m.groupValues[5].ifBlank { m.groupValues[3] }.toInt(), column(m.groupValues[4].ifBlank { m.groupValues[2] }))
        }
        private fun encoded(value: Any?): JSONObject = JSONObject().apply {
            if (value != null) put(when (value) { is Number -> "numberValue"; is Boolean -> "boolValue"; else -> "stringValue" }, value)
        }
        override fun get(url: String): JSONObject {
            if (posts > 0 && failVerification) throw SocketTimeoutException("verification unavailable")
            val query = url.substringAfter('?', "").split('&').filter { '=' in it }.map {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val ranges = query.filter { it.first == "ranges" }.map { it.second }
            if (url.contains("/values:batchGet")) return JSONObject().put("spreadsheetId", book).put("valueRanges", JSONArray(ranges.map { a1 ->
                val r = range(a1)
                val lastRow = cells.keys.filter { it.first == r.title && it.second in r.row..r.endRow }.maxOfOrNull { it.second }
                val rows = if (lastRow == null) emptyList() else (r.row..lastRow).map { row ->
                    (r.col..r.endCol).map { col -> cells[Triple(r.title, row, col)]?.first }
                }
                JSONObject().put("range", a1).put("values", JSONArray(rows))
            }))
            if (ranges.isEmpty()) return JSONObject().put("spreadsheetId", book)
                .put("properties", JSONObject().put("title", "Test").put("timeZone", "America/Lima"))
                .put("sheets", JSONArray(sheets.keys.map { JSONObject().put("properties", properties(it)) }))
                .put("namedRanges", JSONArray(names.map { JSONObject().put("namedRangeId", it).put("name", it.uppercase()) }))
            val groups = ranges.map(::range).groupBy { it.title }
            val result = JSONObject().put("spreadsheetId", book).put("sheets", JSONArray(groups.map { (title, values) ->
                JSONObject().put("properties", properties(title)).put("data", JSONArray(values.map { r ->
                    JSONObject().put("startRow", r.row - 1).put("startColumn", r.col - 1)
                        .put("rowData", JSONArray((r.row..r.endRow).map { row ->
                            JSONObject().put("values", JSONArray((r.col..r.endCol).map { col ->
                                val v = cells[Triple(title, row, col)]
                                JSONObject().put("effectiveValue", encoded(v?.first)).put("userEnteredValue",
                                    if (v?.second == null) encoded(v?.first) else JSONObject().put("formulaValue", v.second))
                                    .apply { if (title in listOf("2026", "2027") && col >= (if (title == "2026") 18 else 22) &&
                                        (col - (if (title == "2026") 18 else 22)) % 5 == 2) put("dataValidation", JSONObject().put("condition", JSONObject().put("type", "BOOLEAN"))) }
                            }))
                        }))
                }))
            }))
            if (ranges == listOf("'_CAPITAL'!B1:B1")) {
                val callback = afterCapitalRead; afterCapitalRead = null; callback?.invoke()
            }
            return result
        }
        override fun post(url: String, body: JSONObject): JSONObject {
            posts++; posted = body
            if (rejectPost) throw GoogleHttpException(400, "conflict")
            val requests = body.getJSONArray("requests")
            // Validate all uniqueness guards before applying any request, like an atomic Sheets batch.
            for (i in 0 until requests.length()) {
                requests.getJSONObject(i).optJSONObject("addNamedRange")?.getJSONObject("namedRange")?.let {
                    if (it.getString("namedRangeId") in names) throw GoogleHttpException(400, "revision conflict")
                }
            }
            fun title(sheetId: Int) = sheets.entries.single { it.value.first == sheetId }.key
            fun value(cell: JSONObject?): Any? = cell?.optJSONObject("userEnteredValue")?.let {
                it.opt("stringValue") ?: it.opt("numberValue") ?: it.opt("boolValue")
            }
            for (i in 0 until requests.length()) {
                val request = requests.getJSONObject(i)
                request.optJSONObject("addSheet")?.getJSONObject("properties")?.let { props ->
                    sheets[props.getString("title")] = props.getInt("sheetId") to 1000
                }
                request.optJSONObject("addNamedRange")?.getJSONObject("namedRange")?.let { names.add(it.getString("namedRangeId")) }
                request.optJSONObject("updateCells")?.let { update ->
                    val r = update.getJSONObject("range"); val sheet = title(r.getInt("sheetId"))
                    val rows = update.optJSONArray("rows")
                    for (row in r.getInt("startRowIndex") until r.getInt("endRowIndex")) {
                        val values = rows?.optJSONObject(row - r.getInt("startRowIndex"))?.optJSONArray("values")
                        for (col in r.getInt("startColumnIndex") until r.getInt("endColumnIndex")) {
                            val v = value(values?.optJSONObject(col - r.getInt("startColumnIndex")))
                            if (sheet == "_CAPITAL" && row == 0 && col == 1) {
                                val before = (cells[Triple(sheet, 1, 2)]!!.first as Number).toDouble()
                                val delta = (v as Number).toDouble() - before
                                put(sheet, 5, 10, (cells[Triple(sheet, 5, 10)]!!.first as Number).toDouble() + delta, "=CASH")
                            }
                            if (v == null) cells.remove(Triple(sheet, row + 1, col + 1)) else put(sheet, row + 1, col + 1, v)
                        }
                    }
                }
                request.optJSONObject("appendCells")?.let { append ->
                    val sheet = title(append.getInt("sheetId"))
                    var next = (cells.keys.filter { it.first == sheet }.maxOfOrNull { it.second } ?: 0) + 1
                    val rows = append.getJSONArray("rows")
                    for (j in 0 until rows.length()) {
                        val row = rows.getJSONObject(j).getJSONArray("values")
                        val values = (0 until row.length()).map { value(row.getJSONObject(it))!! }
                        values.forEachIndexed { col, v -> put(sheet, next, col + 1, v) }
                        next++
                        if (sheet == "_APP_TRANSACCIONES" && values.first() != "OPERACION") journal = values
                    }
                }
            }
            if (commitThenTimeout) throw SocketTimeoutException("lost acknowledgement")
            return JSONObject().put("spreadsheetId", book)
        }
    }

    private fun connection() = GoogleConnection(SheetsIdentity.PRODUCTION_ID, "ANOTHER_BOOK_ID_123456789012345", "", emptyList(), false)
    private fun pending(server: FakeSheets, loan: SheetLoan = server.loan()) = PendingRemoteWrite("deletion-operation-1", "subject", server.book,
        JSONObject().put("bookId", server.book).put("operationId", "deletion-operation-1").put("transport", "sheets").put("action", "deleteLoan")
            .put("loanId", loan.id).put("year", loan.year).put("version", loanDeletionVersion(loan)).toString())
    private fun repository(server: FakeSheets, cache: Cache = Cache()) = SheetsRepository(server.book, "subject", server, cache,
        if (server.book == SheetsIdentity.EMPTY_TEST_ID) connection().copy(testBookId = server.book) else connection(), GoogleTokenProvider { "fake" })
    private fun requests(server: FakeSheets) = server.posted!!.getJSONArray("requests").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun deletionArchivesAllChangesAndPreservesFormulasRowsAndOtherLoans() {
        val server = FakeSheets(); val cache = Cache()
        val receipt = repository(server, cache).write(pending(server), true)
        assertTrue(receipt.getBoolean("deleted")); assertTrue(cache.deleted); assertEquals(1, server.posts)
        val reqs = requests(server)
        assertFalse(reqs.any { it.has("deleteDimension") || it.has("deleteSheet") })
        val writes = reqs.mapNotNull { it.optJSONObject("updateCells") }
        val source = writes.filter { it.getJSONObject("range").getInt("sheetId") == 1 && it.getJSONObject("range").getInt("startColumnIndex") < 17 }
        assertEquals(setOf(1, 3, 4, 5, 6, 15), source.map { it.getJSONObject("range").getInt("startColumnIndex") }.toSet())
        assertTrue(writes.all { it.getString("fields") == "userEnteredValue" })
        assertTrue(writes.none { it.getJSONObject("range").getInt("sheetId") == 1 && it.getJSONObject("range").getInt("startRowIndex") == 6 })
        assertTrue(writes.none { it.getJSONObject("range").getInt("sheetId") == 2 && it.getJSONObject("range").getInt("startColumnIndex") < 17 })
        assertTrue(writes.any { it.getJSONObject("range").getInt("sheetId") == 5 && it.getJSONObject("range").getInt("startColumnIndex") == 3 })
        val archiveId = server.sheets.getValue("_PRESTAMOS_ELIMINADOS").first
        val archive = reqs.mapNotNull { it.optJSONObject("appendCells") }.single { it.getInt("sheetId") == archiveId && it.getJSONArray("rows").length() > 1 }
        assertEquals(writes.size + 1, archive.getJSONArray("rows").length())
        assertTrue(server.names.containsAll(listOf(deletedLoanKey(server.id), loanSlotKey(2026, 6), loanItemKey(2026, 1), loanAuxSlotKey(2027, 6))))
    }
    @Test fun deletingACarryViewResolvesAndClearsItsOriginal() {
        val server = FakeSheets(); val receipt = repository(server).write(pending(server, server.loan(2027)), true)
        assertEquals(server.id, receipt.getString("loanId")); assertTrue(server.names.contains(loanSlotKey(2026, 6)))
    }
    @Test fun staleConfirmationRejectsWithoutPosting() {
        val server = FakeSheets(); val operation = pending(server)
        server.put("2026", 6, 2, "Otro nombre")
        assertThrows(LoanDeletionRejected::class.java) { repository(server).write(operation, true) }; assertEquals(0, server.posts)
    }
    @Test fun formulaInAnInputPreventsAnyDeletion() {
        val server = FakeSheets(); val operation = pending(server)
        server.put("2026", 6, 4, 500, "=500")
        assertThrows(LoanDeletionRejected::class.java) { repository(server).write(operation, true) }; assertEquals(0, server.posts)
    }
    @Test fun lostAcknowledgementAndRestartUseReceiptWithoutAnotherPost() {
        val server = FakeSheets(); server.commitThenTimeout = true
        val operation = pending(server); val result = repository(server).write(operation, true)
        assertTrue(result.getBoolean("deleted"))
        assertTrue(repository(server).write(operation).getBoolean("deleted")); assertEquals(1, server.posts)
    }
    @Test fun uncertainFailureStaysPendingUntilReceiptCanBeRead() {
        val server = FakeSheets(); val operation = pending(server)
        server.commitThenTimeout = true; server.failVerification = true
        assertThrows(SocketTimeoutException::class.java) { repository(server).write(operation, true) }
        server.failVerification = false
        assertTrue(repository(server).write(operation).getBoolean("deleted")); assertEquals(1, server.posts)
    }
    @Test fun confirmedHttpRejectionCanBeReviewedWithoutPendingDeletion() {
        val server = FakeSheets(); server.rejectPost = true
        assertThrows(LoanDeletionRejected::class.java) { repository(server).write(pending(server), true) }
        assertEquals(1, server.posts); assertFalse(server.names.contains(deletedLoanKey(server.id)))
    }
    @Test fun differentOperationCannotDeleteTheSameLoanTwice() {
        val server = FakeSheets(); server.names.add(deletedLoanKey(server.id))
        assertThrows(LoanDeletionRejected::class.java) { repository(server).write(pending(server), true) }; assertEquals(0, server.posts)
    }
    @Test fun otherBooksCannotDeleteAndDeletedIdsCannotBeReused() {
        assertFalse(connection().canDeleteLoan(connection().testBookId))
        val server = FakeSheets(); repository(server).write(pending(server), true)
        assertTrue(server.names.contains(loanItemKey(2026, 1)))
        assertNotEquals(deletedLoanKey("2026-001"), deletedLoanKey("2027-001"))
        assertThrows(IllegalArgumentException::class.java) { deletedLoanKey("invalid") }
    }
    @Test fun financialAndTextChangesInvalidateConfirmationButRowMovementDoesNot() {
        val loan = FakeSheets().loan(); val version = loanDeletionVersion(loan)
        listOf(loan.copy(principal = loan.principal + 1), loan.copy(client = "Otra"), loan.copy(guarantee = "Otra"),
            loan.copy(paid = 1), loan.copy(collected = 1), loan.copy(debt = 1)).forEach { assertNotEquals(version, loanDeletionVersion(it)) }
        assertEquals(version, loanDeletionVersion(loan.copy(row = 20)))
    }
    @Test fun textSeparatorsCannotHideAChangeInTheConfirmation() {
        val loan = FakeSheets().loan()
        assertNotEquals(loanDeletionVersion(loan.copy(client = "Ana|Torres", guarantee = "Equipo")),
            loanDeletionVersion(loan.copy(client = "Ana", guarantee = "Torres|Equipo")))
    }
    @Test fun deletionReservesTheSameRevisionAsAnOverlappingPayment() {
        val due = LocalDate.of(2026, 7, 17); assumeTrue(today() >= due)
        val server = FakeSheets(due); val loan = server.loan()
        val payment = SheetPayment(loan, due, loan.first, true, calculatedLate(due, today()))
        val marker = paymentRevisionKey(directPaymentVersion(server.book, payment, 200))
        repository(server).write(pending(server), true)
        assertTrue(server.names.contains(marker))
    }
    @Test fun stalePaymentOfADeletedLoanIsRejectedInsteadOfStayingPending() {
        val due = LocalDate.of(2026, 7, 17); assumeTrue(today() >= due)
        val server = FakeSheets(due); val loan = server.loan()
        repository(server).write(pending(server), true)
        server.put("2026", 6, 2, "")
        val version = directPaymentVersion(server.book, SheetPayment(loan, due, loan.first, true, 0), 200)
        val body = JSONObject().put("bookId", server.book).put("operationId", "stale-payment-1").put("transport", "sheets").put("action", "pay")
            .put("edits", JSONArray().put(JSONObject().put("version", version.toJson()).put("unpaid", false).put("collectedLate", 0).put("paidOn", today().toString())))
        assertThrows(DirectPaymentRejected::class.java) {
            repository(server).write(PendingRemoteWrite("stale-payment-1", "subject", server.book, body.toString()), true)
        }
        assertEquals(1, server.posts)
    }
    @Test fun formulaInLinkedContributionIsRejectedWithoutAnyWrite() {
        val server = FakeSheets(); server.put("_APORTES", 2, 4, 500, "=500")
        assertThrows(LoanDeletionRejected::class.java) { repository(server).write(pending(server), true) }
        assertEquals(0, server.posts)
    }
    @Test fun deletingInThePinnedTestBookNeverTargetsTheOfficialBook() {
        val server = FakeSheets(book = SheetsIdentity.EMPTY_TEST_ID)
        val receipt = repository(server).write(pending(server), true)
        assertEquals(SheetsIdentity.EMPTY_TEST_ID, receipt.getString("bookId"))
        assertEquals(1, server.posts)
        assertFalse(server.posted.toString().contains(SheetsIdentity.PRODUCTION_ID))
    }
}

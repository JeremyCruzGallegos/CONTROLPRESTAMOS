package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Exercises the actual atomic write, rather than just its date preview. No remote book is used. */
class BackdatedLoanRepositoryTest {
    private class Cache : SheetCache {
        override fun read(): JSONObject? = null
        override fun write(value: JSONObject) = Unit
        override fun delete() = Unit
    }
    private fun server(book: String, disbursedOn: LocalDate): LoanDeletionTest.FakeSheets =
        LoanDeletionTest.FakeSheets(disbursedOn.plusDays(1), book).apply {
            cells.keys.filter { (sheet, row, _) -> row >= 6 && (sheet == "2026" || sheet.startsWith("_MORA")) ||
                sheet == "_APORTES" && row >= 2 }.toList().forEach(cells::remove)
            sheets["_CAPITAL"] = 6 to 8
            sheets["CAJA"] = 7 to 500
            for (offset in 0..ChronoUnit.DAYS.between(disbursedOn, today())) {
                val row = offset.toInt() + 6
                listOf<Any>(sheetSerial(disbursedOn.plusDays(offset)), 0, 0, 0, 1000)
                    .forEachIndexed { col, value -> put("CAJA", row, col + 1, value) }
            }
            put("_CAPITAL", 1, 2, 1000)
            put("_CAPITAL", 4, 10, "CAJA")
            listOf<Any>(today().year, sheetSerial(today()), 1000, 0, 0, 0, 0, 0, 0, 1000, 0, 1000, 0)
                .forEachIndexed { i, v -> put("_CAPITAL", 5, i + 1, v) }
            put("2026", 6, 1, 1)
            listOf(3, 9, 10).forEach { put("2026", 6, it, 0, "=STATE") }
            mapOf(8 to "=D6+D6*(E6*F6)", 11 to "=D6*(E6/30)", 12 to "=ROUNDDOWN(K6,1)",
                13 to "=IFERROR(ROUNDDOWN(D6/(F6*30)+L6,1),\"\")", 14 to "=H6-(M6*(F6*30))+M6",
                15 to "=ABS(H6-(N6+(M6*((F6*30)-1))))", 17 to "=IF(OR(B6=\"\",P6=\"\",F6<=0),\"\",P6+F6*30-1)")
                .forEach { (col, formula) -> put("2026", 6, col, 0, formula) }
            repeat(30) { index ->
                val due = disbursedOn.plusDays(index + 1L)
                val col = 18 + ChronoUnit.DAYS.between(LocalDate.of(2026, 7, 16), due).toInt() * 5
                (0..4).forEach { offset ->
                    put("2026", 6, col + offset, if (offset == 2) false else 0, if (offset == 2) null else "=CALENDAR")
                }
            }
        }
    private fun repository(server: LoanDeletionTest.FakeSheets) = SheetsRepository(server.book, "account", server, Cache(),
        GoogleConnection(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "", emptyList(), false), GoogleTokenProvider { "fake" })
    private fun pending(book: String, date: LocalDate) = PendingRemoteWrite("backdated-loan-001", "account", book,
        JSONObject().put("bookId", book).put("operationId", "backdated-loan-001").put("action", "createLoan").put("transport", "sheets")
            .put("disbursedOn", date.toString()).put("loan", JSONObject().put("client", "Cliente de prueba")
                .put("principal", 500).put("rate", 0.02).put("months", 1).put("guarantee", "")
                .put("origin", "Del capital").put("contribution", 0)).toString())
    private fun date(): LocalDate {
        // The repository uses the real Lima clock, as do the existing transport tests.
        assumeTrue(today() in LocalDate.of(2026, 7, 26)..LocalDate.of(2026, 12, 1))
        return today().minusDays(10)
    }

    @Test fun bothBooksWriteTheOriginalDateAndMarkDueInstallmentsPendingInOneBatch() {
        val date = date()
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID)) {
            val server = server(book, date)
            val result = repository(server).write(pending(book, date), true)
            assertEquals(date.plusDays(1).toString(), result.getString("firstDue"))
            assertEquals("2026-001", result.getString("loanId")); assertEquals(1, server.posts)
            assertEquals(sheetSerial(date.plusDays(1)), (server.cells[Triple("2026", 6, 16)]!!.first as Number).toLong())
            repeat(30) { index ->
                val due = date.plusDays(index + 1L)
                val check = 20 + ChronoUnit.DAYS.between(LocalDate.of(2026, 7, 16), due).toInt() * 5
                assertEquals("Cuota de $due", due <= today(), server.cells[Triple("2026", 6, check)]!!.first)
            }
            assertEquals(sheetSerial(date), (server.cells[Triple("_APORTES", 2, 3)]!!.first as Number).toLong())
            assertTrue(server.journal.isNotEmpty())
        }
    }
    @Test fun retryAfterLostResponseDoesNotCreateAnotherBackdatedLoan() {
        val date = date(); val server = server(SheetsIdentity.EMPTY_TEST_ID, date)
        server.commitThenTimeout = true
        val operation = pending(server.book, date)
        val first = repository(server).write(operation, true)
        val retry = repository(server).write(operation)
        assertEquals(first.getString("loanId"), retry.getString("loanId")); assertEquals(1, server.posts)
    }
    @Test fun futureDateIsRejectedBeforeSendingAnyChanges() {
        val date = date(); val server = server(SheetsIdentity.EMPTY_TEST_ID, date)
        assertThrows(DirectLoanRejected::class.java) { repository(server).write(pending(server.book, today().plusDays(1)), true) }
        assertEquals(0, server.posts)
    }
    @Test fun confirmedDeletedRowAndHelperAreReusedWithANewIdentityInBothBooks() {
        val date = date()
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID)) {
            val server = server(book, date)
            server.names.addAll(listOf(deletedLoanKey("2026-001"), loanSlotKey(2026, 6), loanItemKey(2026, 1), loanAuxSlotKey(2026, 6)))
            server.put("_MORA_COBRADA_2026", 6, 1, 1)
            server.put("2026", 6, 13, 0, "=16.90")
            server.put("2026", 6, 14, 0, "=19.90")
            server.put("2026", 6, 15, 0, "=ROUND(H6-510.00,2)")
            val result = repository(server).write(pending(book, date), true)
            assertEquals("2026-002", result.getString("loanId")); assertEquals(1, server.posts)
            assertEquals(2, (server.cells[Triple("2026", 6, 1)]!!.first as Number).toInt())
            assertEquals(2, (server.cells[Triple("_MORA_COBRADA_2026", 6, 1)]!!.first as Number).toInt())
            assertTrue(server.names.contains(loanSlotRevisionKey(2026, 6, "2026-001")))
            assertTrue(server.names.contains(loanAuxRevisionKey(2026, 6, "2026-001")))
            val body = server.posted.toString()
            assertTrue(body.contains("ROUNDDOWN")); assertTrue(body.contains("*1.00"))
        }
    }

    @Test fun initialPaidSelectionWritesStatesDatesAndReceiptAtomicallyInBothBooks() {
        val date=date()
        for(book in listOf(SheetsIdentity.PRODUCTION_ID,SheetsIdentity.EMPTY_TEST_ID)) {
            val server=server(book,date)
            val old=pending(book,date)
            val operation=old.copy(payload=JSONObject(old.payload).put("pastPaymentsPaid",true).toString())
            val receipt=repository(server).write(operation,true)
            assertEquals(1,server.posts)
            assertEquals(672.0,receipt.getDouble("cashAfter"),0.001) // 500 remaining + 19.90 + 9 x 16.90.
            repeat(30) { index ->
                val due=date.plusDays(index+1L)
                val check=20+ChronoUnit.DAYS.between(LocalDate.of(2026,7,16),due).toInt()*5
                assertEquals(false,server.cells[Triple("2026",6,check)]!!.first)
            }
            val requests=server.posted!!.getJSONArray("requests")
            val auditId=server.sheets.getValue("_PAGOS_HISTORIAL").first
            val audit=(0 until requests.length()).mapNotNull { requests.getJSONObject(it).optJSONObject("appendCells") }
                .filter { it.getInt("sheetId")==auditId }.flatMap { append ->
                    val rows=append.getJSONArray("rows")
                    (0 until rows.length()).map { row -> rows.getJSONObject(row).getJSONArray("values") }
                }.filter { it.getJSONObject(0).optJSONObject("userEnteredValue")?.optString("stringValue")!="REGISTRADO" }
            assertEquals(10,audit.size)
            audit.forEach { values ->
                assertEquals(values.getJSONObject(4).toString(),values.getJSONObject(9).toString())
                assertFalse(values.getJSONObject(6).getJSONObject("userEnteredValue").getBoolean("boolValue"))
            }
            repository(server).write(operation)
            assertEquals(1,server.posts)
        }
    }
    @Test fun automaticContributionIsSavedWithTheLoanAndNotRepeatedAfterLostResponse() {
        val date = date()
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID)) {
            val server = server(book, date)
            val original = pending(book, date)
            val body = JSONObject(original.payload)
            body.getJSONObject("loan").put("principal", 1500)
            val operation = original.copy(payload = body.toString())
            server.commitThenTimeout = true
            val receipt = repository(server).write(operation, true)
            assertEquals(50_000L, receipt.getLong("contributionCents"))
            assertEquals(0.0, receipt.getDouble("cashAfter"), 0.001)
            assertEquals(1, server.posts)
            assertEquals(500.0, (server.cells[Triple("_APORTES", 2, 4)]!!.first as Number).toDouble(), 0.001)
            assertEquals(receipt.toString(), repository(server).write(operation).toString())
            assertEquals(1, server.posts)
        }
    }

}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class CapitalEditTest {
    private class Cache : SheetCache {
        var deleted = false
        override fun read(): JSONObject? = null
        override fun write(value: JSONObject) = Unit
        override fun delete() { deleted = true }
    }
    private fun server(book: String = SheetsIdentity.PRODUCTION_ID, initial: Number = 7000, cash: Number = 6000) =
        LoanDeletionTest.FakeSheets(book = book).apply {
            sheets["_CAPITAL"] = 6 to 2410
            put("_CAPITAL", 1, 1, "CAPITAL INICIAL"); put("_CAPITAL", 1, 2, initial)
            put("_CAPITAL", 4, 10, "CAJA")
            listOf<Any>(today().year, sheetSerial(today()), initial, 0, 1000, 0, 0, 0, 0, cash, 1000, 7000, 0)
                .forEachIndexed { index, value -> put("_CAPITAL", 5, index + 1, value, if (index > 0) "=SUMMARY" else null) }
        }
    private fun repo(server: LoanDeletionTest.FakeSheets, cache: Cache = Cache()) = SheetsRepository(server.book, "account", server,
        cache, GoogleConnection(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "", emptyList(), false), GoogleTokenProvider { "fake" })
    private fun pending(server: LoanDeletionTest.FakeSheets, before: Long = 700000, after: Long = 800000, cash: Long = 600000,
        operation: String = "capital-operation-1") = PendingRemoteWrite(operation, "account", server.book,
        JSONObject().put("bookId", server.book).put("operationId", operation).put("action", "setCapital").put("transport", "sheets")
            .put("year", today().year).put("beforeCents", before).put("afterCents", after).put("cashBeforeCents", cash).put("actor", "Test account").toString())

    @Test fun firstCapitalFundsAnEmptyTestBook() {
        val server = server(SheetsIdentity.EMPTY_TEST_ID, 0, 0); val cache = Cache()
        val receipt = repo(server, cache).write(pending(server, 0, 100000, 0), true)
        assertEquals(100000L, receipt.getLong("capitalAfterCents")); assertEquals(100000L, receipt.getLong("cashAfterCents"))
        assertTrue(cache.deleted); assertEquals(SheetsIdentity.EMPTY_TEST_ID, receipt.getString("bookId"))
        assertEquals(1000, server.cells[Triple("_CAPITAL", 1, 2)]!!.first.toString().toBigDecimal().toInt())
        assertFalse(server.posted.toString().contains(SheetsIdentity.PRODUCTION_ID))
    }
    @Test fun officialCapitalChangesOnlyB1AndCreatesAuditAndReceipt() {
        val server = server(); val receipt = repo(server).write(pending(server), true)
        assertEquals(700000L, receipt.getLong("cashAfterCents"))
        val requests = server.posted!!.getJSONArray("requests")
        val updates = (0 until requests.length()).map { requests.getJSONObject(it) }.mapNotNull { it.optJSONObject("updateCells") }
        assertEquals(1, updates.size)
        val update = updates.single(); val r = update.getJSONObject("range")
        assertEquals(6, r.getInt("sheetId")); assertEquals(0, r.getInt("startRowIndex")); assertEquals(1, r.getInt("endRowIndex"))
        assertEquals(1, r.getInt("startColumnIndex")); assertEquals(2, r.getInt("endColumnIndex")); assertEquals("userEnteredValue", update.getString("fields"))
        assertEquals(7000, (server.cells[Triple("_CAPITAL_HISTORIAL", 2, 4)]!!.first as Number).toInt())
        assertEquals(8000, (server.cells[Triple("_CAPITAL_HISTORIAL", 2, 5)]!!.first as Number).toInt())
        assertTrue(server.journal.isNotEmpty())
    }
    @Test fun staleCapitalOrCashIsRejectedBeforeAnyPost() {
        val server = server()
        assertThrows(CapitalEditRejected::class.java) { repo(server).write(pending(server, before = 650000), true) }
        assertThrows(CapitalEditRejected::class.java) { repo(server).write(pending(server, cash = 599999), true) }
        assertEquals(0, server.posts)
    }
    @Test fun formulasAndValidationInTheCapitalCellAreProtected() {
        val server = server(); server.put("_CAPITAL", 1, 2, 7000, "=7000")
        assertThrows(CapitalEditRejected::class.java) { repo(server).write(pending(server), true) }
        assertEquals(0, server.posts)
        val summary = SheetSummary(2026, today(), 700000, 700000, 0, 100000, 0, 0, 0, 0, 600000, 100000)
        assertThrows(IllegalArgumentException::class.java) {
            validateCapitalChange(CapitalChange(2026, 700000, 800000, 600000), summary, DirectLoanCell(7000, validation = "NUMBER_GREATER_THAN_EQ"))
        }
    }
    @Test fun negativesNoChangeAndInsufficientCashCannotBeSaved() {
        assertThrows(IllegalArgumentException::class.java) { CapitalChange(2026, 700000, -1, 600000) }
        assertThrows(IllegalArgumentException::class.java) { CapitalChange(2026, 700000, 700000, 600000) }
        assertThrows(IllegalArgumentException::class.java) { CapitalChange(2026, 700000, 0, 600000) }
        assertThrows(IllegalArgumentException::class.java) { CapitalChange(2026, 0, MAX_INITIAL_CAPITAL + 1, 0) }
        assertEquals(0L, CapitalChange(2026, 700000, 100000, 600000).cashAfter)
    }
    @Test fun lostAcknowledgementAndRestartReadTheReceiptBeforeOldValues() {
        val server = server(); server.commitThenTimeout = true
        val operation = pending(server)
        assertEquals(800000L, repo(server).write(operation, true).getLong("capitalAfterCents"))
        assertEquals(800000L, repo(server).write(operation).getLong("capitalAfterCents")); assertEquals(1, server.posts)
    }
    @Test fun failedVerificationKeepsAnUnknownOutcomeForTheSameRetry() {
        val server = server(); val operation = pending(server)
        server.commitThenTimeout = true; server.failVerification = true
        assertThrows(SocketTimeoutException::class.java) { repo(server).write(operation, true) }
        server.failVerification = false
        assertEquals(800000L, repo(server).write(operation).getLong("capitalAfterCents")); assertEquals(1, server.posts)
    }
    @Test fun aConfirmedConflictLeavesTheCapitalUnchanged() {
        val server = server(); server.rejectPost = true
        assertThrows(CapitalEditRejected::class.java) { repo(server).write(pending(server), true) }
        assertEquals(7000, (server.cells[Triple("_CAPITAL", 1, 2)]!!.first as Number).toInt())
        assertFalse(server.sheets.containsKey("_CAPITAL_HISTORIAL"))
    }
    @Test fun repeatedAmountsUseNewRevisionsRatherThanReusingOldLocks() {
        val server = server()
        repo(server).write(pending(server), true)
        repo(server).write(pending(server, 800000, 700000, 700000, "capital-operation-2"), true)
        repo(server).write(pending(server, 700000, 750000, 600000, "capital-operation-3"), true)
        assertEquals(3, server.posts)
        assertTrue(server.names.contains(capitalRevisionKey(server.book, 700000, "initial")))
        assertTrue(server.names.contains(capitalRevisionKey(server.book, 700000, "capital-operation-2")))
    }
    @Test fun concurrentChangesShareARevisionGuardAndRejectAtomically() {
        val server = server(); server.names.add(capitalRevisionKey(server.book, 700000, "initial"))
        assertThrows(CapitalEditRejected::class.java) { repo(server).write(pending(server), true) }
        assertEquals(7000, (server.cells[Triple("_CAPITAL", 1, 2)]!!.first as Number).toInt())
        assertFalse(server.sheets.containsKey("_CAPITAL_HISTORIAL"))
    }
    @Test fun unconfiguredBooksCannotEditCapital() {
        val server = server("UNKNOWN_BOOK_ID_1234567890")
        assertThrows(IllegalArgumentException::class.java) { repo(server).write(pending(server), true) }
        assertEquals(0, server.posts)
    }
    @Test fun capitalAndDeletionPendingOperationsBlockPaymentDrafts() {
        val server = server(); assertTrue(pendingLocksPayments(pending(server)))
        assertFalse(pendingLocksPayments(null))
        val payment = pending(server).copy(payload = JSONObject().put("action", "pay").toString())
        assertFalse(pendingLocksPayments(payment))
    }
    @Test fun fractionalCentsCannotBeSilentlyTruncatedFromThePayload() {
        val value = JSONObject(pending(server()).payload).put("afterCents", 800000.5)
        assertThrows(ArithmeticException::class.java) { capitalChange(value) }
    }
    @Test fun anotherEditAfterReadingTheCapitalCannotBeOverwrittenUsingItsNewRevision() {
        val server = server()
        server.sheets["_CAPITAL_HISTORIAL"] = 7 to 1000
        server.sheets["_APP_TRANSACCIONES"] = 8 to 1000
        listOf("REGISTRADO", "OPERACION", "USUARIO", "CAPITAL ANTERIOR", "CAPITAL NUEVO", "DIFERENCIA", "CAJA ANTES", "CAJA DESPUES", "AÑO")
            .forEachIndexed { i, v -> server.put("_CAPITAL_HISTORIAL", 1, i + 1, v) }
        listOf("OPERACION", "HUELLA", "REGISTRADO", "RESULTADO JSON")
            .forEachIndexed { i, v -> server.put("_APP_TRANSACCIONES", 1, i + 1, v) }
        server.put("_CAPITAL_HISTORIAL", 2, 1, "2026-10-15 10:00:00")
        server.put("_CAPITAL_HISTORIAL", 2, 2, "capital-earlier-operation")
        server.afterCapitalRead = {
            repo(server).write(pending(server, after = 900000, operation = "capital-concurrent-operation"), true)
        }
        assertThrows(CapitalEditRejected::class.java) { repo(server).write(pending(server), true) }
        assertEquals(9000, (server.cells[Triple("_CAPITAL", 1, 2)]!!.first as Number).toInt())
        assertEquals("capital-concurrent-operation", server.cells[Triple("_CAPITAL_HISTORIAL", 3, 2)]!!.first)
        assertNull(server.cells[Triple("_CAPITAL_HISTORIAL", 4, 2)])
    }

}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectCashMovementsTest {
    private val day = today()
    private fun server() = LoanDeletionTest.FakeSheets().apply {
        sheets["_CAPITAL"] = 6 to 2410; sheets["CAJA"] = 7 to 910
        put("_CAPITAL", 1, 1, "CAPITAL INICIAL"); put("_CAPITAL", 1, 2, 1000)
        put("_CAPITAL", 4, 10, "CAJA")
        listOf<Any>(day.year, sheetSerial(day), 1000, 0, 500, 0, 0, 0, 0, 500, 500, 1000, 0)
            .forEachIndexed { i, v -> put("_CAPITAL", 5, i + 1, v) }
        listOf<Any>(sheetSerial(day), 0, 0, 0, 500).forEachIndexed { i, v -> put("CAJA", 6, i + 1, v) }
    }
    private fun repo(server: LoanDeletionTest.FakeSheets) = SheetsRepository(server.book, "account", server, MemorySheetCache(),
        GoogleConnection(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "", emptyList(), false), GoogleTokenProvider { "fake" })
    private fun pending(server: LoanDeletionTest.FakeSheets, type: CashMovementType = CashMovementType.WITHDRAWAL, amount: Long = 10000,
        id: String = "cash-operation-123") = PendingRemoteWrite(id, "account", server.book, JSONObject()
        .put("bookId", server.book).put("operationId", id).put("action", "cashMovement").put("transport", "sheets")
        .put("type", type.name).put("amountCents", amount).put("date", day.toString()).put("description", "Compra de equipo").toString())
    @Test fun withdrawalUsesTheDatedCapitalLedgerAndWritesTheReceiptAtomically() {
        val server = server(); val repo = repo(server); val operation = pending(server)
        val receipt = repo.write(operation)
        assertEquals(40000L, receipt.getLong("cashAfterCents"))
        assertEquals(-100.0, (server.cells[Triple("_APORTES", 3, 4)]!!.first as Number).toDouble(), 0.0)
        assertEquals(sheetSerial(day), (server.cells[Triple("_APORTES", 3, 3)]!!.first as Number).toLong())
        assertEquals("", server.cells[Triple("_APORTES", 3, 2)]!!.first)
        assertEquals(1000, server.cells[Triple("_CAPITAL", 1, 2)]!!.first)
        assertEquals("Compra de equipo", repo.cashMovements().single().description)
        assertTrue(server.names.any { it.startsWith("cp_cash_rev_") })
        assertEquals(1, server.posts)
    }
    @Test fun depositIsPositiveCapitalAndRetryDoesNotCreateASecondEntry() {
        val server = server(); val repo = repo(server); val operation = pending(server, CashMovementType.DEPOSIT)
        repo.write(operation); repo.write(operation)
        assertEquals(100.0, (server.cells[Triple("_APORTES", 3, 4)]!!.first as Number).toDouble(), 0.0)
        assertEquals(1, server.posts); assertEquals(1, repo.cashMovements().size)
    }
    @Test fun cashMovementSurvivesLostNetworkAcknowledgmentWithoutDuplication() {
        val server = server(); server.commitThenTimeout = true
        val repo = repo(server); val operation = pending(server)
        repo.write(operation); repo.write(operation)
        assertEquals(1, server.posts); assertEquals(1, repo.cashMovements().size)
    }
    @Test fun manualLedgerEditsAreDetectedBeforePublishingInconsistentHistory() {
        val server = server(); val repo = repo(server); repo.write(pending(server))
        server.put("_APORTES", 3, 4, -200)
        assertThrows(IllegalArgumentException::class.java) { repo.cashMovements() }
    }
    @Test fun overdraftIsRejectedWithoutAnyRemoteMutation() {
        val server = server()
        assertThrows(DirectPaymentRejected::class.java) { repo(server).write(pending(server, amount = 50001)) }
        assertEquals(0, server.posts); assertNull(server.cells[Triple("_APORTES", 3, 4)])
    }
    @Test fun rejectedAtomicBatchLeavesTheCashAndJournalUntouched() {
        val server = server(); server.rejectPost = true
        assertThrows(DirectPaymentRejected::class.java) { repo(server).write(pending(server)) }
        assertNull(server.cells[Triple("_APORTES", 3, 4)])
        assertTrue(server.journal.isEmpty())
    }
}

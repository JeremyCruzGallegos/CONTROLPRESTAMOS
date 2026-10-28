package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class BatchLoanDeletionTest {
    private val day = today()
    private val book = SheetsIdentity.EMPTY_TEST_ID
    private fun initial(): LocalBookView {
        val records = (1..3).map { number -> SimpleRecord(number + 7,
            Rules.createLoan("${day.year}-${number.toString().padStart(3, '0')}",
                LoanRequest("Cliente $number", 30000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(4)), 100, false, "rev") }
        val model = SimpleBook("Pruebas", 200000, "rev", day.plusMonths(3), records, Instant.now())
        val catalog = records.map { it.view(day) }
        return LocalBookView(model.preload(day.year, day, book), catalog,
            records.flatMap { record -> record.loan.installments.map { model.payment(record, it, day, book) } },
            model.cash(day), catalog.associate { it.id to 0L }, true)
    }
    private fun command(loans: List<SheetLoan>) = JSONObject().put("action", "deleteLoans")
        .put("loans", JSONArray(loans.map { loan -> JSONObject().put("loanId", loan.id).put("year", loan.year).put("version", loanDeletionVersion(loan)) }))
    @Test fun selectedLoansAreDeletedInOneLocalCommitAndTheOthersAndInitialCapitalArePreserved() {
        val file = MemorySheetCache(); val queue = LocalOperations(file, "account", book); val before = initial(); queue.refresh(before)
        queue.enqueue(command(before.catalog.take(2)), "actor")
        val restored = LocalOperations(file, "account", book).read()
        assertEquals(listOf(before.catalog.last()), restored.view!!.catalog)
        assertTrue(restored.view.rows.all { it.loan.id == before.catalog.last().id })
        assertEquals(before.data.summary.initial, restored.view.data.summary.initial)
        assertEquals(before.data.summary.cash + 60000, restored.view.data.summary.cash)
        assertEquals(2, restored.operations.size)
        assertTrue(restored.operations.all { it.body.getString("action") == "deleteLoan" })
    }
    @Test fun aStaleSecondLoanRejectsTheWholeLocalSelectionBeforeAnyDeletionIsPublished() {
        val file = MemorySheetCache(); val queue = LocalOperations(file, "account", book); val before = initial(); queue.refresh(before)
        val input = command(before.catalog.take(2))
        input.getJSONArray("loans").getJSONObject(1).put("version", "outdated")
        assertThrows(IllegalArgumentException::class.java) { queue.enqueue(input, "actor") }
        assertEquals(before.catalog, queue.read().view!!.catalog)
        assertEquals(before.data.summary, queue.read().view!!.data.summary)
        assertTrue(queue.read().operations.isEmpty())
    }
    @Test fun diskFailurePreservesEverySelectedLoanAndNoPartialCommandList() {
        val file = MemorySheetCache(); val queue = LocalOperations(file, "account", book); val before = initial(); queue.refresh(before)
        file.failWrite = true
        assertThrows(IllegalStateException::class.java) { queue.enqueue(command(before.catalog.take(2)), "actor") }
        assertEquals(before.catalog, queue.read().view!!.catalog)
        assertTrue(queue.read().operations.isEmpty())
    }
    @Test fun repeatedIdsAndUnverifiedContributionsCannotSilentlyDeletePartOfASelection() {
        val file = MemorySheetCache(); val queue = LocalOperations(file, "account", book); val before = initial(); queue.refresh(before)
        assertThrows(IllegalArgumentException::class.java) { queue.enqueue(command(listOf(before.catalog.first(), before.catalog.first())), "actor") }
        val withoutContribution = before.copy(contributions = before.contributions - before.catalog[1].id)
        queue.refresh(withoutContribution)
        assertThrows(IllegalArgumentException::class.java) { queue.enqueue(command(before.catalog.take(2)), "actor") }
        assertEquals(before.catalog, queue.read().view!!.catalog)
        assertTrue(queue.read().operations.isEmpty())
    }
}

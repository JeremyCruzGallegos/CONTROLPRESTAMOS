package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class SimpleCalendarPresentationTest {
    private val day = LocalDate.of(2026, 10, 21)
    private fun record(deleted: Boolean = false): SimpleRecord {
        val loan = Rules.createLoan("2026-001", LoanRequest("Cliente", 50_000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(3))
        return SimpleRecord(8, loan, 0, deleted, "revision")
    }
    private fun book(records: List<SimpleRecord>) = SimpleBook("Pruebas", 100_000, "revision", LocalDate.of(2026,12,31), records, Instant.EPOCH, headerRow = 7)
    @Test fun withoutActiveLoansTheCalendarStartsTodayAndIncludesFutureDays() {
        val empty = book(emptyList())
        assertEquals(day, simpleVisibleCalendarDays(empty, day).min())
        assertEquals(empty.until, simpleVisibleCalendarDays(empty, day).max())
        assertEquals(day.plusDays(1), simpleVisibleCalendarDays(empty, day.plusDays(1)).min())
    }
    @Test fun allDaysFromTheOldestActiveLoanRemainVisibleIncludingFutureAndEmptyDays() {
        val book = book(listOf(record()))
        val visible = simpleVisibleCalendarDays(book, day)
        assertEquals(day.minusDays(2), visible.min()); assertEquals(book.until, visible.max())
        assertTrue(day.plusDays(1) in visible); assertTrue(book.until in visible)
        assertFalse(day.minusDays(3) in visible)
    }
    @Test fun paidHistoryStaysVisibleWhileTheLoanStillHasInstallmentsPending() {
        val r = record(); val due = day.minusDays(2)
        val paid = r.copy(loan = r.loan.copy(installments = r.loan.installments.map { if (it.due == due) it.copy(paidOn = day) else it }))
        assertTrue(due in simpleVisibleCalendarDays(book(listOf(paid)), day))
        assertTrue(due in simpleVisibleCalendarDays(book(listOf(paid, r.copy(row=9))), day))
        assertTrue(due in simpleVisibleCalendarDays(book(listOf(r)), day))
        assertEquals(day, simpleVisibleCalendarDays(book(listOf(r.copy(deleted=true))), day).min())
    }
    @Test fun unverifiedPaidHistoryIsRetainedAndFullyPaidOrDeletedLoansDoNotSetTheStart() {
        val r = record().copy(unverifiedPaid = setOf(day.minusDays(2)))
        assertTrue(day.minusDays(2) in simpleVisibleCalendarDays(book(listOf(r)), day))
        val settled = r.copy(loan=r.loan.copy(installments=r.loan.installments.map { it.copy(paidOn=it.due) }))
        val later=day.plusDays(35)
        assertEquals(later,simpleVisibleCalendarDays(book(listOf(settled)),later).min())
    }
    @Test fun presentationPreservesValuesAndExpandedDetailsAndRotatesTheActualHeaderUpwards() {
        val b = book(listOf(record())); val requests = simpleCalendarPresentationRequests(22, b, day)
        val dimensions = requests.mapNotNull { it.optJSONObject("updateDimensionProperties") }
        val column = simpleDayColumn(day)
        assertTrue(dimensions.any { it.getJSONObject("range").getInt("startIndex") == column && !it.getJSONObject("properties").getBoolean("hiddenByUser") })
        assertFalse(dimensions.any {
            val r = it.getJSONObject("range")
            r.getInt("startIndex") <= column+1 && r.getInt("endIndex") > column+1
        })
        assertTrue(requests.all { it.has("updateDimensionProperties") || it.has("repeatCell") })
        val format = requests.single { it.has("repeatCell") }.getJSONObject("repeatCell")
        assertEquals(6, format.getJSONObject("range").getInt("startRowIndex"))
        val rotation = format.getJSONObject("cell").getJSONObject("userEnteredFormat").getJSONObject("textRotation")
        assertEquals(90, rotation.getInt("angle")); assertFalse(rotation.has("vertical"))
    }
    @Test fun yearEndKeepsAContinuousCalendarFromTheFirstActiveLoan() {
        val b = book(emptyList()).copy(until=LocalDate.of(2027,12,31))
        assertEquals(LocalDate.of(2027,1,1), simpleVisibleCalendarDays(b,LocalDate.of(2027,1,1)).min())
        assertEquals(b.until, simpleVisibleCalendarDays(b,LocalDate.of(2027,1,1)).max())
        assertTrue(simpleVisibleCalendarDays(book(emptyList()),LocalDate.of(2027,1,1)).isEmpty())
    }
    @Test fun addingAnOlderLoanMovesTheStartBackAndRemovingItMovesTheStartForward() {
        val recent=record()
        val old=recent.copy(row=9,loan=Rules.createLoan("2026-002",LoanRequest("Anterior",50_000,BigDecimal("2"),2,"",Funding.CAPITAL,0),day.minusDays(20)))
        assertEquals(old.loan.installments.first().due,simpleVisibleCalendarDays(book(listOf(recent,old)),day).min())
        assertEquals(recent.loan.installments.first().due,simpleVisibleCalendarDays(book(listOf(recent,old.copy(deleted=true))),day).min())
    }
}

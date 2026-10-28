package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TodayCollectionsTest {
    private val day = LocalDate.of(2026, 10, 16)
    private val loan = SheetLoan("2026-001", "1", 2026, 6, "Cliente", 50000, BigDecimal("2"), 1, "",
        51000, 0, 51000, 1690, 1990, day, day.plusDays(29), 0, 30)

    @Test fun todaySeparatesScheduledPendingAndPaidWithoutAddingOverdueOrFutureInstallments() {
        val second = loan.copy(id = "2026-002", rawId = "2", row = 7)
        val rows = listOf(SheetPayment(loan, day, 1990, true, 0), SheetPayment(second, day, 1690, false, 100),
            SheetPayment(loan, day.minusDays(1), 99999, true, 100), SheetPayment(loan, day.plusDays(1), 99999, true, 0))
        val totals = todayCollections(rows, day)
        assertEquals(3680L, totals.scheduled); assertEquals(1990L, totals.pending)
        assertEquals(1790L, totals.collected); assertEquals(2, totals.totalCount); assertEquals(1, totals.pendingCount)
        assertEquals(1690f / 3680f, totals.progress, 0.0001f)
    }
    @Test fun repeatedViewsOfTheSameLoanAreOnlyCountedOnce() {
        val payment = SheetPayment(loan, day, 1990, true, 0)
        val totals = todayCollections(listOf(payment, payment.copy(loan = loan.copy(year = 2027, row = 406))), day)
        assertEquals(1990L, totals.pending); assertEquals(1, totals.totalCount)
    }
    @Test fun emptyAndFullyCollectedSchedulesHaveFiniteProgress() {
        val empty = todayCollections(emptyList(), day)
        assertEquals(0L, empty.scheduled); assertEquals(0f, empty.progress, 0f)
        val paid = todayCollections(listOf(SheetPayment(loan, day, 1990, false, 0)), day)
        assertEquals(0L, paid.pending); assertEquals(1990L, paid.collected); assertEquals(1f, paid.progress, 0f)
    }
}

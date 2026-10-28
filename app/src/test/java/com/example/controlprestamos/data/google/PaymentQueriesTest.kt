package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class PaymentQueriesTest {
    private val today = LocalDate.of(2027, 3, 1)
    private fun loan(id: Int, client: String, paid: Int = 0) = parseSheetLoans(2026, listOf(listOf(
        id, client, "$paid/30", 500, 0.02, 1, "Documento", 510, 0, 510,
        0.3333333, 0.3, 16.9, 19.9, 0, sheetSerial(today.minusDays(29)), sheetSerial(today)))).single()

    @Test fun singleDayKeepsBothEndpoints() {
        assertEquals(listOf(PaymentWindow(today, today)), paymentWindows(today, today, today))
    }
    @Test fun longRangeCrossesYearsWithoutMissingOrRepeatingAnyDay() {
        val from = LocalDate.of(2026, 11, 15)
        val windows = paymentWindows(from, today, today)
        assertEquals(from, windows.first().from)
        assertEquals(today, windows.last().to)
        windows.forEach { assertEquals(it.from.year, it.to.year); assertTrue(ChronoUnit.DAYS.between(it.from, it.to) in 0..30) }
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.to.plusDays(1), b.from) }
        val dates = windows.flatMap { w -> (0..ChronoUnit.DAYS.between(w.from, w.to)).map { w.from.plusDays(it) } }
        assertEquals(dates.size, dates.toSet().size)
        assertEquals(ChronoUnit.DAYS.between(from, today) + 1, dates.size.toLong())
    }
    @Test fun fullLeapYearIncludesFebruary29() {
        val end = LocalDate.of(2028, 12, 31)
        assertEquals(366L, paymentWindows(LocalDate.of(2028, 1, 1), end, end).sumOf { ChronoUnit.DAYS.between(it.from, it.to) + 1 })
    }
    @Test fun rejectsFutureReversedAndUnsupportedDates() {
        listOf(today to today.plusDays(1), today to today.minusDays(1), LocalDate.of(2025, 12, 31) to today).forEach { (start, end) ->
            assertThrows(IllegalArgumentException::class.java) { paymentWindows(start, end, today) }
        }
    }
    @Test fun clientPickerIncludesOnlyActiveStartedLoansAndDeduplicatesNames() {
        val first = loan(1, "Kévin")
        val same = loan(2, "kevin")
        val finished = loan(3, "Ana", 30)
        val future = loan(4, "Pedro").copy(firstDue = today.plusDays(1), lastDue = today.plusDays(30))
        assertEquals(listOf("Kévin"), paymentClients(listOf(first, same, finished, future), today, true))
        assertEquals(3, paymentClients(listOf(first, same, finished, future), today, false).size)
    }
    @Test fun todayExcludesCompletedLoansButHistoricalAllKeepsThem() {
        val active = loan(1, "Kévin")
        val completed = loan(2, "Kevin", 30)
        val rows = listOf(SheetPayment(active, today, 1690, true, 0), SheetPayment(completed, today, 1690, false, 0))
        assertEquals(listOf(rows[0]), filterSheetPayments(rows, listOf(active, completed), "", true, today))
        assertEquals(rows, filterSheetPayments(rows, listOf(active, completed), " kevín ", false, today))
        assertTrue(filterSheetPayments(rows, listOf(active, completed), "kev", false, today).isEmpty())
    }
    @Test fun carriedCurrentViewDeterminesActiveFilterAndDuplicatesAreRemoved() {
        val old = loan(1, "Kevin")
        val current = old.copy(year = 2027, paid = 30)
        val future = old.copy(year = 2028, paid = 0)
        val row = SheetPayment(old, today, 1690, false, 0)
        assertTrue(filterSheetPayments(listOf(row), listOf(old, current, future), "", true, today).isEmpty())
        assertEquals(listOf(row), filterSheetPayments(listOf(row, row), listOf(old, current), "", false, today))
    }
    @Test fun selectedClientUsesAnExactNormalizedNameRatherThanAPrefix() {
        assertTrue(selectedClientMatches(" Kévin ", "kevin"))
        assertTrue(selectedClientMatches("Ana", ""))
        assertFalse(selectedClientMatches("Anabel", "Ana"))
    }

}

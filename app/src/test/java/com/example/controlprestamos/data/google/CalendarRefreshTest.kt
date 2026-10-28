package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarRefreshTest {
    private val day = LocalDate.of(2026, 10, 16)
    private fun loan() = parseSheetLoans(2026, listOf(listOf(1, "Cliente", "0/30", 500, .02, 1,
        "", 510, 0, 510, .33, .3, 16.9, 19.9, 0, sheetSerial(day.plusDays(1)), sheetSerial(day.plusDays(30))))).single()

    @Test fun movingTomorrowToTheFourteenthRejectsThePreviousCalendar() {
        val original = loan()
        val calendar = completeLoanCalendar(original, emptyMap(), day)
        val changed = original.copy(firstDue = day.minusDays(1), lastDue = day.plusDays(28))
        assertTrue(calendarMatchesLoan(calendar, original))
        assertFalse(calendarMatchesLoan(calendar, changed))
        assertEquals(day.minusDays(1), completeLoanCalendar(changed, emptyMap(), day).days.first().date)
    }

    @Test fun sameDatesWithADifferentLateFeeAlsoRejectThePreviousCalendar() {
        val original = loan()
        assertFalse(calendarMatchesLoan(completeLoanCalendar(original, emptyMap(), day), original.copy(dailyLate = 25)))
        assertFalse(calendarMatchesLoan(null, original))
    }

    @Test fun refreshingTheSelectedLoanReplacesAllFinancialTerms() {
        val original = loan()
        val changed = original.copy(firstDue = day.minusDays(1), dailyLate = 25, client = "Actualizado")
        assertEquals(changed, refreshedLoanSelection(original, listOf(changed)))
        val carried = changed.copy(year = 2027, row = 406)
        assertEquals(carried, refreshedLoanSelection(original, listOf(carried)))
        assertEquals(original, refreshedLoanSelection(original, emptyList()))
    }
}

package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class SheetCalendarTest {
    private val first = LocalDate.of(2026, 12, 20)
    private val day = first.plusDays(3)
    private fun loan(id: Int = 1, paid: Int = 2, client: String = "Kévin de prueba") = parseSheetLoans(2026, listOf(listOf(
        id, client, "$paid/30", 500, 0.02, 1, "Documento", 510, 36.8, 473.2,
        0.3333333333333333, 0.3, 16.9, 19.9, 0, sheetSerial(first), sheetSerial(first.plusDays(29))))).single()
    private fun audit(loan: SheetLoan, due: LocalDate, unpaid: Boolean = false, late: Any? = 0, paidOn: Any? = sheetSerial(day)) =
        listOf("2026-12-23 10:00:00", "operation", loan.year, loan.rawId, sheetSerial(due), true, unpaid, 0, late, paidOn, loan.client, "Cuenta de prueba")

    @Test fun preservesTheMainSheetFieldsPreviouslyOmitted() {
        val loan = loan()
        assertEquals(BigDecimal("0.3333333333333333"), loan.realDailyInterest)
        assertEquals(30L, loan.dailyInterest)
        assertEquals(BigDecimal.ZERO, loan.control)
        assertEquals(51000L, loan.agreed)
        assertEquals(3680L, loan.collected)
        assertEquals("Documento", loan.guarantee)
    }

    @Test fun partialClientSearchReturnsActiveAndCompletedLoansAcrossYears() {
        val active = loan()
        val completed = loan(id = 2, paid = 30).copy(year = 2027)
        val other = loan(id = 3, client = "Otra persona")
        val all = listOf(active, completed, other)
        assertEquals(setOf(active.id, completed.id), matchingSheetLoans(all, " kev ").map { it.id }.toSet())
        assertEquals(listOf(active), matchingSheetLoans(all, "KEV", 1))
        assertEquals(listOf(completed), matchingSheetLoans(all, "kevin", 2))
    }

    @Test fun carriedRowsDoNotDuplicateLoansOrReplaceCurrentStateWithFutureState() {
        val original = loan()
        val current = original.copy(year = 2027, rawId = original.id, row = 406, paid = 15)
        val future = current.copy(year = 2028, row = 806, paid = 30)
        val second = loan(id = 2, paid = 30)
        val result = canonicalLoanViews(listOf(original, current, future, second), 2027)
        assertEquals(2, result.size)
        assertEquals(current, result.single { it.id == original.id })
        assertEquals(setOf(original.id, second.id), matchingSheetLoans(result, "kev").map { it.id }.toSet())
    }

    @Test fun fullCalendarIncludesEveryContractualDateAcrossDecember() {
        val loan = loan()
        val dates = loanDates(loan)
        assertEquals(30, dates.size)
        assertEquals(first, dates.first())
        assertEquals(LocalDate.of(2027, 1, 18), dates.last())
        assertEquals(30, dates.distinct().size)
        assertEquals(setOf(2026, 2027), dates.map { it.year }.toSet())
    }

    @Test fun unknownCarriedDatesRemainVisibleWithoutInventingChecks() {
        val loan = loan()
        val date = first
        val loaded = parseCalendarDay(loan, date, listOf(true, 1, true, 22.9, 3), 0, day)
        val calendar = completeLoanCalendar(loan, mapOf(date to loaded), day)
        assertEquals(30, calendar.days.size)
        assertEquals(CalendarStatus.PENDING, calendar.days.first().status)
        assertEquals(CalendarStatus.UNAVAILABLE, calendar.days.last().status)
        assertNull(calendar.days.last().checked)
        assertNull(calendar.days.last().payment)
    }

    @Test fun pendingDaysPreserveBookAmountAndIndependentLateFee() {
        val entry = parseCalendarDay(loan(), first, listOf(true, 1, true, 22.9, 3), 0, day)
        assertEquals(CalendarStatus.PENDING, entry.status)
        assertEquals(true, entry.checked)
        assertEquals(2290L, entry.bookAmount)
        assertEquals(300L, entry.bookLate)
        assertEquals(1990L, entry.payment!!.base)
        assertEquals(300L, entry.payment.late)
        assertEquals(1, entry.payment.installment)
    }

    @Test fun legacyBlankCheckMeansPaidOnlyForAValidMaturedInstallment() {
        val date = first.plusDays(1)
        val entry = parseCalendarDay(loan(), date, listOf(true, 2, "", 16.9, 0), 0, day)
        assertEquals(CalendarStatus.PAID, entry.status)
        assertEquals(false, entry.checked)
        assertEquals(2, entry.payment!!.installment)
        assertNull(entry.payment.paidOn)
    }

    @Test fun futureDaysKeepBothCheckStatesWithoutBecomingIncomeOrArrears() {
        val date = day.plusDays(1)
        for (check in listOf(true, false)) {
            val entry = parseCalendarDay(loan(), date, listOf(false, 5, check, 0, 0), 0, day)
            assertEquals(CalendarStatus.FUTURE, entry.status)
            assertEquals(check, entry.checked)
            assertEquals(0L, entry.bookAmount)
            assertNull(entry.payment)
        }
    }

    @Test fun invalidMaturedDayDoesNotBecomePaidWhenItsCheckIsFalse() {
        val entry = parseCalendarDay(loan(), first, listOf(false, 1, false, 0, 0), 0, day)
        assertEquals(CalendarStatus.INVALID, entry.status)
        assertNull(entry.payment)
    }

    @Test fun historyIdentifiesLoansFromYearAndIdNotTheClientColumn() {
        val row = audit(loan(), first)
        assertEquals("2026-001", historyLoanId(row))
        assertEquals("2026-001", historyLoanId(row.toMutableList().also { it[2] = 2027; it[3] = "2026-001" }))
    }

    @Test fun historyShowsRealDateOnlyWhenLatestStateAndCollectedFeeMatch() {
        val loan = loan()
        val payment = parsePayment(loan, first, listOf(true, 1, false, 20.9, 1), 1, day)!!
        val index = PaymentHistoryIndex(listOf(audit(loan, first, late = 1)))
        assertEquals(day, index.paidOn(payment, day))
        assertNull(index.paidOn(payment.copy(late = 200), day))
        assertNull(index.paidOn(payment.copy(unpaid = true), day))
    }

    @Test fun laterUnpaidOrInvalidHistoryDoesNotReuseAnOlderPaidDate() {
        val loan = loan()
        val payment = parsePayment(loan, first, listOf(true, 1, false, 19.9, 0), 0, day)!!
        val firstLog = audit(loan, first)
        assertNull(PaymentHistoryIndex(listOf(firstLog, audit(loan, first, unpaid = true, paidOn = ""))).paidOn(payment, day))
        assertNull(PaymentHistoryIndex(listOf(firstLog, audit(loan, first, late = "invalid"))).paidOn(payment, day))
        assertNull(PaymentHistoryIndex(emptyList()).paidOn(payment, day))
    }

    @Test fun historyRejectsRealPaymentDatesOutsideTheDueToTodayInterval() {
        val loan = loan()
        val payment = parsePayment(loan, first, listOf(true, 1, false, 19.9, 0), 0, day)!!
        for (date in listOf(first.minusDays(1), day.plusDays(1))) {
            assertNull(PaymentHistoryIndex(listOf(audit(loan, first, paidOn = date.toString()))).paidOn(payment, day))
        }
    }
}

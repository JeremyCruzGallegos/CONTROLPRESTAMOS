package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LoanStandingTest {
    private val day = LocalDate.of(2026, 10, 25)
    private fun loan(id: String = "2026-001", start: LocalDate = day.minusDays(4), fee: Long = 100): SheetLoan =
        SimpleRecord(8, Rules.createLoan(id, LoanRequest("Cliente_Demo", 30000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), start), fee, false, "rev").view(day)
    @Test fun finishedLoansAreClosedAndTodaysUnpaidInstallmentDoesNotMakeANewLoanOverdue() {
        assertEquals(LoanStanding.CLOSED, loanStanding(loan().copy(paid = 30), emptyList(), emptyMap(), day))
        assertEquals(LoanStanding.ACTIVE, loanStanding(loan(start = day), emptyList(), emptyMap(), day))
        assertEquals(LoanStanding.ACTIVE, loanStanding(loan(start = day.minusDays(1)), emptyList(), emptyMap(), day))
    }
    @Test fun previousUnpaidInstallmentsAreOverdueEvenWithZeroLateFee() {
        assertEquals(LoanStanding.OVERDUE, loanStanding(loan(), emptyList(), emptyMap(), day))
        assertEquals(LoanStanding.OVERDUE, loanStanding(loan(fee = 0), emptyList(), emptyMap(), day))
    }
    @Test fun payingOldInstallmentsAndReversingThemChangesStandingWithoutCountingTodaysDueAsLate() {
        val source = loan()
        val dates = loanDates(source).filter { it < day }
        val paid = dates.map { SheetPayment(source, it, source.baseOn(it), false, 0, day) }
        val current = source.copy(paid = paid.size)
        assertEquals(LoanStanding.ACTIVE, loanStanding(current, paid.map { it.copy(loan = current) }, emptyMap(), day))
        val first = paid.first().copy(loan = current)
        val reversal = PaymentEdit(current.id, first.id, 0, true, 0, null)
        assertEquals(LoanStanding.OVERDUE, loanStanding(current, paid, mapOf(first.id to (first to reversal)), day))
    }
    @Test fun missingPaidHistoryIsUnverifiedInsteadOfClaimingArrearsOrAnUpToDateLoan() {
        assertEquals(LoanStanding.UNVERIFIED, loanStanding(loan().copy(paid = 2), emptyList(), emptyMap(), day))
    }
    @Test fun numberingIsOldestFirstDeduplicatesAnnualViewsAndKeepsNumericOrderOnTheSameDate() {
        val old = loan("2026-999", day.minusDays(10))
        val sameDayLater = loan("2026-1000", day.minusDays(10))
        val newer = loan("2026-002", day.minusDays(1))
        val groups = clientLoanGroups(listOf(newer, sameDayLater, old.copy(year = 2027), old), day)
        assertEquals(listOf(old.id, sameDayLater.id, newer.id), groups.single().map { it.id })
        assertEquals(2026, groups.single().first().year)
        val active = groups.single().filter { it.id != old.id }
        assertEquals(listOf(2, 3), active.map { row -> groups.single().indexOfFirst { it.id == row.id } + 1 })
    }
}

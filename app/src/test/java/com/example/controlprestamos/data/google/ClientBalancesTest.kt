package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ClientBalancesTest {
    private val day = LocalDate.of(2026, 10, 25)
    private fun loan(id: String = "2026-001", client: String = "Ana Pérez", start: LocalDate = day.minusDays(4)) =
        SimpleRecord(8, Rules.createLoan(id, LoanRequest(client, 30000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), start), 100, false, "rev").view(day)

    @Test fun currentDebtExcludesFutureInstallmentsAndPaidInstallmentsButIncludesAccruedLateFees() {
        val loan = loan().copy(paid = 1)
        val row = SheetPayment(loan, loan.firstDue, loan.first, false, 0, day)
        val balances = loanBalances(loan, listOf(row), emptyMap(), day)!!
        val due = loanDates(loan).filter { it <= day && it != row.due }
        val totalPending = loanDates(loan).filter { it != row.due }
        assertEquals(due.sumOf { loan.baseOn(it) + loan.lateAt(it, day) }, balances.dueToday)
        assertEquals(totalPending.sumOf { loan.baseOn(it) + loan.lateAt(it, day) }, balances.pending)
        assertTrue(balances.pending > balances.dueToday)
    }
    @Test fun newLoanHasFullPendingBalanceAndZeroDueToday() {
        val loan = loan(start = day)
        assertEquals(LoanBalances(loan.agreed, 0), loanBalances(loan, emptyList(), emptyMap(), day))
    }
    @Test fun incompletePaidHistoryDoesNotInventCurrentDebt() {
        assertNull(loanBalances(loan().copy(paid = 2), emptyList(), emptyMap(), day))
        assertEquals(LoanBalances(0, 0), loanBalances(loan().copy(paid = 30), emptyList(), emptyMap(), day))
    }
    @Test fun localPaymentAndReversalRecalculateBothBalances() {
        val loan = loan()
        val row = SheetPayment(loan, loan.firstDue, loan.first, true, loan.lateAt(loan.firstDue, day))
        val before = loanBalances(loan, listOf(row), emptyMap(), day)!!
        val edit = PaymentEdit(loan.id, row.id, 0, false, 0, day)
        val after = loanBalances(loan, listOf(row), mapOf(row.id to (row to edit)), day)!!
        val difference = row.base + loan.lateAt(row.due, day)
        assertEquals(before.pending - difference, after.pending)
        assertEquals(before.dueToday - difference, after.dueToday)
        val paidLoan = loan.copy(paid = 1)
        val paid = row.copy(loan = paidLoan, unpaid = false, late = 0, paidOn = day)
        val reverse = PaymentEdit(loan.id, row.id, 0, true, 0, null)
        assertEquals(before, loanBalances(paidLoan, listOf(paid), mapOf(paid.id to (paid to reverse)), day))
    }
    @Test fun clientGroupsDeduplicateAnnualViewsNormalizeNamesAndOrderTheirLoans() {
        val first = loan()
        val second = loan("2026-002", "  ANA   PEREZ ", day.minusDays(1))
        val other = loan("2026-003", "Luis")
        val groups = clientLoanGroups(listOf(second, first, first.copy(year = 2027), other), day)
        assertEquals(2, groups.size)
        assertEquals(listOf(first.id, second.id), groups.first().map { it.id })
        assertEquals(2026, groups.first().first().year)
    }
    @Test fun growthUsesInitialCapitalEvenWhenThePeriodStartsAfterEarlierEarnings() {
        val opening = CapitalGrowthPoint(day.minusDays(10), 110000, 8000, 2000)
        val closing = CapitalGrowthPoint(day, 125000, 20000, 5000)
        assertEquals(BigDecimal("10.00"), opening.growthPercent)
        assertEquals(BigDecimal("25.00"), closing.growthPercent)
        assertNull(CapitalGrowthPoint(day, 5000, 4000, 1000).growthPercent)
    }
}

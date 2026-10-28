package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LoanDebtBreakdownTest {
    private val day = LocalDate.of(2026, 10, 25)
    private fun record(start: LocalDate = day.minusDays(4)) = SimpleRecord(8,
        Rules.createLoan("2026-001", LoanRequest("Cliente_Demo", 30000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), start), 100, false, "rev")
    private fun rows(record: SimpleRecord, asOf: LocalDate = day): List<SheetPayment> {
        val model = SimpleBook("Pruebas", 100000, "rev", asOf.plusMonths(2), listOf(record), java.time.Instant.now())
        return record.loan.installments.map { model.payment(record, it, asOf, SheetsIdentity.EMPTY_TEST_ID) }
    }
    @Test fun threePartsExactlyPartitionTheCurrentDebtAndTheFirstTwoAreDueToday() {
        val record = record(); val loan = record.view(day)
        val debt = loanDebtBreakdown(loan, rows(record), emptyMap(), day)!!
        assertEquals(loan.debt, debt.pending)
        assertEquals(loan.agreed, debt.withoutLate)
        assertEquals(loanDates(loan).filter { it <= day }.sumOf(loan::baseOn), debt.overdueBase)
        assertEquals(600L, debt.late)
        assertEquals(debt.overdueBase + debt.late, debt.dueToday)
        assertEquals(debt.overdueBase + debt.late + debt.futureBase, debt.pending)
        assertEquals(BigDecimal("1.96"), debt.latePercent)
    }
    @Test fun aNewDayMovesOneBaseToDueAndAddsFeesOnlyToStillUnpaidInstallments() {
        val record = record(); val before = loanDebtBreakdown(record.view(day), rows(record), emptyMap(), day)!!
        val next = day.plusDays(1)
        val after = loanDebtBreakdown(record.view(next), rows(record, next), emptyMap(), next)!!
        assertEquals(before.withoutLate, after.withoutLate)
        assertEquals(400L, after.late - before.late)
        assertEquals(before.overdueBase + record.view(day).baseOn(next), after.overdueBase)
        assertEquals(before.futureBase - record.view(day).baseOn(next), after.futureBase)
    }
    @Test fun payingACurrentlyOverdueInstallmentReducesTheBarAndRemovesItsPendingFee() {
        val record = record(); val loan = record.view(day); val rows = rows(record)
        val before = loanDebtBreakdown(loan, rows, emptyMap(), day)!!
        val row = rows.first()
        val edit = PaymentEdit(loan.id, row.id, 0, false, row.late, day)
        val after = loanDebtBreakdown(loan, rows, mapOf(row.id to (row to edit)), day)!!
        assertEquals(before.withoutLate - row.base, after.withoutLate)
        assertEquals(before.late - row.late, after.late)
        assertEquals(before.pending - row.base - row.late, after.pending)
        assertEquals(before.futureBase, after.futureBase)
    }
    @Test fun paidInstallmentsStopAccruingPendingFeesOnLaterDays() {
        val record = record()
        val paid = record.copy(loan = record.loan.copy(installments = record.loan.installments.mapIndexed { i, item ->
            if (i == 0) item.copy(paidOn = day, collectedLate = 300) else item
        }))
        val before = loanDebtBreakdown(paid.view(day), rows(paid), emptyMap(), day)!!
        val next = day.plusDays(1)
        val after = loanDebtBreakdown(paid.view(next), rows(paid, next), emptyMap(), next)!!
        assertEquals(300L, after.late - before.late)
    }
    @Test fun multiplePaymentAdvancesAreIncludedWithoutCountingThemAgainAsUnpaidDebt() {
        val record = record(); val first = record.loan.installments.first()
        val plan = planAmountPayment(record, first.base + record.view(day).lateAt(first.due, day) + 50, day)
        val debt = loanDebtBreakdown(plan.after.view(day), rows(plan.after), emptyMap(), day)!!
        assertEquals(plan.after.view(day).debt, debt.pending)
        assertTrue(debt.pending < record.view(day).debt)
    }
    @Test fun futureLoansMissingPaidRowsAndZeroDebtDoNotInventFeesOrPercentages() {
        val future = record(day)
        assertEquals(LoanDebtBreakdown(0, 0, future.loan.agreed), loanDebtBreakdown(future.view(day), emptyList(), emptyMap(), day))
        assertNull(loanDebtBreakdown(record().view(day).copy(paid = 1), emptyList(), emptyMap(), day))
        assertEquals(LoanDebtBreakdown(0, 0, 0), loanDebtBreakdown(record().view(day).copy(paid = 30), emptyList(), emptyMap(), day))
        assertNull(LoanDebtBreakdown(0, 100, 0).latePercent)
    }
}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LoanReturnsTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun loan(): SheetLoan {
        val contract = Rules.createLoan("2026-001", LoanRequest("Cliente", 100000, BigDecimal("2"),
            1, "", Funding.CAPITAL, 0), day)
        return SheetLoan(contract.id, "1", 2026, 6, contract.client, contract.principal, contract.ratePercent,
            contract.months, "", contract.agreed, 0, contract.agreed, contract.installments[1].base,
            contract.installments[0].base, contract.installments.first().due, contract.installments.last().due, 0, 30)
    }

    @Test fun lendingOneThousandAtTwoPercentForOneMonthShowsOneThousandTwenty() {
        val result = activeLoanReturns(listOf(loan()), emptyMap(), day)
        assertEquals(1, result.count)
        assertEquals(100000L, result.principal)
        assertEquals(2000L, result.interest)
        assertEquals(102000L, result.total)
    }
    @Test fun partialPaymentsAndCollectedLateFeesDoNotChangeTheOriginalContractReturn() {
        val partial = loan().copy(paid = 10, collected = 40000, debt = 65000)
        val result = activeLoanReturns(listOf(partial), emptyMap(), day.plusDays(15))
        assertEquals(102000L, result.total)
        assertEquals(2000L, result.interest)
    }
    @Test fun amendedAgreementsUseTheStoredTotalAndTransferredLoansAreCountedOnce() {
        val original = loan()
        val amended = original.copy(year = 2027, months = 2, agreed = 104001)
        val result = activeLoanReturns(listOf(original, amended), emptyMap(), LocalDate.of(2027, 1, 1))
        assertEquals(1, result.count)
        assertEquals(104001L, result.total)
        assertEquals(4001L, result.interest)
    }
    @Test fun completedAndUndisbursedLoansDoNotInflateActiveExpectedReturns() {
        val complete = loan().copy(paid = 30)
        val future = loan().copy(id = "2026-002", firstDue = day.plusDays(2))
        assertEquals(LoanReturns(0, 0, 0), activeLoanReturns(listOf(complete, future), emptyMap(), day))
    }
    @Test fun collectingTheLastInstallmentLocallyRemovesItFromTheActiveProjection() {
        val last = loan().copy(paid = 29)
        val date = last.lastDue
        val row = SheetPayment(last, date, last.daily, true, 0)
        val changes = mapOf(row.id to (row to PaymentEdit(last.id, row.id, 0, false, 0, date)))
        assertEquals(LoanReturns(0, 0, 0), activeLoanReturns(listOf(last), changes, date))
    }
}

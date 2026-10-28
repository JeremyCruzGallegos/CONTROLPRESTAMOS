package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.PaymentEdit
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CashEvolutionPaymentsTest {
    private val day = LocalDate.of(2026, 10, 3)
    private val due = LocalDate.of(2026, 9, 30)
    private val loan = SheetLoan("2026-001", "1", 2026, 6, "Cliente", 30000, BigDecimal("2"), 1,
        "", 30600, 0, 30600, 1020, 1020, due, due.plusDays(29), 0, 30)
    private val unpaid = SheetPayment(loan, due, 1020, true, 300)
    private fun changes(row: SheetPayment = unpaid, reopen: Boolean = false, fee: Long = 50,
        paidOn: LocalDate = day): PaymentChanges = mapOf(row.id to
        (row to PaymentEdit(loan.id, row.id, 0, reopen, if (reopen) 0 else fee, if (reopen) null else paidOn)))
    private fun rows(received: Long = 0, paidOn: LocalDate = due) = (0L..3L).map { offset ->
        val date = due.plusDays(offset)
        BookCashDay(date, 0, if (date == paidOn) received else 0, 0,
            100000 + if (date >= paidOn) received else 0)
    }

    @Test fun overduePaymentsIncreaseCashOnTheirRealDateIncludingCollectedFees() {
        val projected = localCashRows(rows(), changes(paidOn = day.minusDays(1)), true, day)
        assertEquals(listOf(100000L, 100000L, 101070L, 101070L), projected.map { it.cash })
        val report = bookCashReport(projected, day, 2026, 10)!!
        assertEquals(1070L, report.receipts)
        assertEquals(100000L, report.openingCash)
        assertEquals(report.receipts, report.cashChange)
        assertEquals(report.closingCash, report.cashEvolution.last())
    }
    @Test fun originalBooksKeepTheirContractualDateForBothReceiptsAndCash() {
        val projected = localCashRows(rows(), changes(), false, day)
        assertEquals(1070L, projected.first().receipts)
        assertTrue(projected.all { it.cash == 101070L })
        val october = bookCashReport(projected, day, 2026, 10)!!
        assertEquals(0L, october.receipts)
        assertEquals(101070L, october.openingCash)
    }
    @Test fun reopeningAPaidInstallmentRemovesItsBaseAndActualCollectedFee() {
        val paid = unpaid.copy(unpaid = false, late = 50, paidOn = day.minusDays(1))
        val projected = localCashRows(rows(1070, paid.paidOn!!), changes(paid, reopen = true), true, day)
        assertTrue(projected.all { it.cash == 100000L && it.receipts == 0L })
    }
    @Test fun movingAPaymentAcrossMonthsMovesRevenueAndPreservesFinalCash() {
        val paid = unpaid.copy(unpaid = false, late = 50, paidOn = due)
        val projected = localCashRows(rows(1070), changes(paid, paidOn = day), true, day)
        val september = bookCashReport(projected, day, 2026, 9)!!
        val october = bookCashReport(projected, day, 2026, 10)!!
        assertEquals(0L, september.receipts)
        assertEquals(100000L, september.closingCash)
        assertEquals(1070L, october.receipts)
        assertEquals(101070L, october.closingCash)
    }
    @Test fun correctingFeesChangesOnlyTheDifferenceAndReconcilesWithTheLedger() {
        val paid = unpaid.copy(unpaid = false, late = 100, paidOn = day.minusDays(1))
        val projected = localCashRows(rows(1120, paid.paidOn!!), changes(paid, fee = 25, paidOn = paid.paidOn!!), true, day)
        val report = bookCashReport(projected, day, 2026, 10)!!
        assertEquals(1045L, report.receipts)
        assertEquals(101045L, report.closingCash)
    }
    @Test fun localReceiptsExtendYesterdayCacheWithoutCreatingFutureRevenue() {
        val future = unpaid.copy(due = day.plusDays(1))
        val edits = changes() + changes(future, paidOn = day.plusDays(1))
        val projected = localCashRows(rows().dropLast(1), edits, true, day)
        assertEquals(day, projected.last().date)
        assertEquals(101070L, projected.last().cash)
        assertEquals(1070L, projected.sumOf { it.receipts })
        assertTrue(localCashRows(emptyList(), edits, true, day).isEmpty())
    }
    @Test fun acknowledgedPaymentsAreNotAddedAgainToAFreshCashSnapshot() {
        val edit = changes()
        val pending = PendingRemoteWrite("receipt-test", "account", SheetsIdentity.PRODUCTION_ID, "{}")
        val queue = PaymentQueue(edit, PaymentFlight(pending, edit, acknowledgedAt = 1000))
        val fresh = rows(1070, day)
        val projected = localCashRows(fresh, financialPaymentChanges(queue, 1001, day), true, day)
        assertEquals(fresh, projected)
    }
    @Test fun aConfirmedReceiptSurvivesOutboxRemovalAndRepeatedAcknowledgements() {
        val store = OfflineBookStore(MemorySheetCache(), "account", SheetsIdentity.EMPTY_TEST_ID)
        store.saveCash(rows(), 500)
        val edit = changes()
        val pending = PendingRemoteWrite("receipt-test", "account", SheetsIdentity.EMPTY_TEST_ID, "{}")
        val flight = PaymentFlight(pending, edit, acknowledgedAt = 1000)
        store.confirmCash(flight, true, day)
        store.confirmCash(flight, true, day)
        assertEquals(101070L, store.cash().last().cash)
        assertEquals(1070L, store.cash().sumOf { it.receipts })
        assertEquals(1000L, store.cashSnapshot().syncedAtMillis)
        val paid = unpaid.copy(unpaid = false, late = 50, paidOn = day)
        val reversal = localCashRows(store.cash(), changes(paid, reopen = true), true, day)
        assertEquals(100000L, reversal.last().cash)
    }
    @Test fun paymentsAccumulateAlongsideContributionsAndDisbursements() {
        val ledger = listOf(BookCashDay(day.minusDays(1), 50000, 0, 30000, 120000),
            BookCashDay(day, 0, 500, 10000, 110500))
        val second = unpaid.copy(loan = loan.copy(id = "2026-002"))
        val report = bookCashReport(localCashRows(ledger, changes() + changes(second), true, day), day, 2026, 0)!!
        assertEquals(2640L, report.receipts)
        assertEquals(50000L, report.contribution)
        assertEquals(40000L, report.disbursed)
        assertEquals(112640L, report.closingCash)
        assertEquals(report.receipts + report.contribution - report.disbursed, report.cashChange)
        assertEquals(report.receipts, report.movements.sumOf { it.receipts })
    }
    @Test fun cashAlreadyRefreshedFromGoogleIsNotChangedByReceiptConfirmation() {
        val store = OfflineBookStore(MemorySheetCache(), "account", SheetsIdentity.EMPTY_TEST_ID)
        val fresh = rows(1070, day)
        store.saveCash(fresh, 1001)
        val edit = changes()
        val pending = PendingRemoteWrite("receipt-test", "account", SheetsIdentity.EMPTY_TEST_ID, "{}")
        store.confirmCash(PaymentFlight(pending, edit, acknowledgedAt = 1000), true, day)
        assertEquals(fresh, store.cash())
        assertEquals(1001L, store.cashSnapshot().syncedAtMillis)
    }
}

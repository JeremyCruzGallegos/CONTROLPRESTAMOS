package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class PortfolioForecastTest {
    private val start = LocalDate.of(2026, 9, 20)
    private val day = start.plusDays(20)
    private fun contract() = Rules.createLoan("2026-001", LoanRequest("Cliente", 100_000, BigDecimal("2"),
        1, "", Funding.CAPITAL, 0), start)
    private fun record(loan: Loan = contract()) = SimpleRecord(2, loan, 100, false, "test")
    private fun payments(record: SimpleRecord) = record.loan.installments.filter { it.due <= day }.map {
        SheetPayment(record.view(day), it.due, it.base, !it.paid(day), it.collectedLate, it.paidOn)
    }
    @Test fun cashAfterAllLoansIncludesUnpaidBaseAndAccruedLateFeesAsSeparateAmounts() {
        val loan = contract().let { it.copy(installments = it.installments.mapIndexed { i, row ->
            if (i < 10) row.copy(paidOn = row.due, collectedLate = 50) else row
        }) }
        val record = record(loan)
        val forecast = portfolioForecast(listOf(record.view(day)), payments(record), emptyMap(), day)!!
        val book = SimpleBook("Test", 100_000, "c", LocalDate.of(2026, 12, 31), listOf(record), Instant.EPOCH)
        val cash = book.summary(2026, day).cash
        assertEquals(4_500L,forecast.late)
        assertEquals(107_000L, forecast.cashAfterCollection(cash))
        assertEquals(loan.agreed - loan.installments.take(10).sumOf { it.base }, forecast.pending)
        assertEquals(2_000L - loan.interest(day), forecast.interest)
    }
    @Test fun aCatalogWithoutPartialPaymentDetailsDoesNotGuessTheBaseFeeSplit() {
        assertNull(portfolioForecast(listOf(record().view(day).copy(paid = 5, collected = 20000)), emptyList(), emptyMap(), day))
    }
    @Test fun aNewLoanForecastIsImmediatelyAvailableWithoutPaymentHistory() {
        val forecast = portfolioForecast(listOf(record().view(day)), emptyList(), emptyMap(), day)!!
        assertEquals(102_000L, forecast.pending)
        assertEquals(2_000L, forecast.interest)
    }
    @Test fun transferredCompletedAndFutureLoansDoNotDoubleTheProjection() {
        val original = record().view(day)
        val transferred = original.copy(year = 2027)
        val complete = original.copy(id = "2026-002", paid = 30)
        val future = original.copy(id = "2026-003", firstDue = day.plusDays(2))
        val forecast = portfolioForecast(listOf(original, transferred, complete, future), emptyList(), emptyMap(), day)!!
        assertEquals(1, forecast.count); assertEquals(102_000L, forecast.pending)
    }
    @Test fun localPaymentAndReopeningChangeTheProjectionWithoutDoubleCountingCash() {
        val record = record(); val rows = payments(record); val row = rows.first()
        val edit = PaymentEdit(row.loan.id, row.id, 0, false, 100, day)
        val changes = mapOf(row.id to (row to edit))
        val forecast = portfolioForecast(listOf(record.view(day)), rows, changes, day)!!
        assertEquals(102_000L - row.base, forecast.pending)
        assertEquals(17_100L,forecast.late)
        assertEquals(119_200L, forecast.cashAfterCollection(row.base + 100))
        val paid = row.copy(unpaid = false, paidOn = day, late = 100)
        val reopened = mapOf(row.id to (paid to edit.copy(unpaid = true, collectedLate = 0, paidOn = null)))
        val loan = record.view(day).copy(paid = 1)
        assertEquals(102_000L, portfolioForecast(listOf(loan), listOf(paid), reopened, day)!!.pending)
    }
    @Test fun recycledReceiptsFundAnotherLoanWithoutAddingFreshCapital() {
        val paidLoan = contract().let { loan -> loan.copy(installments = loan.installments.mapIndexed { i, row ->
            if (i < 15) row.copy(paidOn = row.due) else row
        }) }
        val record = record(paidLoan)
        val book = SimpleBook("Test", 100_000, "c", LocalDate.of(2026, 12, 31), listOf(record), Instant.EPOCH)
        val cash = book.summary(2026, day).cash
        assertTrue(cash >= 50_000)
        val funded = Rules.fundLoan(LoanRequest("Segundo", 50_000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), cash)
        assertEquals(0L, funded.contribution)
        val second = Rules.createLoan("2026-002", funded, day)
        val after = book.copy(records = book.records + record(second).copy(row = 3))
        assertTrue(after.cash(day).all { it.cash >= 0 })
        assertEquals(100_000L, after.initial)
        assertEquals(cash - 50_000, after.summary(2026, day).cash)
    }
    @Test fun loanLargerThanCashRecordsExactlyTheMissingMoneyNotTheWholeLoan() {
        val request = LoanRequest("Cliente", 150_000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0)
        val funded = Rules.fundLoan(request, 100_000)
        assertEquals(50_000L, funded.contribution)
        assertEquals(Funding.EXTERNAL, funded.funding)
        assertEquals(0L, Rules.cashAfter(100_000, funded.principal, funded.contribution))
        assertEquals(75_000L, Rules.fundLoan(request.copy(funding = Funding.EXTERNAL, contribution = 75_000), 100_000).contribution)
    }
    @Test fun backdatedLoanCannotSpendPaymentsThatHadNotArrivedYet() {
        val rows = listOf(BookCashDay(start, 0, 0, 100_000, 0), BookCashDay(day, 0, 50_000, 0, 50_000))
        // Sparse input contains only recorded rows; real cash snapshots include each intervening day.
        val complete = listOf(rows[0], rows[0].copy(date = start.plusDays(1), disbursed = 0), rows[1])
        assertEquals(0L, cashForNewLoan(complete, start.plusDays(1), 50_000))
        assertEquals(50_000L, cashForNewLoan(rows, day, 50_000))
    }
    @Test fun paymentDateCorrectionMovesCashAcrossMonthsAndKeepsTheFinalBalance() {
        val loan = record().view(day)
        val due = LocalDate.of(2026, 9, 30)
        val paidOn = LocalDate.of(2026, 10, 2)
        val rows = (0L..3L).map { n -> BookCashDay(due.plusDays(n), 0, if (n == 0L) 10_000 else 0, 0, 110_000) }
        val payment = SheetPayment(loan, due, 9_900, false, 100, paidOn)
        val cash = cashByPaymentDates(rows, listOf(payment, payment), day)
        assertEquals(listOf(100_000L, 100_000L, 110_000L, 110_000L), cash.map { it.cash })
        assertEquals(0L, bookCashReport(cash, day, 2026, 9)!!.receipts)
        assertEquals(10_000L, bookCashReport(cash, day, 2026, 10)!!.receipts)
        assertEquals(110_000L, cash.last().cash)
    }
    @Test fun missingPaymentDatesAreNeverInvented() {
        val row = BookCashDay(start, 0, 10_000, 0, 110_000)
        val payment = SheetPayment(record().view(day), start, 10_000, false, 0)
        assertEquals(listOf(row), cashByPaymentDates(listOf(row), listOf(payment), day))
    }
    @Test fun futureCashRowsCannotForceAPresentContribution() {
        val rows = listOf(BookCashDay(day, 0, 0, 0, 50_000),
            BookCashDay(day.plusDays(1), 0, 0, 100_000, -50_000))
        assertEquals(50_000L, cashForNewLoan(rows, start, 50_000, day))
    }

    @Test fun aBusinessWithoutLoansHasNoPendingProfitAndKeepsItsCash() {
        val forecast = portfolioForecast(emptyList(), emptyList(), emptyMap(), day)!!
        assertEquals(0L, forecast.pending)
        assertEquals(0L, forecast.interest)
        assertEquals(100_000L, forecast.cashAfterCollection(100_000))
    }

}

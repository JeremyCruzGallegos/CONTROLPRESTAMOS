package com.example.controlprestamos.domain

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class FinancialReportTest {
    private val day = LocalDate.of(2026, 10, 11)
    private fun loan(id: String, start: LocalDate, amount: Long = 10_001, contribution: Long = 0, rate: String = "2.37", months: Int = 1) =
        Rules.createLoan(id, LoanRequest("Cliente ficticio", amount, BigDecimal(rate), months, "",
            if (contribution == 0L) Funding.CAPITAL else Funding.EXTERNAL, contribution), start)
    private fun paid(loan: Loan, dates: Map<Int, LocalDate>, late: Long = 0): Loan = loan.copy(
        installments = loan.installments.mapIndexed { index, item -> dates[index]?.let { item.copy(paidOn = it, collectedLate = late) } ?: item })

    @Test fun monthlyBarsUsePaymentDateInsteadOfDueDate() {
        val original = loan("late", LocalDate.of(2026, 9, 20))
        val state = Snapshot(listOf(paid(original, mapOf(0 to day), 150)))
        val annual = periodReport(state, day, 2026, 0)!!
        assertEquals(0L, annual.movements[8].receipts)
        assertEquals(original.installments.first().base + 150, annual.movements[9].receipts)
        assertEquals(150L, annual.movements[9].late)
        assertTrue(annual.monthly)
        assertEquals(10, annual.movements.size)
        assertEquals(day, annual.movements.last().end)
    }
    @Test fun dailyLedgerMatchesEverySummaryWithHistoricalLoansAndContributions() {
        val older = loan("older", LocalDate.of(2025, 12, 20), 50_000, months = 2)
        val current = loan("current", LocalDate.of(2026, 10, 2), 100_000, 150_000)
        val state = Snapshot(listOf(paid(older, mapOf(0 to LocalDate.of(2026, 1, 2), 1 to day), 75),
            paid(current, mapOf(0 to day.minusDays(1), 1 to day), 100)))
        val report = periodReport(state, day, 2026, 0)!!
        report.cashEvolution.forEachIndexed { index, cash ->
            assertEquals("Fecha ${report.start.minusDays(1).plusDays(index.toLong())}",
                Rules.summary(state, report.start.minusDays(1).plusDays(index.toLong())).cash, cash)
        }
        assertEquals(report.closingCash, report.cashEvolution.last())
        assertEquals(report.cashChange, report.receipts + report.contribution - report.disbursed)
    }
    @Test fun roundedInterestReconcilesAcrossDaysAndMonths() {
        val original = loan("rounding", LocalDate.of(2026, 9, 15))
        val state = Snapshot(listOf(paid(original, original.installments.indices.associateWith { index ->
            if (index < 15) original.installments[index].due else LocalDate.of(2026, 10, 15)
        })))
        val cutoff = LocalDate.of(2026, 10, 31)
        val annual = periodReport(state, cutoff, 2026, 0)!!
        assertEquals(annual.interest, annual.movements.sumOf { it.interest })
        assertEquals(original.agreed - original.principal, annual.interest)
        val september = periodReport(state, cutoff, 2026, 9)!!
        val october = periodReport(state, cutoff, 2026, 10)!!
        assertEquals(annual.interest, september.interest + october.interest)
        assertEquals(october.interest, october.movements.sumOf { it.interest })
        assertEquals(annual.receipts, annual.recovered + annual.interest + annual.late)
    }
    @Test fun projectionsAndFutureChecksNeverBecomeChartRevenue() {
        val original = loan("future", day)
        val falselyUnchecked = original.copy(installments = original.installments.map { it.copy(paidOn = day) })
        val report = periodReport(Snapshot(listOf(falselyUnchecked)), day, 2026, 10)!!
        assertEquals(0L, report.receipts)
        assertEquals(0L, report.interest)
        assertEquals(0L, report.movements.sumOf { it.receipts })
        assertTrue(report.projected > 0)
        assertEquals(original.principal, report.pendingCapital)
        assertNull(periodReport(Snapshot(listOf(original)), day, 2026, 11))
    }
    @Test fun contributionsIncreaseCashButNeverEarningsOrReceipts() {
        val report = periodReport(Snapshot(listOf(loan("external", day, 100_000, 150_000))), day, 2026, 10)!!
        assertEquals(50_000L, report.cashChange)
        assertEquals(150_000L, report.contribution)
        assertEquals(0L, report.earnings)
        assertEquals(0L, report.receipts)
        assertEquals(0L, report.movements.sumOf { it.earnings })
    }
    @Test fun transferredLoanDoesNotCreateAnotherDisbursement() {
        val original = loan("stable", LocalDate.of(2026, 12, 20), 50_000)
        val cutoff = LocalDate.of(2027, 1, 15)
        val report = periodReport(Snapshot(listOf(paid(original, mapOf(0 to cutoff)))), cutoff, 2027, 0)!!
        assertEquals(0L, report.disbursed)
        assertEquals(0L, report.movements.sumOf { it.disbursed })
        assertEquals(original.installments.first().base, report.receipts)
        assertTrue(report.pendingCapital > 0)
    }
    @Test fun compositionMatchesCollectionsAndDebtExactly() {
        val original = loan("components", day.minusDays(10), 50_000)
        val state = Snapshot(listOf(paid(original, mapOf(0 to day.minusDays(1)), 50)))
        val report = periodReport(state, day, 2026, 10)!!
        assertEquals(report.receipts, report.recovered + report.interest + report.late)
        assertEquals(report.debt, report.pendingCapital + report.pendingInterest + report.pendingLate)
        assertEquals(50L, report.late)
        assertTrue(report.pendingLate > 50)
        assertEquals(Rules.summary(state, day).pendingCapital, report.pendingCapital)
    }
    @Test fun dailyPeriodsIncludeLeapDayAndStopAtCutoff() {
        val state = Snapshot(emptyList())
        val full = periodReport(state, LocalDate.of(2028, 3, 1), 2028, 2)!!
        assertEquals(29, full.movements.size)
        assertEquals(30, full.cashEvolution.size)
        assertFalse(full.monthly)
        val partial = periodReport(state, LocalDate.of(2028, 2, 15), 2028, 2)!!
        assertEquals(15, partial.movements.size)
        assertEquals(LocalDate.of(2028, 2, 15), partial.movements.last().end)
    }
    @Test fun firstDayAnnualChartKeepsMonthlyGrouping() {
        val report = periodReport(Snapshot(emptyList()), LocalDate.of(2026, 1, 1), 2026, 0)!!
        assertTrue(report.monthly)
        assertEquals(1, report.movements.size)
        assertEquals(report.openingCash, report.closingCash)
        assertEquals(0L, report.debt)
    }
    @Test fun flatAndEmptyAxesNeverDivideByZero() {
        for (values in listOf(emptyList(), listOf(0L), List(30) { 700_000L })) {
            val axis = moneyAxis(values)
            assertTrue(axis.maximum > axis.minimum)
            assertTrue(axis.ticks.all { it.isFinite() })
            assertTrue(axis.fraction(values.firstOrNull() ?: 0).isFinite())
        }
        val bars = moneyAxis(listOf(0), includeZero = true)
        assertEquals(0.0, bars.minimum, 0.0)
    }
    @Test fun axesCoverNegativeAndLargeAmounts() {
        val values = listOf(-150_000L, 0L, 90_123L, 1_000_000_000L)
        val axis = moneyAxis(values, includeZero = true)
        assertTrue(axis.minimum <= values.min())
        assertTrue(axis.maximum >= values.max())
        assertTrue(axis.ticks.contains(0.0))
        assertTrue(axis.fraction(values.first()) < axis.fraction(values.last()))
    }
    @Test fun touchingEndpointsAndOutsidePlotSelectsValidData() {
        assertEquals(0, nearestChartIndex(-1f, 31))
        assertEquals(30, nearestChartIndex(2f, 31))
        assertEquals(15, nearestChartIndex(0.5f, 31))
        assertEquals(0, nearestChartIndex(0.5f, 1))
    }
}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Reproduces the dates and amounts observed in the test book, using anonymous clients. */
class LegacyOctoberCashTest {
    private val day = LocalDate.of(2026, 10, 20)
    private val paidOn = LocalDate.of(2026, 10, 17)
    private fun loan(id: String, amount: Long, date: LocalDate) = Rules.createLoan(id,
        LoanRequest("Cliente de prueba", amount, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), date)
    private fun scenario(earlyCollections: Boolean = false, contribution: Long = 0): SimpleBook {
        val first = loan("2026-005", 100_000, LocalDate.of(2026, 9, 27)).let { loan ->
            loan.copy(installments = loan.installments.mapIndexed { i, item ->
                when {
                    i < 20 -> item.copy(paidOn = if (earlyCollections) item.due else paidOn)
                    i == 21 -> item.copy(paidOn = LocalDate.of(2026, 10, 19))
                    else -> item
                }
            })
        }
        val second = loan("2026-006", 50_000, LocalDate.of(2026, 10, 14)).let { loan ->
            loan.copy(funding = if (contribution > 0) Funding.EXTERNAL else Funding.CAPITAL, contribution = contribution,
                installments = loan.installments.mapIndexed { i, item ->
                    item.copy(base = 1700, paidOn = when (i) {
                        0, 1, 2 -> paidOn
                        4 -> LocalDate.of(2026, 10, 19)
                        else -> null
                    }, collectedLate = when (i) { 0 -> 200; 1 -> 100; else -> 0 })
                })
        }
        return SimpleBook("Pruebas", 100_000, "capital", LocalDate.of(2026, 12, 31),
            listOf(SimpleRecord(5, first, 100, false, "first"), SimpleRecord(6, second, 100, false, "second")), Instant.EPOCH)
    }
    @Test fun octoberUsesADatedCapitalContributionForAnOldUnfundedLoan() {
        val book = scenario(); val cash = book.cash(day)
        val report = bookCashReport(cash, day, 2026, 10)!!
        assertTrue(report.cashEvolution.all { it >= 0 })
        assertEquals(50_000L, cash.single { it.date == LocalDate.of(2026, 10, 14) }.assumedContribution)
        assertEquals(0L, cash.single { it.date == LocalDate.of(2026, 10, 14) }.cash)
        assertEquals(73_500L, cash.single { it.date == paidOn }.receipts)
        assertEquals(78_590L, report.closingCash)
        assertEquals(report.closingCash, book.summary(2026, day).cash)
        assertEquals(100_000L, book.summary(2026, day).initial)
        assertEquals(50_000L, book.summary(2026, day).assumedContribution)
        assertEquals(50_000L, report.contribution)
        assertEquals(report.cashChange, report.receipts + report.contribution - report.disbursed)
        assertTrue(book.records.all { it.loan.contribution == 0L }) // Read-only reconciliation.
    }
    @Test fun alreadyRecordedCapitalIsNeverAddedTwice() {
        val book = scenario(contribution = 50_000)
        assertEquals(78_590L, book.summary(2026, day).cash)
        assertEquals(0L, book.summary(2026, day).assumedContribution)
        assertEquals(50_000L, book.cash(day).sumOf { it.contribution })
    }
    @Test fun receiptsAvailableBeforeTheSecondLoanReduceTheMissingCapital() {
        val book = scenario(earlyCollections = true)
        // By October 14 the first loan had returned 579.30, so 500 can be reinvested.
        assertEquals(0L, book.summary(2026, day).assumedContribution)
        assertTrue(book.cash(day).all { it.cash >= 0 })
        assertEquals(28_590L, book.summary(2026, day).cash)
    }
    @Test fun reconciliationIsIdempotentAndSurvivesCashCacheRecreation() {
        val book = scenario(); val rows = book.cash(day)
        assertEquals(rows, reconcileLegacyFunding(rows))
        val cache = MemorySheetCache(); val store = OfflineBookStore(cache, "account", SheetsIdentity.EMPTY_TEST_ID)
        store.saveCash(rows)
        val restored = OfflineBookStore(cache, "account", SheetsIdentity.EMPTY_TEST_ID).cash()
        assertEquals(rows, restored)
        assertEquals(50_000L, restored.sumOf { it.assumedContribution })
    }
    @Test fun localDateCorrectionsRecomputeTheAssumedContributionAndSummaryTogether() {
        val date = LocalDate.of(2026, 10, 14)
        val rows = listOf(BookCashDay(date, 0, 0, 50_000, -50_000),
            BookCashDay(date.plusDays(1), 0, 50_000, 0, 0))
        val adjusted = reconcileLegacyFunding(rows)
        val source = SheetPayment(scenario().active.first().view(day), date, 50_000, false, 0, date.plusDays(1))
        val changes = mapOf(source.id to (source to PaymentEdit(source.loan.id, source.id, 0, false, 0, date)))
        val corrected = localCashRows(adjusted, changes, true, day)
        assertEquals(0L, corrected.sumOf { it.assumedContribution })
        val summary = SheetSummary(2026, date.plusDays(1), 100_000, 0, 50_000, 50_000, 50_000, 0, 0, 0, 50_000, 0, 50_000)
        assertEquals(corrected.last().cash, effectiveSheetSummary(summary, changes, corrected).cash)
    }
    @Test fun anUnknownPaymentDateIsStillReportedInsteadOfBeingInvented() {
        val before = scenario(); val first = before.records.first()
        val missing = first.loan.installments.first().due
        val changed = first.copy(unverifiedPaid = setOf(missing), loan = first.loan.copy(installments = first.loan.installments.map {
            if (it.due == missing) it.copy(paidOn = null) else it
        }))
        assertThrows(IllegalArgumentException::class.java) { before.copy(records = listOf(changed) + before.records.drop(1)).cash(day) }
    }
}

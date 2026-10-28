package com.example.controlprestamos.domain

import com.example.controlprestamos.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*
import java.util.concurrent.Executors

class BusinessRulesTest {
    private val day = LocalDate.of(2026, 10, 11)
    private val clock = Clock.fixed(day.atTime(12, 0).atZone(LIMA).toInstant(), LIMA)
    private fun request(amount: Long = 50_000, contribution: Long = 0, months: Int = 1, rate: String = "2") =
        LoanRequest("Cliente ficticio", amount, BigDecimal(rate), months, "", if (contribution > 0) Funding.EXTERNAL else Funding.CAPITAL, contribution)
    private fun loan(start: LocalDate = day.minusDays(2)) = Rules.createLoan("loan", request(), start)
    private class MemoryStore(var snapshot: Snapshot? = Snapshot(emptyList())) : SnapshotStore {
        var fail = false
        override fun load() = snapshot
        override fun save(snapshot: Snapshot) { if (fail) error("Disco lleno"); this.snapshot = snapshot }
    }
    private fun repository(snapshot: Snapshot = Snapshot(emptyList())) = DemoLoanRepository(MemoryStore(snapshot), clock)
    private fun edit(loan: Loan, index: Int = 0, unpaid: Boolean = false, late: Long = 0) =
        loan.installments[index].let { PaymentEdit(loan.id, it.id, it.revision, unpaid, late) }
    private fun rejects(block: () -> Unit) { try { block(); fail("Debió rechazar la operación") } catch (_: IllegalArgumentException) { } }

    @Test fun externalContributionLessThanLoan() { assertEquals(640_000, Rules.cashAfter(700_000, 100_000, 40_000)) }
    @Test fun externalContributionEqualToLoan() { assertEquals(700_000, Rules.cashAfter(700_000, 100_000, 100_000)) }
    @Test fun externalContributionGreaterThanLoan() { assertEquals(750_000, Rules.cashAfter(700_000, 100_000, 150_000)) }
    @Test fun capitalLoanUsesCash() { assertEquals(600_000, Rules.cashAfter(700_000, 100_000, 0)) }
    @Test fun insufficientFundsRecordOnlyTheMissingCapitalAtomically() {
        val repo = repository(); val first = repo.create("no-funds", request(800_000))
        assertEquals(100_000L, first.loans.single().contribution)
        assertEquals(0L, Rules.summary(first, day).cash)
        assertEquals(0L, Rules.summary(first, day).interest)
        assertEquals(first, repo.create("no-funds", request(800_000)))
    }
    @Test fun contributionsAreNeverProfit() {
        val state = repository().create("external", request(100_000, 150_000))
        val summary = Rules.summary(state, day)
        assertEquals(750_000, summary.cash); assertEquals(0, summary.interest); assertEquals(0, summary.late)
        assertEquals(100_000, summary.pendingCapital)
    }
    @Test fun verifiedSpreadsheetRounding500At2Percent() {
        val loan = loan()
        assertEquals(51_000, loan.agreed); assertEquals(1_990, loan.installments.first().base)
        assertEquals(1_690, loan.installments.last().base); assertEquals(30, loan.installments.size)
    }
    @Test fun verifiedSpreadsheetTwoMonths() {
        val loan = Rules.createLoan("test", request(100_000, months = 2), day)
        assertEquals(104_000, loan.agreed); assertEquals(2_520, loan.installments.first().base)
        assertEquals(1_720, loan.installments.last().base); assertEquals(60, loan.installments.size)
    }
    @Test fun schedulePreservesAgreedAmountAcrossInputs() {
        for (amount in listOf(10_001L, 50_000L, 100_123L)) for (months in 1..12) {
            val loan = Rules.createLoan("t", request(amount, months = months, rate = "2.37"), day)
            val expected = BigDecimal(amount).multiply(BigDecimal.ONE + BigDecimal("0.0237") * BigDecimal(months))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
            assertEquals(expected, loan.agreed)
        }
    }
    @Test fun firstInstallmentIsNextDayInLima() {
        val midnightUtc = Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC)
        val loan = Rules.createLoan("t", request(), today(midnightUtc))
        assertEquals(LocalDate.of(2026, 10, 12), loan.installments.first().due)
    }
    @Test fun lateFeeStartsAfterDueDate() {
        val cuota = Installment("t", day, 1500)
        assertEquals(0, cuota.late(day)); assertEquals(100, cuota.late(day.plusDays(1)))
        assertEquals(1600, cuota.base + cuota.late(day.plusDays(1)))
    }
    @Test fun futureInstallmentIsNotRevenueEvenIfUnchecked() {
        val loan = loan(day).let { l -> l.copy(installments = l.installments.map { it.copy(paidOn = it.due) }) }
        assertEquals(0, loan.receipts(day)); assertEquals(0, loan.paidCount(day))
        rejects { repository(Snapshot(listOf(loan))).pay("future", listOf(edit(loan))) }
    }
    @Test fun eachInstallmentIsIndependent() {
        val loan = loan(); val state = repository(Snapshot(listOf(loan))).pay("one", listOf(edit(loan)))
        assertEquals(1, state.loans.single().paidCount(day))
        assertNull(state.loans.single().installments[1].paidOn)
    }
    @Test fun paidFeeFreezesAndComponentsAreSeparated() {
        val loan = loan(); val state = repository(Snapshot(listOf(loan))).pay("fee", listOf(edit(loan, late = 100)))
        val updated = state.loans.single(); val summary = Rules.summary(state, day.plusDays(100))
        assertEquals(100, updated.installments.first().late(day.plusDays(100)))
        assertEquals(100, summary.late)
        assertEquals(updated.installments.first().base, summary.recovered + summary.interest)
        assertEquals(50_000 - summary.recovered, summary.pendingCapital)
    }
    @Test fun reducedFeeCanBeCollected() {
        val loan = loan(); val state = repository(Snapshot(listOf(loan))).pay("discount", listOf(edit(loan, late = 50)))
        assertEquals(50, state.loans.single().installments.first().collectedLate)
    }
    @Test fun recordedFeeCanBeReducedThenIncreasedWithoutChangingPaymentDate() {
        val loan = loan(); val repo = repository(Snapshot(listOf(loan)))
        val discounted = repo.pay("discount", listOf(edit(loan, late = 50))).loans.single()
        val adjusted = repo.pay("increase", listOf(edit(discounted, late = 100))).loans.single()
        assertEquals(day, adjusted.installments.first().paidOn)
        assertEquals(100, adjusted.installments.first().collectedLate)
        val forgiven = repo.pay("forgive", listOf(edit(adjusted, late = 0))).loans.single()
        assertEquals(day, forgiven.installments.first().paidOn)
        val restored = repo.pay("restore", listOf(edit(forgiven, late = 100))).loans.single()
        assertEquals(100, restored.installments.first().collectedLate)
        assertEquals(100, Rules.summary(repo.read(), day).late)
    }
    @Test fun overchargedFeeRejected() { val loan = loan(); rejects { repository(Snapshot(listOf(loan))).pay("fee", listOf(edit(loan, late = 200))) } }
    @Test fun markedCheckMeansUnpaidAndRestartsFee() {
        val loan = loan(); val repo = repository(Snapshot(listOf(loan)))
        val paid = repo.pay("paid", listOf(edit(loan, late = 100))).loans.single()
        val unpaid = repo.pay("reopen", listOf(edit(paid, unpaid = true))).loans.single()
        assertEquals(0, unpaid.receipts(day)); assertEquals(100, unpaid.installments.first().late(day))
        assertEquals(0, unpaid.installments.first().collectedLate)
    }
    @Test fun sameCreateKeyDoesNotDuplicateAfterRestart() {
        val store = MemoryStore(); val repo = DemoLoanRepository(store, clock)
        repo.create("same", request()); repo.create("same", request())
        val restarted = DemoLoanRepository(store, clock); restarted.create("same", request())
        assertEquals(1, restarted.read().loans.size); assertEquals(1, restarted.read().audits.size)
    }
    @Test fun keyReuseWithChangedPayloadRejected() { val repo = repository(); repo.create("same", request()); rejects { repo.create("same", request(60_000)) } }
    @Test fun doubleTapConcurrentRequestsCommitOnce() {
        val repo = repository(); val pool = Executors.newFixedThreadPool(2)
        try { pool.invokeAll(List(2) { java.util.concurrent.Callable { repo.create("same", request()) } }).forEach { it.get() } }
        finally { pool.shutdown() }
        assertEquals(1, repo.read().loans.size)
    }
    @Test fun samePaymentRetryDoesNotDuplicateAudit() {
        val loan = loan(); val repo = repository(Snapshot(listOf(loan))); val batch = listOf(edit(loan))
        repo.pay("same", batch); repo.pay("same", batch)
        assertEquals(1, repo.read().audits.size); assertEquals(1, repo.read().loans.single().paidCount(day))
    }
    @Test fun staleRevisionRejectsWholeBatch() {
        val loan = loan(); val repo = repository(Snapshot(listOf(loan)))
        repo.pay("first", listOf(edit(loan)))
        rejects { repo.pay("stale", listOf(edit(loan), edit(loan, 1))) }
        assertEquals(1, repo.read().loans.single().paidCount(day)); assertEquals(1, repo.read().audits.size)
    }
    @Test fun batchRejectsDuplicateInstallments() { val loan = loan(); rejects { repository(Snapshot(listOf(loan))).pay("dupe", listOf(edit(loan), edit(loan))) } }
    @Test fun batchAcrossLoansAndDays() {
        val a = loan(); val b = a.copy(id = "b", installments = a.installments.map { it.copy(id = "b:${it.id}") })
        val repo = repository(Snapshot(listOf(a,b)))
        val state = repo.pay("batch", listOf(edit(a), edit(a, 1), edit(b)))
        assertEquals(3, state.audits.size); assertEquals(3, state.loans.sumOf { it.paidCount(day) })
    }
    @Test fun failedPersistenceDoesNotChangeState() {
        val store = MemoryStore(); val repo = DemoLoanRepository(store, clock); store.fail = true
        try { repo.create("disk", request()); fail() } catch (_: IllegalStateException) { }
        assertTrue(repo.read().loans.isEmpty()); store.fail = false
        assertEquals(1, repo.create("disk", request()).loans.size)
    }
    @Test fun crossYearKeepsIdentityAndDisbursesOnce() {
        val loan = Rules.createLoan("stable", request(), LocalDate.of(2026,12,20))
        assertTrue(loan.belongsToYear(2026)); assertTrue(loan.belongsToYear(2027))
        val snapshot = Snapshot(listOf(loan))
        assertEquals(50_000, Rules.summary(snapshot, LocalDate.of(2027,1,15)).disbursed)
        assertEquals(0, periodReport(snapshot, LocalDate.of(2027,1,15), 2027, 1)!!.disbursed)
    }
    @Test fun fullyPaidFractionNeverExceedsSchedule() {
        val loan = loan(day.minusDays(60)).let { l -> l.copy(installments = l.installments.map { it.copy(paidOn = it.due, collectedLate = 300) }) }
        assertEquals(30, loan.paidCount(day)); assertFalse(loan.active(day))
        assertEquals(0, loan.balance(day)); assertEquals(50_000, loan.recovered(day))
    }
    @Test fun reportsUsePaymentDateAndSeparateProjections() {
        val loan = loan(LocalDate.of(2026,9,29)); val state = repository(Snapshot(listOf(loan))).pay("october", listOf(edit(loan)))
        assertEquals(0, periodReport(state, day, 2026, 9)!!.receipts)
        assertEquals(loan.installments.first().base, periodReport(state, day, 2026, 10)!!.receipts)
        assertTrue(periodReport(state, day, 2026, 10)!!.projected > 0)
        assertNull(periodReport(state, day, 2027, 0))
    }
    @Test fun moneyParsingRejectsAmbiguousPrecision() {
        assertEquals(1234L, parseMoney("12,34")); assertEquals(1234L, parseMoney("12.34"))
        assertNull(parseMoney("12.345")); assertNull(parseMoney("-1")); assertNull(parseMoney("1,000.00"))
    }
    @Test fun sheetsCarriedIdentityIsCanonical() {
        assertEquals("2026-001", com.example.controlprestamos.data.google.SheetsIdentity.canonical(2027, "2026-001"))
        assertEquals("2026-001", com.example.controlprestamos.data.google.SheetsIdentity.canonical(2026, "1"))
    }
    @Test fun sheetsFractionRejectsCountsAboveTotal() {
        rejects { com.example.controlprestamos.data.google.SheetsIdentity.paidFraction("31/30") }
        assertEquals(30 to 30, com.example.controlprestamos.data.google.SheetsIdentity.paidFraction("30/30"))
    }
    @Test fun remotePreparationRejectsProduction() {
        rejects { com.example.controlprestamos.data.google.SheetsIdentity.requireCopy(com.example.controlprestamos.data.google.SheetsIdentity.PRODUCTION_ID) }
    }
}

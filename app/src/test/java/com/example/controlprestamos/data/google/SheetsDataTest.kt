package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class SheetsDataTest {
    private val day = LocalDate.of(2026, 10, 11)
    // Formula values observed in the book; no customer identities are included.
    private fun row(id: Any = 1, fraction: String = "30/30") = listOf(
        id, "Cliente de prueba", fraction, 500, 0.02, 1, null, 510, 510, 0,
        0.3333333333333333, 0.3, 16.9, 19.900000000000055, 0, 46220, 46249)
    private fun loan() = parseSheetLoans(2026, listOf(row())).single()
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Debía rechazar los datos") } catch (_: IllegalArgumentException) { }
    }

    @Test fun serialsUseSheetsEpochAndLimaCalendar() {
        assertEquals(day, sheetDate(46306))
        assertEquals(LocalDate.of(1899, 12, 30), sheetDate(0))
        assertEquals(46306L, sheetSerial(day))
    }
    @Test fun leapDayRoundTrips() {
        val date = LocalDate.of(2028, 2, 29)
        assertEquals(date, sheetDate(sheetSerial(date)))
    }
    @Test fun apiSupportsIsoAndSerialPaymentDates() {
        assertEquals(day, sheetDate("2026-10-11"))
        assertEquals(day, sheetDate("46306.0"))
    }
    @Test fun fractionalDatesAreRejected() { rejects { sheetDate(46306.5) } }
    @Test fun absentDatesAreRejected() { rejects { sheetDate(null) }; rejects { sheetDate("") } }
    @Test fun formulaNoiseRoundsOnceToCents() {
        assertEquals(471930L, sheetCents(4719.299999999999))
        assertEquals(1990L, sheetCents(19.900000000000055))
        assertEquals(468L, sheetCents("4.675"))
        assertEquals(-100L, sheetCents(-1))
    }
    @Test fun sheetColumnsCoverAnnualCalendars() {
        assertEquals("A", columnName(1)); assertEquals("Z", columnName(26))
        assertEquals("AA", columnName(27)); assertEquals("QK", columnName(453))
        assertEquals("AAA", columnName(703))
    }
    @Test fun loansKeepMoneyRatesAndPaidCount() {
        val loan = loan()
        assertEquals("2026-001", loan.id); assertEquals(50000L, loan.principal)
        assertEquals(0, loan.ratePercent.compareTo(BigDecimal("2")))
        assertEquals(1990L, loan.first); assertEquals(1690L, loan.daily)
        assertEquals(30, loan.paid); assertEquals(30, loan.total); assertFalse(loan.active)
    }
    @Test fun blankFormulaRowsKeepPhysicalPositions() {
        val loans = parseSheetLoans(2026, listOf(emptyList(), row()))
        assertEquals(7, loans.single().row)
    }
    @Test fun carriedLoansPreserveOriginIdentity() {
        val loan = parseSheetLoans(2027, listOf(row("2026-001")), 406).single()
        assertEquals("2026-001", loan.id); assertEquals(2027, loan.year); assertEquals(406, loan.row)
    }
    @Test fun repeatedIdentitiesAreRejected() { rejects { parseSheetLoans(2026, listOf(row(), row())) } }
    @Test fun impossiblePaidCountsAreRejected() {
        rejects { parseSheetLoans(2026, listOf(row(fraction = "31/30"))) }
        rejects { parseSheetLoans(2026, listOf(row(fraction = "0/60"))) }
    }
    @Test fun pendingFirstInstallmentHasIndependentDailyLateFee() {
        val loan = loan(); val due = loan.firstDue
        val payment = parsePayment(loan, due, listOf(true, 1, true, 0, 0), 0, due.plusDays(2))!!
        assertTrue(payment.unpaid); assertEquals(1990L, payment.base); assertEquals(200L, payment.late)
    }
    @Test fun paidInstallmentKeepsCollectedFeeInsteadOfGrowing() {
        val loan = loan(); val due = loan.firstDue.plusDays(1)
        val payment = parsePayment(loan, due, listOf(true, 2, false, 16.9, 0), 0.5, due.plusDays(9))!!
        assertFalse(payment.unpaid); assertEquals(1690L, payment.base); assertEquals(50L, payment.late)
        assertNull(payment.paidOn)
    }
    @Test fun futureAndInvalidInstallmentsAreExcluded() {
        val loan = loan(); val due = loan.firstDue
        assertNull(parsePayment(loan, due, listOf(true, 1, false), 0, due.minusDays(1)))
        assertNull(parsePayment(loan, due, listOf(false, 1, false), 0, due))
        assertNull(parsePayment(loan, loan.lastDue.plusDays(1), listOf(true, 1, false), 0, day))
    }
    @Test fun invalidChecksAndNegativeCollectedFeesAreRejected() {
        val loan = loan()
        rejects { parsePayment(loan, loan.firstDue, listOf(true, 1, "TRUE"), 0, day) }
        rejects { parsePayment(loan, loan.firstDue, listOf(true, 1, false), -1, day) }
    }
    @Test fun summaryUsesCapitalControlAndKeepsContributionsSeparate() {
        val summary = parseSheetSummary(2026, listOf(2026, 46306, 7000, 500, 1000, 200, 20, 1, 179, 6700, 821, 7521, 0), 7000)
        assertEquals(670000L, summary.cash); assertEquals(82100L, summary.pendingCapital)
        assertEquals(2100L, summary.earnings); assertEquals(50000L, summary.contribution)
        rejects { parseSheetSummary(2026, listOf(2026, 46306, 7000, 500, 1000, 200, 20, 1, 179, 6700, 821, 7521, 3), 7000) }
    }
    @Test fun cashReportReconcilesAndExcludesFutureRows() {
        val rows = listOf(BookCashDay(day.minusDays(1), 50000, 20000, 100000, 670000),
            BookCashDay(day, 0, 10000, 0, 680000), BookCashDay(day.plusDays(1), 0, 999999, 0, 1679999))
        val report = bookCashReport(rows, day, 2026, 10)!!
        assertEquals(700000L, report.openingCash); assertEquals(680000L, report.closingCash)
        assertEquals(30000L, report.receipts); assertEquals(0L, report.interest); assertEquals(0L, report.late)
    }
    @Test fun cashReportRejectsMissingDaysAndBrokenBalances() {
        rejects { bookCashReport(listOf(BookCashDay(day.minusDays(2), 0, 0, 0, 100), BookCashDay(day, 0, 0, 0, 100)), day, 2026, 10) }
        rejects { bookCashReport(listOf(BookCashDay(day.minusDays(1), 0, 0, 0, 100), BookCashDay(day, 0, 0, 0, 500)), day, 2026, 10) }
        assertNull(bookCashReport(listOf(BookCashDay(day.plusDays(1), 0, 1, 0, 1)), day, 2026, 10))
    }
    @Test fun cacheAndOutboxAreIsolatedByOwnerAndBook() {
        val copy = "COPIA_LOCAL_FICTICIA_1234567890"
        assertNotEquals(remoteCacheKey("a", copy), remoteCacheKey("b", copy))
        assertNotEquals(remoteCacheKey("a", copy), remoteCacheKey("a", SheetsIdentity.PRODUCTION_ID))
        val pending = PendingRemoteWrite("operation-001", "a", copy, "{}")
        pending.requireOwner("a", copy)
        rejects { pending.requireOwner("b", copy) }
        rejects { pending.requireOwner("a", SheetsIdentity.PRODUCTION_ID) }
        val principal = PendingRemoteWrite("operation-001", "a", SheetsIdentity.PRODUCTION_ID, "{}")
        principal.requireOwner("a", SheetsIdentity.PRODUCTION_ID)
        rejects { principal.requireOwner("a", copy) }
        rejects { PendingRemoteWrite("operation-001", "a", "short", "{}") }
    }
    @Test fun principalPaymentsAreConfiguredIndependentlyOfCopyVerification() {
        val connection = GoogleConnection(SheetsIdentity.PRODUCTION_ID, "COPIA_LOCAL_FICTICIA_1234567890", "DEPLOYMENT_LOCAL_FICTICIO_123456", listOf(READ_SCOPE), true)
        assertTrue(connection.canWrite(SheetsIdentity.PRODUCTION_ID)); assertTrue(connection.canWrite(connection.testBookId))
        assertTrue(connection.copy(copyWritesVerified = false).canWrite(connection.testBookId))
        assertTrue(connection.copy(copyWritesVerified = false).canWrite(SheetsIdentity.PRODUCTION_ID))
        assertTrue(connection.canCreateLoan(SheetsIdentity.PRODUCTION_ID)); assertTrue(connection.canCreateLoan(connection.testBookId))
        assertFalse(connection.scopes(false).contains("https://www.googleapis.com/auth/script.scriptapp"))
        assertTrue(connection.scopes(false).contains(WRITE_SCOPE))
        assertFalse(connection.copy(deploymentId = "").canWrite(connection.testBookId))
        assertFalse(connection.copy(scriptScopes = emptyList()).canWrite(connection.testBookId))
        assertTrue(connection.copy(deploymentId = "").canWrite(SheetsIdentity.PRODUCTION_ID))
        assertTrue(connection.copy(scriptScopes = emptyList()).canWrite(SheetsIdentity.PRODUCTION_ID))
        assertTrue(connection.usesDirectPayments(SheetsIdentity.PRODUCTION_ID))
        assertFalse(connection.usesDirectPayments(connection.testBookId))
    }
}

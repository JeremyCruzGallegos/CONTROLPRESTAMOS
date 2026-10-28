package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DirectPaymentEditsTest {
    private val day = LocalDate.of(2026, 10, 13)
    private val book = SheetsIdentity.PRODUCTION_ID
    private val loan = SheetLoan("2026-001", "1", 2026, 6, "Cliente", 50000, BigDecimal("2"), 1,
        "", 51000, 0, 51000, 1690, 1990, day.minusDays(10), day.plusDays(19), 1, 30)
    private val due = day.minusDays(3)
    private val paid = SheetPayment(loan, due, 1690, false, 0)
    private val version = directPaymentVersion(book, paid, 0)
    private val current = DirectPaymentSnapshot(loan, due, true, false, 0, null)
    private fun validate(change: DirectPaymentChange, snapshot: DirectPaymentSnapshot = current, targetBook: String = book) =
        validateDirectPaymentChange(targetBook, change, snapshot, day)
    private fun rejects(change: DirectPaymentChange, snapshot: DirectPaymentSnapshot = current, targetBook: String = book) {
        assertThrows(IllegalArgumentException::class.java) { validate(change, snapshot, targetBook) }
    }

    @Test fun paidDefaultCanBeMarkedUnpaidWithoutInventingDateOrChargingLate() {
        validate(DirectPaymentChange(version, true, 0, null))
        rejects(DirectPaymentChange(version, true, 100, null))
        rejects(DirectPaymentChange(version, true, 0, day))
    }
    @Test fun blankAndUncheckedArePaidButExplicitUnpaidIsPreserved() {
        for (check in listOf(null, "", false)) {
            val row = parsePayment(loan, due, listOf(true, 4, check, 16.9, 0), null, day)!!
            assertFalse(row.unpaid); assertEquals(0L, row.late)
        }
        assertTrue(parsePayment(loan, due, listOf(true, 4, true, 0, 3), null, day)!!.unpaid)
        assertNull(parsePayment(loan, day.plusDays(1), listOf(true, 5, false), null, day))
    }
    @Test fun pendingPaymentCanBeCollectedWithDiscountOrNoMora() {
        val before = version.copy(unpaid = true)
        val pending = current.copy(unpaid = true)
        validate(DirectPaymentChange(before, false, 300, day), pending)
        validate(DirectPaymentChange(before, false, 50, day), pending)
        validate(DirectPaymentChange(before, false, 0, day), pending)
        rejects(DirectPaymentChange(before, false, 301, day), pending)
        validate(DirectPaymentChange(before, false, 0, day.minusDays(1)), pending)
    }
    @Test fun historicalPaymentRequiresExplicitDateAndFreezesLimitAtThatDate() {
        validate(DirectPaymentChange(version, false, 200, day.minusDays(1)))
        rejects(DirectPaymentChange(version, false, 201, day.minusDays(1)))
        rejects(DirectPaymentChange(version, false, 0, null))
        rejects(DirectPaymentChange(version, false, 0, due.minusDays(1)))
        rejects(DirectPaymentChange(version, false, 0, day.plusDays(1)))
    }
    @Test fun paidDateCannotBeMovedToIncreaseFee() {
        val before = version.copy(paidOn = day.minusDays(1), collectedLate = 50)
        val known = current.copy(paidOn = before.paidOn, collectedLate = 50)
        validate(DirectPaymentChange(before, false, 200, before.paidOn), known)
        validate(DirectPaymentChange(before, false, 0, before.paidOn), known)
        rejects(DirectPaymentChange(before, false, 300, day), known)
    }
    @Test fun staleStateFeeDateAndRevisionAllRejectBeforeAnyWrite() {
        val change = DirectPaymentChange(version, true, 0, null)
        rejects(change, current.copy(unpaid = true))
        rejects(change, current.copy(collectedLate = 1))
        rejects(change, current.copy(paidOn = due))
        rejects(change, current.copy(revision = "3:other-operation"))
    }
    @Test fun changedTermsOrInvalidControlOrFormulasRejectPayment() {
        val change = DirectPaymentChange(version, true, 0, null)
        rejects(change, current.copy(loan = loan.copy(daily = 1700)))
        rejects(change, current.copy(valid = false))
        rejects(change, current.copy(checkFormula = true))
        rejects(change, current.copy(feeFormula = true))
        rejects(change, current.copy(due = day.plusDays(1)))
        rejects(change, targetBook = "COPY_WITH_VALID_ID_1234567890")
    }
    @Test fun anotherQuotaChangingTotalsDoesNotInvalidateThisQuota() {
        validate(DirectPaymentChange(version, true, 0, null), current.copy(loan = loan.copy(paid = 2, collected = 1690, debt = 49310)))
    }
    @Test fun revisionGuardIsStableForConcurrentEditsAndChangesAfterAnAuditedCycle() {
        assertEquals(paymentRevisionKey(version), paymentRevisionKey(version.copy()))
        assertNotEquals(paymentRevisionKey(version), paymentRevisionKey(version.copy(revision = "3:payment-001")))
        assertNotEquals(paymentRevisionKey(version), paymentRevisionKey(version.copy(bookId = "OTHER_BOOK_ID_1234567890")))
        assertNotEquals(paymentRevisionKey(version), paymentRevisionKey(version.copy(due = due.minusDays(1))))
        assertTrue(paymentRevisionKey(version).matches(Regex("cp_rev_[a-f0-9]{40}")))
    }
    @Test fun retryReturnsReceiptWithoutAnotherWriteAndDifferentPayloadIsRejected() {
        val hash = directPaymentHash("payload")
        val rows = listOf(listOf<Any?>("operation-001", hash, "date", "receipt"))
        assertEquals("receipt", directPaymentReceipt(rows, "operation-001", hash))
        assertNull(directPaymentReceipt(rows, "operation-002", hash))
        assertThrows(IllegalArgumentException::class.java) { directPaymentReceipt(rows, "operation-001", directPaymentHash("changed")) }
        assertThrows(IllegalArgumentException::class.java) { directPaymentReceipt(rows + rows, "operation-001", hash) }
    }
    @Test fun auditRevisionSurvivesUnpaidPaidUnpaidCycle() {
        fun log(id: String, unpaid: Boolean, fee: Double, date: Any) =
            listOf<Any?>("date", id, 2026, 1, sheetSerial(due), !unpaid, unpaid, 0, fee, date, "Cliente", "owner")
        val first = PaymentHistoryIndex(listOf(log("first", true, 0.0, "")))
        val cycle = PaymentHistoryIndex(listOf(log("first", true, 0.0, ""), log("paid", false, 1.0, sheetSerial(day)), log("reopen", true, 0.0, "")))
        val row = paid.copy(unpaid = true, late = 300)
        assertNotEquals(first.revision(row), cycle.revision(row))
        assertNull(cycle.paidOn(row, day))
    }
}

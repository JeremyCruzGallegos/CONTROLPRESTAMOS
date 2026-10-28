package com.example.controlprestamos.data.google

import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PaymentEditsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val loan = SheetLoan("2026:1", "1", 2026, 6, "Cliente", 50000, BigDecimal("2"), 1,
        "", 51000, 0, 51000, 1690, 1990, day.minusDays(10), day.plusDays(19), 1, 30)
    private val pending = SheetPayment(loan, day.minusDays(3), 1690, true, 300, version = "version")
    private val paid = pending.copy(unpaid = false, paidOn = day.minusDays(1))

    @Test fun newPaymentAllowsFullDiscountAndZeroLate() {
        validateRemotePaymentEdit(pending, false, 300, day)
        validateRemotePaymentEdit(pending, false, 100, day)
        validateRemotePaymentEdit(pending, false, 0, day)
    }
    @Test fun recordedPaymentAllowsReductionKeepingKnownPaymentDate() {
        validateRemotePaymentEdit(paid, false, 200, day)
        validateRemotePaymentEdit(paid, false, 0, day)
    }
    @Test fun discountedRecordedFeeCanIncreaseUpToOriginalPaymentDateLimit() {
        val discounted = paid.copy(late = 50)
        validateRemotePaymentEdit(discounted, false, 200, day)
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(discounted, false, 201, day) }
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(discounted, false, 300, day.plusDays(20)) }
    }
    @Test fun sameDayPaymentHasZeroMaximumEvenWhenStoredFeeIsIncorrect() {
        val sameDay = paid.copy(paidOn = pending.due)
        validateRemotePaymentEdit(sameDay, false, 0, day)
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(sameDay, false, 1, day) }
    }
    @Test fun unknownHistoricalDateCannotBeInventedWhenAdjustingFee() {
        assertThrows(IllegalArgumentException::class.java) {
            validateRemotePaymentEdit(paid.copy(paidOn = null), false, 100, day)
        }
    }
    @Test fun unknownHistoricalDateCanBeSuppliedExplicitlyWithinContractualBounds() {
        val historical = paid.copy(paidOn = null, late = 0)
        validateRemotePaymentEdit(historical, false, 200, day, day.minusDays(1))
        assertThrows(IllegalArgumentException::class.java) {
            validateRemotePaymentEdit(historical, false, 201, day, day.minusDays(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateRemotePaymentEdit(historical, false, 0, day, day.plusDays(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateRemotePaymentEdit(historical, false, 0, day, historical.due.minusDays(1))
        }
    }
    @Test fun recordedDateCannotBeChangedButLateRegistrationAcceptsAnExplicitHistoricalDate() {
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(paid, false, 0, day, day) }
        validateRemotePaymentEdit(pending, false, 0, day, day.minusDays(1))
    }
    @Test fun paidCheckCanBeReturnedToUnpaidWithoutCollectingAnyFee() {
        validateRemotePaymentEdit(paid, true, 0, day)
        validateRemotePaymentEdit(paid.copy(paidOn = null), true, 0, day)
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(paid, true, 100, day) }
    }
    @Test fun staleUnversionedQuotaAndFutureQuotaAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(pending.copy(version = null), false, 100, day) }
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(pending.copy(due = day.plusDays(1)), false, 100, day) }
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(pending.copy(due = loan.firstDue.minusDays(1)), false, 100, day) }
    }
    @Test fun lateFeeCannotBeNegativeOrExceedCurrentFee() {
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(paid, false, -1, day) }
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(paid, false, 301, day) }
    }
    @Test fun invalidRecordedDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(paid.copy(paidOn = pending.due.minusDays(1)), false, 0, day) }
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(paid.copy(paidOn = day.plusDays(1)), false, 0, day) }
    }
}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class LoanTermsEditsTest {
    private val day = LocalDate.of(2026, 10, 16)
    private val start = day.minusDays(10)
    private val loan = SheetLoan("2026-001", "1", 2026, 6, "Ana", 50000, BigDecimal("2"), 1, "Equipo",
        51000, 0, 51000, 1690, 1990, start.plusDays(1), start.plusDays(30), 0, 30)
    private fun input() = LoanTermsInput(loan.client, loan.guarantee, loan.principal, loan.ratePercent, loan.months, start, loan.dailyLate)
    private fun calendar(paid: Set<Int> = emptySet(), source: SheetLoan = loan): LoanCalendar = LoanCalendar(source.id,
        loanDates(source).mapIndexed { index, due ->
            val status = when { due > day -> CalendarStatus.FUTURE; index in paid -> CalendarStatus.PAID; else -> CalendarStatus.PENDING }
            val payment = if (due > day) null else SheetPayment(source, due, source.baseOn(due), index !in paid,
                if (index in paid) 50 else source.lateAt(due, day), if (index in paid) due.plusDays(1) else null, "version-$due")
            SheetCalendarDay(due, index + 1, status, index !in paid, due <= day,
                payment?.let { it.base + it.late }, payment?.late, payment, index + 1)
        })
    private fun plan(value: LoanTermsInput = input(), paid: Set<Int> = emptySet(), source: SheetLoan = loan) =
        planLoanTerms(source, calendar(paid, source), value, day, 100000)

    @Test fun rateAndTermChangesPreserveCollectedDatesAmountsAndDistributeEveryCent() {
        val result = plan(input().copy(rate = BigDecimal("3.5"), months = 2), setOf(0, 2, 4))
        assertEquals(53500L, result.agreed)
        assertEquals(5370L, result.collectedBase); assertEquals(150L, result.collectedLate)
        assertEquals(setOf(loan.firstDue, loan.firstDue.plusDays(2), loan.firstDue.plusDays(4)), result.paidDates)
        assertEquals(1990L, result.bases.getValue(loan.firstDue))
        assertEquals(1690L, result.bases.getValue(loan.firstDue.plusDays(2)))
        assertEquals(57, result.pendingCount)
        assertEquals(result.agreed, result.bases.values.sum())
        val pendingBases = result.bases.filterKeys { it !in result.paidDates }.values
        assertTrue(pendingBases.max() - pendingBases.min() <= 1L)
        assertEquals(100000L, result.cashAfter)
    }
    @Test fun justChangingNameGuaranteeOrMoraNeverRedistributesAnExistingSchedule() {
        val result = plan(input().copy(client = "Ana nueva", guarantee = "Otra", dailyLate = 25), setOf(0))
        assertEquals(loanDates(loan).associateWith(loan::baseOn), result.bases)
        assertEquals(1990L, result.collectedBase); assertEquals(50L, result.collectedLate)
        assertEquals(900L, result.pendingLate) // 8 + 7 + ... + 0 days at 25 cents.
    }
    @Test fun changingDateRecomputesDatesAndLateFromTheNewSchedule() {
        val result = plan(input().copy(disbursedOn = start.minusDays(3)))
        assertEquals(loan.firstDue.minusDays(3), result.firstDue)
        assertEquals(loan.lastDue.minusDays(3), result.lastDue)
        assertEquals(7800L, result.pendingLate)
        assertEquals(loan.agreed, result.bases.values.sum())
    }
    @Test fun reducingOrWaivingDailyLateKeepsCollectedFees() {
        val waived = plan(input().copy(dailyLate = 0), setOf(0, 1))
        assertEquals(0L, waived.pendingLate); assertEquals(100L, waived.collectedLate)
        assertEquals(100L, loan.dailyLate)
    }
    @Test fun changingPrincipalShowsItsExactEffectOnCash() {
        assertEquals(90000L, plan(input().copy(principal = 60000)).cashAfter)
        assertEquals(110000L, plan(input().copy(principal = 40000)).cashAfter)
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(principal = 150001)) }
    }
    @Test fun paidInstallmentsCannotBeMovedOutsideTheNewContract() {
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(disbursedOn = start.plusDays(2)), setOf(0)) }
        val previous = calendar(setOf(0))
        val result = planLoanTerms(loan, previous, input().copy(disbursedOn = start.minusDays(2)), day, 100000)
        assertEquals(1990L, result.bases.getValue(loan.firstDue))
    }
    @Test fun shrinkingAgreementBelowWhatWasCollectedIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(principal = 1000), setOf(0, 1)) }
    }
    @Test fun fullyCollectedLoanNeedsNewPendingDatesBeforeIncreasingItsTotal() {
        val ended = loan.copy(firstDue = day.minusDays(29), lastDue = day)
        val endedInput = input().copy(disbursedOn = ended.firstDue.minusDays(1))
        assertThrows(IllegalArgumentException::class.java) { plan(endedInput.copy(rate = BigDecimal("3")), (0..29).toSet(), ended) }
        val extended = plan(endedInput.copy(months = 2), (0..29).toSet(), ended)
        assertEquals(30, extended.pendingCount); assertEquals(51000L, extended.collectedBase)
        assertEquals(1000L, extended.remaining)
    }
    @Test fun originYearFutureAndCalendarEndAreValidatedBeforeAWrite() {
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(disbursedOn = LocalDate.of(2027, 1, 1))) }
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(disbursedOn = day.plusDays(1))) }
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(months = 25)) }
        assertThrows(IllegalArgumentException::class.java) { plan(input().copy(dailyLate = -1)) }
    }
    @Test fun incompleteOrInvalidCalendarCannotInventCollectedAmounts() {
        assertThrows(IllegalArgumentException::class.java) { planLoanTerms(loan, calendar().copy(days = calendar().days.drop(1)), input(), day, 100000) }
        val incomplete = calendar().copy(days = calendar().days.mapIndexed { i, d -> if (i == 0) d.copy(payment = null) else d })
        assertThrows(IllegalArgumentException::class.java) { planLoanTerms(loan, incomplete, input(), day, 100000) }
    }
    @Test fun confirmationChangesWhenCashPaymentOrContractChanges() {
        val first = plan()
        assertNotEquals(first.confirmation(), plan(input().copy(dailyLate = 0)).confirmation())
        assertNotEquals(first.confirmation(), plan(paid = setOf(0)).confirmation())
        assertNotEquals(first.confirmation(), planLoanTerms(loan, calendar(), input(), day, 100001).confirmation())
    }
    @Test fun customConditionsRoundTripAndProtectAgainstManualContractDrift() {
        val plan = plan(input().copy(months = 2), setOf(0))
        val config = LoanConditions(25, plan.bases, "operation-terms-001")
        assertEquals(config, loanConditions(config.json()))
        val changed = loan.copy(months = 2, total = 60, agreed = plan.agreed, lastDue = plan.lastDue).withConditions(config)
        assertEquals(25L, changed.dailyLate); assertEquals(1990L, changed.baseOn(loan.firstDue))
        assertEquals(config.revision, changed.termsRevision)
        assertThrows(IllegalArgumentException::class.java) { loan.withConditions(config) }
    }
    @Test fun paymentVersionsDetectCustomRatesAndRedistributedAmounts() {
        assertNotEquals(paymentTerms(loan), paymentTerms(loan.copy(dailyLate = 0)))
        assertNotEquals(paymentTerms(loan), paymentTerms(loan.copy(termsRevision = "changed")))
        assertNotEquals(paymentTerms(loan), paymentTerms(loan.copy(scheduledBases = mapOf(loan.firstDue to 2000))))
    }
    @Test fun customizedMoraAllowsReductionOfAlreadyCollectedFeeWithoutRepricingIt() {
        val customized = loan.copy(dailyLate = 25, termsRevision = "changed")
        val row = SheetPayment(customized, day.minusDays(3), 1690, false, 250, day.minusDays(1), "version")
        validateRemotePaymentEdit(row, false, 250, day)
        validateRemotePaymentEdit(row, false, 0, day)
        assertThrows(IllegalArgumentException::class.java) { validateRemotePaymentEdit(row, false, 251, day) }
    }
}

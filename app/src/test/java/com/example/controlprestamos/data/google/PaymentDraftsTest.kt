package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.PaymentEdit
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class PaymentDraftsTest {
    private val day = LocalDate.of(2026, 10, 13)
    private val loan = SheetLoan("2026-001", "1", 2026, 6, "Cliente", 50000, BigDecimal("2"), 1,
        "", 51000, 0, 51000, 1690, 1990, day.minusDays(10), day.plusDays(19), 1, 30)
    private val first = SheetPayment(loan, day.minusDays(3), 1690, true, 300, version = "original")
    private val second = first.copy(loan = loan.copy(id = "2026-002", rawId = "2", client = "Otro cliente"))
    private fun edit(row: SheetPayment, unpaid: Boolean, late: Long, date: LocalDate? = null) =
        row to PaymentEdit(row.loan.id, row.id, 0, unpaid, late, date)

    @Test fun confirmingAnEarlierBatchKeepsChangesPreparedWhileItWasSending() {
        val submitted = mapOf(first.id to edit(first, false, 100))
        val whileSending = submitted + (second.id to edit(second, false, 0))
        val remaining = remainingPaymentDrafts(whileSending, submitted.keys)
        assertEquals(setOf(second.id), remaining.keys)
        assertEquals(0L, remaining.getValue(second.id).second.collectedLate)
        assertEquals(100L, submitted.getValue(first.id).second.collectedLate)
    }
    @Test fun acknowledgedPaymentIsVisibleEvenWhenTheFollowingRefreshFails() {
        val updated = confirmedPaymentRows(listOf(first, second), mapOf(first.id to edit(first, false, 100)), day)
        assertFalse(updated.first().unpaid)
        assertEquals(100L, updated.first().late)
        assertEquals(day, updated.first().paidOn)
        assertNull(updated.first().version) // Re-edit only after fetching the new revision.
        assertEquals(second, updated.last())
    }
    @Test fun confirmedHistoricalAdjustmentKeepsItsPaymentDate() {
        val paid = first.copy(unpaid = false, late = 200, paidOn = day.minusDays(1))
        val updated = confirmedPaymentRows(listOf(paid), mapOf(paid.id to edit(paid, false, 50, paid.paidOn)), day).single()
        assertEquals(paid.paidOn, updated.paidOn)
        assertEquals(50L, updated.late)
    }
    @Test fun reopeningPaymentShowsPendingLateAndDoesNotKeepCollectedFee() {
        val paid = first.copy(unpaid = false, late = 100, paidOn = day.minusDays(1))
        val updated = confirmedPaymentRows(listOf(paid), mapOf(paid.id to edit(paid, true, 0)), day).single()
        assertTrue(updated.unpaid)
        assertEquals(300L, updated.late)
        assertNull(updated.paidOn)
        assertNull(updated.bookAmount)
    }
    @Test fun acknowledgementAfterMidnightKeepsTheDateSentInThePayment() {
        val updated = confirmedPaymentRows(listOf(first), mapOf(first.id to edit(first, false, 100, day)), day.plusDays(1)).single()
        assertEquals(day, updated.paidOn)
    }
    @Test fun calendarImmediatelyShowsThreeSolesWhenOctoberTenthIsMarkedUnpaidOnTheThirteenth() {
        val paid = first.copy(unpaid = false, late = 0, paidOn = null)
        val entry = parseCalendarDay(loan, paid.due, listOf(true, paid.installment, false, 16.9, 0), 0, day)
            .copy(payment = paid)
        val calendar = LoanCalendar(loan.id, listOf(entry))
        val updated = calendarWithDrafts(calendar, mapOf(paid.id to edit(paid, true, 0)), day).days.single()
        assertEquals(LocalDate.of(2026, 10, 10), updated.date)
        assertEquals(CalendarStatus.PENDING, updated.status)
        assertEquals(true, updated.checked)
        assertEquals(300L, updated.bookLate)
        assertEquals(1990L, updated.bookAmount)
        assertNull(updated.payment!!.paidOn)
        assertEquals("original", updated.payment.version)
        assertEquals(CalendarStatus.PAID, calendar.days.single().status)
        assertEquals(paid, calendar.days.single().payment) // Keep the validated original snapshot.
    }
    @Test fun calendarDraftsKeepFutureAndInvalidDaysAndOtherLoansUnchanged() {
        val pending = parseCalendarDay(loan, first.due, listOf(true, first.installment, true, 19.9, 3), 0, day)
            .copy(payment = first)
        val future = parseCalendarDay(loan, day.plusDays(1), listOf(false, 12, false, 0, 0), 0, day)
        val invalid = parseCalendarDay(loan, day, listOf(false, 11, false, 0, 0), 0, day)
        val calendar = LoanCalendar(loan.id, listOf(pending, future, invalid))
        val updated = calendarWithDrafts(calendar, mapOf(second.id to edit(second, false, 100)), day)
        assertEquals(calendar, updated)
        val changed = calendarWithDrafts(calendar, mapOf(first.id to edit(first, false, 100)), day)
        assertEquals(CalendarStatus.PAID, changed.days.first().status)
        assertEquals(100L, changed.days.first().bookLate)
        assertEquals(future, changed.days[1])
        assertEquals(invalid, changed.days[2])
        assertEquals(calendar, calendarWithDrafts(calendar, emptyMap(), day))
    }
    @Test fun calendarAcknowledgementUpdatesOnlySubmittedDaysThenAcceptsNewRevisions() {
        val entry = parseCalendarDay(loan, first.due, listOf(true, first.installment, true, 19.9, 3), 0, day)
            .copy(payment = first)
        val other = parseCalendarDay(loan, first.due.plusDays(1), listOf(true, first.installment + 1, false, 16.9, 0), 0, day)
        val calendar = LoanCalendar(loan.id, listOf(entry, other))
        val confirmed = confirmedPaymentRows(calendar.days.mapNotNull { it.payment }, mapOf(first.id to edit(first, false, 100)), day)
        val updated = calendarWithPayments(calendar, confirmed)
        assertEquals(CalendarStatus.PAID, updated.days.first().status)
        assertEquals(100L, updated.days.first().bookLate)
        assertNull(updated.days.first().payment!!.version)
        assertEquals(other, updated.days.last())
        val fresh = updated.days.first().payment!!.copy(version = "new-revision", bookAmount = 1790)
        val refreshed = calendarWithPayments(updated, listOf(fresh))
        assertEquals("new-revision", refreshed.days.first().payment!!.version)
        assertEquals(other, refreshed.days.last())
    }


    @Test fun bulkSelectionPreservesPaidHistorySkipsFutureAndRestoresPendingDrafts() {
        val paid=first.copy(unpaid=false,late=100,paidOn=day.minusDays(1))
        val future=second.copy(due=day.plusDays(1))
        val rows=listOf(paid,second,future)
        val draft=stageBulkPayments(emptyMap(),rows,false,day)
        assertEquals(setOf(second.id),draft.keys)
        assertEquals(second.due,draft.getValue(second.id).second.paidOn)
        assertEquals(0L,draft.getValue(second.id).second.collectedLate)
        val reopened=stageBulkPayments(draft,rows,true,day)
        assertEquals(setOf(paid.id),reopened.keys)
        assertNull(reopened.getValue(paid.id).second.paidOn)
    }
    @Test fun bulkCommonDateIsValidatedForEveryDayBeforeChangingAnything() {
        val edits=mapOf(first.id to edit(first,false,0,day))
        assertThrows(IllegalArgumentException::class.java) {
            stageBulkPayments(edits,listOf(first,second.copy(due=day)),false,day,day.minusDays(1))
        }
        assertEquals(day,edits.getValue(first.id).second.paidOn)
        assertThrows(IllegalArgumentException::class.java) { stageBulkPayments(emptyMap(),listOf(first.copy(version=null)),true,day) }
    }
    @Test fun undoAfterAQueryRefreshStillUsesTheDraftsOriginalVersionAndFee() {
        val original=first.copy(unpaid=false,late=150,paidOn=day)
        val draft=stagePaymentDraft(emptyMap(),original,true,0,null,day)
        val refreshed=original.copy(version="refreshed",late=50)
        val restored=stagePaymentDraft(draft,refreshed,false,150,day,day)
        assertTrue(restored.isEmpty())
    }
    @Test fun rebasingCannotHideAChangedAmountDateOrContract() {
        val draft=mapOf(first.id to edit(first,false,0,day))
        val fresh=first.copy(version="new",loan=first.loan.copy(termsRevision="payment-revision"))
        assertEquals("new",rebasePaymentDrafts(draft,listOf(fresh)).getValue(first.id).first.version)
        for(conflict in listOf(fresh.copy(unpaid=false,paidOn=day),fresh.copy(loan=fresh.loan.copy(principal=60000))))
            assertEquals("original",rebasePaymentDrafts(draft,listOf(conflict)).getValue(first.id).first.version)
    }
}

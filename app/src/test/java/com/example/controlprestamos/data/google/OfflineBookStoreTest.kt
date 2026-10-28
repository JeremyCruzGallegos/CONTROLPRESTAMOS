package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class OfflineBookStoreTest {
    private val day = today()
    private val book = SheetsIdentity.PRODUCTION_ID
    private val loan = SheetLoan("${day.year}-001", "1", day.year, 6, "Cliente", 30000, BigDecimal("2"), 1,
        "Garantía", 30600, 0, 30600, 1020, 1020, day, day.plusDays(29), 0, 30)
    private fun row(offset: Long) = SheetPayment(loan, day.plusDays(offset), 1020, true, 0).let {
        it.copy(version = directPaymentVersion(book, it, 0).toJson().toString())
    }
    @Test fun tomorrowBecomesEditableOfflineWithoutTreatingItAsTodaysPayment() {
        val cache = MemorySheetCache(); val store = OfflineBookStore(cache, "account", book)
        store.saveCatalog(listOf(loan)); store.saveRows(listOf(row(0), row(1)))
        assertEquals(listOf(row(0)), store.payments(day, day))
        assertTrue(store.payments(day.plusDays(1), day.plusDays(1)).isEmpty())
        val afterMidnight = OfflineBookStore(cache, "account", book).payments(day.plusDays(1), day.plusDays(1), day = day.plusDays(1))
        assertEquals(row(1), afterMidnight.single()); assertNotNull(afterMidnight.single().version)
    }
    @Test fun removedLoansCannotLeaveGhostPayments() {
        val store = OfflineBookStore(MemorySheetCache(), "account", book)
        store.saveCatalog(listOf(loan)); store.saveRows(listOf(row(0)))
        store.saveCatalog(emptyList())
        assertTrue(store.payments(day, day).isEmpty()); assertTrue(store.loans().isEmpty())
    }
    @Test fun aChangedScheduleReplacesTheOldDatesAndDoesNotMergeThem() {
        val store = OfflineBookStore(MemorySheetCache(), "account", book)
        store.saveRows(listOf(row(0), row(1)))
        store.saveCalendar(LoanCalendar(loan.id, emptyList(), paymentTerms(loan)), listOf(row(0)))
        assertTrue(store.payments(day.plusDays(1), day.plusDays(1), day = day.plusDays(1)).isEmpty())
    }
    @Test fun cachedCalendarsKeepFutureStatusUntilTheDueDayAndRecalculateLateFees() {
        val store = OfflineBookStore(MemorySheetCache(), "account", book)
        store.saveCalendar(LoanCalendar(loan.id, emptyList(), paymentTerms(loan)), listOf(row(0), row(1)))
        assertEquals(CalendarStatus.FUTURE, store.calendar(loan)!!.days[1].status)
        val nextDay = store.calendar(loan, day.plusDays(1))!!
        assertEquals(CalendarStatus.PENDING, nextDay.days[1].status)
        assertEquals(100L, nextDay.days.first().payment!!.late)
    }
    @Test fun aCalendarWithDifferentFinancialTermsIsNotReused() {
        val store = OfflineBookStore(MemorySheetCache(), "account", book)
        store.saveCalendar(LoanCalendar(loan.id, emptyList(), paymentTerms(loan)), listOf(row(0)))
        assertNull(store.calendar(loan.copy(principal = 31000)))
    }
    @Test fun paymentsAreScopedToTheSelectedLoan() {
        val store = OfflineBookStore(MemorySheetCache(), "account", book)
        val other = row(0).copy(loan = loan.copy(id = "${day.year}-002", rawId = "2"))
        store.saveRows(listOf(row(0), other))
        assertEquals(listOf(row(0)), store.payments(day, day, loan.id))
    }
    @Test fun cashAndCatalogSurviveRecreation() {
        val cache = MemorySheetCache(); val store = OfflineBookStore(cache, "account", book)
        val cash = listOf(BookCashDay(day, 0, 1020, 0, 101020))
        store.saveCatalog(listOf(loan)); store.saveCash(cash)
        val recreated = OfflineBookStore(cache, "account", book)
        assertEquals(listOf(loan), recreated.loans()); assertEquals(cash, recreated.cash())
    }
    @Test fun anEmptyCatalogIsKnownAfterSyncAndSurvivesRestart() {
        val cache = MemorySheetCache()
        val store = OfflineBookStore(cache, "account", book)
        assertFalse(store.hasCatalog())
        store.saveCatalog(emptyList())
        val restored = OfflineBookStore(cache, "account", book)
        assertTrue(restored.hasCatalog())
        assertTrue(restored.loans().isEmpty())
    }

}

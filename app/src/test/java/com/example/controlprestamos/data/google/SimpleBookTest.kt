package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class SimpleBookTest {
    private val day = LocalDate.of(2026,10,16)
    private fun record(date: LocalDate = day.minusDays(3), id: String = "2026-001"): SimpleRecord {
        val loan = Rules.createLoan(id, LoanRequest("Cliente",100_000,BigDecimal("2"),1,"Garantía",Funding.EXTERNAL,50_000),date)
        return SimpleRecord(2,loan,100,false,"revision")
    }
    private fun book(record: SimpleRecord = record()) = SimpleBook("Pruebas",100_000,"revision", LocalDate.of(2027,12,31),listOf(record),Instant.EPOCH)
    @Test fun contractRoundTripsWithoutSpreadsheetFormulas() {
        val record = record(); val cells = record.cells(LocalDate.of(2027,12,31))
        assertFalse(cells.any { it?.toString()?.startsWith("=") == true })
        assertEquals(record,parseSimpleRecord(2,cells,LocalDate.of(2027,12,31)))
        val restored = simpleBookFromJson(book(record).json())
        assertEquals(book(record),restored)
    }
    @Test fun pendingPaymentsAndLateAreCalculatedAgainOnEachDay() {
        val book = book(); val first = book.active.single().loan.installments.first().due
        assertTrue(book.payments(first,day,null,null,day,SheetsIdentity.EMPTY_TEST_ID).all { it.unpaid })
        assertEquals(200L,book.payments(first,first,null,null,day,SheetsIdentity.EMPTY_TEST_ID).single().late)
        assertEquals(300L,book.payments(first,first,null,null,day.plusDays(1),SheetsIdentity.EMPTY_TEST_ID).single().late)
        assertEquals(50_000L,book.summary(2026,day).cash)
    }
    @Test fun cashUsesActualPaymentDateAndReconcilesSummary() {
        val old=record(); val paid=old.copy(loan=old.loan.copy(installments=old.loan.installments.mapIndexed { i,it ->
            if(i==0) it.copy(paidOn=day,collectedLate=150) else it
        }))
        val book=book(paid); val rows=book.cash(day)
        assertEquals(0L,rows.single { it.date==day.minusDays(1) }.receipts)
        assertEquals(paid.loan.installments.first().base+150,rows.last().receipts)
        assertEquals(book.summary(2026,day).cash,rows.last().cash)
        assertEquals(150L,book.summary(2026,day).late)
    }
    @Test fun oneCrossYearLoanCanBeFilteredInBothYearsWithoutDuplicatingTheRecord() {
        val record=record(LocalDate.of(2026,12,20))
        val book=book(record)
        assertEquals(1,book.records.size)
        assertEquals(record.loan.id,book.loans(2026,day).single().id)
        assertEquals(record.loan.id,book.loans(2027,day).single().id)
        assertTrue(book.loans(2028,day).isEmpty())
        assertEquals(30,book.calendar(record.loan.id,day,SheetsIdentity.EMPTY_TEST_ID).days.size)
    }
    @Test fun deletedLoansAreRetainedInTheSheetAndExcludedFromBalances() {
        val record=record().copy(deleted=true)
        val book=book(record)
        assertTrue(book.allActiveForTest().isEmpty())
        assertEquals("ELIMINADO",record.cells(book.until)[14])
        assertEquals(100_000L,book.summary(2026,day).cash)
        assertEquals(1,simpleBookFromJson(book.json()).records.size)
    }
    @Test fun missingOrChangedInstallmentAmountsAreRejected() {
        val record=record();val cells=record.cells(book().until).toMutableList()
        cells[simpleDayColumn(record.loan.installments.first().due)]=""
        assertThrows(IllegalArgumentException::class.java) { parseSimpleRecord(2,cells,book().until) }
    }
    @Test fun everyDateHasVisibleDataColumnsIncludingTheWholeYear() {
        val headings=simpleHeadings(LocalDate.of(2026,12,31))
        assertEquals(16+365*4,headings.size)
        assertEquals("01 ENE 2026\nNO PAGÓ",headings[16])
        assertEquals("2026-12-31 · MORA COBRADA",headings.last())
    }
    @Test fun legacyLocalSnapshotsRemainReadableAfterTheCheckboxUpgrade() {
        val old=book().copy(checkboxes=false)
        val json=old.json();json.remove("checkboxes")
        assertEquals(old,simpleBookFromJson(json))
    }
    @Test fun anUncheckedBoxMeansPaidWithoutInventingTheCollectionDate() {
        val original=record();val cells=original.cells(book().until).toMutableList()
        val due=original.loan.installments.first().due
        cells[simpleDayColumn(due)]=false
        val parsed=parseSimpleRecord(2,cells,book().until)
        val snapshot=book(parsed);val payment=snapshot.payments(due,due,null,null,day,SheetsIdentity.EMPTY_TEST_ID).single()
        assertFalse(payment.unpaid);assertNull(payment.paidOn)
        assertEquals(1,parsed.view(day).paid)
        assertThrows(IllegalArgumentException::class.java) { snapshot.cash(day) }
        assertEquals(parsed,simpleBookFromJson(snapshot.json()).records.single())
    }
    @Test fun recheckingNoPagoCancelsACollectionEvenIfAnOldDateIsStillInItsDetails() {
        val original=record();val due=original.loan.installments.first().due
        val cells=original.cells(book().until).toMutableList();cells[simpleDayColumn(due)+2]=day.toString();cells[simpleDayColumn(due)+3]=1.0
        val parsed=parseSimpleRecord(2,cells,book().until)
        val payment=book(parsed).payments(due,due,null,null,day,SheetsIdentity.EMPTY_TEST_ID).single()
        assertTrue(payment.unpaid);assertNull(payment.paidOn);assertEquals(0,parsed.view(day).paid)
    }
    @Test fun dailyGroupsKeepTheCheckboxOutsideTheCollapsedDetails() {
        val date=LocalDate.of(2026,10,16);val requests=simpleCalendarGroups(123,date,date)
        assertEquals(3,requests.size)
        val range=requests.first().getJSONObject("addDimensionGroup").getJSONObject("range")
        assertEquals(simpleDayColumn(date)+1,range.getInt("startIndex"))
        assertEquals(simpleDayColumn(date)+4,range.getInt("endIndex"))
    }
    private fun SimpleBook.allActiveForTest() = active
}

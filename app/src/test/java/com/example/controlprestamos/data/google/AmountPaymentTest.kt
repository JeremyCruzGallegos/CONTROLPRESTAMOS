package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class AmountPaymentTest {
    private val day=LocalDate.of(2026,10,21)
    private fun record()=SimpleRecord(8,Rules.createLoan("2026-001",LoanRequest("Cliente",30_000,BigDecimal.ZERO,1,"",Funding.CAPITAL,0),day.minusDays(5)),100,false,"original")
    @Test fun paysOldestInstallmentsAndDeductsTheRemainderFromNextFeeThenBase() {
        val before=record() // 10 soles base; accrued fees 4, 3, 2, 1, 0.
        val plan=planAmountPayment(before,3_250,day)
        assertEquals(listOf(day.minusDays(4),day.minusDays(3)),plan.paidDates)
        assertEquals(day.minusDays(2),plan.partialDate);assertEquals(550L,plan.partialAmount)
        val after=plan.after.view(day)
        assertEquals(650L,after.baseOn(plan.partialDate!!));assertEquals(0L,after.lateAt(plan.partialDate!!,day))
        assertEquals(before.loan.agreed,plan.after.loan.agreed)
        assertEquals(3_250L,plan.after.loan.receipts(day))
        assertEquals(before.view(day).debt-3_250,after.debt)
    }
    @Test fun aRemainderSmallerThanTheFeeIsCollectedOnceAndNeverMakesBaseNegative() {
        val plan=planAmountPayment(record(),1_450,day)
        val due=day.minusDays(3)
        assertEquals(1_000L,plan.after.view(day).baseOn(due));assertEquals(250L,plan.after.view(day).lateAt(due,day))
        assertEquals(50L,plan.partialAmount);assertEquals(1_450L,plan.after.loan.receipts(day))
    }
    @Test fun futureInstallmentsCannotBeCollectedAndExcessIsRejectedWithoutMutation() {
        val before=record();val maximum=before.loan.installments.filter { it.due<=day }.sumOf { it.base+before.view(day).lateAt(it.due,day) }
        assertThrows(IllegalArgumentException::class.java){planAmountPayment(before,maximum+1,day)}
        assertTrue(before.loan.installments.all { it.paidOn==null })
        assertEquals(5,planAmountPayment(before,maximum,day).paidDates.size)
    }
    @Test fun feesCanBeWaivedAndExactPaymentsHaveNoPartialRemainder() {
        val plan=planAmountPayment(record(),2_000,day,true)
        assertEquals(2,plan.paidDates.size);assertNull(plan.partialDate);assertEquals(0L,plan.after.loan.lateReceipts(day))
    }
    @Test fun roundTripAndNormalPaymentPreserveTheRemainingFeeCredit() {
        val plan=planAmountPayment(record(),1_450,day)
        val saved=plan.after.copy(revision="saved")
        val parsed=parseSimpleRecord(saved.row,saved.cells(LocalDate.of(2026,12,31)),LocalDate.of(2026,12,31))
        assertEquals(saved.view(day).debt,parsed.view(day).debt)
        assertEquals(250L,parsed.view(day).lateAt(day.minusDays(3),day))
        val next=planAmountPayment(parsed,1_250,day)
        assertEquals(2_700L,next.after.loan.receipts(day));assertEquals(2,next.after.view(day).paid)
        val book=SimpleBook("Test",30_000,"rev",LocalDate.of(2026,12,31),listOf(next.after),Instant.EPOCH)
        assertEquals(2_700L,book.cash(day).last().cash)
    }
    @Test fun reopeningTheSourceRemovesItsFeeCreditAndDoesNotInventCash() {
        val plan=planAmountPayment(record(),1_450,day)
        val source=plan.paidDates.single()
        val after=plan.after.copy(loan=plan.after.loan.copy(installments=plan.after.loan.installments.map { if(it.due==source)it.copy(paidOn=null,collectedLate=0)else it }))
        assertEquals(300L,after.view(day).lateAt(day.minusDays(3),day))
        assertEquals(0L,after.loan.receipts(day))
    }
    @Test fun feeCreditDoesNotStopNewDailyFeesFromAccruing() {
        val plan=planAmountPayment(record(),1_450,day)
        assertEquals(350L,plan.after.view(day.plusDays(1)).lateAt(day.minusDays(3),day.plusDays(1)))
    }
    @Test fun onlyTheExactNativeFeeAdvanceFormulaIsAcceptedAsAnInputFormula() {
        val record=planAmountPayment(record(),1_450,day).after
        val values=record.cells(LocalDate.of(2026,12,31)).toMutableList()
        val due=day.minusDays(3);val column=simpleDayColumn(due)+3
        values[column]=simpleAdvanceFormula(record.row,due,record.advances)!!
        assertEquals(record.view(day).debt,parseSimpleRecord(record.row,values,LocalDate.of(2026,12,31)).view(day).debt)
        values[column]="=999"
        assertThrows(IllegalArgumentException::class.java){parseSimpleRecord(record.row,values,LocalDate.of(2026,12,31))}
    }
    @Test fun anOfflineReceiptKeepsItsRealPaymentDayWhenItIsAppliedLater() {
        val record=record();val body=org.json.JSONObject().put("paidOn",day.toString())
        val later=day.plusDays(3);val date=amountPaymentDate(body,later)
        val plan=planAmountPayment(record,1_450,date)
        assertEquals(day,plan.after.loan.installments.first().paidOn)
        assertEquals(450L,plan.after.loan.installments.first().collectedLate)
        assertEquals(250L,plan.after.view(day).lateAt(day.minusDays(3),day))
        assertThrows(IllegalArgumentException::class.java){amountPaymentDate(body,day.minusDays(1))}
    }
}

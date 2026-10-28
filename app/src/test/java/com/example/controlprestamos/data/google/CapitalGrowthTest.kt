package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CapitalGrowthTest {
    @Test fun startsAtOneThousandAndAddsOnlyActuallyCollectedInterestAndLateFees() {
        val start=LocalDate.of(2026,1,15);val day=LocalDate.of(2026,2,15)
        val contract=Rules.createLoan("2026-001",LoanRequest("Cliente",100_000,BigDecimal("2"),1,"",Funding.CAPITAL,0),start)
        val paid=contract.copy(installments=contract.installments.mapIndexed { i,item->item.copy(paidOn=day.minusDays(1),collectedLate=if(i==0)1_000 else 0) })
        val record=SimpleRecord(8,paid,100,false,"rev")
        val rows=paid.installments.map { SheetPayment(record.view(day),it.due,it.base,false,it.collectedLate,it.paidOn) }
        val points=capitalGrowthPoints(100_000,listOf(record.view(day)),rows,day,2026,0)!!
        assertEquals(listOf(100_000L,100_000L,103_000L),points.map { it.capital })
        assertEquals(2_000L,points.last().interest);assertEquals(1_000L,points.last().late)
        val reinvested=Rules.createLoan("2026-002",LoanRequest("Segundo",100_000,BigDecimal("2"),1,"",Funding.CAPITAL,0),day)
        assertEquals(points,capitalGrowthPoints(100_000,listOf(record.view(day),SimpleRecord(9,reinvested,100,false,"r").view(day)),rows,day,2026,0))
    }
    @Test fun missingPaymentDatesAndMissingPaidInstallmentsDoNotProduceInventedGrowth() {
        val day=LocalDate.of(2026,2,15)
        val loan=SimpleRecord(8,Rules.createLoan("2026-001",LoanRequest("C",100_000,BigDecimal("2"),1,"",Funding.CAPITAL,0),day.minusDays(5)),100,false,"r").view(day).copy(paid=1)
        assertNull(capitalGrowthPoints(100_000,listOf(loan),emptyList(),day,2026,0))
        assertNull(capitalGrowthPoints(100_000,listOf(loan),listOf(SheetPayment(loan,loan.firstDue,loan.first,false,0)),day,2026,0))
    }
}

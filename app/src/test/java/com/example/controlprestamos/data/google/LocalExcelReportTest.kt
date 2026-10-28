package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class LocalExcelReportTest {
    @Test fun reportKeepsLocalPaymentsUnknownRowsAndCompleteBackupFragments() {
        val day=today()
        val loan=SheetLoan("2026-001","1",2026,2,"Ana",30000,BigDecimal("2"),1,"Documento",30600,0,30600,
            1020,1020,day.minusDays(1),day.plusDays(28),0,30)
        val row=SheetPayment(loan,day.minusDays(1),1020,true,100)
        val summary=SheetSummary(2026,day,700000,700000,0,30000,0,0,0,0,670000,30000)
        val view=LocalBookView(SheetsPreload("Local",summary,listOf(loan),listOf(row),Instant.now()),listOf(loan),listOf(row),emptyList())
        val changes=mapOf(row.id to (row to PaymentEdit(loan.id,row.id,0,false,200,day)))
        val original="á & datos ".repeat(8000)
        val sheets=localExcelReport(view,changes,mapOf("operations.json" to original))
        val dues=sheets.single{it.name=="Cuotas y pagos"}.rows.drop(1)
        assertEquals("Pagada",dues[0][5])
        assertEquals(BigDecimal("2.00"),dues[0][6])
        assertEquals(BigDecimal("12.20"),dues[0][8])
        assertEquals("Sí",dues[0][9])
        assertEquals("Sin datos locales",dues[1][5])
        assertEquals("Próxima",dues[2][5])
        val parts=sheets.single{it.name=="Respaldo local"}.rows.drop(1)
        assertEquals(original,parts.joinToString(""){it[2].toString()})
        assertTrue(parts.all{it[2].toString().length<=30000})
        val loans=sheets.single{it.name=="Préstamos"}.rows[1]
        assertEquals(1,loans[10])
        assertEquals(BigDecimal("12.20"),loans[8])
    }
}

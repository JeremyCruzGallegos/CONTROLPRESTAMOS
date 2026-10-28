package com.example.controlprestamos.data.google
import com.example.controlprestamos.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.math.BigDecimal
class ClientDirectoryTest {
    private fun record(id:String,name:String="José Cruz")=SimpleRecord(2,Rules.createLoan(id,LoanRequest(name,10000,BigDecimal("2"),1,"",Funding.CAPITAL,0),LocalDate.of(2026,10,27)),100,false,"revision")
    @Test fun codeIncludesDateAndNormalizedInitialsWithUniqueSuffix(){
        val first=newClientCode("José Cruz",LocalDate.of(2026,10,27));val next=newClientCode("José Cruz",LocalDate.of(2026,10,27),setOf(first))
        assertTrue(first.matches(Regex("CP-20261027-JC-[0-9A-F]{8}")));assertNotEquals(first,next)
    }
    @Test fun firstLoanCreatesVisibleDirectoryInTheSameAtomicRequestPlan(){
        val plan=clientDirectoryPlan(listOf(record("2026-001")),"José Cruz",null,emptyList(),190027)
        assertTrue(plan.requests.any{it.has("addSheet")});assertTrue(plan.requests.any{it.optJSONObject("addNamedRange")?.getJSONObject("namedRange")?.optString("name")=="cp_clientes_init_v1"})
        val texts=plan.requests.toString();assertTrue(texts.contains("CÓDIGO CLIENTE"));assertTrue(texts.contains(plan.code));assertTrue(texts.contains("2026-001"))
    }
    @Test fun laterLoansReuseCodeWithoutOverwritingPermissionOrLinkedEmail(){
        val code="CP-20261027-JC-1234ABCD";val props=JSONObject().put("gridProperties",JSONObject().put("rowCount",100))
        val rows=listOf(CLIENT_DIRECTORY_HEADERS+listOf("","a".repeat(32)),listOf(code,"José Cruz","2026-10-27","2026-001","PERMITIDA","cliente@gmail.com"))
        val plan=clientDirectoryPlan(listOf(record("2026-001"),record("2026-002")),"José Cruz",props,rows,190027)
        assertEquals(code,plan.code)
        val update=plan.requests.mapNotNull{it.optJSONObject("updateCells")}.single{it.getJSONObject("start").getInt("rowIndex")==1}
        assertEquals(4,update.getJSONArray("rows").getJSONObject(0).getJSONArray("values").length())
        assertTrue(update.toString().contains("2026-001, 2026-002"))
    }
}

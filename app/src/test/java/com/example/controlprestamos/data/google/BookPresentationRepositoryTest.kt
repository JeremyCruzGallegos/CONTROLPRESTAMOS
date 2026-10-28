package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

class BookPresentationRepositoryTest {
    @Test fun bothBooksSynchronizePaymentStateWithoutChangingFinancialCells() {
        val day = today()
        assumeTrue(day in LocalDate.of(2026, 7, 17)..LocalDate.of(2027, 12, 30))
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID)) {
            val server = LoanDeletionTest.FakeSheets(day, book)
            server.sheets["_RESUMEN_DIARIO"] = 20 to 535
            listOf("AÑO", "FECHA", "COBRADO", "MORA COBRADA", "MORA PENDIENTE", "CUOTAS IMPAGAS")
                .forEachIndexed { col, value -> server.put("_RESUMEN_DIARIO", 1, col + 1, value) }
            var row = 2; var pastRow = 0
            for (year in 2026..2027) {
                val first = if (year == 2026) LocalDate.of(2026, 7, 16) else LocalDate.of(2027, 1, 1)
                repeat(if (year == 2026) 169 else 365) { index ->
                    val date = first.plusDays(index.toLong())
                    if (date == day.minusDays(1)) pastRow = row
                    listOf(year, sheetSerial(date), 0, 0, 0, if (date == day.minusDays(1)) 1 else 0)
                        .forEachIndexed { col, value -> server.put("_RESUMEN_DIARIO", row, col + 1, value) }
                    row++
                }
            }
            val cache = object : SheetCache {
                override fun read(): JSONObject? = null
                override fun write(value: JSONObject) = Unit
                override fun delete() = Unit
            }
            val repo = SheetsRepository(book, "account", server, cache,
                GoogleConnection(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "", emptyList(), false), GoogleTokenProvider { "fake" })
            fun shown(): Int {
                val requests = server.posted!!.getJSONArray("requests")
                return (0 until requests.length()).count { index ->
                    val request = requests.getJSONObject(index)
                    assertTrue(request.has("updateDimensionProperties") || request.has("setBasicFilter"))
                    request.optJSONObject("updateDimensionProperties")?.getJSONObject("properties")?.getBoolean("hiddenByUser") == false
                }
            }
            val before = server.cells.toMap()
            repo.syncBookPresentation()
            assertEquals(2, shown()); assertEquals(before, server.cells)
            server.put("_RESUMEN_DIARIO", pastRow, 6, 0)
            val paid = server.cells.toMap()
            repo.syncBookPresentation()
            assertEquals(1, shown()); assertEquals(paid, server.cells)
        }
    }
}

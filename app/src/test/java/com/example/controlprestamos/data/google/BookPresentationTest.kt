package com.example.controlprestamos.data.google

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BookPresentationTest {
    private val today = LocalDate.of(2026, 10, 16)
    private val calendar = (-2..1).associate { today.plusDays(it.toLong()) to (18 + (it + 2) * 5) }
    private val sheet = PresentationSheet(42, 2026, 405, calendar)
    private fun daily(pending: Int = 1) = calendar.keys.map { date ->
        listOf<Any?>(2026, sheetSerial(date), 0, 0, 0, if (date == today.minusDays(1) || date > today) pending else 0)
    }
    private fun shown(rows: List<List<Any?>>) = bookPresentationRequests(listOf(sheet), rows, today)
        .mapNotNull { it.optJSONObject("updateDimensionProperties") }
        .filter { !it.getJSONObject("properties").getBoolean("hiddenByUser") }
        .map { it.getJSONObject("range").getInt("startIndex") }

    @Test fun todayAndUnpaidPastDaysAreShownEvenWithZeroLateFee() {
        assertEquals(listOf(24, 29), shown(daily()))
    }
    @Test fun payingTheLastPendingInstallmentHidesItsDateButTodayStaysVisible() {
        assertEquals(listOf(29), shown(daily(0)))
        assertEquals(listOf(24, 29), shown(daily(1)))
    }
    @Test fun incompleteDuplicateAndNegativeSummariesRejectTheWholePlan() {
        for (rows in listOf(daily().drop(1), daily() + listOf(daily().first()), daily(-1))) {
            assertThrows(IllegalArgumentException::class.java) { shown(rows) }
        }
    }
    @Test fun blankClientCriterionPreservesExistingDebtCriterionAndSorting() {
        val original = JSONObject("""{"range":{"sheetId":42,"startRowIndex":4,"endRowIndex":405,"startColumnIndex":0,"endColumnIndex":40},"criteria":{"9":{"condition":{"type":"NUMBER_GREATER","values":[{"userEnteredValue":"0"}]}}},"sortSpecs":[{"dimensionIndex":1,"sortOrder":"ASCENDING"}]}""")
        val result = nonEmptyLoanFilter(sheet.copy(filter = original))
        assertTrue(original.has("criteria"))
        assertFalse(result.has("criteria"))
        assertEquals("ASCENDING", result.getJSONArray("sortSpecs").getJSONObject(0).getString("sortOrder"))
        val specs = result.getJSONArray("filterSpecs")
        assertEquals(2, specs.length())
        assertEquals(9, specs.getJSONObject(0).getInt("columnIndex"))
        assertEquals("NOT_BLANK", specs.getJSONObject(1).getJSONObject("filterCriteria").getJSONObject("condition").getString("type"))
        assertEquals(result.toString(), nonEmptyLoanFilter(sheet.copy(filter = result)).toString())
    }
    @Test fun requestPlanOnlyChangesVisibilityAndFilters() {
        assertTrue(bookPresentationRequests(listOf(sheet), daily(), today).all {
            it.has("updateDimensionProperties") || it.has("setBasicFilter")
        })
    }
    @Test fun deletingRowsRefreshesTheExistingFilterWithoutDuplicatingCriteria() {
        val installed = sheet.copy(filter = nonEmptyLoanFilter(sheet))
        val filter = bookPresentationRequests(listOf(installed), daily(), today).single { it.has("setBasicFilter") }
            .getJSONObject("setBasicFilter").getJSONObject("filter")
        assertEquals(1, filter.getJSONArray("filterSpecs").length())
        assertEquals("NOT_BLANK", filter.getJSONArray("filterSpecs").getJSONObject(0).getJSONObject("filterCriteria")
            .getJSONObject("condition").getString("type"))
    }
}

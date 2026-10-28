package com.example.controlprestamos.data.google

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

internal data class PresentationSheet(val id: Int, val year: Int, val rows: Int,
    val calendar: Map<LocalDate, Int>, val filter: JSONObject? = null)

internal fun nonEmptyLoanFilter(sheet: PresentationSheet): JSONObject {
    val filter = sheet.filter?.let { JSONObject(it.toString()) } ?: JSONObject().put("range", JSONObject()
        .put("sheetId", sheet.id).put("startRowIndex", 4).put("endRowIndex", sheet.rows)
        .put("startColumnIndex", 0).put("endColumnIndex", sheet.calendar.values.max() + 4))
    val range = filter.getJSONObject("range")
    require(range.getInt("sheetId") == sheet.id && range.optInt("startRowIndex") == 4 &&
        range.optInt("startColumnIndex") == 0 && range.optInt("endColumnIndex") >= 2) { "El filtro anual cambió de posición" }
    val specs = filter.optJSONArray("filterSpecs")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        ?: filter.optJSONObject("criteria")?.let { criteria -> criteria.keys().asSequence().map { col ->
            JSONObject().put("columnIndex", col.toInt()).put("filterCriteria", criteria.getJSONObject(col))
        }.toList() }.orEmpty()
    val notBlank = JSONObject().put("columnIndex", 1).put("filterCriteria", JSONObject().put("condition", JSONObject().put("type", "NOT_BLANK")))
    filter.remove("criteria")
    filter.put("filterSpecs", JSONArray(specs.filter { it.optInt("columnIndex", -1) != 1 } + notBlank))
    return filter
}

/** Validate every counter and date before any visibility mutation. */
internal fun bookPresentationRequests(sheets: List<PresentationSheet>, daily: List<List<Any?>>, day: LocalDate): List<JSONObject> {
    require(sheets.isNotEmpty() && sheets.map { it.year }.distinct().size == sheets.size)
    val counters = mutableMapOf<Pair<Int, LocalDate>, Int>()
    daily.filter { it.any { cell -> hasLoanValue(cell) } }.forEach { row ->
        val year = BigDecimal(row.getOrNull(0).toString()).intValueExact()
        val date = sheetDate(row.getOrNull(1)); val count = BigDecimal(row.getOrNull(5).toString()).intValueExact()
        require(date.year == year && count >= 0 && counters.put(year to date, count) == null) { "El resumen diario contiene fechas o pendientes inválidos" }
    }
    val expected = sheets.flatMap { s -> s.calendar.keys.map { s.year to it } }.toSet()
    require(counters.keys == expected) { "El resumen no coincide con el calendario del libro" }
    val requests = mutableListOf<JSONObject>()
    sheets.forEach { sheet ->
        require(sheet.rows >= 6 && sheet.calendar.isNotEmpty())
        val columns = sheet.calendar.values.sorted()
        require(columns.zipWithNext().all { (a, b) -> b - a == 5 }) { "El calendario dejó de tener cinco columnas por día" }
        fun visibility(start: Int, end: Int, hidden: Boolean) = JSONObject().put("updateDimensionProperties", JSONObject()
            .put("range", JSONObject().put("sheetId", sheet.id).put("dimension", "COLUMNS").put("startIndex", start).put("endIndex", end))
            .put("properties", JSONObject().put("hiddenByUser", hidden)).put("fields", "hiddenByUser"))
        requests.add(visibility(columns.first() - 1, columns.last() + 4, true))
        sheet.calendar.toSortedMap().forEach { (date, col) ->
            if (date == day || date < day && counters.getValue(sheet.year to date) > 0) requests.add(visibility(col + 1, col + 4, false))
        }
        // Reapply after deletions too: refresh the filtered rows, even if the criterion already existed.
        requests.add(JSONObject().put("setBasicFilter", JSONObject().put("filter", nonEmptyLoanFilter(sheet))))
    }
    return requests
}

package com.example.controlprestamos.data.google

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A continuous calendar from the first installment of the oldest still-active loan. */
internal fun simpleVisibleCalendarDays(book: SimpleBook, day: LocalDate): Set<LocalDate> = buildSet {
    val first = maxOf(SIMPLE_START, book.active
        .filter { record -> record.loan.installments.any { !record.isPaid(it, day) } }
        .minOfOrNull { it.loan.installments.first().due } ?: day)
    if (first <= book.until) {
        for (offset in 0..ChronoUnit.DAYS.between(first, book.until)) add(first.plusDays(offset))
    }
}

/** Only presentation fields: no financial values, validations, revisions or journal writes. */
internal fun simpleCalendarPresentationRequests(sheetId: Int, book: SimpleBook, day: LocalDate): List<JSONObject> {
    if (!book.checkboxes) return emptyList()
    val visible = simpleVisibleCalendarDays(book, day)
    val result = mutableListOf<JSONObject>()
    fun visibility(first: Int, end: Int, hidden: Boolean) {
        result += JSONObject().put("updateDimensionProperties", JSONObject()
            .put("range", JSONObject().put("sheetId", sheetId).put("dimension", "COLUMNS").put("startIndex", first).put("endIndex", end))
            .put("properties", JSONObject().put("hiddenByUser", hidden)).put("fields", "hiddenByUser"))
    }
    var hiddenStart: Int? = null
    val days = ChronoUnit.DAYS.between(SIMPLE_START, book.until).toInt() + 1
    for (offset in 0 until days) {
        val date = SIMPLE_START.plusDays(offset.toLong()); val column = simpleDayColumn(date)
        if (date !in visible) {
            if (hiddenStart == null) hiddenStart = column
        } else {
            hiddenStart?.let { visibility(it, column, true) }; hiddenStart = null
            // Keep expanded detail groups on visible days as the user left them.
            visibility(column, column + 1, false)
        }
    }
    hiddenStart?.let { visibility(it, simpleDayColumn(book.until) + 4, true) }
    result += JSONObject().put("repeatCell", JSONObject()
        .put("range", JSONObject().put("sheetId", sheetId).put("startRowIndex", book.headerRow - 1).put("endRowIndex", book.headerRow)
            .put("startColumnIndex", SIMPLE_COLUMNS).put("endColumnIndex", simpleDayColumn(book.until) + 4))
        .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("textRotation", JSONObject().put("angle", 90))))
        .put("fields", "userEnteredFormat.textRotation"))
    return result
}

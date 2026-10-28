package com.example.controlprestamos.data.google

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Only added dates get groups; a user's expanded groups are left as they are on ordinary writes. */
internal fun simpleCalendarGroups(sheetId: Int, from: LocalDate, until: LocalDate): List<JSONObject> {
    if (from > until) return emptyList()
    return (0..ChronoUnit.DAYS.between(from, until)).flatMap { offset ->
        val start = simpleDayColumn(from.plusDays(offset)) + 1
        val range = JSONObject().put("sheetId", sheetId).put("dimension", "COLUMNS").put("startIndex", start).put("endIndex", start + 3)
        listOf(
            JSONObject().put("addDimensionGroup", JSONObject().put("range", range)),
            JSONObject().put("updateDimensionGroup", JSONObject().put("dimensionGroup", JSONObject().put("range", range).put("depth", 1).put("collapsed", true)).put("fields", "collapsed")),
            JSONObject().put("updateDimensionProperties", JSONObject().put("range", range).put("properties", JSONObject().put("hiddenByUser", true)).put("fields", "hiddenByUser"))
        )
    }
}

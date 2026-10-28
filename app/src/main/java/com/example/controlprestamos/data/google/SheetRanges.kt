package com.example.controlprestamos.data.google

import java.util.Locale

private val boundedA1 = Regex("^('(?:[^']|'')+'|[^'!]+)!\\$?([A-Za-z]+)\\$?([1-9][0-9]*)(?::\\$?([A-Za-z]+)\\$?([1-9][0-9]*))?$")
private data class RangeCell(val column: String, val row: Long)
private data class BoundedSheetRange(val sheet: String, val first: RangeCell, val last: RangeCell)

private fun parseBoundedSheetRange(value: String): BoundedSheetRange? {
    val match = boundedA1.matchEntire(value) ?: return null
    val groups = match.groupValues
    val name = groups[1]
    val sheet = if (name.startsWith("'")) name.substring(1, name.length - 1).replace("''", "'") else name
    val first = RangeCell(groups[2].uppercase(Locale.ROOT), groups[3].toLongOrNull() ?: return null)
    val last = if (groups[4].isEmpty()) first else
        RangeCell(groups[4].uppercase(Locale.ROOT), groups[5].toLongOrNull() ?: return null)
    return BoundedSheetRange(sheet, first, last)
}

/** Google can return A1 for A1:A1. Compare coordinates without discarding sheet-name apostrophes. */
internal fun sameSheetRange(requested: String, returned: String): Boolean {
    val expected = parseBoundedSheetRange(requested) ?: return false
    return expected == parseBoundedSheetRange(returned)
}

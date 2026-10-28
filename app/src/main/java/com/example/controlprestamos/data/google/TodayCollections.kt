package com.example.controlprestamos.data.google

import java.time.LocalDate

internal data class TodayCollections(val scheduled: Long, val pending: Long, val collected: Long,
    val totalCount: Int, val pendingCount: Int) {
    val progress: Float get() = if (scheduled == 0L) 0f else
        ((scheduled - pending).toDouble() / scheduled).toFloat().coerceIn(0f, 1f)
}

/** This is the status of installments due today, not receipts for other due dates. */
internal fun todayCollections(rows: List<SheetPayment>, day: LocalDate): TodayCollections {
    val due = rows.filter { it.due == day }.distinctBy { it.id }
    return TodayCollections(due.sumOf { it.base }, due.filter { it.unpaid }.sumOf { it.base },
        due.filterNot { it.unpaid }.sumOf { it.base + it.late }, due.size, due.count { it.unpaid })
}

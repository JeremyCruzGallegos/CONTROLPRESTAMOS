package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.MovementPeriod
import com.example.controlprestamos.domain.PeriodReport
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Old loans may predate automatic funding. Treat only their dated shortfall as capital,
 * as for a new loan; keep it distinct from recorded contributions and from earnings. */
internal fun reconcileLegacyFunding(rows: List<BookCashDay>): List<BookCashDay> {
    var oldAssumed = 0L
    var added = 0L
    return rows.sortedBy { it.date }.map { row ->
        oldAssumed += row.assumedContribution
        val rawCash = row.cash - oldAssumed
        val missing = (-(rawCash + added)).coerceAtLeast(0)
        added += missing
        row.copy(contribution = row.contribution - row.assumedContribution + missing,
            cash = rawCash + added, assumedContribution = missing)
    }
}

internal fun effectiveSheetSummary(summary: SheetSummary, changes: PaymentChanges, cash: List<BookCashDay>): SheetSummary {
    val assumed = if (cash.lastOrNull()?.date?.let { it >= summary.cutoff } == true)
        cash.filter { it.date <= summary.cutoff }.sumOf { it.assumedContribution } else summary.assumedContribution
    return summary.copy(cash = summary.cash + localReceiptDelta(changes) + assumed - summary.assumedContribution,
        assumedContribution = assumed)
}

/** Move only receipts with a confirmed real date. Undated legacy receipts retain their book date. */
internal fun cashByPaymentDates(rows: List<BookCashDay>, payments: List<SheetPayment>, day: LocalDate): List<BookCashDay> {
    val changes = payments.distinctBy { it.id }.filter { !it.unpaid && it.paidOn != null && it.paidOn != it.due }
        .associate { row -> row.id to (row.copy(paidOn = row.due) to
            com.example.controlprestamos.domain.PaymentEdit(row.loan.id, row.id, 0, false, row.late, row.paidOn)) }
    return localCashRows(rows, changes, true, day)
}

/** A backdated loan must also be funded on its date, before later receipts exist. */
internal fun cashForNewLoan(rows: List<BookCashDay>, date: LocalDate, current: Long,
    asOf: LocalDate = com.example.controlprestamos.domain.today()): Long =
    minOf(current, rows.filter { it.date in date..asOf }.minOfOrNull { it.cash } ?: current)

/** Apply only the difference from the cash snapshot's acknowledged payment state.
 * The simple book records real payment dates; the original book's CAJA uses due dates.
 * Existing ledger balances are preserved, and a local receipt carries forward into later days.
 */
internal fun localCashRows(rows: List<BookCashDay>, changes: PaymentChanges,
    actualPaymentDates: Boolean, day: LocalDate): List<BookCashDay> {
    if (rows.isEmpty() || changes.isEmpty()) return rows
    val deltas = sortedMapOf<LocalDate, Long>()
    fun add(date: LocalDate, amount: Long) {
        if (date <= day) deltas[date] = (deltas[date] ?: 0L) + amount
    }
    changes.values.filter { it.first.due <= day }.forEach { (row, edit) ->
        if (!row.unpaid) add(if (actualPaymentDates) row.paidOn ?: row.due else row.due, -row.base - row.late)
        if (!edit.unpaid) add(if (actualPaymentDates) edit.paidOn ?: row.paidOn ?: day else row.due,
            row.base + edit.collectedLate)
    }
    val nonzero = deltas.filterValues { it != 0L }
    val movements = nonzero.entries.iterator()
    if (!movements.hasNext()) return rows
    val recorded = rows.filter { it.date <= day }.sortedBy { it.date }
    if (recorded.isEmpty()) return rows
    val extended = recorded.toMutableList()
    val last = recorded.last()
    // Carry the cached balance only as far as the latest local payment; no future cash is projected.
    val end = maxOf(last.date, nonzero.keys.max())
    var date = last.date.plusDays(1)
    while (date <= end) {
        extended += BookCashDay(date, 0, 0, 0, last.cash)
        date = date.plusDays(1)
    }
    var next = movements.next()
    var remaining = true
    var accumulated = 0L
    val adjusted = extended.map { row ->
        while (remaining && next.key <= row.date) {
            accumulated += next.value
            if (movements.hasNext()) next = movements.next() else remaining = false
        }
        row.copy(receipts = row.receipts + (deltas[row.date] ?: 0L), cash = row.cash + accumulated)
    }
    return if (rows.any { it.assumedContribution > 0 }) reconcileLegacyFunding(adjusted) else adjusted
}

/** CAJA is grouped by installment due date in the existing book, not actual payment date.
 * Only cash/flows from this adapter may be displayed. It supplies no inferred earnings/debt.
 */
fun bookCashReport(rows: List<BookCashDay>, asOf: LocalDate, year: Int, month: Int): PeriodReport? {
    require(month in 0..12)
    val requested = if (month == 0) LocalDate.of(year, 1, 1) else LocalDate.of(year, month, 1)
    val end = minOf(asOf, if (month == 0) LocalDate.of(year, 12, 31) else YearMonth.of(year, month).atEndOfMonth())
    val selected = rows.filter { it.date in requested..end }.sortedBy { it.date }
    if (selected.isEmpty()) return null
    require(selected.map { it.date }.distinct().size == selected.size) { "Fechas de caja repetidas" }
    require(ChronoUnit.DAYS.between(selected.first().date, selected.last().date) + 1 == selected.size.toLong()) { "Faltan días en la evolución de caja" }
    val start = selected.first().date; val cutoff = selected.last().date
    val first = selected.first()
    selected.zipWithNext().forEach { (previous, day) ->
        require(kotlin.math.abs(day.cash - (previous.cash + day.receipts + day.contribution - day.disbursed)) <= 2) { "La evolución de caja no concilia el ${day.date}" }
    }
    // Exact ledger identity; this is not a fabricated balance before the recorded period.
    val opening = first.cash - first.receipts - first.contribution + first.disbursed
    fun movement(from: LocalDate, to: LocalDate): MovementPeriod {
        val values = selected.filter { it.date in from..to }
        return MovementPeriod(from, to, values.sumOf { it.receipts }, values.sumOf { it.disbursed }, 0, 0, values.sumOf { it.contribution })
    }
    val movements = if (month == 0) (start.monthValue..cutoff.monthValue).map {
        val ym = YearMonth.of(year, it); movement(maxOf(start, ym.atDay(1)), minOf(cutoff, ym.atEndOfMonth()))
    } else selected.map { movement(it.date, it.date) }
    return PeriodReport(start, cutoff, selected.sumOf { it.receipts }, selected.sumOf { it.disbursed }, 0, 0,
        selected.sumOf { it.contribution }, 0, opening, selected.last().cash, 0,
        listOf(opening) + selected.map { it.cash }, 0, 0, 0, 0, movements, month == 0)
}

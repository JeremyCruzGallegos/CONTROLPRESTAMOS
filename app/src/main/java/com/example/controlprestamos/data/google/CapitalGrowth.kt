package com.example.controlprestamos.data.google

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class CapitalGrowthPoint(val date: LocalDate, val capital: Long, val interest: Long, val late: Long) {
    val initial: Long get() = capital - interest - late
    val growthPercent: BigDecimal? get() = initial.takeIf { it > 0 }?.let {
        BigDecimal(interest + late).multiply(BigDecimal(100)).divide(BigDecimal(it), 2, RoundingMode.HALF_UP)
    }
}

/** Collected earnings grow capital; recovering or lending the same principal does not. */
internal fun capitalGrowthPoints(initial: Long, loans: List<SheetLoan>, payments: List<SheetPayment>,
    day: LocalDate, year: Int, month: Int): List<CapitalGrowthPoint>? {
    require(month in 0..12 && initial >= 0)
    val catalog = canonicalLoanViews(loans, day.year).filter { it.firstDue.minusDays(1)<=day }
    val paid = payments.distinctBy { it.id }.filter { !it.unpaid && it.due <= day }
    val flows = sortedMapOf<LocalDate, Pair<Long, Long>>()
    for (loan in catalog) {
        val rows = paid.filter { it.loan.id == loan.id }
        if (rows.size != loan.paid || rows.any { it.paidOn == null }) return null
        var base = 0L
        fun earned(amount: Long) = if (loan.agreed == 0L) 0L else BigDecimal(amount)
            .multiply(BigDecimal(loan.agreed - loan.principal)).divide(BigDecimal(loan.agreed), 0, RoundingMode.HALF_UP).longValueExact()
        rows.groupBy { it.paidOn!! }.toSortedMap().forEach { (date, entries) ->
            val before = earned(base); base += entries.sumOf { it.base }
            val old = flows[date] ?: (0L to 0L)
            flows[date] = old.first + earned(base) - before to old.second + entries.sumOf { it.late }
        }
    }
    val requested = LocalDate.of(year, if (month == 0) 1 else month, 1)
    val start = maxOf(requested, catalog.minOfOrNull { it.firstDue.minusDays(1) } ?: requested)
    val cutoff = minOf(day, if (month == 0) LocalDate.of(year,12,31) else YearMonth.of(year,month).atEndOfMonth())
    if (start > cutoff) return emptyList()
    fun point(date: LocalDate): CapitalGrowthPoint {
        val rows = flows.filterKeys { it <= date }.values
        val interest = rows.sumOf { it.first }; val fees = rows.sumOf { it.second }
        return CapitalGrowthPoint(date, initial + interest + fees, interest, fees)
    }
    val dates = if (month == 0) generateSequence(YearMonth.from(start)) { it.plusMonths(1) }
        .takeWhile { it.atDay(1) <= cutoff }.map { minOf(it.atEndOfMonth(), cutoff) }.toList()
        else generateSequence(start) { it.plusDays(1) }.takeWhile { it <= cutoff }.toList()
    return (listOf(start.minusDays(1)) + dates).distinct().map(::point)
}

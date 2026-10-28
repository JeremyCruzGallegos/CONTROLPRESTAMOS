package com.example.controlprestamos.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Actual movements by payment/disbursement date; future buckets are not created. */
data class MovementPeriod(val start: LocalDate, val end: LocalDate, val receipts: Long,
    val disbursed: Long, val interest: Long, val late: Long, val contribution: Long) {
    val earnings: Long get() = interest + late
}
data class PeriodReport(val start: LocalDate, val cutoff: LocalDate, val receipts: Long,
    val disbursed: Long, val interest: Long, val late: Long, val contribution: Long,
    val debt: Long, val openingCash: Long, val closingCash: Long, val projected: Long,
    /** Index zero is the closing balance on start.minusDays(1), then each actual day. */
    val cashEvolution: List<Long>, val recovered: Long, val pendingCapital: Long,
    val pendingInterest: Long, val pendingLate: Long, val movements: List<MovementPeriod>, val monthly: Boolean) {
    val earnings: Long get() = interest + late
    val cashChange: Long get() = closingCash - openingCash
}
private class DailyMovement {
    var receipts = 0L; var disbursed = 0L; var interest = 0L; var late = 0L; var contribution = 0L
}

fun periodReport(snapshot: Snapshot, asOf: LocalDate, year: Int, month: Int): PeriodReport? {
    require(month in 0..12)
    val start = if (month == 0) LocalDate.of(year, 1, 1) else LocalDate.of(year, month, 1)
    if (start > asOf) return null
    val end = if (month == 0) LocalDate.of(year, 12, 31) else YearMonth.of(year, month).atEndOfMonth()
    val cutoff = minOf(end, asOf)
    val opening = Rules.summary(snapshot, start.minusDays(1))
    val closing = Rules.summary(snapshot, cutoff)
    val eligibleLoans = snapshot.loans.filter { it.disbursed <= cutoff }
    val daily = mutableMapOf<LocalDate, DailyMovement>()
    fun entry(date: LocalDate) = daily.getOrPut(date) { DailyMovement() }
    eligibleLoans.forEach { loan ->
        if (loan.disbursed >= start) entry(loan.disbursed).apply {
            disbursed += loan.principal; contribution += loan.contribution
        }
        // Allocate interest by differences of cumulative rounded totals, as _CAPITAL does.
        // Rounding each installment independently would drift from the verified summary.
        var accumulatedBase = 0L
        loan.installments.filter { it.paid(cutoff) }.groupBy { it.paidOn!! }.toSortedMap().forEach { (date, paid) ->
            val before = loan.proportionalInterest(accumulatedBase)
            val base = paid.sumOf { it.base }
            accumulatedBase += base
            if (date >= start) entry(date).apply {
                val fee = paid.sumOf { it.collectedLate }
                receipts += base + fee
                interest += loan.proportionalInterest(accumulatedBase) - before
                late += fee
            }
        }
    }
    val days = List(ChronoUnit.DAYS.between(start, cutoff).toInt() + 1) { start.plusDays(it.toLong()) }
    var cash = opening.cash
    val evolution = listOf(cash) + days.map { date ->
        daily[date]?.let { cash += it.receipts + it.contribution - it.disbursed }
        cash
    }
    fun movement(from: LocalDate, until: LocalDate): MovementPeriod {
        val entries = daily.filterKeys { it in from..until }.values
        return MovementPeriod(from, until, entries.sumOf { it.receipts }, entries.sumOf { it.disbursed },
            entries.sumOf { it.interest }, entries.sumOf { it.late }, entries.sumOf { it.contribution })
    }
    val movements = if (month == 0) (1..cutoff.monthValue).map { number ->
        val ym = YearMonth.of(year, number)
        movement(ym.atDay(1), minOf(ym.atEndOfMonth(), cutoff))
    } else days.map { movement(it, it) }
    val debt = eligibleLoans.sumOf { it.balance(cutoff) }
    val unpaidBase = eligibleLoans.sumOf { loan -> loan.installments.filterNot { it.paid(cutoff) }.sumOf { it.base } }
    return PeriodReport(start, cutoff, daily.values.sumOf { it.receipts }, daily.values.sumOf { it.disbursed },
        closing.interest - opening.interest, closing.late - opening.late, daily.values.sumOf { it.contribution },
        debt, opening.cash, closing.cash,
        snapshot.loans.flatMap { it.installments }.filter { it.due > asOf && it.due in start..end }.sumOf { it.base },
        evolution, closing.recovered - opening.recovered, closing.pendingCapital,
        unpaidBase - closing.pendingCapital, debt - unpaidBase, movements, month == 0)
}

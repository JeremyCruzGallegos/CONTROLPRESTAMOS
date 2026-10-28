package com.example.controlprestamos.data.google

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

internal data class PortfolioForecast(val count: Int, val pending: Long, val interest: Long, val late: Long = 0) {
    fun cashAfterCollection(cash: Long): Long = Math.addExact(Math.addExact(cash, pending), late)
}

/** Outstanding contractual money only: paid principal and fees must never be counted twice. */
internal fun portfolioForecast(loans: List<SheetLoan>, payments: List<SheetPayment>, changes: PaymentChanges,
    day: LocalDate): PortfolioForecast? {
    var pending = 0L; var interest = 0L; var count = 0; var late = 0L
    val rows = payments.associateBy { it.id } + changes.mapValues { it.value.first }
    for (original in canonicalLoanViews(loans, day.year).filter { it.firstDue.minusDays(1) <= day }) {
        val loan = localLoanView(original, changes, day)
        if (!loan.active) continue
        val paid = rows.values.filter { row ->
            row.loan.id == loan.id && row.due <= day && !(changes[row.id]?.second?.unpaid ?: row.unpaid)
        }
        // A catalog alone cannot identify the base/fee split of partially paid loans.
        if (paid.size != loan.paid) return null
        val base = paid.sumOf { it.base }
        if (base !in 0..loan.agreed || loan.agreed <= 0) return null
        val earned = BigDecimal(base).multiply(BigDecimal(loan.agreed - loan.principal))
            .divide(BigDecimal(loan.agreed), 0, RoundingMode.HALF_UP).longValueExact()
        pending += loan.agreed - base
        val paidDates = paid.map { it.due }.toSet()
        late += loanDates(loan).filter { it <= day && it !in paidDates }.sumOf { loan.lateAt(it, day) }
        interest += (loan.agreed - loan.principal - earned).coerceAtLeast(0)
        count++
    }
    return PortfolioForecast(count, pending, interest, late)
}

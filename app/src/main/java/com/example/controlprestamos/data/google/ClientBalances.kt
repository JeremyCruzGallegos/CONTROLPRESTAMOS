package com.example.controlprestamos.data.google

import java.time.LocalDate

internal data class LoanBalances(val pending: Long, val dueToday: Long)

internal data class LoanDebtBreakdown(val overdueBase: Long, val late: Long, val futureBase: Long) {
    val withoutLate: Long get() = overdueBase + futureBase
    val dueToday: Long get() = overdueBase + late
    val pending: Long get() = withoutLate + late
    val latePercent: java.math.BigDecimal? get() = withoutLate.takeIf { it > 0 }?.let {
        java.math.BigDecimal(late).multiply(java.math.BigDecimal(100))
            .divide(java.math.BigDecimal(it), 2, java.math.RoundingMode.HALF_UP)
    }
}

/** Reconstruct actual unpaid bases and accrued fees; future fees are never projected. */
internal fun loanDebtBreakdown(loan: SheetLoan, payments: List<SheetPayment>, changes: PaymentChanges,
    day: LocalDate): LoanDebtBreakdown? {
    val rows = (payments + changes.values.map { it.first }).filter { it.loan.id == loan.id }.associateBy { it.due }
    val paid = rows.values.filter { it.due <= day && !(changes[it.id]?.second?.unpaid ?: it.unpaid) }.map { it.due }.toSet()
    val effective = localLoanView(loan, changes, day)
    if (!effective.active) return LoanDebtBreakdown(0, 0, 0)
    if (paid.size != effective.paid) return null
    val pending = loanDates(effective).filter { it !in paid }
    val due = pending.filter { it <= day }
    return LoanDebtBreakdown(due.sumOf { effective.baseOn(it) }, due.sumOf { effective.lateAt(it, day) },
        pending.filter { it > day }.sumOf { effective.baseOn(it) })
}

internal fun loanBalances(loan: SheetLoan, payments: List<SheetPayment>, changes: PaymentChanges,
    day: LocalDate): LoanBalances? = loanDebtBreakdown(loan, payments, changes, day)?.let { LoanBalances(it.pending, it.dueToday) }

/** Existing loans retain their own IDs; a normalized name identifies their shared client. */
internal fun clientLoanGroups(loans: List<SheetLoan>, day: LocalDate): List<List<SheetLoan>> =
    canonicalLoanViews(loans, day.year).groupBy { normalizedClient(it.client) }.toSortedMap().values
        .map(::chronologicalLoans)

internal fun chronologicalLoans(loans: List<SheetLoan>): List<SheetLoan> = loans.sortedWith(
    compareBy<SheetLoan> { it.firstDue }.thenBy { it.id.substringAfter('-').toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.id })

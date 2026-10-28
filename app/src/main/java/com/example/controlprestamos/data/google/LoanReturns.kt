package com.example.controlprestamos.data.google

import java.time.LocalDate

/** Contract totals for active loans, including installments already collected, without late fees. */
internal data class LoanReturns(val count: Int, val principal: Long, val total: Long) {
    val interest: Long get() = total - principal
}

internal fun activeLoanReturns(loans: List<SheetLoan>, changes: PaymentChanges, day: LocalDate): LoanReturns {
    val active = canonicalLoanViews(loans, day.year).map { localLoanView(it, changes, day) }
        .filter { it.active && it.firstDue.minusDays(1) <= day }
    return LoanReturns(active.size, active.sumOf { it.principal }, active.sumOf { it.agreed })
}

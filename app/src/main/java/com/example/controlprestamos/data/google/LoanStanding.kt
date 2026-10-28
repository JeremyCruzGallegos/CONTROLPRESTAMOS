package com.example.controlprestamos.data.google

import java.time.LocalDate

internal enum class LoanStanding(val label: String) {
    CLOSED("Concluido"), ACTIVE("Activo"), OVERDUE("Con atraso"), UNVERIFIED("Por actualizar")
}

/** Today's unpaid installment is not late; zero-fee loans can still be in arrears. */
internal fun loanStanding(loan: SheetLoan, payments: List<SheetPayment>, changes: PaymentChanges, day: LocalDate): LoanStanding {
    val effective = localLoanView(loan, changes, day)
    if (!effective.active) return LoanStanding.CLOSED
    val rows = (payments + changes.values.map { it.first }).filter { it.loan.id == loan.id }.associateBy { it.due }
    val paid = rows.values.filter { it.due <= day && !(changes[it.id]?.second?.unpaid ?: it.unpaid) }.map { it.due }.toSet()
    if (paid.size != effective.paid) return LoanStanding.UNVERIFIED
    return if (loanDates(effective).any { it < day && it !in paid }) LoanStanding.OVERDUE else LoanStanding.ACTIVE
}

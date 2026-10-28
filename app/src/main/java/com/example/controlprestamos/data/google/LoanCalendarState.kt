package com.example.controlprestamos.data.google

/** Identity alone is insufficient: a loan may keep its ID while its entire schedule changes. */
internal fun calendarMatchesLoan(calendar: LoanCalendar?, loan: SheetLoan): Boolean = calendar != null &&
    calendar.loanId == loan.id && calendar.days.map { it.date } == loanDates(loan) &&
    (calendar.terms == null || calendar.terms == paymentTerms(loan))

internal fun refreshedLoanSelection(selected: SheetLoan, views: List<SheetLoan>): SheetLoan =
    views.firstOrNull { it.id == selected.id && it.year == selected.year }
        ?: canonicalLoanViews(views.filter { it.id == selected.id }, selected.year).firstOrNull() ?: selected

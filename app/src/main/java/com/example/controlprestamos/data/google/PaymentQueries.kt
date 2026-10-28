package com.example.controlprestamos.data.google

import java.time.LocalDate

data class PaymentWindow(val from: LocalDate, val to: LocalDate)

internal fun selectedClientMatches(client: String, selected: String): Boolean =
    selected.isBlank() || normalizedClient(client) == normalizedClient(selected)

/** Keep Sheets' 31-day request limit while offering continuous, longer periods in the UI. */
internal fun paymentWindows(from: LocalDate, to: LocalDate, asOf: LocalDate): List<PaymentWindow> {
    require(from <= to && to <= asOf && from.year >= 2026 && to.year <= 2028) {
        "Elige fechas entre 2026 y 2028, hasta hoy, con el inicio anterior al fin"
    }
    val windows = mutableListOf<PaymentWindow>()
    var start = from
    while (start <= to) {
        val end = minOf(start.plusDays(30), LocalDate.of(start.year, 12, 31), to)
        windows += PaymentWindow(start, end)
        start = end.plusDays(1)
    }
    return windows
}

internal fun paymentClients(loans: List<SheetLoan>, asOf: LocalDate, activeOnly: Boolean): List<String> =
    canonicalLoanViews(loans, asOf.year).filter { !activeOnly || (it.active && it.firstDue <= asOf) }
        .map { it.client }.distinctBy(::normalizedClient).sortedBy(::normalizedClient)

internal fun filterSheetPayments(rows: List<SheetPayment>, loans: List<SheetLoan>, client: String,
    activeOnly: Boolean, asOf: LocalDate): List<SheetPayment> {
    val views = canonicalLoanViews(loans + rows.map { it.loan }, asOf.year).associateBy { it.id }
    return rows.distinctBy { it.id }.filter { row ->
        val loan = views.getValue(row.loan.id)
        (!activeOnly || (loan.active && loan.firstDue <= asOf)) &&
            (client.isBlank() || normalizedClient(loan.client) == normalizedClient(client))
    }.sortedWith(compareBy({ normalizedClient(it.loan.client) }, { it.loan.id }, { it.due }))
}

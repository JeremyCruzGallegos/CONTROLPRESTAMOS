package com.example.controlprestamos.data.google

import java.time.LocalDate

internal data class LoanGroupingPlan(val client: String, val loans: List<SheetLoan>) {
    val changes: List<SheetLoan> get() = loans.filter { it.client != client }
}

/** A group changes names only. Loan identities, schedules, payments and balances stay independent. */
internal fun planLoanGrouping(catalog: List<SheetLoan>, ids: List<String>, name: String, day: LocalDate): LoanGroupingPlan {
    require(ids.size in 2..400 && ids.distinct().size == ids.size) { "Selecciona al menos dos préstamos distintos" }
    require(name.none { it.isISOControl() }) { "El nombre no admite saltos de línea ni caracteres de control" }
    val client = name.trim().replace(Regex("\\s+"), " ")
    require(client.isNotBlank() && client.length <= 200) { "Ingresa un nombre único de hasta 200 caracteres" }
    val loans = canonicalLoanViews(catalog, day.year).associateBy { it.id }
    val selected = ids.map { loans[it] ?: error("Un préstamo seleccionado ya no está disponible") }
    require(loans.values.none { it.id !in ids && normalizedClient(it.client) == normalizedClient(client) }) {
        "Ese nombre tiene otros préstamos. Selecciónalos también para incluirlos o elige otro nombre"
    }
    return LoanGroupingPlan(client, selected).also { require(it.changes.isNotEmpty()) { "Los préstamos ya tienen ese nombre" } }
}

package com.example.controlprestamos.data.google

import org.json.JSONObject

internal const val MAX_INITIAL_CAPITAL = 99_999_999_999L

internal data class CapitalChange(val year: Int, val before: Long, val after: Long, val cashBefore: Long) {
    init {
        require(year in 2026..2028) { "Año de capital no disponible" }
        require(before in 0..MAX_INITIAL_CAPITAL && after in 0..MAX_INITIAL_CAPITAL) { "Ingresa un capital entre S/ 0.00 y S/ 999,999,999.99" }
        require(before != after) { "El capital no tiene cambios" }
        require(cashAfter >= 0) { "Ese capital dejaría la caja en negativo. Revisa el monto" }
    }
    val difference: Long get() = Math.subtractExact(after, before)
    val cashAfter: Long get() = Math.addExact(cashBefore, difference)
}

internal fun capitalChange(body: JSONObject): CapitalChange {
    fun cents(key: String) = java.math.BigDecimal(body.get(key).toString()).longValueExact()
    return CapitalChange(java.math.BigDecimal(body.get("year").toString()).intValueExact(), cents("beforeCents"),
        cents("afterCents"), cents("cashBeforeCents"))
}

internal fun validateCapitalChange(change: CapitalChange, summary: SheetSummary, cell: DirectLoanCell) {
    require(cell.formula == null && cell.validation == null) { "El capital inicial contiene una fórmula o validación. Revísalo en la hoja" }
    require(summary.year == change.year && summary.initial == change.before && sheetCents(cell.value) == change.before &&
        summary.cash == change.cashBefore) { "El capital o la caja cambiaron. Actualiza Inicio y revisa el importe antes de confirmar" }
}

internal fun capitalRevisionKey(book: String, before: Long, revision: String) =
    "cp_caprev_" + directPaymentHash("$book|$before|$revision").take(40)
internal fun capitalOperationKey(operation: String) = "cp_capop_" + directPaymentHash(operation).take(40)

internal fun pendingLocksPayments(pending: PendingRemoteWrite?): Boolean = pending?.let {
    JSONObject(it.payload).optString("action") in listOf("deleteLoan", "setCapital", "editLoanTerms", "amountPayment")
} == true

internal class CapitalEditRejected(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

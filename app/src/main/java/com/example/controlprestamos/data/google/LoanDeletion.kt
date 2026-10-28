package com.example.controlprestamos.data.google

/** Includes the values shown at confirmation, so an old detail cannot delete a changed loan. */
internal fun loanDeletionVersion(loan: SheetLoan): String = directPaymentHash(listOf(
    paymentTerms(loan), loan.client, loan.guarantee, loan.collected, loan.debt, loan.paid, loan.total
).joinToString("|") { "${it.toString().length}:$it" })

internal fun deletedLoanKey(id: String): String {
    require(id.matches(Regex("202[6-8]-[0-9]{3,}")) && id.substringAfter('-').toInt() > 0) { "Identidad de préstamo inválida" }
    return "cp_deleted_${id.substringBefore('-')}_${id.substringAfter('-').toInt()}"
}

internal fun loanDeletionInputs(cells: Map<Int, DirectLoanCell>): List<Int> {
    val columns = listOf(2, 4, 5, 6, 7, 16)
    require(cells[1]?.formula == null) { "La fila original contiene una identidad calculada" }
    columns.forEach { column ->
        val cell = cells[column] ?: DirectLoanCell()
        require(cell.formula == null && cell.validation == null) { "Las entradas del préstamo contienen fórmulas o validaciones. Revisa el libro" }
    }
    return columns
}

internal class LoanDeletionRejected(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

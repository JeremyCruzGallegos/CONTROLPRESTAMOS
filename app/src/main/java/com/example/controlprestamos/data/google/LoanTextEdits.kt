package com.example.controlprestamos.data.google

data class LoanTextEdit(val loanId: String, val beforeClient: String, val beforeGuarantee: String,
    val client: String, val guarantee: String) {
    init {
        require(loanId.matches(Regex("202[6-8]-[0-9]{3,}")) && loanId.substringAfter('-').toInt() > 0)
        require(client.isNotBlank() && client == client.trim() && client.length <= 200 && client.none { it.isISOControl() }) {
            "Ingresa un nombre entre 1 y 200 caracteres"
        }
        require(guarantee.length <= 2000 && guarantee.none { it.isISOControl() && it != '\n' }) { "La garantía admite hasta 2000 caracteres" }
        require(client != beforeClient || guarantee != beforeGuarantee) { "No hay cambios para guardar" }
    }
    val originYear: Int get() = loanId.substringBefore('-').toInt()
    val fields: List<LoanTextField> get() = listOfNotNull(
        if (client != beforeClient) LoanTextField(1, beforeClient, client) else null,
        if (guarantee != beforeGuarantee) LoanTextField(6, beforeGuarantee, guarantee) else null)
}
data class LoanTextField(val columnIndex: Int, val before: String, val after: String)
data class LoanTextCell(val value: String, val formula: Boolean = false, val validated: Boolean = false)
data class PendingLoanTextEdit(val subject: String, val bookId: String, val edit: LoanTextEdit) {
    init { remoteCacheKey(subject, bookId) }
    fun requireOwner(subject: String, book: String) { require(this.subject == subject && bookId == book) { "Edición de otra cuenta o libro" } }
}

/** Text only: these zero-based columns are B (client) and G (guarantee). */
internal fun loanTextWritePlan(edit: LoanTextEdit, cells: Map<Int, LoanTextCell>): List<LoanTextField> = edit.fields.filter { field ->
    val cell = cells.getValue(field.columnIndex)
    require(!cell.formula && !cell.validated) { "El campo contiene una fórmula o validación. Revísalo en la hoja antes de editarlo" }
    val current = if (field.columnIndex == 1) cell.value.trim() else cell.value
    require(current == field.before || current == field.after) { "Estos datos cambiaron en la hoja. Actualiza y revisa antes de guardar" }
    current != field.after
}

internal fun exactClientLoans(loans: List<SheetLoan>, client: String, filter: Int): List<SheetLoan> =
    matchingSheetLoans(canonicalLoanViews(loans, com.example.controlprestamos.domain.today().year), "", filter).filter { client.isBlank() || normalizedClient(it.client) == normalizedClient(client) }

internal fun rebasedLoanTextEdit(before: SheetLoan, current: SheetLoan, client: String, guarantee: String): LoanTextEdit {
    val clientChanged = client != before.client
    val guaranteeChanged = guarantee != before.guarantee
    return LoanTextEdit(current.id, if (clientChanged) before.client else current.client,
        if (guaranteeChanged) before.guarantee else current.guarantee,
        if (clientChanged) client else current.client, if (guaranteeChanged) guarantee else current.guarantee)
}

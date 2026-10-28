package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LoanTextEditsTest {
    private fun edit(client: String = "Kevin actualizado", guarantee: String = "Documento") =
        LoanTextEdit("2026-002", "Kevin", "Documento", client, guarantee)
    @Test fun writesOnlyTheChangedTextColumns() {
        assertEquals(listOf(1), edit().fields.map { it.columnIndex })
        assertEquals(listOf(6), edit("Kevin", "Celular").fields.map { it.columnIndex })
        assertEquals(listOf(1, 6), edit(guarantee = "Celular").fields.map { it.columnIndex })
        assertEquals(2026, edit().originYear)
    }
    @Test fun acceptsLiteralTextIncludingFormulaLikeCharacters() {
        assertEquals("=Kevin + $1", edit("=Kevin + $1").fields.single().after)
    }
    @Test fun rejectsEmptyOversizedAndControlCharacters() {
        listOf("", " ", "a".repeat(201), "Kevin\nOtro").forEach { assertThrows(IllegalArgumentException::class.java) { edit(it) } }
        assertThrows(IllegalArgumentException::class.java) { edit(guarantee = "a".repeat(2001)) }
        assertThrows(IllegalArgumentException::class.java) { edit("Kevin") }
    }
    @Test fun rejectsReplacingFormulasAndValidationBackedCells() {
        assertThrows(IllegalArgumentException::class.java) { loanTextWritePlan(edit(), mapOf(1 to LoanTextCell("Kevin", formula = true))) }
        assertThrows(IllegalArgumentException::class.java) { loanTextWritePlan(edit(), mapOf(1 to LoanTextCell("Kevin", validated = true))) }
    }
    @Test fun refusesStaleEditsInsteadOfReplacingAnotherName() {
        assertThrows(IllegalArgumentException::class.java) { loanTextWritePlan(edit(), mapOf(1 to LoanTextCell("Nombre cambiado por otra persona"))) }
    }
    @Test fun retryDoesNotRepeatAnAlreadyAppliedChange() {
        assertTrue(loanTextWritePlan(edit(), mapOf(1 to LoanTextCell("Kevin actualizado"))).isEmpty())
        assertEquals(edit().fields, loanTextWritePlan(edit(), mapOf(1 to LoanTextCell(" Kevin "))))
    }
    @Test fun retryCanCompleteOneRemainingFieldWithoutRewritingTheOther() {
        val edit = edit(guarantee = "Celular")
        assertEquals(listOf(6), loanTextWritePlan(edit, mapOf(1 to LoanTextCell(edit.client), 6 to LoanTextCell("Documento"))).map { it.columnIndex })
    }
    @Test fun anUnchangedFieldIsNotWrittenOrUsedToOverwriteAnExternalChange() {
        assertEquals(listOf(1), loanTextWritePlan(edit(), mapOf(1 to LoanTextCell("Kevin"), 6 to LoanTextCell("Nueva garantía externa"))).map { it.columnIndex })
    }
    @Test fun pendingTextEditsAreBoundToTheSameAccountAndBook() {
        val p = PendingLoanTextEdit("a", SheetsIdentity.PRODUCTION_ID, edit())
        p.requireOwner("a", SheetsIdentity.PRODUCTION_ID)
        assertThrows(IllegalArgumentException::class.java) { p.requireOwner("b", SheetsIdentity.PRODUCTION_ID) }
        assertThrows(IllegalArgumentException::class.java) { p.requireOwner("a", "COPIA_LOCAL_FICTICIA_1234567890") }
    }
    @Test fun principalTextAndPaymentsUseSheetsScopeWithoutScriptDeployment() {
        val connection = GoogleConnection(SheetsIdentity.PRODUCTION_ID, "COPIA_LOCAL_FICTICIA_1234567890", "", emptyList(), false)
        assertTrue(WRITE_SCOPE in connection.scopes(false))
        assertTrue(connection.canEditLoanText(SheetsIdentity.PRODUCTION_ID))
        assertFalse(connection.canEditLoanText("OTRO_LIBRO_FICTICIO_1234567890"))
        assertTrue(connection.canWrite(SheetsIdentity.PRODUCTION_ID))
        assertFalse(connection.canWrite(connection.testBookId))
    }
    @Test fun dropdownSelectionIsExactAndIncludesAllStatusesForThatClient() {
        val date = LocalDate.of(2026, 7, 17)
        fun loan(id: Int, name: String, paid: Int) = parseSheetLoans(2026, listOf(listOf(id, name, "$paid/30", 500, 0.02, 1, "", 510, 0, 510,
            0.3333333, 0.3, 16.9, 19.9, 0, sheetSerial(date), sheetSerial(date.plusDays(29))))).single()
        val active = loan(1, "Kévin", 0); val finished = loan(2, "Kevin", 30); val other = loan(3, "Kevin Ramos", 0)
        assertEquals(listOf(active, finished), exactClientLoans(listOf(active, finished, other), "kevin", 0))
        assertEquals(listOf(active), exactClientLoans(listOf(active, finished, other), "kevin", 1))
        assertEquals(listOf(finished), exactClientLoans(listOf(active, finished, other), "kevin", 2))
    }
}

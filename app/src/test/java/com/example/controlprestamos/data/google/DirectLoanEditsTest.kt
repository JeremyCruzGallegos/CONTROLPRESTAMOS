package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DirectLoanEditsTest {
    private val day = LocalDate.of(2026, 10, 13)
    private val request = LoanRequest("Cliente", 50000, BigDecimal("2"), 1, "Garantía", Funding.CAPITAL, 0)
    private fun row(item: Int, client: String = "", principal: Any? = "", first: Any? = "") =
        MutableList<Any?>(17) { "" }.apply { this[0] = item; this[1] = client; this[3] = principal; this[15] = first }
    private fun plan(req: LoanRequest = request, date: LocalDate = day, cash: Long = 100000,
        rows: List<List<Any?>> = listOf(row(1)), contributions: List<List<Any?>> = emptyList(),
        reserved: Set<Int> = emptySet(), reservedItems: Set<Int> = emptySet()) =
        planDirectLoan(SheetsIdentity.PRODUCTION_ID, req, date, date, cash, rows, contributions, reserved, reservedItems)
    private fun template(p: DirectLoanPlan): MutableMap<Int, DirectLoanCell> {
        val r = p.row
        return mutableMapOf<Int, DirectLoanCell>().apply {
            this[1] = DirectLoanCell(p.item)
            listOf(3, 9, 10).forEach { this[it] = DirectLoanCell(formula = "=SUM(1,2)") }
            mapOf(8 to "=D$r+D$r*(E$r*F$r)", 11 to "=D$r*(E$r/30)", 12 to "=ROUNDDOWN(K$r,1)",
                13 to "=IFERROR(ROUNDDOWN(D$r/(F$r*30)+L$r,1),\"\")", 14 to "=H$r-(M$r*(F$r*30))+M$r",
                15 to "=ABS(H$r-(N$r+(M$r*((F$r*30)-1))))", 17 to "=IF(OR(\$B$r=\"\",\$P$r=\"\",\$F$r<=0),\"\",\$P$r+\$F$r*30-1)")
                .forEach { (col, formula) -> this[col] = DirectLoanCell(formula = formula) }
        }
    }
    @Test fun capitalLoanUsesTomorrowAndOriginalRoundingWithoutFutureIncome() {
        val p = plan()
        assertEquals("2026-001", p.loan.id); assertEquals(6, p.row)
        assertEquals(50000L, p.cashAfter); assertEquals(day.plusDays(1), p.firstDue)
        assertEquals(day.plusDays(30), p.lastDue); assertEquals(51000L, p.loan.agreed)
        assertEquals(1990L, p.loan.installments.first().base)
        assertEquals(1690L, p.loan.installments.last().base)
        assertTrue(p.loan.installments.none { it.paid(day) })
    }
    @Test fun externalContributionFundsPrincipalWithoutCountingAsProfit() {
        val p = plan(request.copy(funding = Funding.EXTERNAL, contribution = 40000), cash = 10000)
        assertEquals(0L, p.cashAfter); assertEquals(40000L, p.loan.contribution)
        assertEquals(51000L, p.loan.agreed)
    }
    @Test fun insufficientCashAddsOnlyTheShortfallAndInvalidFundingIsRejected() {
        val funded = plan(cash = 49999)
        assertEquals(1L, funded.loan.contribution)
        assertEquals(0L, funded.cashAfter)
        assertThrows(IllegalArgumentException::class.java) { plan(request.copy(contribution = 1)) }
    }
    @Test fun occupiedRowsEvenWithMissingClientAreNeverReused() {
        val p = plan(rows = listOf(row(1, principal = 1), row(2, first = sheetSerial(day)), row(3)))
        assertEquals(8, p.row); assertEquals(3, p.item)
    }
    @Test fun deletedClientNeverReusesARecordedLoanId() {
        val p = plan(rows = listOf(row(1)), contributions = listOf(listOf("date", "2026-001")))
        assertEquals(2, p.item); assertEquals("2026-002", p.loan.id)
    }
    @Test fun concurrentReservationsExcludeSlotAndLoanNumber() {
        val p = plan(rows = listOf(row(1), row(2)), reserved = setOf(6), reservedItems = setOf(2))
        assertEquals(7, p.row); assertEquals(3, p.item)
    }
    @Test fun originalRowAndContributionLimitsAreEnforced() {
        assertThrows(IllegalStateException::class.java) { plan(rows = List(400) { row(it + 1, "Registrado") }) }
        assertThrows(IllegalArgumentException::class.java) { plan(rows = List(401) { row(it + 1) }) }
        assertThrows(IllegalArgumentException::class.java) { plan(contributions = List(1200) { listOf("date", "2025-${it + 1}") }) }
    }
    @Test fun wrongBookOrFutureDisbursementCannotWrite() {
        assertThrows(IllegalArgumentException::class.java) {
            planDirectLoan("OTHER_BOOK_ID_12345", request, day, day, 100000, emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            planDirectLoan(SheetsIdentity.PRODUCTION_ID, request, day.plusDays(1), day, 100000, emptyList(), emptyList())
        }
    }
    @Test fun lateRegistrationKeepsOriginalDatesAndNoInventedReceiptsInBothBooks() {
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID)) {
            val p = planDirectLoan(book, request, day.minusDays(10), day, 100000, emptyList(), emptyList())
            assertEquals(day.minusDays(10), p.loan.disbursed)
            assertEquals(day.minusDays(9), p.firstDue)
            assertEquals(day.plusDays(20), p.lastDue)
            assertEquals(50000L, p.cashAfter)
            assertEquals(0L, p.loan.receipts(day))
            assertEquals(0L, p.loan.interest(day))
            assertEquals(10, p.loan.installments.count { initialLoanUnpaid(it.due, day) })
            assertEquals(4500L, p.loan.installments.sumOf { it.late(day) })
        }
    }
    @Test fun registrationOfACompletedPastScheduleStartsWithEveryInstallmentPending() {
        val p = planDirectLoan(SheetsIdentity.EMPTY_TEST_ID, request, day.minusDays(60), day, 100000, emptyList(), emptyList())
        assertTrue(p.loan.installments.all { initialLoanUnpaid(it.due, day) })
        assertEquals(0L, p.loan.receipts(day)); assertTrue(p.loan.active(day))
    }
    @Test fun priorYearDisbursementRetainsItsOriginalIdentityAndCarryRows() {
        val p = planDirectLoan(SheetsIdentity.PRODUCTION_ID, request, LocalDate.of(2026, 12, 20),
            LocalDate.of(2027, 1, 10), 100000, emptyList(), emptyList())
        assertEquals("2026-001", p.loan.id); assertEquals(2026, p.year)
        assertEquals(406, p.rowIn(2027)); assertEquals("2026-001", p.rawId(2027))
        assertEquals(21, p.loan.installments.count { initialLoanUnpaid(it.due, LocalDate.of(2027, 1, 10)) })
    }
    @Test fun datesBeforeTheBookAndSchedulesBeyondItsEndAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            planDirectLoan(SheetsIdentity.PRODUCTION_ID, request, LocalDate.of(2025, 12, 31), day, 100000, emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            previewDatedLoan(request, LocalDate.of(2028, 12, 2), LocalDate.of(2028, 12, 20))
        }
    }
    @Test fun calendarBoundaryAndYearCarryAreExplicit() {
        val p = plan(date = LocalDate.of(2026, 12, 31))
        assertEquals(LocalDate.of(2027, 1, 1), p.firstDue); assertEquals(406, p.rowIn(2027))
        assertEquals("2026-001", p.rawId(2027)); assertEquals("1", p.rawId(2026))
        assertThrows(IllegalArgumentException::class.java) { plan(date = LocalDate.of(2028, 12, 2)) }
    }
    @Test fun changedFinancialFormulaOrInputFormulaRejectsEntireCreation() {
        val p = plan(); val cells = template(p)
        validateLoanTemplate(p, cells, p.year)
        cells[13] = DirectLoanCell(formula = "=ROUND(D6/30,2)")
        assertThrows(IllegalArgumentException::class.java) { validateLoanTemplate(p, cells, p.year) }
        val occupied = template(p).apply { this[2] = DirectLoanCell("Otro cliente") }
        assertThrows(IllegalArgumentException::class.java) { validateLoanTemplate(p, occupied, p.year) }
        val inputs = template(p).apply { this[4] = DirectLoanCell(formula = "=1") }
        assertThrows(IllegalArgumentException::class.java) { validateLoanTemplate(p, inputs, p.year) }
    }
    @Test fun carriedJanuaryFirstLoanCorrectsOnlyItsBoundaryFormula() {
        val p = plan(date = LocalDate.of(2026, 12, 31))
        val formula = "=IF(AND('2026'!\$B6<>\"\",ISNUMBER('2026'!\$P6),'2026'!\$P6<DATE(2027,1,1),'2026'!\$Q6>=DATE(2027,1,1)),'2026'!\$B6,\"\")"
        val cells = (1..17).associateWith { DirectLoanCell(formula = if (it == 2) formula else "='2026'!A6") }
        validateLoanTemplate(p, cells, 2027)
        assertEquals(formula.replace("<DATE", "<=DATE"), carryBoundaryFormula(p, 2027, formula))
        assertNull(carryBoundaryFormula(plan(date = LocalDate.of(2026, 12, 30)), 2027, formula))
        assertThrows(IllegalArgumentException::class.java) {
            validateLoanTemplate(p, cells + (2 to DirectLoanCell(formula = "='2026'!B7")), 2027)
        }
    }
    @Test fun calendarChecksMustHaveNativeBooleanValidationAndNoFormula() {
        for (value in listOf(null, "", false, true)) validateLoanCalendarCell(DirectLoanCell(value, validation = "BOOLEAN"), true)
        assertThrows(IllegalArgumentException::class.java) { validateLoanCalendarCell(DirectLoanCell(false), true) }
        assertThrows(IllegalArgumentException::class.java) { validateLoanCalendarCell(DirectLoanCell(formula = "=FALSE", validation = "BOOLEAN"), true) }
        assertThrows(IllegalArgumentException::class.java) { validateLoanCalendarCell(DirectLoanCell("Sí", validation = "BOOLEAN", validationValues = listOf("Sí", "No")), true) }
        assertThrows(IllegalArgumentException::class.java) { validateLoanCalendarCell(DirectLoanCell(), false) }
    }
    @Test fun actualHeadingsAcceptLineBreaksAndAccentsButRejectShiftedColumns() {
        val headings = MutableList<Any?>(17) { "" }.apply {
            this[0] = "ÍTEM"; this[1] = "CLIENTE"; this[3] = "PRÉSTAMO"; this[4] = "TASA\nmensual"
            this[5] = "MESES"; this[6] = "GARANTÍA"; this[15] = "PRIMER\nPAGO"; this[16] = "ÚLTIMO\nPAGO"
        }
        validateLoanHeadings(headings)
        headings[3] = "TOTAL PACTADO"
        assertThrows(IllegalArgumentException::class.java) { validateLoanHeadings(headings) }
    }
    @Test fun loanGuardsAreStableAcrossRetriesButSeparateOperations() {
        assertEquals(loanOperationKey("uuid-a"), loanOperationKey("uuid-a"))
        assertNotEquals(loanOperationKey("uuid-a"), loanOperationKey("uuid-b"))
        assertTrue(loanOperationKey("uuid-a").matches(Regex("cp_create_[a-f0-9]{40}")))
        assertNotEquals(loanSlotKey(2026, 6), loanSlotKey(2027, 6))
        assertNotEquals(loanItemKey(2026, 1), loanAuxSlotKey(2026, 1))
    }
    @Test fun configuredPrincipalCreatesWithoutAppsScriptButOtherBooksStayBlocked() {
        val connection = GoogleConnection(SheetsIdentity.PRODUCTION_ID, "EXAMPLE_FILE_39c1392094058ef3ccbf99d44f062604", "", emptyList(), false)
        assertTrue(connection.canCreateLoan(SheetsIdentity.PRODUCTION_ID))
        assertFalse(connection.canCreateLoan(connection.testBookId)); assertFalse(connection.canCreateLoan("OTHER_BOOK_ID_12345"))
        assertEquals(listOf(WRITE_SCOPE, EMAIL_SCOPE, "openid"), connection.scopes(false))
    }
    @Test fun deletedPositionsCanBeReusedOnlyWithConfirmedDeletionAndEmptyInputs() {
        val deleted = deletedLoanKey("2026-001")
        assertTrue(reusableLoanSlots(2026, listOf(row(1)), emptySet()).isEmpty())
        assertEquals(mapOf(6 to "2026-001"), reusableLoanSlots(2026, listOf(row(1)), setOf(deleted)))
        for (occupied in listOf(row(1, "Cliente"), row(1, principal = 10), row(1, first = sheetSerial(day)),
            row(1).apply { this[4] = .02 }, row(1).apply { this[5] = 1 })) {
            assertTrue(reusableLoanSlots(2026, listOf(occupied), setOf(deleted)).isEmpty())
        }
    }
    @Test fun recreatedPositionsKeepANewIdentityAndASeparateAtomicClaim() {
        val p = plan(rows = listOf(row(1)), reservedItems = setOf(1))
        assertEquals(6, p.row); assertEquals("2026-002", p.loan.id)
        assertEquals(loanSlotRevisionKey(2026, 6, "2026-001"), loanSlotRevisionKey(2026, 6, "2026-001"))
        assertNotEquals(loanSlotRevisionKey(2026, 6, null), loanSlotRevisionKey(2026, 6, "2026-001"))
        assertNotEquals(loanSlotRevisionKey(2026, 6, "2026-001"), loanSlotRevisionKey(2026, 6, "2026-002"))
        assertNotEquals(loanAuxRevisionKey(2026, 6, "2026-001"), loanAuxRevisionKey(2026, 6, "2026-002"))
    }
    @Test fun helperReuseRequiresTheExactRetiredIdentity() {
        val names = setOf(deletedLoanKey("2026-001"))
        assertEquals("2026-001", retiredAuxIdentity(2026, "1", names))
        assertNull(retiredAuxIdentity(2026, "2", names))
        assertNull(retiredAuxIdentity(2026, null, names))
    }
    @Test fun recyclingRestoresEditedRepartitionButRejectsUnrecognizedFormulas() {
        val p = plan()
        val cells = template(p).apply {
            this[13] = DirectLoanCell(formula = "=16.90")
            this[14] = DirectLoanCell(formula = "=19.90")
            this[15] = DirectLoanCell(formula = "=ROUND(H6-510.00,2)")
        }
        val restored = recycledLoanTemplate(6, cells)
        validateLoanTemplate(p, restored, 2026)
        assertEquals(nativeRepartitionFormulas(6)[13], restored[13]!!.formula)
        cells[13] = DirectLoanCell(formula = "=D6/30")
        assertThrows(IllegalArgumentException::class.java) { recycledLoanTemplate(6, cells) }
    }
}

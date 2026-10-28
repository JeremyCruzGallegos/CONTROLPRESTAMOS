package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class CashMovementsTest {
    private val day = today()
    @Test fun cashHistoryHasAllFourTypesAndUsesActualReceiptAndDisbursementDates() {
        val loan = Rules.createLoan("2026-001", LoanRequest("Cliente_Demo", 50000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(3))
        val record = SimpleRecord(8, loan, 100, false, "revision")
        val view = record.view(day)
        val payments = listOf(SheetPayment(view, view.firstDue, view.first, false, 200, day),
            SheetPayment(view, view.firstDue.plusDays(1), view.daily, true, 100))
        val rows = cashHistory(listOf(view), payments + payments, listOf(
            CashMovement("deposit", day, CashMovementType.DEPOSIT, 10000, "Ahorros"),
            CashMovement("withdrawal", day, CashMovementType.WITHDRAWAL, 2000, "")), emptyMap(), day)
        assertEquals(4, rows.size)
        assertEquals(day.minusDays(3), rows.single { it.type == CashMovementType.LOAN }.date)
        assertEquals(day, rows.single { it.type == CashMovementType.PAYMENT }.date)
        assertEquals(view.first + 200, rows.single { it.type == CashMovementType.PAYMENT }.amount)
        assertEquals("Ahorros", rows.single { it.type == CashMovementType.DEPOSIT }.description)
    }
    @Test fun deletingALoanAlsoRemovesItsPaymentsFromTheEffectiveMovementTable() {
        val loan = Rules.createLoan("2026-001", LoanRequest("Cliente", 50000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(2))
        val view = SimpleRecord(8, loan, 100, false, "rev").view(day)
        assertTrue(cashHistory(emptyList(), listOf(SheetPayment(view, view.firstDue, view.first, false, 0, day)), emptyList(), emptyMap(), day).isEmpty())
    }
    @Test fun negativeZeroOrExcessAmountsAndLongDescriptionsAreRejected() {
        for (amount in listOf(0L, -1L, MAX_INITIAL_CAPITAL + 1)) assertThrows(IllegalArgumentException::class.java) {
            CashMovement("id", day, CashMovementType.DEPOSIT, amount)
        }
        assertThrows(IllegalArgumentException::class.java) { CashMovement("id", day, CashMovementType.DEPOSIT, 1, "a".repeat(501)) }
        assertThrows(IllegalArgumentException::class.java) { movementCommand(JSONObject().put("operationId", "id").put("date", day.plusDays(1)).put("type", "DEPOSIT").put("amountCents", 1)) }
    }
    @Test fun depositsCannotFallOutsideTheAvailableNativeCashCalendar() {
        val movement = CashMovement("deposit", day.minusDays(2), CashMovementType.DEPOSIT, 10000)
        assertThrows(IllegalArgumentException::class.java) {
            validateCashMovement(movement, listOf(BookCashDay(day, 0, 0, 0, 100000)), 100000, day)
        }
    }
    @Test fun backdatedDepositReplacingPriorYearFundingKeepsTheCurrentYearOpeningAndFlowsCorrect() {
        val date = java.time.LocalDate.of(2027, 1, 10)
        val loan = Rules.createLoan("2026-001", LoanRequest("Cliente", 50000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), java.time.LocalDate.of(2026, 12, 20))
        val book = SimpleBook("Pruebas", 0, "rev", java.time.LocalDate.of(2027, 12, 31), listOf(SimpleRecord(8, loan, 100, false, "rev")), Instant.now())
        val summary = book.summary(2027, date)
        val movement = CashMovement("deposit", loan.disbursed, CashMovementType.DEPOSIT, 50000)
        val (after, cash) = projectCashMovement(summary, book.cash(date), movement, date, true)
        assertEquals(0L, after.contribution); assertEquals(0L, after.opening); assertEquals(0L, after.cash)
        assertEquals(0L, after.assumedContribution); assertEquals(0L, cash.last().cash)
        assertEquals(book.copy(movements = listOf(movement)).summary(2027, date), after)
    }
    @Test fun depositsAndWithdrawalsChangeOnlyExternalCapitalAndKeepTheDatedCashIdentity() {
        val movements = listOf(CashMovement("deposit", day.minusDays(1), CashMovementType.DEPOSIT, 10000),
            CashMovement("withdrawal", day, CashMovementType.WITHDRAWAL, 4000))
        val book = SimpleBook("Pruebas", 100000, "rev", day, emptyList(), Instant.now(), movements = movements)
        assertEquals(106000L, book.summary(day.year, day).cash)
        assertEquals(6000L, book.summary(day.year, day).contribution)
        assertEquals(0L, book.summary(day.year, day).assumedContribution)
        assertEquals(0L, book.summary(day.year, day).earnings)
        assertEquals(110000L, book.cash(day).single { it.date == day.minusDays(1) }.cash)
        assertNotNull(bookCashReport(book.cash(day), day, day.year, 0))
        assertEquals(movements, simpleBookFromJson(book.json()).movements)
    }
}

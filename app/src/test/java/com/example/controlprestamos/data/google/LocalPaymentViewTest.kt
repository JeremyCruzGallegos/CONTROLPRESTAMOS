package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.PaymentEdit
import com.example.controlprestamos.domain.today
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class LocalPaymentViewTest {
    private val day = today()
    private val loan = SheetLoan("${day.year}-001", "1", day.year, 6, "Cliente", 30000, BigDecimal("2"), 1,
        "", 30600, 0, 30700, 1020, 1020, day.minusDays(1), day.plusDays(28), 0, 30)
    private val row = SheetPayment(loan, day.minusDays(1), 1020, true, 100)
    @Test fun aLocalPaymentUpdatesCashDebtAndProgressWithoutChangingTheValidationSnapshot() {
        val changes = mapOf(row.id to (row to PaymentEdit(loan.id, row.id, 0, false, 50, day)))
        val view = localLoanView(loan, changes, day)
        assertEquals(1070L, localReceiptDelta(changes))
        assertEquals(29580L, view.debt); assertEquals(1, view.paid); assertEquals(1070L, view.collected)
        assertEquals(0, loan.paid); assertTrue(changes.getValue(row.id).first.unpaid)
    }
    @Test fun reopeningAPaymentRemovesActualReceiptsAndRestoresTheCurrentLateDebt() {
        val paid = row.copy(unpaid = false, late = 50, paidOn = day)
        val base = loan.copy(paid = 1, collected = 1070, debt = 29580)
        val changes = mapOf(row.id to (paid to PaymentEdit(loan.id, row.id, 0, true)))
        val view = localLoanView(base, changes, day)
        assertEquals(-1070L, localReceiptDelta(changes)); assertEquals(30700L, view.debt); assertEquals(0, view.paid)
    }
    @Test fun feeAdjustmentsChangeCashWithoutAddingAPaidInstallment() {
        val paid = row.copy(unpaid = false, late = 100, paidOn = day)
        val changes = mapOf(row.id to (paid to PaymentEdit(loan.id, row.id, 0, false, 50, day)))
        val view = localLoanView(loan.copy(paid = 1), changes, day)
        assertEquals(-50L, localReceiptDelta(changes)); assertEquals(1, view.paid)
    }
}

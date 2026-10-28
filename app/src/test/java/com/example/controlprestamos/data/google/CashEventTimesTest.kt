package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class CashEventTimesTest {
    private val day = today()
    private val book = SheetsIdentity.EMPTY_TEST_ID
    private val stamp = day.atTime(12, 0).atZone(LIMA).toInstant()
    private fun initial(): LocalBookView {
        val model = SimpleBook("Pruebas", 100000, "initial", day, emptyList(), stamp)
        return LocalBookView(model.preload(day.year, day, book), emptyList(), emptyList(), model.cash(day), actualDates = true)
    }
    private fun create() = JSONObject().put("action", "createLoan").put("disbursedOn", day.toString())
        .put("loan", JSONObject().put("client", "Cliente").put("principal", 1200).put("rate", 0.02)
            .put("months", 1).put("guarantee", "").put("origin", "Del capital").put("contribution", 0))
        .put("recordedAt", stamp.toString())

    @Test fun mixedHistoryIsSortedByExactTimeRegardlessOfTypeOrBackdatedBusinessDate() {
        val loan = SimpleRecord(8, Rules.createLoan("2026-001", LoanRequest("Ana", 30000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(2)), 100, false, "r").view(day)
        val payment = SheetPayment(loan, loan.firstDue, loan.first, false, 0, day)
        val entries = listOf(CashMovement("deposit", day.minusDays(1), CashMovementType.DEPOSIT, 5000),
            CashMovement("withdrawal", day, CashMovementType.WITHDRAWAL, 1000))
        val times = mapOf("loan:${loan.id}" to stamp, "pay:${payment.id}" to stamp.plusSeconds(2),
            "deposit" to stamp.plusSeconds(3), "withdrawal" to stamp.plusSeconds(1))
        val history = cashHistory(listOf(loan), listOf(payment), entries, emptyMap(), day, times)
        assertEquals(listOf("deposit", "pay:${payment.id}", "withdrawal", "loan:${loan.id}"), history.map { it.id })
        assertEquals(stamp.plusSeconds(3), history.first().recordedAt)
    }
    @Test fun externalShortfallIsRecordedOnceAsADepositAndSurvivesRestartWithItsExactTime() {
        val file = MemorySheetCache(); val queue = LocalOperations(file, "account", book); queue.refresh(initial())
        val command = queue.enqueue(create(), "actor")
        val restored = LocalOperations(file, "account", book).read().view!!
        val id = command.body.getString("localLoanId")
        assertEquals(20000L, restored.contributions[id])
        assertEquals(0L, restored.data.summary.cash)
        val history = cashHistory(restored.catalog, restored.rows, restored.movements, restored.contributions, day, restored.cashEventTimes)
        assertEquals(2, history.size)
        val deposit = history.single { it.type == CashMovementType.DEPOSIT }
        assertEquals(20000L, deposit.amount)
        assertEquals("excedente entre prestamo y caja, se abono dinero externo para completar el prestamo", deposit.description)
        assertEquals(stamp, deposit.recordedAt)
        assertEquals(stamp, restored.cashEventTimes["loan:$id"])
        val prepared = queue.prepare(initial()) { error("No calendar needed") }!!
        assertEquals(stamp.toString(), JSONObject(prepared.payload).getString("recordedAt"))
    }
    @Test fun bothCapitalIncreaseAndDecreaseAreDurableBeforeAnyNetworkRequest() {
        val file = MemorySheetCache(); val queue = LocalOperations(file, "account", book); queue.refresh(initial())
        fun change(before: Long, after: Long, cash: Long) = JSONObject().put("action", "setCapital").put("year", day.year)
            .put("beforeCents", before).put("afterCents", after).put("cashBeforeCents", cash)
        queue.enqueue(change(100000, 150000, 100000), "actor")
        queue.enqueue(change(150000, 80000, 150000), "actor")
        val restored = LocalOperations(file, "account", book).read()
        assertEquals(80000L, restored.view!!.data.summary.initial)
        assertEquals(80000L, restored.view.cash.last().cash)
        assertEquals(2, restored.operations.size)
    }
    @Test fun legacyLimaTimestampsAndNewPreciseTimestampsRoundTripWithoutInventingMissingTimes() {
        assertEquals(Instant.parse("2026-10-25T17:00:00Z"), cashTimestamp("2026-10-25 12:00:00"))
        assertEquals(Instant.parse("2026-10-25T17:00:00Z"), cashTimestamp(java.math.BigDecimal(sheetSerial(LocalDate.of(2026, 10, 25))).add(BigDecimal("0.5"))))
        assertNull(cashTimestamp(""))
        assertNull(cashTimestamp("sin hora"))
        val times = mapOf("pay:2026-001:$day" to stamp.plusNanos(123456))
        assertEquals(times, decodeCashTimes(encodeCashTimes(times)))
        val model = SimpleBook("Pruebas", 100000, "rev", day, emptyList(), stamp, cashEventTimes = times)
        assertEquals(times, simpleBookFromJson(model.json()).cashEventTimes)
    }
}

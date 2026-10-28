package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalOperationsTest {
    private val day = today()
    private val book = SheetsIdentity.EMPTY_TEST_ID
    @Test fun amountPaymentSavesItsFullAllocationAndRemainderBeforeAnyNetworkAndSurvivesRestart() {
        val base=initial();val loan=base.catalog.single()
        val amount=loan.first+loan.lateAt(loan.firstDue,day)+50
        val plan=planAmountPayment(base.record(loan.id,day),amount,day)
        val command=JSONObject().put("action","amountPayment").put("loanId",loan.id).put("year",loan.year)
            .put("version",loanDeletionVersion(loan)).put("amountCents",amount).put("waiveLate",false).put("confirmation",plan.confirmation())
        val store=MemorySheetCache();val queue=LocalOperations(store,"account",book);queue.refresh(base);queue.enqueue(command,"actor")
        val restored=LocalOperations(store,"account",book).read()
        assertEquals(base.data.summary.cash+amount,restored.view!!.data.summary.cash)
        assertEquals(1,restored.view.loan(loan.id).paid)
        assertEquals(1,restored.operations.size)
    }
    private fun move(type: CashMovementType, amount: Long, date: java.time.LocalDate = day, description: String = "") =
        JSONObject().put("action", "cashMovement").put("type", type.name).put("amountCents", amount)
            .put("date", date.toString()).put("description", description)
    @Test fun depositAndWithdrawalAreDurableLocalFlowsWithoutChangingInitialOrProfits() {
        val cache = MemorySheetCache(); val queue = queue(cache); val base = initial(); queue.refresh(base)
        queue.enqueue(move(CashMovementType.DEPOSIT, 12000, description = "Ahorros"), "actor")
        queue.enqueue(move(CashMovementType.WITHDRAWAL, 4500, description = "Compra de equipo"), "actor")
        val restored = queue(cache).read(); val view = restored.view!!
        assertEquals(base.data.summary.cash + 7500, view.data.summary.cash)
        assertEquals(base.data.summary.initial, view.data.summary.initial)
        assertEquals(base.data.summary.earnings, view.data.summary.earnings)
        assertEquals(base.data.summary.disbursed, view.data.summary.disbursed)
        assertEquals(listOf("Ahorros", "Compra de equipo"), view.movements.map { it.description })
        assertEquals(view.data.summary.cash, view.cash.last().cash)
        assertNotNull(bookCashReport(view.cash, day, day.year, 0))
        assertEquals(2, restored.operations.size)
    }
    @Test fun withdrawalCannotUseReceiptsThatArriveAfterItsDate() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        queue.enqueue(move(CashMovementType.DEPOSIT, 10000), "actor")
        assertThrows(IllegalArgumentException::class.java) {
            queue.enqueue(move(CashMovementType.WITHDRAWAL, 55000, day.minusDays(1)), "actor")
        }
        assertEquals(1, queue.read().operations.size)
    }
    @Test fun withdrawalIsRevalidatedAgainstTheRemoteCashBeforeItIsPrepared() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        queue.enqueue(move(CashMovementType.WITHDRAWAL, 40000), "actor")
        val fresh = base.copy(data = base.data.copy(summary = base.data.summary.copy(cash = 100)),
            cash = base.cash.map { it.copy(cash = 100) })
        assertThrows(IllegalArgumentException::class.java) { queue.prepare(fresh) { error("No loan query") } }
        assertNotNull(queue.read().conflict)
        assertEquals(base.data.summary.cash - 40000, queue.read().view!!.data.summary.cash)
    }
    @Test fun confirmedCashMovementIsNotAppliedTwiceAndKeepsTheRemainingOfflineWithdrawal() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        val entry = queue.enqueue(move(CashMovementType.DEPOSIT, 10000), "actor")
        queue.enqueue(move(CashMovementType.WITHDRAWAL, 5000), "actor")
        val prepared = queue.prepare(base) { error("No loan query") }!!
        val fresh = applyLocalOperation(base, entry.body, book, day)
        queue.confirmed(prepared, JSONObject(), fresh)
        val restored = queue.read()
        assertEquals(1, restored.operations.size)
        assertEquals(base.data.summary.cash + 5000, restored.view!!.data.summary.cash)
        assertEquals(2, restored.view.movements.size)
    }
    @Test fun failedCashCommitDoesNotChangeTheBalanceOrLoseThePreviousSnapshot() {
        val cache = MemorySheetCache(); val queue = queue(cache); val base = initial(); queue.refresh(base); cache.failWrite = true
        assertThrows(IllegalStateException::class.java) { queue.enqueue(move(CashMovementType.DEPOSIT, 10000), "actor") }
        assertEquals(base.data.summary.cash, queue.read().view!!.data.summary.cash)
        assertTrue(queue.read().view!!.movements.isEmpty())
    }
    @Test fun backdatedDepositReplacesInferredFundingWithoutArtificiallyIncreasingCash() {
        val day = this.day; val base = initial()
        val summary = base.data.summary.copy(initial = 0, cash = 0, contribution = 50000, assumedContribution = 50000)
        val source = base.copy(data = base.data.copy(summary = summary), cash = base.cash.map { row ->
            row.copy(contribution = if (row.date == base.catalog.single().firstDue.minusDays(1)) 50000 else 0,
                assumedContribution = if (row.date == base.catalog.single().firstDue.minusDays(1)) 50000 else 0,
                cash = 0)
        })
        val body = move(CashMovementType.DEPOSIT, 50000, base.catalog.single().firstDue.minusDays(1)).put("operationId", "deposit-operation-123")
        val projected = applyLocalOperation(source, body, book, day)
        assertEquals(0L, projected.data.summary.cash)
        assertEquals(0L, projected.data.summary.assumedContribution)
        assertEquals(50000L, projected.data.summary.contribution)
        assertEquals(0L, projected.cash.last().cash)
    }
    private fun initial(months: Int = 1): LocalBookView {
        val loan = Rules.createLoan("${day.year}-001", LoanRequest("Cliente", 50_000, BigDecimal("2"), months, "Garantía", Funding.CAPITAL, 0), day.minusDays(months * 30L - 1))
        val record = SimpleRecord(8, loan, 100, false, "initial")
        val model = SimpleBook("Pruebas", 100_000, "initial", day.plusYears(1), listOf(record), Instant.now(), headerRow = 7)
        return LocalBookView(model.preload(day.year, day, book), model.allViews(day), loan.installments.map { model.payment(record, it, day, book) }, model.cash(day), mapOf(loan.id to 0), true)
    }
    private fun SimpleBook.allViews(day: java.time.LocalDate) = active.map { it.view(day) }
    private fun create(date: java.time.LocalDate = day, principal: Double = 100.0) = JSONObject().put("action", "createLoan")
        .put("loan", JSONObject().put("client", "Nuevo cliente").put("principal", principal).put("rate", 0.02).put("months", 1)
            .put("guarantee", "").put("origin", "Del capital").put("contribution", 0))
        .put("disbursedOn", date.toString()).put("pastPaymentsPaid", false)
    private fun pay(view: LocalBookView, id: String = view.catalog.first().id, unpaid: Boolean = false): JSONObject {
        val row = view.rows.first { it.loan.id == id && it.due <= day }
        return JSONObject().put("action", "pay").put("edits", JSONArray().put(JSONObject().put("version", JSONObject(row.version!!))
            .put("unpaid", unpaid).put("collectedLate", 0).put("paidOn", if (unpaid) "" else day.toString())))
    }
    private fun queue(cache: SheetCache = MemorySheetCache()) = LocalOperations(cache, "account", book)

    @Test fun creationIsLocalDurableAndUpdatesCashBeforeAnyNetworkRequest() {
        val cache = MemorySheetCache(); val queue = queue(cache); val base = initial(); queue.refresh(base)
        val operation = queue.enqueue(create(), "actor")
        val restored = queue(cache).read()
        assertEquals(1, restored.operations.size)
        assertEquals(2, restored.view!!.catalog.size)
        assertEquals(base.data.summary.cash - 10_000, restored.view.data.summary.cash)
        assertEquals(operation.body.getString("localLoanId"), restored.view.catalog.last().id)
        assertNull(restored.operations.single().prepared)
    }
    @Test fun failedLocalCommitNeverPublishesAnUnpersistedCreation() {
        val cache = MemorySheetCache(); val queue = queue(cache); val base = initial(); queue.refresh(base); cache.failWrite = true
        assertThrows(IllegalStateException::class.java) { queue.enqueue(create(), "actor") }
        assertEquals(base.data.summary.cash, queue.read().view!!.data.summary.cash)
        assertTrue(queue.read().operations.isEmpty())
    }
    @Test fun aNewClientCanReceivePaymentsOfflineBeforeItHasARemoteId() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        val created = queue.enqueue(create(day.minusDays(1)), "actor")
        val localId = created.body.getString("localLoanId")
        queue.enqueue(pay(queue.read().view!!, localId), "actor")
        assertEquals(2, queue.read().operations.size)
        assertEquals(1, queue.read().view!!.loan(localId).paid)
    }
    @Test fun retriesAfterProcessRestartKeepTheExactPreparedPayload() {
        val cache = MemorySheetCache(); val queue = queue(cache); val base = initial(); queue.refresh(base)
        queue.enqueue(pay(base), "actor")
        val pending = queue.prepare(base) { base.calendar(it.id) }!!
        repeat(4) { assertEquals(pending, queue(cache).prepare(base.copy(data = base.data.copy(summary = base.data.summary.copy(cash = 1)))) { error("Must not reprepare") }) }
    }
    @Test fun remoteCreationRemapsDependentPaymentsAndDoesNotDoubleSubtractCash() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        val created = queue.enqueue(create(day.minusDays(1)), "actor")
        val provisional = created.body.getString("localLoanId")
        queue.enqueue(pay(queue.read().view!!, provisional), "actor")
        val pending = queue.prepare(base) { base.calendar(it.id) }!!
        val actualId = "${day.year}-002"
        val remoteBody = JSONObject(created.body.toString()).put("localLoanId", actualId)
        val remote = applyLocalOperation(base, remoteBody, book)
        queue.confirmed(pending, JSONObject().put("loanId", actualId), remote)
        val state = queue.read()
        assertEquals(actualId, queue.resolveId(provisional))
        assertEquals(1, state.operations.size)
        val next = queue.prepare(remote) { remote.calendar(it.id) }!!
        assertEquals(actualId, JSONObject(next.payload).getJSONArray("edits").getJSONObject(0).getJSONObject("version").getString("loanId"))
        assertEquals(1, state.view!!.loan(actualId).paid)
        assertEquals(remote.data.summary.cash + state.view.rows.first { it.loan.id == actualId }.base, state.view.data.summary.cash)
    }
    @Test fun aConcurrentRemotePaymentIsRetainedAsAConflictAndLocalDataSurvives() {
        val queue = queue(); val base = initial(); queue.refresh(base); queue.enqueue(pay(base), "actor")
        val remote = applyLocalOperation(base, pay(base).put("operationId", "remote-payment-001"), book)
        assertThrows(IllegalArgumentException::class.java) { queue.prepare(remote) { remote.calendar(it.id) } }
        assertNotNull(queue.read().conflict)
        assertEquals(1, queue.read().operations.size)
        assertNull(queue.read().operations.single().prepared)
        queue.discardRejected()
        assertTrue(queue.read().operations.isEmpty())
        assertEquals(remote.data.summary.cash, queue.read().view!!.data.summary.cash)
    }
    @Test fun paymentReversalAndCapitalChangesAreImmediatelyReflectedLocally() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        queue.enqueue(pay(base), "actor")
        val paid = queue.read().view!!
        queue.enqueue(pay(paid, unpaid = true), "actor")
        assertEquals(base.data.summary.cash, queue.read().view!!.data.summary.cash)
        val capital = JSONObject().put("action", "setCapital").put("year", day.year).put("beforeCents", 100_000).put("afterCents", 120_000).put("cashBeforeCents", base.data.summary.cash)
        queue.enqueue(capital, "actor")
        assertEquals(120_000L, queue.read().view!!.data.summary.initial)
        assertEquals(base.data.summary.cash + 20_000, queue.read().view!!.data.summary.cash)
    }
    @Test fun deletingOfflineRestoresPrincipalAndRetainsTheQueuedDeletion() {
        val queue = queue(); val base = initial(); queue.refresh(base)
        queue.enqueue(JSONObject().put("action", "deleteLoan").put("loanId", base.catalog.single().id), "actor")
        assertTrue(queue.read().view!!.catalog.isEmpty())
        assertEquals(100_000L, queue.read().view!!.data.summary.cash)
        assertEquals(1, queue.read().operations.size)
    }
    @Test fun editingTermsOfflineRecalculatesTheScheduleAndCash() {
        val queue = queue(); val base = initial(); queue.refresh(base); val loan = base.catalog.single()
        val input = LoanTermsInput("Otro cliente", "Otra garantía", 60_000, BigDecimal("2"), 1, loan.firstDue.minusDays(1), 50)
        queue.enqueue(JSONObject().put("action", "editLoanTerms").put("loanId", loan.id).put("terms", input.json()), "actor")
        val after = queue.read().view!!
        assertEquals("Otro cliente", after.loan(loan.id).client)
        assertEquals(60_000L, after.loan(loan.id).principal)
        assertEquals(base.data.summary.cash - 10_000, after.data.summary.cash)
        assertEquals(30, after.calendar(loan.id).days.size)
    }
    @Test fun oneHundredAndOnePaymentsAreCommittedAtomicallyAndSentInBoundedBatches() {
        val base = initial(4); val cache = MemorySheetCache(); val queue = queue(cache); queue.refresh(base)
        val edits = base.rows.filter { it.due <= day }.take(101).map { row -> JSONObject().put("version", JSONObject(row.version!!))
            .put("unpaid", false).put("collectedLate", 0).put("paidOn", day.toString()) }
        queue.enqueue(JSONObject().put("action", "pay").put("operationId", "bulk-operation-001").put("edits", JSONArray(edits)), "actor")
        val restored = queue(cache).read()
        assertEquals(listOf(100, 1), restored.operations.map { it.body.getJSONArray("edits").length() })
        assertEquals(101, restored.view!!.catalog.single().paid)
    }
    @Test fun networkPreparationNeverBlocksTheLocalCommitLock() {
        val base = initial(); val queue = queue(); queue.refresh(base); queue.enqueue(pay(base), "actor")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<PendingRemoteWrite?> { queue.prepare(base) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); base.calendar(it.id) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.enqueue(JSONObject().put("action", "editLoanText").put("loanId", base.catalog.single().id).put("client", "Nuevo nombre").put("guarantee", ""), "actor")
            assertEquals(2, queue.read().operations.size)
            release.countDown(); assertNotNull(future.get(2, TimeUnit.SECONDS))
            assertEquals(2, queue.read().operations.size)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun accountAndBookIsolationAreCheckedWhenReadingTheLocalJournal() {
        val cache = MemorySheetCache(); queue(cache).refresh(initial())
        assertThrows(IllegalArgumentException::class.java) { LocalOperations(cache, "another-account", book).read() }
        assertThrows(IllegalArgumentException::class.java) { LocalOperations(cache, "account", SheetsIdentity.PRODUCTION_ID).read() }
    }

    private fun historicalFunding(): LocalBookView {
        val first = Rules.createLoan("${day.year}-001", LoanRequest("Primero", 100_000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(24))
            .let { loan -> loan.copy(installments = loan.installments.mapIndexed { i, item -> if (i < 10) item.copy(paidOn = day.minusDays(4)) else item }) }
        val second = Rules.createLoan("${day.year}-002", LoanRequest("Segundo", 50_000, BigDecimal("2"), 1, "", Funding.CAPITAL, 0), day.minusDays(7))
        val records = listOf(SimpleRecord(8, first, 100, false, "first"), SimpleRecord(9, second, 100, false, "second"))
        val model = SimpleBook("Pruebas", 100_000, "initial", day.plusYears(1), records, Instant.now())
        return LocalBookView(model.preload(day.year, day, book), records.map { it.view(day) }, records.flatMap { record -> record.loan.installments.map { model.payment(record, it, day, book) } },
            model.cash(day), records.associate { it.loan.id to 0L }, true)
    }
    @Test fun aHistoricalReceiptReconcilesAssumedFundingWithoutCountingTheMoneyTwice() {
        val base = historicalFunding(); assertEquals(50_000L, base.data.summary.assumedContribution)
        val queue = queue(); queue.refresh(base)
        val row = base.rows.first { it.loan.id == base.catalog.first().id && it.unpaid && it.due <= day.minusDays(8) }
        val body = JSONObject().put("action", "pay").put("edits", JSONArray().put(JSONObject().put("version", JSONObject(row.version!!))
            .put("unpaid", false).put("collectedLate", 0).put("paidOn", day.minusDays(8).toString())))
        queue.enqueue(body, "actor")
        val after = queue.read().view!!
        assertEquals(50_000L - row.base, after.data.summary.assumedContribution)
        assertEquals(base.data.summary.cash, after.data.summary.cash)
        assertEquals(after.data.summary.cash, after.cash.last().cash)
    }
    @Test fun increasingInitialCapitalDoesNotAddAnAlreadyAssumedContributionTwice() {
        val base = historicalFunding(); val queue = queue(); queue.refresh(base)
        queue.enqueue(JSONObject().put("action", "setCapital").put("year", day.year).put("beforeCents", 100_000)
            .put("afterCents", 140_000).put("cashBeforeCents", base.data.summary.cash), "actor")
        val after = queue.read().view!!
        assertEquals(140_000L, after.data.summary.initial)
        assertEquals(10_000L, after.data.summary.assumedContribution)
        assertEquals(base.data.summary.cash, after.data.summary.cash)
        assertEquals(after.data.summary.cash, after.cash.last().cash)
    }
}

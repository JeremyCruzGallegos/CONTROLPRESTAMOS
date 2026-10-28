package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class LoanGroupingTest {
    private val day = today()
    private val book = SheetsIdentity.EMPTY_TEST_ID
    private fun initial(): LocalBookView {
        val records = listOf("Ghustabo", "Cliente_Demo", "Otro cliente").mapIndexed { index, client ->
            val contract = Rules.createLoan("${day.year}-${(index + 1).toString().padStart(3, '0')}",
                LoanRequest(client, 30000, BigDecimal("2"), 1, "Garantía $index", Funding.CAPITAL, 0), day.minusDays(4))
            SimpleRecord(index + 8, contract, 100, false, "rev$index")
        }
        val model = SimpleBook("Pruebas", 200000, "rev", day.plusMonths(3), records, Instant.now())
        val catalog = records.map { it.view(day) }
        val rows = records.flatMap { record -> record.loan.installments.map { model.payment(record, it, day, book) } }
        return LocalBookView(model.preload(day.year, day, book), catalog, rows, model.cash(day), catalog.associate { it.id to 0L }, true)
    }
    private fun group(view: LocalBookView, name: String = "Cliente_Demo Pérez") = JSONObject().put("action", "groupLoans")
        .put("loanIds", JSONArray(view.catalog.take(2).map { it.id })).put("client", name)
    private fun queue(file: SheetCache = MemorySheetCache()) = LocalOperations(file, "account", book)

    @Test fun groupingDifferentSpellingsChangesOnlySelectedNamesAndSurvivesRestart() {
        val file = MemorySheetCache(); val queue = queue(file); val before = initial(); queue.refresh(before)
        queue.enqueue(group(before), "actor")
        val restored = queue(file).read(); val after = restored.view!!
        assertEquals(listOf("Cliente_Demo Pérez", "Cliente_Demo Pérez", "Otro cliente"), after.catalog.map { it.client })
        assertEquals(before.catalog, after.catalog.mapIndexed { i, loan -> loan.copy(client = before.catalog[i].client) })
        assertEquals(before.data.summary, after.data.summary)
        assertEquals(before.cash, after.cash)
        assertEquals(before.contributions, after.contributions)
        assertEquals(before.rows, after.rows.map { row -> row.copy(loan = before.loan(row.loan.id)) })
        assertEquals(2, restored.operations.size)
        assertTrue(restored.operations.all { it.body.getString("action") == "editLoanText" })
        assertEquals(2, clientLoanGroups(after.catalog, day).size)
    }
    @Test fun diskFailureDoesNotPublishHalfOfTheGroupOrEnqueueAnyOfItsRenames() {
        val file = MemorySheetCache(); val queue = queue(file); val before = initial(); queue.refresh(before)
        file.failWrite = true
        assertThrows(IllegalStateException::class.java) { queue.enqueue(group(before), "actor") }
        assertEquals(before.catalog, queue.read().view!!.catalog)
        assertTrue(queue.read().operations.isEmpty())
    }
    @Test fun anExistingSelectedNameIsReusedWithoutEnqueuingAnUnchangedLoan() {
        val queue = queue(); val before = initial(); queue.refresh(before)
        queue.enqueue(group(before, "Cliente_Demo"), "actor")
        assertEquals(1, queue.read().operations.size)
        assertEquals(listOf("Cliente_Demo", "Cliente_Demo"), queue.read().view!!.catalog.take(2).map { it.client })
    }
    @Test fun duplicateMissingSingleAndUnselectedExistingNameChoicesAreRejected() {
        val before = initial(); val ids = before.catalog.take(2).map { it.id }
        assertThrows(IllegalArgumentException::class.java) { planLoanGrouping(before.catalog, listOf(ids.first()), "Cliente_Demo", day) }
        assertThrows(IllegalArgumentException::class.java) { planLoanGrouping(before.catalog, listOf(ids.first(), ids.first()), "Cliente_Demo", day) }
        assertThrows(IllegalStateException::class.java) { planLoanGrouping(before.catalog, ids + "2026-999", "Cliente_Demo", day) }
        assertThrows(IllegalArgumentException::class.java) { planLoanGrouping(before.catalog, ids, "Otro cliente", day) }
        for (name in listOf(" ", "a".repeat(201), "Cliente_Demo\nPérez")) {
            assertThrows(IllegalArgumentException::class.java) { planLoanGrouping(before.catalog, ids, name, day) }
        }
        assertEquals("Cliente_Demo Pérez", planLoanGrouping(before.catalog, ids, "  Cliente_Demo   Pérez  ", day).client)
    }
    @Test fun partialRemoteAcknowledgementKeepsTheRestOfTheGroupVisibleAndRetryable() {
        val file = MemorySheetCache(); val queue = queue(file); val before = initial(); queue.refresh(before)
        queue.enqueue(group(before), "actor")
        val first = queue.read().operations.first()
        val pending = queue.prepare(before) { error("Text edits do not need calendars") }!!
        val partlySynced = applyLocalOperation(before, first.body, book, day)
        queue.confirmed(pending, JSONObject(), partlySynced)
        val restored = queue(file).read()
        assertEquals(1, restored.operations.size)
        assertEquals(listOf("Cliente_Demo Pérez", "Cliente_Demo Pérez"), restored.view!!.catalog.take(2).map { it.client })
        val second = queue.prepare(partlySynced) { error("Text edits do not need calendars") }!!
        assertEquals(second, queue(file).prepare(before) { error("Frozen retry") })
    }
    @Test fun aNewOfflineLoanCanBeGroupedAndItsRenameFollowsTheRemoteIdAlias() {
        val queue = queue(); val before = initial(); queue.refresh(before)
        val created = queue.enqueue(JSONObject().put("action", "createLoan").put("disbursedOn", day.toString())
            .put("loan", JSONObject().put("client", "Gustabo nuevo").put("principal", 100).put("rate", 0.02)
                .put("months", 1).put("guarantee", "").put("origin", "Del capital").put("contribution", 0)), "actor")
        val id = created.body.getString("localLoanId")
        queue.enqueue(JSONObject().put("action", "groupLoans").put("loanIds", JSONArray(listOf(before.catalog.first().id, id)))
            .put("client", "Cliente_Demo único"), "actor")
        val pending = queue.prepare(before) { error("No calendar") }!!
        val remoteId = "${day.year}-004"
        val remoteCreation = JSONObject(created.body.toString()).put("localLoanId", remoteId)
        val fresh = applyLocalOperation(before, remoteCreation, book, day)
        queue.confirmed(pending, JSONObject().put("loanId", remoteId), fresh)
        assertEquals("Cliente_Demo único", queue.read().view!!.loan(remoteId).client)
        assertTrue(queue.read().operations.any { it.body.optString("loanId") == remoteId })
        assertFalse(queue.read().operations.any { it.body.optString("loanId") == id })
    }
    @Test fun anIdAcknowledgedWhileTheGroupingWasBeingConfirmedResolvesBeforeTheLocalCommit() {
        val queue = queue(); val before = initial(); queue.refresh(before)
        val created = queue.enqueue(JSONObject().put("action", "createLoan").put("disbursedOn", day.toString())
            .put("loan", JSONObject().put("client", "Gustabo nuevo").put("principal", 100).put("rate", 0.02)
                .put("months", 1).put("guarantee", "").put("origin", "Del capital").put("contribution", 0)), "actor")
        val temporary = created.body.getString("localLoanId")
        val pending = queue.prepare(before) { error("No calendar") }!!
        val remoteId = "${day.year}-004"
        val fresh = applyLocalOperation(before, JSONObject(created.body.toString()).put("localLoanId", remoteId), book, day)
        queue.confirmed(pending, JSONObject().put("loanId", remoteId), fresh)
        queue.enqueue(JSONObject().put("action", "groupLoans").put("loanIds", JSONArray(listOf(before.catalog.first().id, temporary)))
            .put("client", "Cliente_Demo único"), "actor")
        assertEquals("Cliente_Demo único", queue.read().view!!.loan(remoteId).client)
        assertFalse(queue.read().operations.any { it.body.optString("loanId") == temporary })
    }

    @Test fun unrelatedRemotePaymentsAndGuaranteesDoNotPreventAClientOnlyGrouping() {
        val queue = queue(); val before = initial(); queue.refresh(before)
        queue.enqueue(group(before), "actor")
        val original = before.catalog.first()
        val changed = original.copy(paid = 1, collected = 1000, guarantee = "Garantía cambiada en Google")
        val fresh = before.copy(catalog = before.catalog.map { if (it.id == original.id) changed else it })
        val pending = queue.prepare(fresh) { error("No calendar") }!!
        val command = JSONObject(pending.payload)
        val edit = rebasedLoanTextEdit(original, changed, command.getString("client"), command.getString("guarantee"))
        assertEquals(listOf(1), edit.fields.map { it.columnIndex })
        assertEquals(changed.guarantee, edit.guarantee)
    }
    @Test fun anUnselectedRemoteLoanWithTheTargetNameCannotBeSilentlyAddedToTheGroup() {
        val queue = queue(); val before = initial(); queue.refresh(before)
        queue.enqueue(group(before), "actor")
        val fresh = before.copy(catalog = before.catalog.mapIndexed { i, loan -> if (i == 2) loan.copy(client = "CLIENTE_DEMO PÉREZ") else loan })
        assertThrows(IllegalArgumentException::class.java) { queue.prepare(fresh) { error("No calendar") } }
        assertNotNull(queue.read().conflict)
        assertEquals("Otro cliente", queue.read().view!!.catalog.last().client)
    }

    @Test fun aNameChangedExternallyCreatesAReviewConflictInsteadOfOverwritingIt() {
        val queue = queue(); val before = initial(); queue.refresh(before)
        queue.enqueue(group(before), "actor")
        val fresh = before.copy(catalog = before.catalog.mapIndexed { i, loan -> if (i == 0) loan.copy(client = "Persona distinta") else loan })
        assertThrows(IllegalArgumentException::class.java) { queue.prepare(fresh) { error("No calendar") } }
        assertNotNull(queue.read().conflict)
        assertEquals("Cliente_Demo Pérez", queue.read().view!!.catalog.first().client)
    }
}

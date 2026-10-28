package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

internal class MemorySheetCache : SheetCache {
    private var value: String? = null
    var failWrite = false
    override fun read() = value?.let(::JSONObject)
    override fun write(value: JSONObject) { check(!failWrite) { "Disco lleno" }; this.value = value.toString() }
    override fun delete() { value = null }
}

class PaymentOutboxTest {
    private val day = today()
    private val book = SheetsIdentity.PRODUCTION_ID
    private val loan = SheetLoan("${day.year}-001", "1", day.year, 6, "Cliente", 30000, BigDecimal("2"), 1,
        "Garantía", 30600, 0, 30600, 1020, 1020, day.minusDays(2), day.plusDays(27), 0, 30)
    private val row = SheetPayment(loan, day, 1020, true, 0).let {
        it.copy(version = directPaymentVersion(book, it, 0).toJson().toString())
    }
    private fun freshPaid(source: SheetPayment = row) = source.copy(unpaid = false, paidOn = day, late = 0).let {
        it.copy(version = directPaymentVersion(book, it, 0, "confirmed").toJson().toString())
    }
    @Test fun aTapIsDurableBeforeAnyNetworkWrite() {
        val cache = MemorySheetCache(); val queue = PaymentOutbox(cache, "account", book)
        queue.stage(row, false, 0, day, day)
        val recovered = PaymentOutbox(cache, "account", book).read()
        assertFalse(recovered.changes.getValue(row.id).second.unpaid)
        assertNull(recovered.flight)
        assertEquals(row, recovered.changes.getValue(row.id).first)
    }
    @Test fun retriesAndProcessRestartsReuseExactlyTheSamePayloadAndOperationId() {
        val cache = MemorySheetCache(); val queue = PaymentOutbox(cache, "account", book)
        queue.stage(row, false, 0, day, day)
        val first = queue.begin("owner@example.test", true)!!
        repeat(8) { assertEquals(first, PaymentOutbox(cache, "account", book).begin("owner@example.test", true)) }
        val body = JSONObject(first.pending.payload)
        assertEquals(first.pending.operationId, body.getString("operationId"))
        assertEquals(book, body.getString("bookId"))
        assertEquals("sheets", body.getString("transport"))
    }
    @Test fun repeatedTapsBeforeSendingAreCoalesced() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day)
        queue.stage(row, true, 0, null, day)
        assertTrue(queue.read().changes.isEmpty()); assertNull(queue.begin("actor", true))
    }
    @Test fun reversingWhileSendingSurvivesAcknowledgementAndUsesFreshVersion() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day)
        val first = queue.begin("actor", true)!!
        queue.stage(row, true, 0, null, day)
        assertEquals(first, queue.begin("actor", true))
        val paid = freshPaid()
        queue.confirmed(first, listOf(paid))
        val reverse = queue.begin("actor", true)!!
        assertNotEquals(first.pending.operationId, reverse.pending.operationId)
        val edit = JSONObject(reverse.pending.payload).getJSONArray("edits").getJSONObject(0)
        assertTrue(edit.getBoolean("unpaid"))
        assertEquals("confirmed", edit.getJSONObject("version").getString("revision"))
        assertEquals(paid, reverse.changes.getValue(row.id).first)
    }
    @Test fun unchangedDesiredStateIsRemovedOnlyAfterConfirmation() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day)
        val flight = queue.begin("actor", true)!!
        queue.confirmed(flight, listOf(freshPaid()))
        assertTrue(queue.read().changes.isEmpty()); assertNull(queue.read().flight)
    }
    @Test fun anotherPaymentPreparedDuringSendIsKept() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        val other = row.copy(loan = loan.copy(id = "${day.year}-002", rawId = "2"))
        queue.stage(row, false, 0, day, day)
        val flight = queue.begin("actor", true)!!
        queue.stage(other, false, 0, day, day)
        queue.confirmed(flight, listOf(freshPaid()))
        assertEquals(setOf(other.id), queue.read().changes.keys)
    }
    @Test fun aDefiniteConflictIsKeptWithoutRetryingOrOverwritingRemoteData() {
        val cache = MemorySheetCache(); val queue = PaymentOutbox(cache, "account", book)
        queue.stage(row, false, 0, day, day)
        val flight = queue.begin("actor", true)!!
        queue.reject(flight, "Otra persona cambió esta cuota")
        val restarted = PaymentOutbox(cache, "account", book)
        assertNull(restarted.begin("actor", true))
        assertNotNull(restarted.read().conflict)
        assertEquals(flight, restarted.read().flight)
        restarted.discardRejected()
        assertTrue(restarted.read().changes.isEmpty()); assertNull(restarted.read().flight)
    }
    @Test fun anUnknownNetworkOutcomeCannotBeDiscarded() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day); queue.begin("actor", true)
        assertThrows(IllegalArgumentException::class.java) { queue.discardRejected() }
        assertNotNull(queue.read().flight)
    }
    @Test fun localDiskFailureDoesNotExposeAnUnpersistedRequest() {
        val cache = MemorySheetCache(); val queue = PaymentOutbox(cache, "account", book)
        queue.stage(row, false, 0, day, day); cache.failWrite = true
        assertThrows(IllegalStateException::class.java) { queue.begin("actor", true) }
        assertNull(queue.read().flight); assertEquals(1, queue.read().changes.size)
    }
    @Test fun aQueueCannotBeReadByAnotherAccountOrBook() {
        val cache = MemorySheetCache(); PaymentOutbox(cache, "account", book).stage(row, false, 0, day, day)
        assertThrows(IllegalArgumentException::class.java) { PaymentOutbox(cache, "another", book).read() }
        assertThrows(IllegalArgumentException::class.java) { PaymentOutbox(cache, "account", SheetsIdentity.EMPTY_TEST_ID).read() }
    }
    @Test fun draftsWithoutEditableVersionsRoundTripWithoutInventingAVersion() {
        val original = row.copy(version = null)
        val changes = mapOf(original.id to (original to com.example.controlprestamos.domain.PaymentEdit(original.loan.id, original.id, 0, false, 0, day)))
        val decoded = decodeDraft(draftPacket("account", book, changes), "account", book)
        assertNull(decoded.getValue(row.id).first.version)
    }
    @Test fun legacyDraftImportDoesNotReplaceNewerAutomaticChanges() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day)
        queue.importDraft(stagePaymentDraft(emptyMap(), row, false, 0, day.minusDays(0), day))
        assertEquals(1, queue.read().changes.size)
    }
    @Test fun batchesAreLimitedToOneHundredAndKeepTheRest() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        val longLoan = loan.copy(months = 4, total = 120, firstDue = day.minusDays(119), lastDue = day,
            principal = 12000, agreed = 12240, debt = 12240, daily = 102, first = 102)
        val rows = (0..100).map { index ->
            SheetPayment(longLoan, longLoan.firstDue.plusDays(index.toLong()), 102, true, 0).let {
                it.copy(version = directPaymentVersion(book, it, 0).toJson().toString())
            }
        }
        rows.forEach { queue.stage(it, false, 0, day, day) }
        val first = queue.begin("actor", true)!!
        assertEquals(100, JSONObject(first.pending.payload).getJSONArray("edits").length())
        queue.confirmed(first, rows.take(100).map { freshPaid(it) })
        assertEquals(1, queue.read().changes.size)
        assertEquals(1, queue.begin("actor", true)!!.changes.size)
    }
    @Test fun bulkChangesAreSavedTogetherAndSurviveRestart() {
        val cache = MemorySheetCache(); val queue = PaymentOutbox(cache, "account", book)
        val second = row.copy(due = day.minusDays(1)).let {
            it.copy(version = directPaymentVersion(book, it, 0).toJson().toString())
        }
        queue.stageAll(listOf(row, second), false, day, day)
        assertEquals(setOf(row.id, second.id), PaymentOutbox(cache, "account", book).read().changes.keys)
    }
    @Test fun anInvalidBulkSelectionLeavesTheWholeQueueUnchanged() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        val invalid = row.copy(due = day.minusDays(1), version = null)
        assertThrows(IllegalArgumentException::class.java) { queue.stageAll(listOf(row, invalid), false, day, day) }
        assertTrue(queue.read().changes.isEmpty())
    }

    @Test fun aReceiptSurvivesRestartAndDoesNotCountTwiceInANewerCashSnapshot() {
        val cache = MemorySheetCache(); val queue = PaymentOutbox(cache, "account", book)
        queue.stage(row, false, 0, day, day)
        val flight = queue.begin("actor", true)!!
        queue.acknowledge(flight, 1000)
        val recovered = PaymentOutbox(cache, "account", book).read()
        assertEquals(1000L, recovered.flight!!.acknowledgedAt)
        assertEquals(1020L, localReceiptDelta(financialPaymentChanges(recovered, 999, day)))
        assertEquals(0L, localReceiptDelta(financialPaymentChanges(recovered, 1001, day)))
        assertEquals(flight.pending, queue.begin("actor", true)!!.pending)
    }
    @Test fun reversingAnAcknowledgedPaymentProjectsOnlyTheNewDifference() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day)
        val flight = queue.begin("actor", true)!!
        queue.acknowledge(flight, 1000)
        queue.stage(row, true, 0, null, day)
        val changes = financialPaymentChanges(queue.read(), 1001, day)
        assertEquals(-1020L, localReceiptDelta(changes))
        val view = localLoanView(loan.copy(paid = 1, collected = 1020, debt = 29580), changes, day)
        assertEquals(0, view.paid); assertEquals(30600L, view.debt)
    }
    @Test fun aReceiptKeepsItsFirstAcknowledgementTimeAcrossReadRetries() {
        val queue = PaymentOutbox(MemorySheetCache(), "account", book)
        queue.stage(row, false, 0, day, day)
        val flight = queue.begin("actor", true)!!
        queue.acknowledge(flight, 1000); queue.acknowledge(flight, 3000)
        assertEquals(1000L, queue.read().flight!!.acknowledgedAt)
        assertEquals(0L, localReceiptDelta(financialPaymentChanges(queue.read(), 2000, day)))
    }

}

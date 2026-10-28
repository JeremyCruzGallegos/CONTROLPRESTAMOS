package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today

import com.example.controlprestamos.domain.PaymentEdit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

internal typealias PaymentChanges = Map<String, Pair<SheetPayment, PaymentEdit>>
internal data class PaymentFlight(val pending: PendingRemoteWrite, val changes: PaymentChanges, val acknowledgedAt: Long? = null)
internal data class PaymentQueue(val changes: PaymentChanges = emptyMap(), val flight: PaymentFlight? = null,
    val conflict: String? = null)

/** One atomic record contains both the desired local state and an immutable, retryable request.
 * A new tap can replace the desired state while the previous request is on the network. */
internal class PaymentOutbox(private val cache: SheetCache, val subject: String, val book: String) {
    fun read(): PaymentQueue = synchronized(lock) {
        val obj = cache.read() ?: return PaymentQueue()
        require(obj.getString("subject") == subject && obj.getString("book") == book)
        val changes = decodeDraft(obj.getJSONObject("desired"), subject, book)
        val flight = obj.optJSONObject("flight")?.let {
            PaymentFlight(PendingRemoteWrite(it.getString("id"), subject, book, it.getString("payload")),
                decodeDraft(it.getJSONObject("changes"), subject, book), it.optLong("acknowledgedAt").takeIf { value -> value > 0 })
        }
        PaymentQueue(changes, flight, obj.optString("conflict").takeIf { it.isNotBlank() })
    }
    private fun write(queue: PaymentQueue) {
        cache.write(JSONObject().put("subject", subject).put("book", book)
            .put("desired", draftPacket(subject, book, queue.changes)).put("conflict", queue.conflict ?: "")
            .apply { queue.flight?.let { put("flight", JSONObject().put("id", it.pending.operationId)
                .put("payload", it.pending.payload).put("changes", draftPacket(subject, book, it.changes)).put("acknowledgedAt", it.acknowledgedAt ?: 0)) } })
    }
    private fun staged(queue: PaymentQueue, row: SheetPayment, unpaid: Boolean, late: Long, paidOn: LocalDate?, day: LocalDate): PaymentQueue {
        val original = queue.changes[row.id]?.first ?: row
        val date = if (unpaid) null else paidOn ?: original.paidOn ?: day
        validateRemotePaymentEdit(original, unpaid, late, day, date)
        val changes = if (row.id in queue.flight?.changes.orEmpty()) queue.changes +
            (row.id to (original to PaymentEdit(original.loan.id, original.id, 0, unpaid, late, date)))
        else stagePaymentDraft(queue.changes, row, unpaid, late, date, day)
        return queue.copy(changes = changes)
    }
    fun stage(row: SheetPayment, unpaid: Boolean, late: Long, paidOn: LocalDate?, day: LocalDate) = synchronized(lock) {
        write(staged(read(), row, unpaid, late, paidOn, day))
    }
    fun stageAll(rows: List<SheetPayment>, unpaid: Boolean, paidOn: LocalDate?, day: LocalDate) = synchronized(lock) {
        val queue = read()
        val changes = stageBulkPayments(queue.changes, rows, unpaid, day, paidOn)
        val updated = rows.filter { it.due <= day }.fold(queue) { current, row ->
            val edit = changes[row.id]?.second
            staged(current, row, edit?.unpaid ?: row.unpaid, edit?.collectedLate ?: if (row.unpaid) 0 else row.late, edit?.paidOn ?: row.paidOn, day)
        }
        write(updated)
    }
    fun importDraft(changes: PaymentChanges) = synchronized(lock) {
        val queue = read()
        write(queue.copy(changes = changes + queue.changes))
    }
    fun begin(actor: String, direct: Boolean): PaymentFlight? = synchronized(lock) {
        val queue = read()
        if (queue.conflict != null) return null
        queue.flight?.let { return it }
        val changes = queue.changes.entries.take(100).associate { it.toPair() }
        if (changes.isEmpty()) return null
        val edits = changes.values.map { (row, edit) ->
            validateRemotePaymentEdit(row, edit.unpaid, edit.collectedLate, today(), edit.paidOn)
            JSONObject().put("version", JSONObject(requireNotNull(row.version)))
                .put("unpaid", edit.unpaid).put("collectedLate", edit.collectedLate / 100.0)
                .put("paidOn", edit.paidOn?.toString() ?: "")
                .put("confirmHistoricalDate", !edit.unpaid && !row.unpaid && row.paidOn == null && edit.paidOn != null)
        }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("bookId", book).put("operationId", id).put("action", "pay").put("edits", JSONArray(edits)).put("actor", actor)
        if (direct) body.put("transport", "sheets")
        val flight = PaymentFlight(PendingRemoteWrite(id, subject, book, body.toString()), changes)
        write(queue.copy(flight = flight))
        flight
    }
    fun acknowledge(flight: PaymentFlight, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        val queue = read()
        val active = requireNotNull(queue.flight)
        check(active.pending == flight.pending)
        if (active.acknowledgedAt == null) write(queue.copy(flight = active.copy(acknowledgedAt = at)))
    }
    fun confirmed(flight: PaymentFlight, fresh: List<SheetPayment>) = synchronized(lock) {
        val queue = read()
        check(queue.flight?.pending == flight.pending)
        val rows = fresh.associateBy { it.id }
        var remaining = queue.changes
        flight.changes.forEach { (id, sent) ->
            val desired = remaining[id] ?: return@forEach
            if (desired.second == sent.second) remaining = remaining - id
            else {
                val row = requireNotNull(rows[id]) { "Falta actualizar la cuota confirmada" }
                remaining = stagePaymentDraft(remaining - id, row, desired.second.unpaid,
                    desired.second.collectedLate, desired.second.paidOn, today())
            }
        }
        write(queue.copy(changes = rebasePaymentDrafts(remaining, fresh), flight = null, conflict = null))
    }
    fun reject(flight: PaymentFlight, message: String) = synchronized(lock) {
        val queue = read()
        if (queue.flight?.pending == flight.pending) write(queue.copy(conflict = message))
    }
    /** Only a definitively rejected batch can be discarded; an unknown network result cannot. */
    fun discardRejected() = synchronized(lock) {
        val queue = read()
        require(queue.conflict != null)
        write(queue.copy(changes = queue.changes - queue.flight?.changes.orEmpty().keys, flight = null, conflict = null))
    }
    companion object { private val lock = Any() }
}

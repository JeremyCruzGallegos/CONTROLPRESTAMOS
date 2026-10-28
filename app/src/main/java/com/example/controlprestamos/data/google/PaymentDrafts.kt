package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.PaymentEdit
import com.example.controlprestamos.domain.calculatedLate
import java.time.LocalDate

internal fun remainingPaymentDrafts(edits: Map<String, Pair<SheetPayment, PaymentEdit>>, confirmedIds: Set<String>) =
    edits.filterKeys { it !in confirmedIds }

/** A server acknowledgement updates visible amounts immediately, even if the following refresh fails. */
internal fun confirmedPaymentRows(rows: List<SheetPayment>, confirmed: Map<String, Pair<SheetPayment, PaymentEdit>>,
    day: LocalDate): List<SheetPayment> = rows.map { row ->
    val submitted = confirmed[row.id] ?: return@map row
    val edit = submitted.second
    row.copy(unpaid = edit.unpaid, late = if (edit.unpaid) row.loan.lateAt(row.due, day) else edit.collectedLate,
        paidOn = if (edit.unpaid) null else edit.paidOn ?: submitted.first.paidOn ?: day,
        version = null, bookAmount = null)
}

/** Use the original row throughout a draft, even if a query refreshes underneath the editor. */
internal fun stagePaymentDraft(edits: Map<String, Pair<SheetPayment, PaymentEdit>>, row: SheetPayment,
    unpaid: Boolean, late: Long, paidOn: LocalDate?, day: LocalDate): Map<String, Pair<SheetPayment, PaymentEdit>> {
    val original = edits[row.id]?.first ?: row
    val date = if (unpaid) null else paidOn ?: original.paidOn ?: if (original.unpaid) day else null
    val same = unpaid == original.unpaid && late == (if (original.unpaid) 0L else original.late) &&
        (unpaid || date == original.paidOn)
    if (same) return edits - row.id
    validateRemotePaymentEdit(original, unpaid, late, day, date)
    return edits + (row.id to (original to PaymentEdit(original.loan.id, original.id, 0, unpaid, late, date)))
}

/** A bulk action affects due installments only. Existing paid dates and collected fees are retained. */
internal fun stageBulkPayments(edits: Map<String, Pair<SheetPayment, PaymentEdit>>, rows: List<SheetPayment>,
    unpaid: Boolean, day: LocalDate, paidOn: LocalDate? = null): Map<String, Pair<SheetPayment, PaymentEdit>> {
    val eligible = rows.distinctBy { it.id }.filter { it.due <= day }
    require(eligible.isNotEmpty()) { "No hay cuotas vencidas para cambiar" }
    require(eligible.all { !it.version.isNullOrBlank() }) { "Actualiza las cuotas antes de cambiar todos los días" }
    // Validate the whole selection before exposing any changes.
    return eligible.fold(edits) { draft, row ->
        val original = draft[row.id]?.first ?: row
        val fee = if (unpaid || original.unpaid) 0 else original.late
        val date = if (unpaid) null else if (!original.unpaid) original.paidOn else paidOn ?: original.due
        stagePaymentDraft(draft, original, unpaid, fee, date, day)
    }
}

/** A simple-book payment updates the entire row revision. Rebase untouched drafts only when the
 * current financial terms and original payment values still match; conflicts keep their old version. */
internal fun rebasePaymentDrafts(edits: Map<String, Pair<SheetPayment, PaymentEdit>>, rows: List<SheetPayment>) :
    Map<String, Pair<SheetPayment, PaymentEdit>> {
    val current = rows.associateBy { it.id }
    return edits.mapValues { (id, pair) ->
        val old = pair.first
        val fresh = current[id]
        if (fresh != null && paymentTerms(old.loan.copy(termsRevision = "")) == paymentTerms(fresh.loan.copy(termsRevision = "")) &&
            old.unpaid == fresh.unpaid && old.paidOn == fresh.paidOn && (old.unpaid || old.late == fresh.late)) fresh to pair.second else pair
    }
}

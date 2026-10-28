package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.calculatedLate
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

internal data class DirectPaymentVersion(val bookId: String, val loanId: String, val year: Int,
    val due: LocalDate, val terms: String, val unpaid: Boolean, val collectedLate: Long, val paidOn: LocalDate?, val revision: String = "")

internal data class DirectPaymentSnapshot(val loan: SheetLoan, val due: LocalDate, val valid: Boolean,
    val unpaid: Boolean, val collectedLate: Long, val paidOn: LocalDate?,
    val checkFormula: Boolean = false, val feeFormula: Boolean = false, val revision: String = "")

internal data class DirectPaymentChange(val before: DirectPaymentVersion, val unpaid: Boolean,
    val collectedLate: Long, val paidOn: LocalDate?)

internal fun paymentTerms(loan: SheetLoan): String = listOf(loan.id, loan.year, loan.principal,
    loan.ratePercent.stripTrailingZeros().toPlainString(), loan.months, loan.agreed, loan.daily, loan.first,
    loan.firstDue, loan.lastDue, loan.dailyLate, loan.termsRevision, directPaymentHash(loan.scheduledBases.toSortedMap().toString()),
    loan.advances.map { listOf(it.source,it.target,it.amount,it.sourcePaid) }).joinToString("|")

internal fun directPaymentVersion(book: String, row: SheetPayment, storedLate: Long, revision: String = "") = DirectPaymentVersion(
    book, row.loan.id, row.loan.year, row.due, paymentTerms(row.loan), row.unpaid, storedLate, row.paidOn, revision)

/** Recheck the entire batch before writing any cell. Paid is the sheet's unchecked/blank default. */
internal fun validateDirectPaymentChange(book: String, change: DirectPaymentChange,
    current: DirectPaymentSnapshot, day: LocalDate) {
    val before = change.before
    require(before.bookId == book && before.loanId == current.loan.id && before.year == current.loan.year &&
        before.due == current.due) { "La cuota pertenece a otro libro o préstamo" }
    require(before.terms == paymentTerms(current.loan)) { "El préstamo cambió. Actualiza y revisa el pago" }
    require(current.valid && current.due <= day && current.due in current.loan.firstDue..current.loan.lastDue) {
        "La cuota es futura o ya no es válida para este préstamo"
    }
    require(!current.checkFormula && !current.feeFormula) { "El pago o la mora contiene una fórmula y no se puede sobrescribir" }
    require(current.unpaid == before.unpaid && current.collectedLate == before.collectedLate && current.paidOn == before.paidOn && current.revision == before.revision) {
        "Otra persona cambió este pago o su mora. Actualiza y revisa antes de guardar"
    }
    require(change.collectedLate >= 0)
    if (change.unpaid) {
        require(change.collectedLate == 0L && change.paidOn == null) { "Una cuota impaga no tiene cobro ni mora cobrada" }
    } else {
        val date = requireNotNull(change.paidOn) { "Indica la fecha real de cobro" }
        require(date in current.due..day) { "La fecha real de cobro debe estar entre el vencimiento y hoy" }
        require(before.paidOn == null || date == before.paidOn) { "El ajuste debe conservar la fecha real de cobro" }
        require(change.collectedLate <= maxOf(current.loan.lateAt(current.due, date), if (before.unpaid || current.loan.termsRevision.isBlank()) 0 else before.collectedLate)) { "La mora supera el máximo a la fecha de cobro" }
    }
}

internal fun DirectPaymentVersion.toJson(): JSONObject = JSONObject().put("transport", "sheets")
    .put("bookId", bookId).put("loanId", loanId).put("year", year).put("due", due.toString())
    .put("terms", terms).put("unpaid", unpaid).put("collectedLate", collectedLate).put("paidOn", paidOn?.toString() ?: "").put("revision", revision)

internal fun directPaymentVersion(value: JSONObject): DirectPaymentVersion {
    require(value.getString("transport") == "sheets") { "Actualiza estas cuotas antes de guardar" }
    return DirectPaymentVersion(value.getString("bookId"), value.getString("loanId"), value.getInt("year"),
        LocalDate.parse(value.getString("due")), value.getString("terms"), value.getBoolean("unpaid"),
        value.getLong("collectedLate"), value.optString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse), value.optString("revision"))
}

internal fun paymentRevisionKey(version: DirectPaymentVersion): String = "cp_rev_" + directPaymentHash(
    listOf(version.bookId, version.loanId, version.year, version.due, version.terms, version.unpaid,
        version.collectedLate, version.paidOn, version.revision).joinToString("|")).take(40)

internal class DirectPaymentRejected(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun directPaymentHash(payload: String): String = MessageDigest.getInstance("SHA-256")
    .digest(("direct-sheets|" + payload).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Read the durable receipt before validating old cell values: an applied retry must never write twice. */
internal fun directPaymentReceipt(rows: List<List<Any?>>, operationId: String, hash: String): String? {
    val matches = rows.filter { it.getOrNull(0) == operationId }
    require(matches.size <= 1) { "La operación aparece repetida en el diario. Revisa la hoja" }
    val row = matches.singleOrNull() ?: return null
    require(row.getOrNull(1) == hash) { "La identidad de la operación ya se usó con otros datos" }
    return row.getOrNull(3)?.toString()?.takeIf { it.isNotBlank() }
        ?: error("La operación no tiene un comprobante válido en el diario")
}

internal val PAYMENT_HISTORY_LABELS = listOf("REGISTRADO", "LOTE", "AÑO", "ID", "FECHA CUOTA", "NO PAGÓ ANTES",
    "NO PAGÓ DESPUÉS", "MORA ANTES", "MORA DESPUÉS", "FECHA PAGO", "CLIENTE", "USUARIO")

package com.example.controlprestamos.data.google

import java.time.LocalDate
import com.example.controlprestamos.domain.calculatedLate

/** Used while preparing the draft and again before sending it. Never invent a historical payment date. */
fun validateRemotePaymentEdit(row: SheetPayment, unpaid: Boolean, collectedLate: Long, day: LocalDate,
    paidOn: LocalDate? = row.paidOn ?: if (row.unpaid) day else null) {
    require(!row.version.isNullOrBlank()) { "Actualiza las cuotas desde el servicio de guardado antes de editarlas" }
    require(row.due <= day && row.due in row.loan.firstDue..row.loan.lastDue) { "No se puede editar una cuota futura o fuera del préstamo" }
    require(!unpaid || collectedLate == 0L) { "Una cuota impaga no tiene mora cobrada" }
    require(row.paidOn == null || row.paidOn in row.due..day) { "La fecha registrada del pago no es válida" }
    if (!unpaid) {
        require(paidOn != null) { "Ingresa la fecha real de cobro antes de ajustar la mora de este pago" }
        require(paidOn in row.due..day) { "La fecha real de cobro debe estar entre el vencimiento y hoy" }
        require(row.paidOn == null || row.paidOn == paidOn) { "El ajuste debe conservar la fecha real de cobro registrada" }
        require(collectedLate in 0..maxOf(row.loan.lateAt(row.due, paidOn), if (row.unpaid || row.loan.termsRevision.isBlank()) 0 else row.late)) { "Ingresa una mora entre 0 y el máximo a la fecha de cobro" }
    }
}

package com.example.controlprestamos.data.google

import java.time.LocalDate

internal fun localReceiptDelta(changes: PaymentChanges): Long = changes.values.sumOf { (row, edit) ->
    (if (edit.unpaid) 0 else row.base + edit.collectedLate) - (if (row.unpaid) 0 else row.base + row.late)
}

/** The original server row stays intact for revision validation; only its presentation is adjusted. */
internal fun localLoanView(loan: SheetLoan, changes: PaymentChanges, day: LocalDate): SheetLoan {
    val relevant = changes.filterValues { it.first.loan.id == loan.id }
    var paidDelta = 0
    var debtDelta = 0L
    relevant.values.forEach { (row, edit) ->
        paidDelta += (if (edit.unpaid) 0 else 1) - (if (row.unpaid) 0 else 1)
        val pending = row.base + row.loan.lateAt(row.due, day)
        debtDelta += (if (edit.unpaid) pending else 0) - (if (row.unpaid) pending else 0)
    }
    return loan.copy(paid = (loan.paid + paidDelta).coerceIn(0, loan.total),
        debt = (loan.debt + debtDelta).coerceAtLeast(0), collected = loan.collected + localReceiptDelta(relevant))
}

/** A receipt may precede a slow calendar refresh. Once the monetary snapshot includes it, only the
 * difference from that acknowledged state is provisional; counting the original edit again doubles cash. */
internal fun financialPaymentChanges(queue: PaymentQueue, syncedAtMillis: Long, day: LocalDate): PaymentChanges {
    val flight = queue.flight ?: return queue.changes
    val acknowledged = flight.acknowledgedAt ?: return queue.changes
    if (syncedAtMillis < acknowledged) return queue.changes
    return queue.changes.mapValues { (id, desired) ->
        val sent = flight.changes[id] ?: return@mapValues desired
        val row = confirmedPaymentRows(listOf(sent.first), mapOf(id to sent), day).single()
        row to desired.second
    }
}

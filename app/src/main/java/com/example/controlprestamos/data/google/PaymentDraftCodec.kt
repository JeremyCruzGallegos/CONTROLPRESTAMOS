package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal fun draftPacket(subject: String, book: String, edits: Map<String, Pair<SheetPayment, PaymentEdit>>): JSONObject =
        JSONObject().put("schema", 1).put("subject", subject).put("book", book).put("edits", JSONArray(edits.values.map { (payment, edit) ->
            val loan = payment.loan
            val values = listOf(loan.rawId, loan.client, "${loan.paid}/${loan.total}", loan.principal / 100.0,
                loan.ratePercent.movePointLeft(2), loan.months, loan.guarantee, loan.agreed / 100.0,
                loan.collected / 100.0, loan.debt / 100.0, loan.realDailyInterest, loan.dailyInterest?.let { it / 100.0 },
                loan.daily / 100.0, loan.first / 100.0, loan.control, sheetSerial(loan.firstDue), sheetSerial(loan.lastDue))
            JSONObject().put("year", loan.year).put("row", loan.row).put("loan", JSONArray(values))
                .apply { if (loan.scheduledBases.isNotEmpty()) put("conditions", LoanConditions(loan.dailyLate, loan.scheduledBases, loan.termsRevision,loan.advances).json()) }
                .put("due", payment.due.toString()).put("base", payment.base).put("unpaid", payment.unpaid)
                .put("late", payment.late).put("paidOn", payment.paidOn?.toString() ?: "").put("version", payment.version ?: "")
                .put("bookAmount", payment.bookAmount ?: JSONObject.NULL)
                .put("newUnpaid", edit.unpaid).put("newLate", edit.collectedLate).put("newPaidOn", edit.paidOn?.toString() ?: "")
        }))
internal fun decodeDraft(packet: JSONObject, subject: String, book: String): Map<String, Pair<SheetPayment, PaymentEdit>> {
        require(packet.getInt("schema") == 1 && packet.getString("subject") == subject && packet.getString("book") == book) { "Borrador de otra cuenta o libro" }
        val values = packet.getJSONArray("edits")
        return (0 until values.length()).map { index ->
            val obj = values.getJSONObject(index); val raw = obj.getJSONArray("loan")
            val row = List(raw.length()) { raw.opt(it).takeUnless { cell -> cell == JSONObject.NULL } }
            val loan = parseSheetLoans(obj.getInt("year"), listOf(row), obj.getInt("row")).single()
                .withConditions(obj.optJSONObject("conditions")?.let(::loanConditions))
            val payment = SheetPayment(loan, LocalDate.parse(obj.getString("due")), obj.getLong("base"), obj.getBoolean("unpaid"), obj.getLong("late"),
                obj.getString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse), obj.optString("version").takeIf { it.isNotBlank() },
                obj.opt("bookAmount").takeUnless { it == null || it == JSONObject.NULL }?.let { (it as Number).toLong() })
            payment.id to (payment to PaymentEdit(loan.id, payment.id, 0, obj.getBoolean("newUnpaid"), obj.getLong("newLate"),
                obj.optString("newPaidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse)))
        }.also { require(it.map { row -> row.first }.distinct().size == it.size) }.toMap()
    }

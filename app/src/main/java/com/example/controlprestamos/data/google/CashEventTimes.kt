package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.LIMA
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal fun cashTimestamp(value: Any?): Instant? = value?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { text ->
    runCatching { Instant.parse(text) }.getOrNull() ?: runCatching {
        LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(LIMA).toInstant()
    }.getOrNull() ?: runCatching {
        // Sheets returns formatted timestamps as serial day numbers when using UNFORMATTED_VALUE.
        val serial = java.math.BigDecimal(text)
        require(serial >= java.math.BigDecimal(40_000) && serial < java.math.BigDecimal(100_000))
        val days = serial.toLong()
        val nanos = serial.subtract(java.math.BigDecimal(days)).multiply(java.math.BigDecimal(86_400_000_000_000L))
            .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
        java.time.LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay().plusNanos(nanos).atZone(LIMA).toInstant()
    }.getOrNull()
}
internal fun cashRecordedAt(body: JSONObject): String = cashTimestamp(body.optString("recordedAt"))?.toString() ?: Instant.now().toString()
internal fun decodeCashTimes(value: JSONObject?): Map<String, Instant> = value?.keys()?.asSequence()?.mapNotNull { key ->
    cashTimestamp(value.optString(key))?.let { key to it }
}?.toMap().orEmpty()
internal fun encodeCashTimes(values: Map<String, Instant>) = JSONObject(values.mapValues { it.value.toString() })

internal fun receiptCashTimes(receipt: JSONObject, registered: Any?): Map<String, Instant> {
    val explicit = decodeCashTimes(receipt.optJSONObject("cashEventTimes"))
    val stamp = cashTimestamp(registered) ?: return explicit
    val times = mutableMapOf<String, Instant>()
    receipt.optJSONObject("cashMovement")?.optString("id")?.takeIf { it.isNotBlank() }?.let { times[it] = stamp }
    // The contribution field distinguishes loan creation receipts from later term edits.
    if (receipt.has("contributionCents")) receipt.optString("loanId").takeIf { it.isNotBlank() }?.let {
        times["loan:$it"] = stamp; times["fund:$it"] = stamp
    }
    receipt.optJSONArray("history")?.rowValues()?.forEach { row ->
        if (row.getOrNull(6) == false) historyLoanId(row)?.let { id ->
            val due = runCatching { sheetDate(row.getOrNull(4)) }.getOrNull()
            if (due != null) times["pay:$id:$due"] = cashTimestamp(row.firstOrNull()) ?: stamp
        }
    }
    return times + explicit
}

internal fun localCashTimes(before: LocalBookView, after: LocalBookView, body: JSONObject): Map<String, Instant> {
    val stamp = cashTimestamp(body.optString("recordedAt")) ?: return before.cashEventTimes
    val times = before.cashEventTimes.toMutableMap()
    if (body.optString("action") == "cashMovement") times[body.getString("operationId")] = stamp
    val priorLoans = before.catalog.map { it.id }.toSet()
    after.catalog.filter { it.id !in priorLoans }.forEach { times["loan:${it.id}"] = stamp; times["fund:${it.id}"] = stamp }
    val prior = before.rows.associateBy { it.id }
    after.rows.filter { !it.unpaid }.forEach { row ->
        val old = prior[row.id]
        if (old == null || old.unpaid || old.late != row.late || old.paidOn != row.paidOn) times["pay:${row.id}"] = stamp
    }
    return times
}

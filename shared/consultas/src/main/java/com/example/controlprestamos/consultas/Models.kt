package com.example.controlprestamos.consultas

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

internal fun token() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
internal fun challenge(verifier: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
internal data class LoginAttempt(val verifier: String, val state: String, val startedAt: Long) {
    fun matches(returnedState: String, now: Long): Boolean = now >= startedAt && now - startedAt <= 600_000 &&
        returnedState.length == state.length && MessageDigest.isEqual(returnedState.toByteArray(), state.toByteArray())
    fun json() = JSONObject().put("verifier", verifier).put("state", state).put("startedAt", startedAt).toString()
    companion object {
        fun create(now: Long = System.currentTimeMillis()) = LoginAttempt(token(), token(), now)
        fun decode(value: String) = JSONObject(value).let { LoginAttempt(it.getString("verifier"), it.getString("state"), it.getLong("startedAt")) }
    }
}
internal data class Profile(val sub: String, val email: String, val displayName: String, val requestCode: String,
    val administrator: Boolean, val authorized: Boolean, val supportEmail: String, val customerCode: String = "", val customerName: String = "") {
    companion object {
        fun decode(value: JSONObject) = Profile(value.getString("sub"), value.getString("email"), value.getString("displayName"),
            value.getString("requestCode"), value.getBoolean("administrator"), value.getBoolean("authorized"), value.getString("supportEmail"), value.optString("customerCode"), value.optString("customerName"))
    }
}
internal enum class DueStatus(val label: String) {
    PAID("Pagada"), REVIEW("Fecha por confirmar"), OVERDUE("Vencida"), PENDING("Pendiente"), FUTURE("Próxima")
}
internal data class Due(val number: Int, val due: LocalDate, val base: Long, val late: Long, val status: DueStatus,
    val paidOn: LocalDate?, val paid: Long, val recordedLate: Long = 0) {
    val settled get() = status == DueStatus.PAID || status == DueStatus.REVIEW
    companion object {
        fun decode(value: JSONObject) = Due(value.getInt("number"), LocalDate.parse(value.getString("due")), value.getLong("baseCents"),
            value.getLong("lateCents"), DueStatus.valueOf(value.getString("status")), value.optString("paidOn").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse), value.getLong("paidCents"), value.optLong("recordedLateCents"))
    }
}
internal data class ConsultationLoan(val id: String, val clientName: String, val principal: Long, val agreed: Long, val balance: Long,
    val collected: Long, val overdue: Long, val pendingLate: Long, val rate: Double, val months: Int, val paidCount: Int,
    val totalCount: Int, val disbursed: LocalDate, val completed: Boolean, val needsReview: Boolean, val dues: List<Due>, val dailyLate: Long = 0) {
    companion object {
        fun decode(value: JSONObject) = ConsultationLoan(value.getString("id"), value.getString("clientName"), value.getLong("principalCents"),
            value.getLong("agreedCents"), value.getLong("balanceCents"), value.getLong("collectedCents"), value.getLong("overdueCents"), value.getLong("pendingLateCents"),
            value.getDouble("ratePercent"), value.getInt("months"), value.getInt("paidCount"), value.getInt("totalCount"), LocalDate.parse(value.getString("disbursedOn")),
            value.getBoolean("completed"), value.getBoolean("needsReview"), value.getJSONArray("installments").objects().map(Due::decode), value.optLong("dailyLateCents"))
    }
}
internal data class AccountAccess(val sub: String, val email: String, val name: String, val requestCode: String, val blocked: Boolean, val loanIds: Set<String>) {
    companion object {
        fun decode(value: JSONObject) = AccountAccess(value.getString("sub"), value.getString("email"), value.getString("displayName"), value.getString("requestCode"),
            value.getBoolean("blocked"), value.getJSONArray("loanIds").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
    }
}
internal data class CatalogLoan(val id: String, val client: String)
internal fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
internal fun money(cents: Long): String = "S/ " + NumberFormat.getNumberInstance(Locale.forLanguageTag("es-PE")).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(BigDecimal.valueOf(cents, 2))
internal fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
internal fun updateText(instant: Instant): String = instant.atZone(ZoneId.of("America/Lima")).format(DateTimeFormatter.ofPattern("dd/MM · HH:mm"))

internal fun exampleLoans(): List<ConsultationLoan> {
    val today = LocalDate.now(ZoneId.of("America/Lima")); val first = today.minusDays(9)
    val dues = (1..30).map { number ->
        val due = first.plusDays(number - 1L)
        val status = when { number <= 7 -> DueStatus.PAID; due < today -> DueStatus.OVERDUE; due == today -> DueStatus.PENDING; else -> DueStatus.FUTURE }
        Due(number, due, 1020, if (status == DueStatus.OVERDUE) java.time.temporal.ChronoUnit.DAYS.between(due, today) * 100 else 0,
            status, if (status == DueStatus.PAID) due else null, if (status == DueStatus.PAID) 1020 else 0)
    }
    return listOf(ConsultationLoan("2026-001", "Cliente de ejemplo", 30000, 30600, dues.filterNot { it.settled }.sumOf { it.base + it.late },
        7140, dues.filter { it.status == DueStatus.OVERDUE }.sumOf { it.base + it.late }, dues.sumOf { it.late }, 2.0, 1, 7, 30,
        first.minusDays(1), false, false, dues))
}

internal data class CustomerRequest(val sub: String, val email: String, val name: String)
internal data class CustomerAccess(val code: String, val name: String, val loanIds: Set<String>, val enabled: Boolean,
    val linkedSub: String?, val email: String, val requests: List<CustomerRequest>) {
    val shareMessage get() = "El codigo de cliente es:\n$code"
    companion object {
        fun decode(value: JSONObject) = CustomerAccess(value.getString("code"), value.getString("name"),
            value.getJSONArray("loanIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }, value.getBoolean("enabled"),
            value.optString("linkedSub").takeUnless { it.isBlank() || it == "null" }, value.optString("email"),
            value.optJSONArray("requests")?.objects()?.map { CustomerRequest(it.getString("sub"),it.getString("email"),it.getString("name")) } ?: emptyList())
    }
}
internal data class AccessConfirmation(val id: String, val purpose: String)

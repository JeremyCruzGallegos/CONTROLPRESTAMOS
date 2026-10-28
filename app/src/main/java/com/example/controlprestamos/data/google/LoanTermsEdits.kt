package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

internal data class LoanConditions(val dailyLate: Long = 100, val bases: Map<LocalDate, Long> = emptyMap(), val revision: String = "",
    val advances: List<FeeAdvance> = emptyList()) {
    fun json(): JSONObject = JSONObject().put("dailyLate", dailyLate).put("revision", revision)
        .put("bases", JSONObject(bases.mapKeys { it.key.toString() })).put("advances",org.json.JSONArray(advances.map { it.json() }))
}
internal fun loanConditions(value: JSONObject): LoanConditions {
    val bases = value.getJSONObject("bases")
    return LoanConditions(value.getLong("dailyLate"), bases.keys().asSequence().associate { LocalDate.parse(it) to bases.getLong(it) }, value.getString("revision"),feeAdvances(value.optJSONArray("advances")))
        .also { config->require(config.dailyLate in 0..1_000_000 && config.bases.values.all { base -> base > 0 } &&
            config.advances.all { it.source in config.bases && it.target in config.bases && it.amount in 1..10_000_000_000L }) }
}
internal fun SheetLoan.withConditions(value: LoanConditions?): SheetLoan = if (value == null) this else {
    require(value.bases.keys == loanDates(this).toSet() && value.bases.values.sum() == agreed) {
        "Las condiciones de $id no coinciden con el libro. Revisa su última edición antes de continuar"
    }
    copy(dailyLate = value.dailyLate, scheduledBases = value.bases, termsRevision = value.revision,advances=value.advances)
}

internal data class LoanTermsInput(val client: String, val guarantee: String, val principal: Long,
    val rate: BigDecimal, val months: Int, val disbursedOn: LocalDate, val dailyLate: Long) {
    fun json(): JSONObject = JSONObject().put("client", client).put("guarantee", guarantee).put("principal", principal)
        .put("rate", rate.toPlainString()).put("months", months).put("disbursedOn", disbursedOn.toString()).put("dailyLate", dailyLate)
}
internal fun loanTermsInput(value: JSONObject) = LoanTermsInput(value.getString("client"), value.getString("guarantee"),
    value.getLong("principal"), BigDecimal(value.getString("rate")), value.getInt("months"),
    LocalDate.parse(value.getString("disbursedOn")), value.getLong("dailyLate"))

internal data class LoanTermsPlan(val input: LoanTermsInput, val agreed: Long, val bases: Map<LocalDate, Long>,
    val collectedBase: Long, val collectedLate: Long, val paidDates: Set<LocalDate>, val cashAfter: Long,
    val pendingLate: Long, val calendarVersion: String) {
    val firstDue: LocalDate get() = bases.keys.min()
    val lastDue: LocalDate get() = bases.keys.max()
    val remaining: Long get() = agreed - collectedBase
    val pendingCount: Int get() = bases.size - paidDates.size
    val nextBase: Long get() = bases.entries.firstOrNull { it.key !in paidDates }?.value ?: 0
    fun confirmation(): String = directPaymentHash(listOf(input.json().toString(), agreed, collectedBase, collectedLate,
        paidDates.sorted(), cashAfter, bases.toSortedMap(), calendarVersion).joinToString("|"))
}

internal fun planLoanTerms(old: SheetLoan, calendar: LoanCalendar, input: LoanTermsInput, day: LocalDate, cash: Long): LoanTermsPlan {
    require(calendar.loanId == old.id && calendar.days.map { it.date } == loanDates(old)) { "Carga el calendario completo antes de editar" }
    require(calendar.days.none { it.date <= day && it.payment == null }) { "Hay cuotas sin datos válidos. Actualiza el calendario antes de editar" }
    require(input.client.isNotBlank() && input.client == input.client.trim() && input.client.length <= 200 && input.client.none { it.isISOControl() }) { "Revisa el nombre del cliente" }
    require(input.guarantee.length <= 2000 && input.guarantee.none { it.isISOControl() && it != '\n' }) { "La garantía admite hasta 2000 caracteres" }
    require(input.dailyLate in 0..1_000_000) { "La mora diaria debe estar entre S/ 0 y S/ 10,000" }
    require(input.disbursedOn.year == old.id.substringBefore('-').toInt()) { "La fecha debe conservar el año de origen del préstamo" }
    val preview = previewDatedLoan(LoanRequest(input.client, input.principal, input.rate, input.months, input.guarantee, Funding.CAPITAL, 0), input.disbursedOn, day)
    val dates = preview.installments.map { it.due }
    val paid = calendar.days.mapNotNull { it.payment }.filterNot { it.unpaid }.associateBy { it.due }
    require(dates.containsAll(paid.keys)) { "La nueva fecha o plazo deja pagos cobrados fuera del calendario. Conserva esas fechas o amplía el plazo" }
    val collected = paid.values.sumOf { it.base }
    require(preview.agreed >= collected) { "El nuevo total es menor que lo ya cobrado. Revisa monto, tasa y plazo" }
    val pending = dates.filter { it !in paid }
    val balance = preview.agreed - collected
    require(pending.isNotEmpty() || balance == 0L) { "Queda saldo por repartir. Amplía el plazo para agregar cuotas pendientes" }
    require(pending.isEmpty() || balance >= pending.size) { "El saldo no alcanza para las cuotas pendientes; reduce el plazo" }
    val reschedule = input.principal != old.principal || input.rate.compareTo(old.ratePercent) != 0 ||
        input.months != old.months || input.disbursedOn != old.firstDue.minusDays(1)
    val regular = if (pending.isEmpty()) 0 else balance / pending.size
    val remainder = if (pending.isEmpty()) 0 else (balance % pending.size).toInt()
    val redistributed = pending.mapIndexed { index, due -> due to regular + if (index < remainder) 1 else 0 }.toMap()
    val bases = dates.associateWith { due -> paid[due]?.base ?: if (!reschedule) old.baseOn(due)
        else redistributed.getValue(due) }
    require(bases.values.sum() == preview.agreed) { "El reparto de cuotas no cuadra con el total pactado" }
    val cashAfter = Math.addExact(cash, Math.subtractExact(old.principal, input.principal))
    require(cashAfter >= 0) { "El aumento del préstamo dejaría la caja sin fondos" }
    return LoanTermsPlan(input, preview.agreed, bases, collected, paid.values.sumOf { it.late }, paid.keys, cashAfter,
        pending.sumOf { calculatedLate(it, day, input.dailyLate) },
        directPaymentHash(calendar.days.joinToString("|") { "${it.date}:${it.status}:${it.payment?.base}:${it.payment?.late}:${it.payment?.paidOn}:${it.payment?.version}" }))
}

internal fun loanTermsRevisionKey(book: String, loan: SheetLoan) = "cp_terms_" + directPaymentHash("$book|${loan.id}|${paymentTerms(loan)}|${loan.termsRevision}").take(40)
internal class LoanTermsRejected(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

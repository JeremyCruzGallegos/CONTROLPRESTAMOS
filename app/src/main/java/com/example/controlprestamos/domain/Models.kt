package com.example.controlprestamos.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val LIMA: ZoneId = ZoneId.of("America/Lima")
fun today(clock: Clock = Clock.system(LIMA)): LocalDate = LocalDate.now(clock.withZone(LIMA))
fun soles(cents: Long): String = "S/ " + NumberFormat.getNumberInstance(Locale.forLanguageTag("es-PE")).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(BigDecimal.valueOf(cents, 2))
fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
fun parseMoney(text: String): Long? = runCatching {
    require(text.trim().matches(Regex("\\d{1,9}([.,]\\d{1,2})?")))
    BigDecimal(text.trim().replace(',', '.')).movePointRight(2).longValueExact()
}.getOrNull()

fun calculatedLate(due: LocalDate, paidOn: LocalDate, dailyRate: Long = 100L): Long {
    require(dailyRate >= 0) { "La mora diaria no puede ser negativa" }
    return Math.multiplyExact(java.time.temporal.ChronoUnit.DAYS.between(due, paidOn).coerceAtLeast(0), dailyRate)
}

enum class Funding { CAPITAL, EXTERNAL }
data class LoanRequest(val client: String, val principal: Long, val ratePercent: BigDecimal,
    val months: Int, val guarantee: String, val funding: Funding, val contribution: Long)
data class Installment(val id: String, val due: LocalDate, val base: Long,
    val paidOn: LocalDate? = null, val collectedLate: Long = 0, val revision: Long = 0) {
    fun paid(asOf: LocalDate): Boolean = paidOn != null && paidOn <= asOf && due <= asOf
    fun late(asOf: LocalDate): Long = if (paid(asOf)) collectedLate else
        calculatedLate(due, asOf)
    fun maximumLate(asOf: LocalDate): Long = calculatedLate(due, if (paid(asOf)) paidOn!! else asOf)
}
data class Loan(val id: String, val client: String, val principal: Long,
    val ratePercent: BigDecimal, val months: Int, val guarantee: String,
    val disbursed: LocalDate, val funding: Funding = Funding.CAPITAL, val contribution: Long = 0,
    val installments: List<Installment>) {
    val agreed: Long get() = installments.sumOf { it.base }
    fun paidCount(asOf: LocalDate) = installments.count { it.paid(asOf) }
    fun active(asOf: LocalDate) = paidCount(asOf) < installments.size
    fun balance(asOf: LocalDate) = installments.filterNot { it.paid(asOf) }.sumOf { it.base + it.late(asOf) }
    fun belongsToYear(year: Int) = disbursed.year == year || installments.any { it.due.year == year }
    fun receipts(asOf: LocalDate) = installments.filter { it.paid(asOf) }.sumOf { it.base + it.collectedLate }
    fun lateReceipts(asOf: LocalDate) = installments.filter { it.paid(asOf) }.sumOf { it.collectedLate }
    // _CAPITAL allocates base receipts proportionally to agreed principal and interest.
    fun interest(asOf: LocalDate): Long = proportionalInterest(installments.filter { it.paid(asOf) }.sumOf { it.base })
    fun proportionalInterest(base: Long): Long = if (agreed == 0L) 0 else BigDecimal(base)
        .multiply(BigDecimal(agreed - principal)).divide(BigDecimal(agreed), 0, RoundingMode.HALF_UP).longValueExact()
    fun recovered(asOf: LocalDate) = installments.filter { it.paid(asOf) }.sumOf { it.base } - interest(asOf)
}
data class Summary(val cash: Long, val interest: Long, val recovered: Long,
    val pendingCapital: Long, val disbursed: Long, val contributions: Long, val late: Long)
data class Audit(val transactionId: String, val at: Instant, val description: String)
data class Snapshot(val loans: List<Loan>, val audits: List<Audit> = emptyList(),
    val transactions: Map<String, String> = emptyMap(), val refreshedAt: Instant? = null)
data class PaymentEdit(val loanId: String, val installmentId: String, val expectedRevision: Long,
    val unpaid: Boolean, val collectedLate: Long = 0, val paidOn: LocalDate? = null)

object Rules {
    const val INITIAL_CAPITAL = 700_000L
    /** Reuse available cash first; only the shortfall is fresh capital, never profit. */
    fun fundLoan(request: LoanRequest, cash: Long): LoanRequest {
        require(request.principal > 0 && request.contribution >= 0 &&
            (request.funding == Funding.EXTERNAL || request.contribution == 0L)) { "Monto o aporte inválido" }
        val needed = Math.subtractExact(request.principal, cash).coerceAtLeast(0)
        val contribution = maxOf(request.contribution, needed)
        return request.copy(funding = if (contribution > 0) Funding.EXTERNAL else request.funding,
            contribution = contribution)
    }
    fun cashAfter(cash: Long, principal: Long, contribution: Long): Long {
        require(principal > 0 && contribution >= 0) { "Monto o aporte inválido" }
        return Math.subtractExact(Math.addExact(cash, contribution), principal)
    }
    fun createLoan(id: String, request: LoanRequest, day: LocalDate): Loan {
        require(request.client.trim().isNotEmpty()) { "Ingresa el cliente" }
        require(request.principal in 1..100_000_000_00L) { "Monto inválido" }
        require(request.months in 1..24) { "El plazo debe ser de 1 a 24 meses" }
        require(request.ratePercent >= BigDecimal.ZERO && request.ratePercent <= BigDecimal("100")) { "Tasa inválida" }
        require(request.contribution >= 0 && (request.funding == Funding.EXTERNAL || request.contribution == 0L)) { "Revisa el origen del aporte" }
        val principal = BigDecimal.valueOf(request.principal, 2)
        val rate = request.ratePercent.movePointLeft(2)
        val days = request.months * 30
        val agreed = principal.multiply(BigDecimal.ONE + rate.multiply(BigDecimal(request.months)))
            .setScale(2, RoundingMode.HALF_UP)
        // Verified cells 2026 H6:N6. First installment absorbs the rounding difference.
        val dailyInterest = principal.multiply(rate).divide(BigDecimal(30), 12, RoundingMode.DOWN)
            .setScale(1, RoundingMode.DOWN)
        val regular = (principal.divide(BigDecimal(days), 12, RoundingMode.DOWN) + dailyInterest)
            .setScale(1, RoundingMode.DOWN).movePointRight(2).longValueExact()
        val first = agreed.movePointRight(2).longValueExact() - regular * (days - 1)
        require(regular > 0 && first > 0) { "El monto es demasiado pequeño para este plazo" }
        return Loan(id, request.client.trim(), request.principal, request.ratePercent, request.months,
            request.guarantee.trim(), day, request.funding, request.contribution,
            List(days) { index -> Installment("$id:${index + 1}", day.plusDays(index + 1L), if (index == 0) first else regular) })
    }
    fun summary(snapshot: Snapshot, asOf: LocalDate): Summary {
        val loans = snapshot.loans.filter { it.disbursed <= asOf }
        val disbursed = loans.sumOf { it.principal }
        val contribution = loans.sumOf { it.contribution }
        val recovered = loans.sumOf { it.recovered(asOf) }
        return Summary(INITIAL_CAPITAL + contribution - disbursed + loans.sumOf { it.receipts(asOf) },
            loans.sumOf { it.interest(asOf) }, recovered, disbursed - recovered, disbursed,
            contribution, loans.sumOf { it.lateReceipts(asOf) })
    }
}

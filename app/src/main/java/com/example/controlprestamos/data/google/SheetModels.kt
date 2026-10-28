package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate

private val SHEETS_EPOCH = LocalDate.of(1899, 12, 30)
fun sheetDate(value: Any?): LocalDate {
    require(value != null && value.toString().isNotBlank()) { "Fecha de Sheets ausente" }
    val text = value.toString()
    if (text.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return LocalDate.parse(text)
    val number = BigDecimal(value.toString())
    require(number.stripTrailingZeros().scale() <= 0) { "Fecha de Sheets inválida" }
    return SHEETS_EPOCH.plusDays(number.longValueExact())
}
fun sheetSerial(date: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(SHEETS_EPOCH, date)
/** Preserve spreadsheet precision until formatting to cents; floating formula noise is not rejected. */
fun sheetCents(value: Any?): Long {
    require(value != null && value.toString().isNotBlank()) { "Importe de Sheets ausente" }
    return BigDecimal(value.toString()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}
fun columnName(number: Int): String {
    require(number > 0)
    var value = number; var result = ""
    while (value > 0) { value--; result = ('A' + value % 26) + result; value /= 26 }
    return result
}
fun remoteCacheKey(subject: String, book: String): String {
    require(subject.isNotBlank() && book.matches(Regex("[A-Za-z0-9_-]{20,}")))
    return MessageDigest.getInstance("SHA-256").digest("$subject|$book".toByteArray()).joinToString("") { "%02x".format(it) }
}
data class SheetLoan(val id: String, val rawId: String, val year: Int, val row: Int, val client: String,
    val principal: Long, val ratePercent: BigDecimal, val months: Int, val guarantee: String,
    val agreed: Long, val collected: Long, val debt: Long, val daily: Long, val first: Long,
    val firstDue: LocalDate, val lastDue: LocalDate, val paid: Int, val total: Int,
    val realDailyInterest: BigDecimal? = null, val dailyInterest: Long? = null, val control: BigDecimal? = null,
    val dailyLate: Long = 100L, val scheduledBases: Map<LocalDate, Long> = emptyMap(), val termsRevision: String = "",
    val advances: List<FeeAdvance> = emptyList()) {
    fun baseOn(due: LocalDate): Long = scheduledBases[due] ?: if (due == firstDue) first else daily
    fun lateAt(due: LocalDate, day: LocalDate): Long = (calculatedLate(due, day, dailyLate) -
        advances.filter { it.target==due && it.sourcePaid }.sumOf { it.amount }).coerceAtLeast(0)
    val active: Boolean get() = paid < total
}
fun parseSheetLoans(year: Int, rows: List<List<Any?>>, firstRow: Int = 6): List<SheetLoan> = rows.mapIndexedNotNull { index, r ->
    fun cell(n: Int): Any? = r.getOrNull(n)
    val client = cell(1)?.toString()?.trim().orEmpty()
    if (client.isEmpty()) return@mapIndexedNotNull null
    val raw = cell(0)?.toString()?.let { if (it.endsWith(".0")) it.dropLast(2) else it } ?: error("Préstamo sin ID")
    val (paid, total) = SheetsIdentity.paidFraction(cell(2).toString())
    val firstDue = sheetDate(cell(15)); val lastDue = sheetDate(cell(16))
    require(lastDue >= firstDue) { "Fechas de préstamo inválidas" }
    val months = BigDecimal(cell(5).toString()).intValueExact()
    require(months > 0) { "Plazo inválido: $raw" }
    require(total == months * 30) { "Total de cuotas distinto del plazo: $raw" }
    SheetLoan(SheetsIdentity.canonical(year, raw), raw, year, firstRow + index, client, sheetCents(cell(3)),
        BigDecimal(cell(4).toString()).movePointRight(2), months, cell(6)?.toString().orEmpty(),
        sheetCents(cell(7)), sheetCents(cell(8)), sheetCents(cell(9)), sheetCents(cell(12)), sheetCents(cell(13)),
        firstDue, lastDue, paid, total,
        cell(10)?.takeIf { it.toString().isNotBlank() }?.let { BigDecimal(it.toString()) },
        cell(11)?.takeIf { it.toString().isNotBlank() }?.let(::sheetCents),
        cell(14)?.takeIf { it.toString().isNotBlank() }?.let { BigDecimal(it.toString()) })
}.also { loans -> require(loans.map { it.id }.distinct().size == loans.size) { "Identidades repetidas en la hoja anual" } }
data class SheetSummary(val year: Int, val cutoff: LocalDate, val initial: Long, val opening: Long,
    val contribution: Long, val disbursed: Long, val receipts: Long, val interest: Long,
    val late: Long, val recovered: Long, val cash: Long, val pendingCapital: Long,
    val assumedContribution: Long = 0) {
    val earnings: Long get() = interest + late
}
fun parseSheetSummary(year: Int, row: List<Any?>, initial: Any?): SheetSummary {
    require(BigDecimal(row.getOrNull(0).toString()).intValueExact() == year) { "Resumen de otro año" }
    fun money(i: Int) = sheetCents(row.getOrNull(i))
    require(kotlin.math.abs(money(12)) <= 1) { "El control de capital del libro no cuadra" }
    return SheetSummary(year, sheetDate(row.getOrNull(1)), sheetCents(initial), money(2), money(3), money(4),
        money(5), money(6), money(7), money(8), money(9), money(10))
}
data class SheetPayment(val loan: SheetLoan, val due: LocalDate, val base: Long, val unpaid: Boolean,
    val late: Long, val paidOn: LocalDate? = null, val version: String? = null, val bookAmount: Long? = null) {
    val id: String get() = "${loan.id}:$due"
    val installment: Int get() = java.time.temporal.ChronoUnit.DAYS.between(loan.firstDue, due).toInt() + 1
    fun maximumLate(day: LocalDate): Long = if (unpaid) loan.lateAt(due, day)
        else paidOn?.let { loan.lateAt(due, it) } ?: 0
}
fun parsePayment(loan: SheetLoan, date: LocalDate, cells: List<Any?>, fee: Any?, day: LocalDate): SheetPayment? {
    if (date > day || date !in loan.firstDue..loan.lastDue) return null
    val raw = cells.getOrNull(2)
    require(raw == true || raw == false || raw == null || raw == "") { "Checkbox inválido" }
    // Read the explicit validity control as well as the loan's contractual bounds.
    val valid = cells.getOrNull(0)
    if (valid == false || valid == "" || valid == null) return null
    require(valid == true) { "Control de cuota inválido" }
    val unpaid = raw == true
    val base = loan.baseOn(date)
    val late = if (unpaid) loan.lateAt(date, day)
        else if (fee == null || fee == "") 0 else sheetCents(fee)
    require(base >= 0 && late >= 0) { "Importe de cuota inválido" }
    return SheetPayment(loan, date, base, unpaid, late, bookAmount = cells.getOrNull(3)
        ?.takeIf { it != "" }?.let(::sheetCents))
}
data class BookCashDay(val date: LocalDate, val contribution: Long, val receipts: Long, val disbursed: Long, val cash: Long,
    val assumedContribution: Long = 0)
fun parseBookCash(rows: List<List<Any?>>): List<BookCashDay> = rows.mapNotNull { r ->
    if (r.getOrNull(0) == null || r[0] == "") null else BookCashDay(sheetDate(r[0]), sheetCents(r.getOrNull(1)),
        sheetCents(r.getOrNull(2)), sheetCents(r.getOrNull(3)), sheetCents(r.getOrNull(4)))
}
data class PendingRemoteWrite(val operationId: String, val subject: String, val bookId: String, val payload: String) {
    init { require(operationId.matches(Regex("[A-Za-z0-9_-]{8,80}"))); SheetsIdentity.requireBook(bookId); require(subject.isNotBlank() && payload.isNotBlank()) }
    fun requireOwner(subject: String, book: String) { require(this.subject == subject && bookId == book) { "Operación pendiente de otra cuenta o libro" } }
}

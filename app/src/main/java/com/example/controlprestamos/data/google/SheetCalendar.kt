package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.PaymentEdit
import com.example.controlprestamos.domain.calculatedLate
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class CalendarStatus { PAID, PENDING, FUTURE, INVALID, UNAVAILABLE }
data class SheetCalendarDay(val date: LocalDate, val installment: Int, val status: CalendarStatus,
    val checked: Boolean?, val valid: Boolean?, val bookAmount: Long?, val bookLate: Long?,
    val payment: SheetPayment?, val recordedInstallment: Int? = null)
data class LoanCalendar(val loanId: String, val days: List<SheetCalendarDay>, val terms: String? = null)

internal fun loanDates(loan: SheetLoan): List<LocalDate> {
    val count = ChronoUnit.DAYS.between(loan.firstDue, loan.lastDue) + 1
    require(count in 1..10_000 && count == loan.total.toLong()) { "Las fechas no coinciden con el total de cuotas" }
    return List(count.toInt()) { loan.firstDue.plusDays(it.toLong()) }
}

internal fun parseCalendarDay(loan: SheetLoan, date: LocalDate, cells: List<Any?>?, fee: Any?, day: LocalDate): SheetCalendarDay {
    val number = ChronoUnit.DAYS.between(loan.firstDue, date).toInt() + 1
    require(date in loan.firstDue..loan.lastDue)
    if (cells == null) return SheetCalendarDay(date, number, CalendarStatus.UNAVAILABLE, null, null, null, null, null)
    val control = cells.getOrNull(0)
    val raw = cells.getOrNull(2)
    require(control == true || control == false || control == null || control == "") { "Control de cuota inválido" }
    require(raw == true || raw == false || raw == null || raw == "") { "Checkbox inválido" }
    val amount = cells.getOrNull(3)?.takeIf { it != "" }?.let(::sheetCents)
    val late = cells.getOrNull(4)?.takeIf { it != "" }?.let(::sheetCents)
    val recorded = cells.getOrNull(1)?.takeIf { it != "" }?.let { BigDecimal(it.toString()).intValueExact() }
    val payment = if (date <= day) parsePayment(loan, date, cells, fee, day) else null
    val status = when {
        date > day -> CalendarStatus.FUTURE
        payment == null -> CalendarStatus.INVALID
        payment.unpaid -> CalendarStatus.PENDING
        else -> CalendarStatus.PAID
    }
    return SheetCalendarDay(date, number, status, raw == true, control == true, amount, late, payment, recorded)
}

internal fun completeLoanCalendar(loan: SheetLoan, loaded: Map<LocalDate, SheetCalendarDay>, day: LocalDate) =
    LoanCalendar(loan.id, loanDates(loan).map { date -> loaded[date] ?: parseCalendarDay(loan, date, null, null, day) }, paymentTerms(loan))

/** A carried row is another view of the same loan, never a second loan. */
internal fun canonicalLoanViews(loans: List<SheetLoan>, currentYear: Int): List<SheetLoan> =
    loans.groupBy { it.id }.values.map { views ->
        views.filter { it.year <= currentYear }.maxByOrNull { it.year } ?: views.minBy { it.year }
    }.sortedWith(compareBy({ normalizedClient(it.client) }, { it.firstDue }, { it.id }))

internal fun normalizedClient(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

internal fun matchingSheetLoans(loans: List<SheetLoan>, query: String, filter: Int = 0): List<SheetLoan> {
    val text = normalizedClient(query)
    return loans.filter { normalizedClient(it.client).contains(text) && (filter == 0 || it.active == (filter == 1)) }
}

internal fun historyLoanId(row: List<Any?>): String? = runCatching {
    val year = BigDecimal(row.getOrNull(2).toString()).intValueExact()
    val raw = row.getOrNull(3)?.toString()?.removeSuffix(".0") ?: return null
    SheetsIdentity.canonical(year, raw)
}.getOrNull()

private data class AuditState(val unpaid: Boolean?, val late: Long?, val paidOn: LocalDate?, val revision: String)
internal class PaymentHistoryIndex(rows: List<List<Any?>>) {
    private val latest = mutableMapOf<Pair<String, LocalDate>, AuditState>()
    init {
        rows.forEachIndexed { index, row ->
            val id = historyLoanId(row) ?: return@forEachIndexed
            val due = runCatching { sheetDate(row.getOrNull(4)) }.getOrNull() ?: return@forEachIndexed
            // A later invalid or unpaid entry must also override an earlier paid entry.
            latest[id to due] = AuditState(row.getOrNull(6) as? Boolean,
                runCatching { sheetCents(row.getOrNull(8)) }.getOrNull(),
                runCatching { sheetDate(row.getOrNull(9)) }.getOrNull(), "${index + 2}:${row.getOrNull(1)}")
        }
    }
    fun paidOn(payment: SheetPayment, day: LocalDate): LocalDate? {
        if (payment.unpaid) return null
        val state = latest[payment.loan.id to payment.due] ?: return null
        return state.paidOn?.takeIf { state.unpaid == false && state.late == payment.late && it in payment.due..day }
    }
    fun revision(payment: SheetPayment): String = latest[payment.loan.id to payment.due]?.revision.orEmpty()
    fun hasDateCorrection(loanId: String, day: LocalDate): Boolean = latest.any { (key, state) ->
        key.first == loanId && state.unpaid == false && state.paidOn != null &&
            state.paidOn > key.second && state.paidOn <= day
    }
}

/** Display staged edits without replacing the original snapshot used to validate a write. */
internal fun calendarWithDrafts(calendar: LoanCalendar, edits: Map<String, Pair<SheetPayment, PaymentEdit>>,
    day: LocalDate): LoanCalendar = calendarWithPayments(calendar, edits.values.map { (original, edit) ->
    original.copy(unpaid = edit.unpaid, late = if (edit.unpaid) original.loan.lateAt(original.due, day) else edit.collectedLate,
        paidOn = if (edit.unpaid) null else edit.paidOn ?: original.paidOn ?: day, bookAmount = null)
})

internal fun calendarWithPayments(calendar: LoanCalendar, payments: List<SheetPayment>): LoanCalendar {
    val rows = payments.associateBy { it.id }
    return calendar.copy(days = calendar.days.map { entry ->
        val original = entry.payment ?: return@map entry
        val row = rows[original.id] ?: return@map entry
        if (row == original) return@map entry
        entry.copy(status = if (row.unpaid) CalendarStatus.PENDING else CalendarStatus.PAID,
            checked = row.unpaid, bookLate = row.late, bookAmount = row.bookAmount ?: row.base + row.late, payment = row)
    })
}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today

import android.content.Context
import com.example.controlprestamos.domain.PaymentEdit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

internal fun accountFile(context: Context, subject: String, book: String, suffix: String) =
    RemoteJsonFile(File(context.filesDir, "sheets-${remoteCacheKey(subject, book)}-$suffix.json"))

internal data class CashSnapshot(val rows: List<BookCashDay>, val syncedAtMillis: Long, val actualPaymentDates: Boolean = false)

/** Separate from the network repository: UI queries do not wait for HTTP or mutate its caches. */
internal class OfflineBookStore(private val file: SheetCache, private val subject: String, private val book: String) {
    constructor(context: Context, subject: String, book: String) : this(accountFile(context, subject, book, "local-book"), subject, book)
    private fun packet() = file.read()?.also {
        require(it.getString("subject") == subject && it.getString("book") == book)
    } ?: JSONObject().put("subject", subject).put("book", book)
    fun saveCatalog(loans: List<SheetLoan>) = synchronized(lock) {
        val obj = packet()
        val rows = loans.map { loan -> SheetPayment(loan, loan.firstDue, loan.first, true, 0) }
        val ids = loans.map { it.id }.toSet()
        obj.put("payments", encode(decode(obj.optJSONObject("payments")).filter { it.loan.id in ids }))
        obj.put("catalog", encode(rows)); file.write(obj)
    }
    fun loans(): List<SheetLoan> = synchronized(lock) { decode(packet().optJSONObject("catalog")).map { it.loan }.distinctBy { it.id } }
    fun hasCatalog(): Boolean = synchronized(lock) { packet().has("catalog") }
    fun allRows(): List<SheetPayment> = synchronized(lock) { decode(packet().optJSONObject("payments")) }
    fun saveContributions(values: Map<String, Long>) = synchronized(lock) { file.write(packet().put("contributions", JSONObject(values))) }
    fun contributions(): Map<String, Long> = synchronized(lock) { packet().optJSONObject("contributions")?.let { obj ->
        obj.keys().asSequence().associateWith { obj.getLong(it) }
    }.orEmpty() }
    fun saveCashEventTimes(values: Map<String, java.time.Instant>) = synchronized(lock) { file.write(packet().put("cashEventTimes", encodeCashTimes(values))) }
    fun cashEventTimes(): Map<String, java.time.Instant> = synchronized(lock) { decodeCashTimes(packet().optJSONObject("cashEventTimes")) }
    fun saveMovements(values: List<CashMovement>) = synchronized(lock) { file.write(packet().put("movements", JSONArray(values.map { it.json() }))) }
    fun movements(): List<CashMovement> = synchronized(lock) { cashMovements(packet().optJSONArray("movements")) }
    fun saveRows(rows: List<SheetPayment>) = synchronized(lock) {
        val obj = packet()
        val all = decode(obj.optJSONObject("payments")).associateBy { it.id } + rows.associateBy { it.id }
        obj.put("payments", encode(all.values.toList())); file.write(obj)
    }
    fun saveCalendar(calendar: LoanCalendar, rows: List<SheetPayment>) = synchronized(lock) {
        val obj = packet()
        val retained = decode(obj.optJSONObject("payments")).filterNot { it.loan.id == calendar.loanId }
        obj.put("payments", encode(retained + rows))
        val calendars = obj.optJSONObject("calendars") ?: JSONObject()
        calendars.put(calendar.loanId, calendar.terms ?: "")
        val stamps = obj.optJSONObject("calendar-stamps") ?: JSONObject()
        val loan = rows.firstOrNull()?.loan
        stamps.put(calendar.loanId, JSONObject().put("at", System.currentTimeMillis()).put("paid", loan?.paid ?: -1)
            .put("collected", loan?.collected ?: -1).put("debt", loan?.debt ?: -1))
        obj.put("calendar-stamps", stamps)
        obj.put("calendars", calendars); file.write(obj)
    }
    fun calendarFresh(loan: SheetLoan, maxAgeMillis: Long = 15 * 60_000): Boolean = synchronized(lock) {
        val obj = packet()
        val stamp = obj.optJSONObject("calendar-stamps")?.optJSONObject(loan.id) ?: return false
        obj.optJSONObject("calendars")?.optString(loan.id) == paymentTerms(loan) &&
            System.currentTimeMillis() - stamp.getLong("at") in 0..maxAgeMillis &&
            stamp.getInt("paid") == loan.paid && stamp.getLong("collected") == loan.collected && stamp.getLong("debt") == loan.debt
    }
    fun payments(from: LocalDate, to: LocalDate, loanId: String? = null, day: LocalDate = today()): List<SheetPayment> = synchronized(lock) {
        decode(packet().optJSONObject("payments")).filter { it.due in from..to && it.due <= day && (loanId == null || it.loan.id == loanId) }
            .map { if (it.unpaid) it.copy(late = it.loan.lateAt(it.due, day)) else it }
    }
    fun calendar(loan: SheetLoan, day: LocalDate = today()): LoanCalendar? = synchronized(lock) {
        val obj = packet()
        if (obj.optJSONObject("calendars")?.optString(loan.id) != paymentTerms(loan)) return null
        val rows = decode(obj.optJSONObject("payments")).filter { it.loan.id == loan.id }.associateBy { it.due }
        LoanCalendar(loan.id, loanDates(loan).map { date ->
            val row = rows[date]?.let { if (it.unpaid) it.copy(late = it.loan.lateAt(date, day)) else it }
            val status = when { date > day -> CalendarStatus.FUTURE; row == null -> CalendarStatus.UNAVAILABLE
                row.unpaid -> CalendarStatus.PENDING; else -> CalendarStatus.PAID }
            SheetCalendarDay(date, (java.time.temporal.ChronoUnit.DAYS.between(loan.firstDue, date) + 1).toInt(),
                status, row?.unpaid, row != null, row?.bookAmount, row?.late, row?.takeIf { date <= day })
        }, paymentTerms(loan))
    }
    fun saveCash(rows: List<BookCashDay>, syncedAtMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        val obj = packet().put("cash", JSONArray(rows.map { JSONArray(listOf(it.date.toString(), it.contribution, it.receipts, it.disbursed, it.cash, it.assumedContribution)) }))
            .put("cash-synced-at", syncedAtMillis).put("cash-actual-dates", true)
        file.write(obj)
    }
    fun cashSnapshot(): CashSnapshot = synchronized(lock) {
        val obj = packet()
        val array = obj.optJSONArray("cash") ?: return CashSnapshot(emptyList(), 0)
        CashSnapshot(List(array.length()) { val row = array.getJSONArray(it)
            BookCashDay(LocalDate.parse(row.getString(0)), row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4), row.optLong(5, 0))
        }, obj.optLong("cash-synced-at", 0), obj.optBoolean("cash-actual-dates", false))
    }
    fun cash(): List<BookCashDay> = cashSnapshot().rows
    /** Keep confirmed receipts in the cached graph before removing them from the outbox.
     * A slow or failed full CAJA refresh must not make a just-confirmed payment disappear.
     */
    fun confirmCash(flight: PaymentFlight, actualPaymentDates: Boolean, day: LocalDate) = synchronized(lock) {
        val snapshot = cashSnapshot()
        val acknowledged = requireNotNull(flight.acknowledgedAt)
        if (snapshot.rows.isNotEmpty() && snapshot.syncedAtMillis < acknowledged) {
            val changes = financialPaymentChanges(PaymentQueue(flight.changes, flight), snapshot.syncedAtMillis, day)
            saveCash(localCashRows(snapshot.rows, changes, actualPaymentDates && snapshot.actualPaymentDates, day), acknowledged)
            if (!snapshot.actualPaymentDates) file.write(packet().put("cash-actual-dates", false))
        }
    }
    private fun encode(rows: List<SheetPayment>) = draftPacket(subject, book, rows.associate {
        it.id to (it to PaymentEdit(it.loan.id, it.id, 0, it.unpaid, if (it.unpaid) 0 else it.late, it.paidOn))
    })
    private fun decode(obj: JSONObject?) = obj?.let { decodeDraft(it, subject, book).values.map { pair -> pair.first } }.orEmpty()
    companion object { private val lock = Any() }
}

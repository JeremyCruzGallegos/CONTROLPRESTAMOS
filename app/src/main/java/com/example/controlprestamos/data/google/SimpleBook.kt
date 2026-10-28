package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal const val SIMPLE_SCHEMA = "CP_SIMPLE_V2"
internal const val SIMPLE_LEGACY_SCHEMA = "CP_SIMPLE_V1"
internal val SIMPLE_HEADERS = listOf("ID", "CLIENTE", "PRESTAMO", "TASA MENSUAL", "MESES", "GARANTIA", "DESEMBOLSO",
    "APORTE", "MORA DIARIA", "TOTAL PACTADO", "CUOTA DIARIA", "PRIMERA CUOTA", "PRIMER PAGO", "ULTIMO PAGO", "ESTADO", "REVISION")
internal val SIMPLE_START: LocalDate = LocalDate.of(2026, 1, 1)
internal const val SIMPLE_COLUMNS = 16
internal fun simpleDayColumn(date: LocalDate, checkboxes: Boolean = true): Int = SIMPLE_COLUMNS + Math.toIntExact(ChronoUnit.DAYS.between(SIMPLE_START, date)) * if (checkboxes) 4 else 3
internal fun simpleDayHeading(date: LocalDate): String {
    val month = listOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC")[date.monthValue - 1]
    return "${date.dayOfMonth.toString().padStart(2, '0')} $month ${date.year}\nNO PAGÓ"
}
internal fun simpleHeadings(until: LocalDate, checkboxes: Boolean = true): List<String> = SIMPLE_HEADERS +
    (0..ChronoUnit.DAYS.between(SIMPLE_START, until)).flatMap { n ->
        val date = SIMPLE_START.plusDays(n)
        (if (checkboxes) listOf(simpleDayHeading(date)) else emptyList()) + listOf("$date · CUOTA", "$date · PAGADO EL", "$date · MORA COBRADA")
    }
internal data class SimpleRecord(val row: Int, val loan: Loan, val dailyLate: Long, val deleted: Boolean, val revision: String,
    val unverifiedPaid: Set<LocalDate> = emptySet(), val advances: List<FeeAdvance> = emptyList()) {
    fun isPaid(item: Installment, day: LocalDate) = item.due <= day && (item.paid(day) || item.due in unverifiedPaid)
    fun view(day: LocalDate): SheetLoan {
        val paid = loan.installments.filter { isPaid(it, day) }
        val pending = loan.installments.filterNot { isPaid(it, day) }
        val first = loan.installments.first(); val regular = loan.installments.getOrElse(1) { first }.base
        val currentAdvances=advances.map { advance->advance.copy(sourcePaid=loan.installments.single { it.due==advance.source }.let { isPaid(it,day) }) }
        return SheetLoan(loan.id, loan.id, loan.disbursed.year, row, loan.client, loan.principal, loan.ratePercent, loan.months,
            loan.guarantee, loan.agreed, paid.sumOf { it.base + it.collectedLate },
            pending.sumOf { item->item.base+(calculatedLate(item.due,day,dailyLate)-currentAdvances.filter { it.target==item.due && it.sourcePaid }.sumOf { it.amount }).coerceAtLeast(0) }, regular, first.base,
            first.due, loan.installments.last().due, paid.size, loan.installments.size,
            dailyLate = dailyLate, scheduledBases = loan.installments.associate { it.due to it.base }, termsRevision = revision,advances=currentAdvances)
    }
    fun cells(until: LocalDate, checkboxes: Boolean = true): List<Any?> {
        val cells = MutableList<Any?>(simpleDayColumn(until, checkboxes) + if (checkboxes) 4 else 3) { "" }
        val first = loan.installments.first()
        val fixed = listOf(loan.id, loan.client, loan.principal / 100.0, loan.ratePercent.movePointLeft(2), loan.months,
            loan.guarantee, loan.disbursed.toString(), loan.contribution / 100.0, dailyLate / 100.0, loan.agreed / 100.0,
            loan.installments.getOrElse(1) { first }.base / 100.0, first.base / 100.0, first.due.toString(),
            loan.installments.last().due.toString(), if (deleted) "ELIMINADO" else "ACTIVO", revision)
        fixed.forEachIndexed { i, v -> cells[i] = v }
        if(advances.isNotEmpty())cells[15]=LoanConditions(dailyLate,loan.installments.associate { it.due to it.base },revision,advances).json().toString()
        if (checkboxes) for (column in SIMPLE_COLUMNS until cells.size step 4) cells[column] = "—"
        loan.installments.forEach { item ->
            val start = simpleDayColumn(item.due, checkboxes)
            val col = start + if (checkboxes) 1 else 0
            if (checkboxes) cells[start] = item.paidOn == null && item.due !in unverifiedPaid
            require(col >= SIMPLE_COLUMNS && col + 2 < cells.size) { "El calendario no cubre el préstamo" }
            cells[col] = item.base / 100.0
            cells[col + 1] = item.paidOn?.toString() ?: ""
            cells[col + 2] = if (item.paidOn == null && item.due !in unverifiedPaid) advances.filter { a->a.target==item.due &&
                loan.installments.single { it.due==a.source }.let { it.paidOn!=null || it.due in unverifiedPaid } }.sumOf { it.amount }.let { if(it==0L) "" else it/100.0 }
                else item.collectedLate / 100.0
        }
        return cells
    }
}
internal data class SimpleBook(val title: String, val initial: Long, val capitalRevision: String, val until: LocalDate,
    val records: List<SimpleRecord>, val syncedAt: Instant, val checkboxes: Boolean = true, val headerRow: Int = 1, val movements: List<CashMovement> = emptyList(), val cashEventTimes: Map<String, Instant> = emptyMap()) {
    val active: List<SimpleRecord> get() = records.filterNot { it.deleted }
    fun record(id: String): SimpleRecord = active.singleOrNull { it.loan.id == id } ?: error("El préstamo cambió o fue eliminado; actualiza")
    fun loans(year: Int, day: LocalDate): List<SheetLoan> = active.filter { it.loan.belongsToYear(year) }.map { it.view(day) }
    fun payment(record: SimpleRecord, item: Installment, day: LocalDate, book: String,
        view: SheetLoan = record.view(day), terms: String = paymentTerms(view)): SheetPayment {
        val paid = record.isPaid(item, day)
        val payment = SheetPayment(view, item.due, item.base, !paid,
            if (paid) item.collectedLate else view.lateAt(item.due, day), if (paid) item.paidOn else null,
            bookAmount = item.base + if (paid) item.collectedLate else view.lateAt(item.due, day))
        return payment.copy(version = DirectPaymentVersion(book, view.id, view.year, item.due, terms, !paid,
            if (paid) item.collectedLate else 0, payment.paidOn, record.revision).toJson().toString())
    }
    fun payments(from: LocalDate, to: LocalDate, loanId: String?, ids: Set<String>?, day: LocalDate, book: String): List<SheetPayment> {
        require(from <= to && to <= day && ChronoUnit.DAYS.between(from, to) <= 30) { "Consulta de hasta 31 días vencidos o de hoy" }
        return active.filter { (loanId == null || it.loan.id == loanId) && (ids == null || it.loan.id in ids) }
            .flatMap { record ->
                val view = record.view(day); val terms = paymentTerms(view)
                record.loan.installments.filter { it.due in from..to }.map { payment(record, it, day, book, view, terms) }
            }
            .sortedWith(compareBy({ it.due }, { it.loan.client }))
    }
    fun calendar(id: String, day: LocalDate, book: String): LoanCalendar {
        val record = record(id); val loan = record.view(day); val terms = paymentTerms(loan)
        return LoanCalendar(id, record.loan.installments.mapIndexed { index, item ->
            val payment = if (item.due <= day) payment(record, item, day, book, loan, terms) else null
            SheetCalendarDay(item.due, index + 1, when { payment == null -> CalendarStatus.FUTURE
                payment.unpaid -> CalendarStatus.PENDING; else -> CalendarStatus.PAID }, payment?.unpaid,
                true, payment?.bookAmount ?: item.base, payment?.late ?: 0, payment, index + 1)
        }, paymentTerms(loan))
    }
    private fun totals(day: LocalDate): Summary {
        val eligible = active.filter { it.loan.disbursed <= day }
        val disbursed = eligible.sumOf { it.loan.principal }; val contributions = eligible.sumOf { it.loan.contribution } + movements.filter { it.date <= day }.sumOf { it.signed }
        var received = 0L; var interest = 0L; var late = 0L; var recovered = 0L
        eligible.forEach { record ->
            val paid = record.loan.installments.filter { record.isPaid(it, day) }
            val base = paid.sumOf { it.base }; val fees = paid.sumOf { it.collectedLate }
            val earnings = record.loan.proportionalInterest(base)
            received += base + fees; late += fees; interest += earnings; recovered += base - earnings
        }
        val assumed = assumedFunding(day)
        return Summary(initial + contributions + assumed - disbursed + received, interest, recovered,
            disbursed - recovered, disbursed, contributions + assumed, late)
    }
    fun summary(year: Int, day: LocalDate): SheetSummary {
        val start = LocalDate.of(year, 1, 1); val cutoff = minOf(day, LocalDate.of(year, 12, 31))
        val previous = totals(start.minusDays(1)); val end = totals(cutoff)
        return SheetSummary(year, cutoff, initial, previous.cash, end.contributions - previous.contributions,
            end.disbursed - previous.disbursed, end.cash - previous.cash - (end.contributions - previous.contributions) +
                (end.disbursed - previous.disbursed), end.interest - previous.interest, end.late - previous.late,
            end.recovered - previous.recovered, end.cash, end.pendingCapital, assumedFunding(cutoff))
    }
    fun preload(year: Int, day: LocalDate, book: String) = SheetsPreload(title, summary(year, day), loans(year, day),
        payments(day, day, null, null, day, book), syncedAt)
    fun cash(day: LocalDate): List<BookCashDay> {
        require(active.none { record -> record.unverifiedPaid.any { it <= day } }) {
            "Hay cuotas marcadas como pagadas sin fecha real de cobro. Completa la fecha en los detalles del día o desde Pagos para consultar la caja por fechas."
        }
        return reconcileLegacyFunding(recordedCash(day))
    }
    private fun assumedFunding(day: LocalDate): Long =
        if (active.any { record -> record.unverifiedPaid.any { it <= day } }) 0L
        else reconcileLegacyFunding(recordedCash(day)).sumOf { it.assumedContribution }

    private fun recordedCash(day: LocalDate): List<BookCashDay> {
        if (day < SIMPLE_START) return emptyList()
        data class Flow(var contribution: Long = 0, var receipts: Long = 0, var disbursed: Long = 0)
        val flows = mutableMapOf<LocalDate, Flow>()
        active.forEach { record ->
            val loan = record.loan
            flows.getOrPut(loan.disbursed) { Flow() }.apply { contribution += loan.contribution; disbursed += loan.principal }
            loan.installments.filter { it.paid(day) }.forEach { item ->
                flows.getOrPut(item.paidOn!!) { Flow() }.receipts += item.base + item.collectedLate
            }
        }
        movements.filter { it.date <= day }.forEach { movement ->
            flows.getOrPut(movement.date) { Flow() }.contribution += movement.signed
        }
        var balance = initial
        return (0..ChronoUnit.DAYS.between(SIMPLE_START, day)).map { offset ->
            val date = SIMPLE_START.plusDays(offset); val flow = flows[date] ?: Flow()
            balance += flow.contribution + flow.receipts - flow.disbursed
            BookCashDay(date, flow.contribution, flow.receipts, flow.disbursed, balance)
        }
    }
    fun json(): JSONObject = JSONObject().put("title", title).put("initial", initial).put("capitalRevision", capitalRevision)
        .put("cashEventTimes", encodeCashTimes(cashEventTimes)).put("movements", JSONArray(movements.map { it.json() })).put("until", until.toString()).put("syncedAt", syncedAt.toString()).put("checkboxes", checkboxes).put("headerRow", headerRow)
        .put("rows", JSONArray(records.map { JSONObject().put("row", it.row).put("values", JSONArray(it.cells(until, checkboxes))) }))
}
internal fun parseSimpleRecord(row: Int, cells: List<Any?>, until: LocalDate, checkboxes: Boolean = true): SimpleRecord {
    fun text(i: Int) = cells.getOrNull(i)?.toString().orEmpty()
    val id = text(0)
    require(id.matches(Regex("20\\d{2}-[0-9]{3,}"))) { "Identidad de préstamo inválida en la fila $row" }
    val disbursed = sheetDate(cells.getOrNull(6))
    require(disbursed >= SIMPLE_START && id.substringBefore('-').toInt() == disbursed.year)
    val months = BigDecimal(text(4)).intValueExact(); require(months in 1..24)
    val first = sheetDate(cells.getOrNull(12)); val last = sheetDate(cells.getOrNull(13))
    require(first == disbursed.plusDays(1) && last == first.plusDays(months * 30L - 1) && last <= until)
    val principal = sheetCents(cells.getOrNull(2)); val rate = BigDecimal(text(3)).movePointRight(2)
    val contribution = sheetCents(cells.getOrNull(7)); val late = sheetCents(cells.getOrNull(8))
    require(principal > 0 && contribution >= 0 && late in 0..1_000_000 && rate in BigDecimal.ZERO..BigDecimal("100"))
    require(text(1).isNotBlank() && text(14) in listOf("ACTIVO", "ELIMINADO") && text(15).isNotBlank())
    val config=if(text(15).startsWith("{"))loanConditions(JSONObject(text(15)))else null
    val unverified = mutableSetOf<LocalDate>()
    val installments = List(months * 30) { index ->
        val due = first.plusDays(index.toLong()); val start = simpleDayColumn(due, checkboxes)
        val col = start + if (checkboxes) 1 else 0
        val check = if (checkboxes) cells.getOrNull(start).also { require(it is Boolean) { "Falta la casilla No pagó de $id / $due" } } else null
        val base = sheetCents(cells.getOrNull(col)); require(base > 0) { "Falta importe de cuota en $id / $due" }
        val paidOn = cells.getOrNull(col + 1)?.takeIf { it != "" }?.let(::sheetDate)
        val rawFee=cells.getOrNull(col+2)?.takeIf { it!="" }
        val fee=if(rawFee is String && rawFee.startsWith("=")) {
            require(checkboxes && check==true && rawFee==simpleAdvanceFormula(row,due,config?.advances.orEmpty())) { "La celda de mora contiene una fórmula inesperada" }
            config!!.advances.filter { it.target==due && cells.getOrNull(simpleDayColumn(it.source))==false }.sumOf { it.amount }
        } else rawFee?.let(::sheetCents)?:0
        require(fee >= 0 && (paidOn == null || paidOn >= due))
        if (checkboxes && check == false && paidOn == null) unverified += due
        if (!checkboxes) require(paidOn != null || fee == 0L)
        Installment("$id:${index + 1}", due, base, if (check == true) null else paidOn, if (check == true) 0 else fee)
    }
    val loan = Loan(id, text(1), principal, rate, months, text(5), disbursed,
        if (contribution > 0) Funding.EXTERNAL else Funding.CAPITAL, contribution, installments)
    val expected = BigDecimal(principal).multiply(BigDecimal.ONE + rate.movePointLeft(2).multiply(BigDecimal(months)))
        .setScale(0, RoundingMode.HALF_UP).longValueExact()
    require(loan.agreed == sheetCents(cells.getOrNull(9)) && loan.agreed == expected &&
        installments.first().base == sheetCents(cells.getOrNull(11)) && installments.getOrElse(1) { installments.first() }.base == sheetCents(cells.getOrNull(10))) {
        "Las cuotas del préstamo $id no cuadran con sus condiciones"
    }
    require(config==null || config.dailyLate==late && config.bases==installments.associate { it.due to it.base }) { "Las condiciones de pago cambiaron" }
    return SimpleRecord(row, loan, late, text(14) == "ELIMINADO", config?.revision?:text(15), unverified,config?.advances.orEmpty())
}
internal fun simpleBookFromJson(value: JSONObject): SimpleBook {
    val until = LocalDate.parse(value.getString("until")); val rows = value.getJSONArray("rows")
    val checkboxes = value.optBoolean("checkboxes", false)
    return SimpleBook(value.getString("title"), value.getLong("initial"), value.getString("capitalRevision"), until,
        List(rows.length()) { index -> val item = rows.getJSONObject(index)
            parseSimpleRecord(item.getInt("row"), JSONArray().put(item.getJSONArray("values")).rowValues().single(), until, checkboxes) },
        Instant.parse(value.getString("syncedAt")), checkboxes, value.optInt("headerRow", 1), cashMovements(value.optJSONArray("movements")), decodeCashTimes(value.optJSONObject("cashEventTimes")))
}

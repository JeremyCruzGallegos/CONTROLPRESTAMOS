package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.today
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import com.example.controlprestamos.domain.LIMA

enum class CashMovementType(val label: String) {
    DEPOSIT("Abono"), WITHDRAWAL("Desembolso"), LOAN("Préstamo"), PAYMENT("Pago")
}

/** Independent capital flows. They never change the initial capital or earned interest. */
data class CashMovement(val id: String, val date: LocalDate, val type: CashMovementType,
    val amount: Long, val description: String = "") {
    init {
        require(id.isNotBlank() && type in listOf(CashMovementType.DEPOSIT, CashMovementType.WITHDRAWAL))
        require(amount in 1..MAX_INITIAL_CAPITAL) { "Ingresa un monto mayor a cero" }
        require(description.length <= 500 && description.none { it.isISOControl() && it != '\n' }) { "La descripción admite hasta 500 caracteres" }
    }
    val signed: Long get() = if (type == CashMovementType.DEPOSIT) amount else -amount
    fun json(): JSONObject = JSONObject().put("id", id).put("date", date.toString()).put("type", type.name)
        .put("amount", amount).put("description", description)
}
internal fun cashMovement(value: JSONObject): CashMovement = CashMovement(value.getString("id"),
    LocalDate.parse(value.getString("date")), CashMovementType.valueOf(value.getString("type")),
    value.getLong("amount"), value.optString("description"))
internal fun cashMovements(values: JSONArray?): List<CashMovement> = values?.let { array ->
    List(array.length()) { cashMovement(array.getJSONObject(it)) }.also { rows ->
        require(rows.map { it.id }.distinct().size == rows.size) { "Hay movimientos de caja duplicados" }
    }
}.orEmpty()
internal fun movementCommand(body: JSONObject, day: LocalDate = today()): CashMovement = CashMovement(
    body.getString("operationId"), LocalDate.parse(body.getString("date")),
    CashMovementType.valueOf(body.getString("type")), body.getLong("amountCents"), body.optString("description").trim()
).also { require(it.date in SIMPLE_START..day) { "Revisa la fecha del movimiento" } }

internal fun validateCashMovement(movement: CashMovement, rows: List<BookCashDay>, current: Long, day: LocalDate) {
    require(rows.isEmpty() || movement.date >= rows.first().date) { "La fecha está fuera del calendario de caja disponible" }
    if (movement.type == CashMovementType.WITHDRAWAL) {
        require(rows.isNotEmpty() && rows.first().date <= movement.date) { "Actualiza Inicio para comprobar la caja de esa fecha" }
        require(movement.amount <= cashForNewLoan(rows, movement.date, current, day)) {
            "La caja disponible no alcanza para este desembolso en la fecha elegida"
        }
    }
}

internal fun cashWithMovement(rows: List<BookCashDay>, movement: CashMovement, day: LocalDate): List<BookCashDay> {
    if (rows.isEmpty()) return rows
    val extended = rows.filter { it.date <= day }.sortedBy { it.date }.toMutableList()
    val last = extended.lastOrNull() ?: return rows
    var date = last.date.plusDays(1)
    while (date <= day) { extended += BookCashDay(date, 0, 0, 0, last.cash); date = date.plusDays(1) }
    return extended.map { row -> row.copy(
        contribution = row.contribution + if (row.date == movement.date) movement.signed else 0,
        cash = row.cash + if (row.date >= movement.date) movement.signed else 0) }
}

/** Shared by the entry preview and durable local projection, including year boundaries. */
internal fun projectCashMovement(summary: SheetSummary, rows: List<BookCashDay>, movement: CashMovement,
    day: LocalDate, reconcileFunding: Boolean): Pair<SheetSummary, List<BookCashDay>> {
    validateCashMovement(movement, rows, summary.cash, day)
    val raw = cashWithMovement(rows, movement, day)
    val cash = if (reconcileFunding) reconcileLegacyFunding(raw) else raw
    val assumed = if (reconcileFunding && cash.isNotEmpty()) cash.sumOf { it.assumedContribution } else summary.assumedContribution
    val delta = assumed - summary.assumedContribution
    val annualDelta = if (reconcileFunding) cash.filter { it.date.year == summary.year }.sumOf { it.assumedContribution } -
        rows.filter { it.date.year == summary.year }.sumOf { it.assumedContribution } else 0L
    return summary.copy(cash = summary.cash + movement.signed + delta,
        contribution = summary.contribution + annualDelta + if (movement.date.year == summary.year) movement.signed else 0,
        opening = summary.opening + delta - annualDelta + if (movement.date.year < summary.year) movement.signed else 0,
        assumedContribution = assumed) to cash
}

data class CashHistoryRow(val id: String, val date: LocalDate, val type: CashMovementType,
    val amount: Long, val description: String, val recordedAt: Instant? = null)

/** Current effective cash transactions: reverted payments and deleted loans do not remain as receipts. */
fun cashHistory(catalog: List<SheetLoan>, payments: List<SheetPayment>, movements: List<CashMovement>,
    contributions: Map<String, Long>, day: LocalDate, eventTimes: Map<String, Instant> = emptyMap()): List<CashHistoryRow> {
    val loans = canonicalLoanViews(catalog, day.year).filter { it.firstDue.minusDays(1) <= day }
    val ids = loans.map { it.id }.toSet()
    return (movements.filter { it.date <= day }.map { CashHistoryRow(it.id, it.date, it.type, it.amount, it.description) } +
        loans.flatMap { loan -> listOfNotNull(
            CashHistoryRow("loan:${loan.id}", loan.firstDue.minusDays(1), CashMovementType.LOAN, loan.principal, loan.client),
            contributions[loan.id]?.takeIf { it > 0 }?.let { CashHistoryRow("fund:${loan.id}", loan.firstDue.minusDays(1),
                CashMovementType.DEPOSIT, it, "excedente entre prestamo y caja, se abono dinero externo para completar el prestamo") }) } +
        payments.distinctBy { it.id }.filter { it.loan.id in ids && !it.unpaid && it.paidOn != null && it.paidOn <= day }
            .map { CashHistoryRow("pay:${it.id}", it.paidOn!!, CashMovementType.PAYMENT, it.base + it.late,
                "${it.loan.client} · Cuota ${it.due}") })
        .map { it.copy(recordedAt = eventTimes[it.id]) }
        .sortedWith(compareByDescending<CashHistoryRow> { it.recordedAt ?: it.date.atStartOfDay(LIMA).toInstant() }.thenByDescending { it.id })
}

internal fun cashLedgerRevisionKey(rows: List<List<Any?>>) = "cp_cash_rev_" + directPaymentHash(JSONArray(rows).toString()).take(40)

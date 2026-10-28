package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

internal class DirectLoanRejected(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
internal data class DirectLoanCell(val value: Any? = null, val formula: String? = null,
    val validation: String? = null, val validationValues: List<String> = emptyList())
internal data class DirectLoanPlan(val row: Int, val item: Int, val loan: Loan, val cashAfter: Long) {
    val year: Int get() = loan.disbursed.year
    val firstDue: LocalDate get() = loan.installments.first().due
    val lastDue: LocalDate get() = loan.installments.last().due
    fun rowIn(year: Int): Int = row + (year - this.year) * 400
    fun rawId(year: Int): String = if (year == this.year) item.toString() else loan.id
}

internal fun directLoanRequest(value: JSONObject): LoanRequest {
    val origin = value.getString("origin")
    require(origin in listOf("Del capital", "Capital nuevo")) { "Origen de fondos inválido" }
    fun cents(name: String) = BigDecimal(value.get(name).toString()).movePointRight(2).longValueExact()
    return LoanRequest(value.getString("client"), cents("principal"),
        BigDecimal(value.get("rate").toString()).movePointRight(2),
        BigDecimal(value.get("months").toString()).intValueExact(), value.optString("guarantee"),
        if (origin == "Capital nuevo") Funding.EXTERNAL else Funding.CAPITAL, cents("contribution"))
}

/** Preserve the original 400-row block; no inserts, formula rewrites or reuse of registered IDs. */
internal fun planDirectLoan(book: String, request: LoanRequest, day: LocalDate, asOf: LocalDate, cash: Long,
    rows: List<List<Any?>>, contributions: List<List<Any?>>, reservedRows: Set<Int> = emptySet(),
    reservedItems: Set<Int> = emptySet()): DirectLoanPlan {
    require(SheetsIdentity.isDirectBook(book)) { "Libro no configurado para altas directas" }
    previewDatedLoan(request, day, asOf)
    val funded = Rules.fundLoan(request, cash)
    val cashAfter = Rules.cashAfter(cash, funded.principal, funded.contribution)
    require(cashAfter >= 0) { "Fondos insuficientes. Revisa el monto y el aporte" }
    require(rows.size <= 400 && contributions.size <= 1200) { "Capacidad fuera del bloque original" }
    require(contributions.count { it.any(::hasLoanValue) } < 1200) { "El registro de aportes alcanzó su capacidad" }
    val priorIds = contributions.mapNotNull { it.getOrNull(1)?.toString()?.takeIf(String::isNotBlank) }.toSet()
    fun item(value: Any?) = runCatching { BigDecimal(value.toString()).intValueExact() }.getOrNull()?.takeIf { it > 0 }
    val used = rows.filter { hasLoanValue(it.getOrNull(1)) }.mapNotNull { item(it.firstOrNull()) }.toSet() + reservedItems +
        priorIds.filter { it.startsWith("${day.year}-") }.mapNotNull { it.substringAfter('-').toIntOrNull() }
    val index = (0 until 400).firstOrNull { i ->
        val row = rows.getOrNull(i).orEmpty()
        i + 6 !in reservedRows && !hasLoanValue(row.getOrNull(1)) &&
            (!hasLoanValue(row.getOrNull(3)) || runCatching { sheetCents(row[3]) == 0L }.getOrDefault(false)) &&
            !hasLoanValue(row.getOrNull(15))
    } ?: error("No quedan filas originales libres para este año")
    val preferred = item(rows.getOrNull(index)?.firstOrNull())
    val number = preferred?.takeIf { it !in used } ?: Math.addExact(used.maxOrNull() ?: 0, 1)
    val id = SheetsIdentity.canonical(day.year, number.toString())
    require(id !in priorIds) { "Ese préstamo ya tiene un registro de capital" }
    return DirectLoanPlan(index + 6, number, Rules.createLoan(id, funded, day), cashAfter)
}

// The original 2026 calendar starts on July 16; its first loan can be disbursed one day earlier.
internal val FIRST_LOAN_DISBURSEMENT: LocalDate = LocalDate.of(2026, 7, 15)

/** The disbursement date drives both the annual identity and the original installment schedule. */
internal fun previewDatedLoan(request: LoanRequest, disbursedOn: LocalDate, asOf: LocalDate): Loan {
    require(disbursedOn >= FIRST_LOAN_DISBURSEMENT && disbursedOn.year <= 2028) {
        "El calendario del libro admite desembolsos desde ${dateLabel(FIRST_LOAN_DISBURSEMENT)} hasta 2028"
    }
    require(disbursedOn <= asOf) { "La fecha de desembolso no puede ser futura" }
    return Rules.createLoan("preview", request, disbursedOn).also {
        require(it.installments.last().due <= LocalDate.of(2028, 12, 31)) { "El plazo supera el calendario disponible hasta 2028" }
    }
}

/** A late registration must never turn past installments into receipts that did not happen. */
internal fun initialLoanUnpaid(due: LocalDate, registeredOn: LocalDate): Boolean = due <= registeredOn
internal fun hasLoanValue(value: Any?): Boolean = value != null && value != ""
internal fun loanSlotKey(year: Int, row: Int) = "cp_newslot_${year}_$row"
/** Reuse the position only when its retained identity has a confirmed deletion marker. */
internal fun reusableLoanSlots(year: Int, rows: List<List<Any?>>, names: Set<String>): Map<Int, String> =
    rows.mapIndexedNotNull { index, cells ->
        val id = runCatching { SheetsIdentity.canonical(year, cells.firstOrNull()?.toString()?.removeSuffix(".0").orEmpty()) }.getOrNull()
            ?: return@mapIndexedNotNull null
        val empty = listOf(1, 4, 5, 6, 15).all { !hasLoanValue(cells.getOrNull(it)) } &&
            (!hasLoanValue(cells.getOrNull(3)) || runCatching { sheetCents(cells[3]) == 0L }.getOrDefault(false))
        if (empty && deletedLoanKey(id) in names) index + 6 to id else null
    }.toMap()

internal fun loanSlotRevisionKey(year: Int, row: Int, deletedId: String?) =
    "cp_slotrev_${year}_${row}_" + directPaymentHash(deletedId ?: "initial").take(24)

internal fun retiredAuxIdentity(year: Int, raw: String?, names: Set<String>): String? = raw?.let {
    runCatching { SheetsIdentity.canonical(year, it) }.getOrNull()?.takeIf { id -> deletedLoanKey(id) in names }
}
internal fun loanAuxRevisionKey(year: Int, row: Int, previousId: String?) =
    "cp_auxrev_${year}_${row}_" + directPaymentHash(previousId ?: "initial").take(24)

internal fun nativeRepartitionFormulas(row: Int) = mapOf(
    13 to "=IFERROR(ROUNDDOWN(D$row/(F$row*30)+L$row,1),\"\")",
    14 to "=H$row-(M$row*(F$row*30))+M$row",
    15 to "=ABS(H$row-(N$row+(M$row*((F$row*30)-1))))")

/** Restore only the native formulas or the precise overrides written by our terms editor. */
internal fun recycledLoanTemplate(row: Int, cells: Map<Int, DirectLoanCell>): Map<Int, DirectLoanCell> {
    val formulas = nativeRepartitionFormulas(row)
    fun normalized(value: String?) = value.orEmpty().replace("$", "").replace(Regex("\\s+"), "").uppercase()
    formulas.forEach { (col, native) ->
        val current = cells[col]?.formula
        val custom = if (col == 15) Regex("=ROUND\\(H$row-[0-9]+\\.[0-9]{2},2\\)") else Regex("=[0-9]+\\.[0-9]{2}")
        require(normalized(current) == normalized(native) || current?.matches(custom) == true) { "La fila eliminada contiene fórmulas distintas; revisa antes de reutilizarla" }
    }
    return cells + formulas.mapValues { (col, formula) -> (cells[col] ?: DirectLoanCell()).copy(formula = formula) }
}
internal fun loanItemKey(year: Int, item: Int) = "cp_newloan_${year}_$item"
internal fun loanOperationKey(operationId: String) = "cp_create_" + directPaymentHash(operationId).take(40)
internal fun loanAuxSlotKey(year: Int, row: Int) = "cp_newaux_${year}_$row"
internal fun sheetHeading(value: Any?) = normalizedClient(value?.toString().orEmpty()).replace(Regex("\\s+"), " ")
internal fun validateLoanHeadings(row: List<Any?>) {
    mapOf(0 to "item", 1 to "cliente", 3 to "prestamo", 4 to "tasa mensual", 5 to "meses", 6 to "garantia",
        15 to "primer pago", 16 to "ultimo pago").forEach { (column, label) ->
        require(sheetHeading(row.getOrNull(column)) == label) { "Los encabezados del préstamo cambiaron" }
    }
}

internal fun validateLoanTemplate(plan: DirectLoanPlan, cells: Map<Int, DirectLoanCell>, year: Int) {
    val row = plan.rowIn(year)
    if (year == plan.year) {
        listOf(1, 2, 4, 5, 6, 7, 16).forEach { column ->
            val cell = cells[column] ?: DirectLoanCell()
            require(cell.formula == null && cell.validation == null) { "La entrada del préstamo contiene una fórmula o validación distinta" }
        }
        require(!hasLoanValue(cells[2]?.value) && !hasLoanValue(cells[16]?.value) &&
            (!hasLoanValue(cells[4]?.value) || sheetCents(cells.getValue(4).value) == 0L)) { "La fila del préstamo dejó de estar libre" }
        val expected = mapOf(8 to "=D$row+D$row*(E$row*F$row)", 11 to "=D$row*(E$row/30)",
            12 to "=ROUNDDOWN(K$row,1)", 13 to "=IFERROR(ROUNDDOWN(D$row/(F$row*30)+L$row,1),\"\")",
            14 to "=H$row-(M$row*(F$row*30))+M$row", 15 to "=ABS(H$row-(N$row+(M$row*((F$row*30)-1))))",
            17 to "=IF(OR(B$row=\"\",P$row=\"\",F$row<=0),\"\",P$row+F$row*30-1)")
        fun normalized(formula: String?) = formula.orEmpty().replace("$", "").replace(Regex("\\s+"), "").uppercase()
        expected.forEach { (col, formula) -> require(normalized(cells[col]?.formula) == normalized(formula)) {
            "Las fórmulas de reparto del préstamo cambiaron; revisa el libro"
        } }
        listOf(3, 9, 10).forEach { require(!cells[it]?.formula.isNullOrBlank()) { "Faltan fórmulas de estado, cobro o deuda" } }
    } else {
        require(!hasLoanValue(cells[2]?.value)) { "La posición de traslado ya contiene otro préstamo" }
        (1..17).forEach { require(!cells[it]?.formula.isNullOrBlank()) { "Faltan las fórmulas del traslado de año" } }
        val previousYear = year - 1
        val previousRow = plan.rowIn(previousYear)
        val expected = "=IF(AND('$previousYear'!\u0024B$previousRow<>\"\",ISNUMBER('$previousYear'!\u0024P$previousRow),'$previousYear'!\u0024P$previousRow<DATE($year,1,1),'$previousYear'!\u0024Q$previousRow>=DATE($year,1,1)),'$previousYear'!\u0024B$previousRow,\"\")"
        val actual = cells.getValue(2).formula.orEmpty()
        require(actual == expected || actual == expected.replace("<DATE", "<=DATE")) { "La fórmula de traslado cambió" }
    }
}

/** The sheet's carried row otherwise excludes a loan whose first due date is exactly January 1. */
internal fun carryBoundaryFormula(plan: DirectLoanPlan, year: Int, original: String): String? =
    if (year > plan.year && plan.firstDue == LocalDate.of(year, 1, 1)) original.replace("<DATE", "<=DATE")
        .takeIf { it != original } else null

internal fun validateLoanCalendarCell(cell: DirectLoanCell, check: Boolean) {
    if (check) require(cell.formula == null && (cell.value == null || cell.value == "" || cell.value is Boolean) &&
        cell.validation == "BOOLEAN" && cell.validationValues.isEmpty()) { "Una casilla del calendario dejó de ser editable" }
    else require(!cell.formula.isNullOrBlank()) { "Faltan fórmulas del calendario" }
}

/** An explicit selection confirms on-time historical receipts, without collecting future days. */
internal fun withInitialPastPayments(loan: Loan, registeredOn: LocalDate, paid: Boolean): Loan =
    if (!paid) loan else loan.copy(installments = loan.installments.map {
        if (it.due <= registeredOn) it.copy(paidOn = it.due, collectedLate = 0) else it
    })

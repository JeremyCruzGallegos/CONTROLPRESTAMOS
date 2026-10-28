package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A durable local view and its ordered commands are committed in the same atomic file. */
internal data class LocalBookView(val data: SheetsPreload, val catalog: List<SheetLoan>, val rows: List<SheetPayment>,
    val cash: List<BookCashDay>, val contributions: Map<String, Long> = emptyMap(), val actualDates: Boolean = false, val movements: List<CashMovement> = emptyList(), val cashEventTimes: Map<String, Instant> = emptyMap()) {
    fun loan(id: String) = catalog.singleOrNull { it.id == id } ?: error("El préstamo ya no está disponible")
    fun calendar(id: String, day: LocalDate = today()): LoanCalendar {
        val loan = loan(id); val payments = rows.filter { it.loan.id == id }.associateBy { it.due }
        return LoanCalendar(id, loanDates(loan).mapIndexed { index, date ->
            val row = payments[date]?.let { if (it.unpaid) it.copy(late = loan.lateAt(date, day)) else it }
            SheetCalendarDay(date, index + 1, when { date > day -> CalendarStatus.FUTURE
                row == null -> CalendarStatus.UNAVAILABLE; row.unpaid -> CalendarStatus.PENDING; else -> CalendarStatus.PAID },
                row?.unpaid, row != null || date > day, row?.bookAmount ?: loan.baseOn(date), row?.late ?: 0, row?.takeIf { date <= day })
        }, paymentTerms(loan))
    }
    fun record(id: String, day: LocalDate): SimpleRecord {
        val view = loan(id); val payments = rows.filter { it.loan.id == id }.associateBy { it.due }
        val unverified = mutableSetOf<LocalDate>()
        val installments = loanDates(view).mapIndexed { index, due ->
            val row = payments[due]
            require(due > day || row != null || view.paid == view.total && view.collected == view.agreed || view.paid == 0) {
                "Abre una vez el calendario de este préstamo con conexión para disponer de sus cuotas"
            }
            val paid = due <= day && (row?.unpaid == false || row == null && view.paid == view.total)
            if (paid && row?.paidOn == null) unverified += due
            Installment("$id:${index + 1}", due, row?.base ?: view.baseOn(due), if (paid) row?.paidOn else null,
                if (paid) row?.late ?: 0 else 0)
        }
        val contribution = contributions[id] ?: 0L
        return SimpleRecord(view.row, Loan(id, view.client, view.principal, view.ratePercent, view.months, view.guarantee,
            view.firstDue.minusDays(1), if (contribution > 0) Funding.EXTERNAL else Funding.CAPITAL, contribution, installments),
            view.dailyLate, false, view.termsRevision, unverified,view.advances)
    }
    fun json(subject: String, book: String): JSONObject {
        val s = data.summary
        val summary = listOf(s.year, s.cutoff.toString(), s.initial, s.opening, s.contribution, s.disbursed, s.receipts,
            s.interest, s.late, s.recovered, s.cash, s.pendingCapital, s.assumedContribution)
        return JSONObject().put("title", data.title).put("summary", JSONArray(summary)).put("syncedAt", data.syncedAt.toString())
            .put("catalog", encodeLocalRows(catalog.map { SheetPayment(it, it.firstDue, it.first, true, 0) }, subject, book))
            .put("rows", encodeLocalRows(rows, subject, book)).put("contributions", JSONObject(contributions))
            .put("cashEventTimes", encodeCashTimes(cashEventTimes)).put("movements", JSONArray(movements.map { it.json() })).put("actualDates", actualDates).put("cash", JSONArray(cash.map { JSONArray(listOf(it.date.toString(), it.contribution,
                it.receipts, it.disbursed, it.cash, it.assumedContribution)) }))
    }
}
internal fun encodeLocalRows(rows: List<SheetPayment>, subject: String, book: String) = draftPacket(subject, book,
    rows.associate { it.id to (it to PaymentEdit(it.loan.id, it.id, 0, it.unpaid, if (it.unpaid) 0 else it.late, it.paidOn)) })
internal fun decodeLocalRows(packet: JSONObject, subject: String, book: String) = decodeDraft(packet, subject, book).values.map { it.first }
internal fun localBookView(packet: JSONObject, subject: String, book: String): LocalBookView {
    val s = packet.getJSONArray("summary")
    val summary = SheetSummary(s.getInt(0), LocalDate.parse(s.getString(1)), s.getLong(2), s.getLong(3), s.getLong(4),
        s.getLong(5), s.getLong(6), s.getLong(7), s.getLong(8), s.getLong(9), s.getLong(10), s.getLong(11), s.optLong(12))
    val catalog = decodeLocalRows(packet.getJSONObject("catalog"), subject, book).map { it.loan }
    val rows = decodeLocalRows(packet.getJSONObject("rows"), subject, book)
    val cash = packet.getJSONArray("cash").let { values -> List(values.length()) { i -> val r = values.getJSONArray(i)
        BookCashDay(LocalDate.parse(r.getString(0)), r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.optLong(5)) } }
    val contributions = packet.getJSONObject("contributions").let { obj -> obj.keys().asSequence().associateWith { obj.getLong(it) } }
    return LocalBookView(SheetsPreload(packet.getString("title"), summary, catalog.filter { it.year == summary.year },
        rows.filter { it.due == today() }, Instant.parse(packet.getString("syncedAt"))), catalog, rows, cash, contributions, packet.optBoolean("actualDates"), cashMovements(packet.optJSONArray("movements")), decodeCashTimes(packet.optJSONObject("cashEventTimes")))
}

private fun stableTerms(loan: SheetLoan) = paymentTerms(loan.copy(id = "2026-001", rawId = "1", row = 6, termsRevision = "",
    scheduledBases = loanDates(loan).associateWith(loan::baseOn)))
private fun stableLoan(loan: SheetLoan) = listOf(stableTerms(loan), loan.client, loan.guarantee, loan.paid, loan.collected).joinToString("|")
private fun samePayment(a: SheetPayment, b: SheetPayment) = stableTerms(a.loan) == stableTerms(b.loan) &&
    a.due == b.due && a.unpaid == b.unpaid && (a.unpaid || a.late == b.late && a.paidOn == b.paidOn)

private fun LocalBookView.partialPayment(row: SheetPayment, unpaid: Boolean, fee: Long, date: LocalDate?, operation: String,
    book: String, day: LocalDate): LocalBookView {
    val edit = PaymentEdit(row.loan.id, row.id, 0, unpaid, fee, date)
    val changes = mapOf(row.id to (row to edit))
    val loan = localLoanView(loan(row.loan.id), changes, day)
    val catalog = catalog.map { if (it.id == loan.id) loan else it }
    val rows = rows.map { old ->
        val updated = if (old.id == row.id) old.copy(unpaid = unpaid, late = if (unpaid) loan.lateAt(old.due, day) else fee,
            paidOn = if (unpaid) null else date, bookAmount = if (unpaid) 0 else old.base + fee) else old
        if (old.loan.id == loan.id) updated.copy(loan = loan).let { payment -> payment.copy(version =
            directPaymentVersion(book, payment, if (payment.unpaid) 0 else payment.late, operation).toJson().toString()) } else old
    }
    val summary = data.summary.copy(cash = data.summary.cash + localReceiptDelta(changes),
        receipts = data.summary.receipts + localReceiptDelta(changes), late = data.summary.late +
            (if (unpaid) 0 else fee) - (if (row.unpaid) 0 else row.late))
    return copy(data = data.copy(summary = summary, loans = catalog.filter { it.year == summary.year }, todayPayments = rows.filter { it.due == day }),
        catalog = catalog, rows = rows, cash = localCashRows(cash, changes, actualDates, day))
}

private data class LocalTotals(val contribution: Long = 0, val disbursed: Long = 0, val receipts: Long = 0,
    val interest: Long = 0, val late: Long = 0, val recovered: Long = 0, val pending: Long = 0)
private fun SimpleRecord.totals(day: LocalDate): LocalTotals {
    if (deleted || loan.disbursed > day) return LocalTotals()
    val paid = loan.installments.filter { isPaid(it, day) }; val base = paid.sumOf { it.base }
    val interest = loan.proportionalInterest(base); val late = paid.sumOf { it.collectedLate }
    return LocalTotals(loan.contribution, loan.principal, base + late, interest, late, base - interest, loan.principal - base + interest)
}
private fun LocalBookView.replaceRecord(before: SimpleRecord?, after: SimpleRecord?, book: String, day: LocalDate): LocalBookView {
    val old = before?.totals(day) ?: LocalTotals(); val new = after?.totals(day) ?: LocalTotals()
    val id = (after ?: before)!!.loan.id
    val catalog = catalog.filterNot { it.id == id } + listOfNotNull(after?.takeUnless { it.deleted }?.view(day))
    val freshRows = after?.takeUnless { it.deleted }?.let { record ->
        val model = SimpleBook(data.title, 0, record.revision, record.loan.installments.last().due, listOf(record), data.syncedAt)
        record.loan.installments.map { model.payment(record, it, day, book) }
    }.orEmpty()
    val rows = rows.filterNot { it.loan.id == id } + freshRows
    val s = data.summary
    val summary = s.copy(contribution = s.contribution + new.contribution - old.contribution,
        disbursed = s.disbursed + new.disbursed - old.disbursed, receipts = s.receipts + new.receipts - old.receipts,
        interest = s.interest + new.interest - old.interest, late = s.late + new.late - old.late,
        recovered = s.recovered + new.recovered - old.recovered, pendingCapital = s.pendingCapital + new.pending - old.pending,
        cash = s.cash + new.contribution - old.contribution - new.disbursed + old.disbursed + new.receipts - old.receipts)
    val flows = mutableMapOf<LocalDate, Triple<Long, Long, Long>>()
    fun flow(date: LocalDate, contribution: Long = 0, receipts: Long = 0, disbursed: Long = 0) {
        val oldFlow = flows[date] ?: Triple(0L, 0L, 0L)
        flows[date] = Triple(oldFlow.first + contribution, oldFlow.second + receipts, oldFlow.third + disbursed)
    }
    fun apply(record: SimpleRecord?, direction: Long) { if (record == null || record.deleted) return
        flow(record.loan.disbursed, contribution = direction * record.loan.contribution, disbursed = direction * record.loan.principal)
        record.loan.installments.filter { record.isPaid(it, day) }.forEach {
            flow(it.paidOn ?: it.due, receipts = direction * (it.base + it.collectedLate))
        }
    }
    apply(before, -1); apply(after, 1)
    var delta = 0L
    val adjustedCash = cash.map { row -> val f = flows[row.date] ?: Triple(0L, 0L, 0L)
        delta += f.first + f.second - f.third
        row.copy(contribution = row.contribution + f.first, receipts = row.receipts + f.second,
            disbursed = row.disbursed + f.third, cash = row.cash + delta) }
    val cash = if (SheetsIdentity.isSimpleBook(book) && actualDates) reconcileLegacyFunding(adjustedCash) else adjustedCash
    val assumed = if (SheetsIdentity.isSimpleBook(book) && actualDates && cash.isNotEmpty()) cash.sumOf { it.assumedContribution } else summary.assumedContribution
    val reconciled = summary.copy(cash = summary.cash + assumed - summary.assumedContribution,
        contribution = summary.contribution + assumed - summary.assumedContribution, assumedContribution = assumed)
    return copy(data = data.copy(summary = reconciled, loans = catalog.filter { it.year == summary.year }, todayPayments = rows.filter { it.due == day }),
        catalog = catalog, rows = rows, cash = cash, contributions = contributions + (id to (after?.loan?.contribution ?: 0)))
}

/** Pure projection. Only commands already committed to disk become visible to the UI. */
internal fun applyLocalOperation(view: LocalBookView, body: JSONObject, book: String, day: LocalDate = today()): LocalBookView {
    val op = body.getString("operationId")
    val after = when (body.getString("action")) {
        "cashMovement" -> {
            val movement = movementCommand(body, day)
            require(view.movements.none { it.id == movement.id }) { "Este movimiento ya está registrado" }
            val (summary, cash) = projectCashMovement(view.data.summary, view.cash, movement, day,
                SheetsIdentity.isSimpleBook(book) && view.actualDates)
            view.copy(data = view.data.copy(summary = summary), cash = cash, movements = view.movements + movement)
        }
        "amountPayment" -> {
            val before=view.record(body.getString("loanId"),day)
            val plan=planAmountPayment(before,body.getLong("amountCents"),amountPaymentDate(body,day),body.optBoolean("waiveLate"))
            require(plan.confirmation()==body.getString("confirmation")) { "El reparto del monto cambió; revísalo" }
            view.replaceRecord(before,plan.after.copy(revision=op),book,day)
        }
        "createLoan" -> {
            val date = LocalDate.parse(body.getString("disbursedOn")); previewDatedLoan(directLoanRequest(body.getJSONObject("loan")), date, day)
            val available = cashForNewLoan(view.cash, date, view.data.summary.cash)
            val request = Rules.fundLoan(directLoanRequest(body.getJSONObject("loan")), available)
            val id = body.getString("localLoanId")
            val loan = withInitialPastPayments(Rules.createLoan(id, request, date), day, body.optBoolean("pastPaymentsPaid"))
            view.replaceRecord(null, SimpleRecord((view.catalog.maxOfOrNull { it.row } ?: 1) + 1, loan, 100, false, op), book, day)
        }
        "setCapital" -> {
            val change = capitalChange(body); val summary = view.data.summary
            require(summary.initial == change.before && summary.cash == change.cashBefore) { "El capital cambió; revisa el importe" }
            val cash = view.cash.map { it.copy(cash = it.cash + change.difference) }.let {
                if (SheetsIdentity.isSimpleBook(book) && view.actualDates) reconcileLegacyFunding(it) else it }
            val assumed = if (SheetsIdentity.isSimpleBook(book) && view.actualDates && cash.isNotEmpty()) cash.sumOf { it.assumedContribution } else summary.assumedContribution
            view.copy(data = view.data.copy(summary = summary.copy(initial = change.after, opening = summary.opening + change.difference,
                cash = change.cashAfter + assumed - summary.assumedContribution, contribution = summary.contribution + assumed - summary.assumedContribution,
                assumedContribution = assumed)), cash = cash)
        }
        "pay" -> {
            var current = view
            val edits = body.getJSONArray("edits")
            for (i in 0 until edits.length()) {
                val edit = edits.getJSONObject(i); val version = directPaymentVersion(edit.getJSONObject("version"))
                val payment = current.rows.single { it.loan.id == version.loanId && it.due == version.due }
                val date = edit.optString("paidOn").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                val unpaid = edit.getBoolean("unpaid"); val late = sheetCents(edit.get("collectedLate"))
                validateRemotePaymentEdit(payment, unpaid, late, day, date)
                val sourceLoan = current.loan(version.loanId)
                val knownDates = current.rows.filter { it.loan.id == sourceLoan.id }.map { it.due }.toSet()
                if (sourceLoan.paid > 0 && loanDates(sourceLoan).any { it <= day && it !in knownDates }) {
                    current = current.partialPayment(payment, unpaid, late, date, op, book, day)
                    continue
                }
                val before = current.record(version.loanId, day)
                val after = before.copy(loan = before.loan.copy(installments = before.loan.installments.map {
                    if (it.due == version.due) it.copy(paidOn = if (unpaid) null else date, collectedLate = if (unpaid) 0 else late) else it
                }), revision = op, unverifiedPaid = before.unverifiedPaid - version.due,
                    advances=before.advances.filterNot { unpaid && it.source==version.due })
                current = current.replaceRecord(before, after, book, day)
            }
            current
        }
        "deleteLoan" -> {
            require(body.getString("loanId") in view.contributions) { "Actualiza una vez el libro para descargar los aportes de este préstamo" }
            val before = view.record(body.getString("loanId"), day)
            view.replaceRecord(before, before.copy(deleted = true, revision = op), book, day)
        }
        "editLoanText" -> {
            val id = body.getString("loanId"); val loan = view.loan(id)
            val after = loan.copy(client = body.getString("client"), guarantee = body.getString("guarantee"))
            require(after.client.isNotBlank()) { "Ingresa el nombre del cliente" }
            val catalog = view.catalog.map { if (it.id == id) after else it }
            val rows = view.rows.map { if (it.loan.id == id) it.copy(loan = after) else it }
            view.copy(catalog = catalog, rows = rows, data = view.data.copy(loans = catalog.filter { it.year == view.data.summary.year }, todayPayments = rows.filter { it.due == day }))
        }
        "editLoanTerms" -> {
            val before = view.record(body.getString("loanId"), day); val old = view.loan(before.loan.id)
            require(before.unverifiedPaid.none { it <= day }) { "Confirma la fecha real de las cuotas cobradas antes de modificar el acuerdo" }
            val input = loanTermsInput(body.getJSONObject("terms"))
            val plan = planLoanTerms(old, view.calendar(old.id, day), input, day, view.data.summary.cash)
            require(before.advances.all { it.source in plan.bases && it.target in plan.bases }) { "Conserva las fechas de cuotas con abonos" }
            val installments = before.loan.installments.associateBy { it.due }
            val after = before.copy(loan = before.loan.copy(client = input.client, guarantee = input.guarantee, principal = input.principal,
                ratePercent = input.rate, months = input.months, disbursed = input.disbursedOn,
                installments = plan.bases.entries.mapIndexed { index, (date, base) -> val paid = installments[date]?.takeIf { it.paid(day) }
                    Installment("${old.id}:${index + 1}", date, base, paid?.paidOn, paid?.collectedLate ?: 0) }), dailyLate = input.dailyLate, revision = op)
            view.replaceRecord(before, after, book, day)
        }
        else -> error("Operación local no admitida")
    }
    return after.copy(cashEventTimes = localCashTimes(view, after, body))
}

internal data class LocalOperation(val body: JSONObject, val expectedLoans: List<SheetLoan>, val expectedRows: List<SheetPayment>,
    val cashBefore: Long, val initialBefore: Long, val prepared: PendingRemoteWrite? = null) {
    val id: String get() = body.getString("operationId")
}
internal data class LocalOperationState(val view: LocalBookView?, val operations: List<LocalOperation>, val aliases: Map<String, String>, val conflict: String? = null, val remoteBase: LocalBookView? = null)

internal class LocalOperations(private val file: SheetCache, val subject: String, val book: String) {
    fun read(): LocalOperationState = synchronized(lock) {
        val packet = file.read() ?: return LocalOperationState(null, emptyList(), emptyMap())
        require(packet.getString("subject") == subject && packet.getString("book") == book) { "Datos locales de otra cuenta o libro" }
        val operations = packet.getJSONArray("operations").let { values -> List(values.length()) { i -> val p = values.getJSONObject(i)
            LocalOperation(p.getJSONObject("body"), decodeLocalRows(p.getJSONObject("loans"), subject, book).map { it.loan },
                decodeLocalRows(p.getJSONObject("rows"), subject, book), p.getLong("cashBefore"), p.getLong("initialBefore"),
                p.optString("prepared").takeIf { it.isNotBlank() }?.let { PendingRemoteWrite(p.getJSONObject("body").getString("operationId"), subject, book, it) }) } }
        val aliases = packet.getJSONObject("aliases").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        LocalOperationState(localBookView(packet.getJSONObject("view"), subject, book), operations, aliases, packet.optString("conflict").takeIf { it.isNotBlank() }, packet.optJSONObject("remoteBase")?.let { localBookView(it, subject, book) })
    }
    private fun write(state: LocalOperationState) {
        file.write(JSONObject().put("subject", subject).put("book", book).put("view", requireNotNull(state.view).json(subject, book))
            .put("aliases", JSONObject(state.aliases)).put("conflict", state.conflict ?: "").put("remoteBase", state.remoteBase?.json(subject, book)).put("operations", JSONArray(state.operations.map { entry ->
                JSONObject().put("body", entry.body).put("cashBefore", entry.cashBefore).put("initialBefore", entry.initialBefore)
                    .put("loans", encodeLocalRows(entry.expectedLoans.map { SheetPayment(it, it.firstDue, it.first, true, 0) }, subject, book))
                    .put("rows", encodeLocalRows(entry.expectedRows, subject, book)).put("prepared", entry.prepared?.payload ?: "")
            })))
    }
    fun refresh(view: LocalBookView) = synchronized(lock) {
        val state = read()
        if (state.operations.isEmpty()) write(state.copy(view = view, conflict = null))
    }
    fun enqueue(input: JSONObject, actor: String, fallback: LocalBookView? = null): LocalOperation = synchronized(lock) {
        val initial = read()
        var state = initial
        val allEdits = input.optJSONArray("edits")
        val inputs = if (input.optString("action") == "deleteLoans") {
            val selected = input.getJSONArray("loans")
            require(selected.length() in 1..400) { "Selecciona los préstamos que deseas eliminar" }
            val ids = List(selected.length()) { selected.getJSONObject(it).getString("loanId") }
            require(ids.distinct().size == ids.size) { "Hay préstamos repetidos en la selección" }
            List(selected.length()) { index -> JSONObject(selected.getJSONObject(index).toString())
                .put("action", "deleteLoan").put("operationId", if (index == 0) input.optString("operationId").ifBlank { UUID.randomUUID().toString() } else UUID.randomUUID().toString()) }
        } else if (input.optString("action") == "groupLoans") {
            val current = initial.view ?: fallback ?: error("Descarga los préstamos una vez antes de agruparlos")
            val ids = input.getJSONArray("loanIds").let { values -> List(values.length()) {
                val id = values.getString(it); initial.aliases[id] ?: id
            } }
            val plan = planLoanGrouping(current.catalog, ids, input.getString("client"), today())
            // Enqueue every rename in a single disk commit. Existing text edits supply durable retries and ID remapping.
            plan.changes.mapIndexed { index, loan -> JSONObject().put("action", "editLoanText")
                .put("operationId", if (index == 0) input.optString("operationId").ifBlank { UUID.randomUUID().toString() } else UUID.randomUUID().toString())
                .put("loanId", loan.id).put("client", plan.client).put("guarantee", loan.guarantee)
                .put("groupLoanIds", JSONArray(plan.loans.map { it.id })) }
        } else if (input.optString("action") == "pay" && allEdits != null && allEdits.length() > 100)
            (0 until allEdits.length()).chunked(100).mapIndexed { index, indexes -> JSONObject(input.toString())
                .put("operationId", if (index == 0) input.getString("operationId") else UUID.randomUUID().toString())
                .put("edits", JSONArray(indexes.map { allEdits.getJSONObject(it) })) }
            else listOf(input)
        val added = mutableListOf<LocalOperation>()
        for (part in inputs) {
            val view = state.view ?: fallback ?: error("Conecta Google una vez para descargar tus datos")
            val body = JSONObject(part.toString()).put("bookId", book).put("operationId", part.optString("operationId").ifBlank { UUID.randomUUID().toString() })
                .put("actor", actor).put("transport", "sheets")
                .apply { if (!has("recordedAt")) put("recordedAt", Instant.now().toString()) }
            if(body.getString("action")=="amountPayment" && !body.has("paidOn"))body.put("paidOn",today().toString())
            if (body.getString("action") == "createLoan") {
                val year = LocalDate.parse(body.getString("disbursedOn")).year
                val number = maxOf(1_000_000, view.catalog.filter { it.id.startsWith("$year-") }.maxOfOrNull { it.id.substringAfter('-').toInt() } ?: 0) + 1
                body.put("localLoanId", "$year-${number.toString().padStart(3, '0')}")
                val available = cashForNewLoan(view.cash, LocalDate.parse(body.getString("disbursedOn")), view.data.summary.cash)
                val funded = Rules.fundLoan(directLoanRequest(body.getJSONObject("loan")), available)
                require(!body.has("maxContributionCents") || funded.contribution <= body.getLong("maxContributionCents")) { "La caja cambió. Revisa el aporte antes de registrar el préstamo" }
                body.getJSONObject("loan").put("contribution", funded.contribution / 100.0)
                    .put("origin", if (funded.contribution > 0) "Capital nuevo" else "Del capital")
                body.put("fundingCashBeforeCents", available).put("maxContributionCents", funded.contribution)
            }
            val expectedRows = body.optJSONArray("edits")?.let { edits -> List(edits.length()) { i ->
                val version = directPaymentVersion(edits.getJSONObject(i).getJSONObject("version"))
                view.rows.single { it.loan.id == version.loanId && it.due == version.due }.also { row ->
                    val ownChange = row.loan.termsRevision in state.operations.map { it.id }
                    require(ownChange || version.terms == paymentTerms(row.loan) && version.unpaid == row.unpaid &&
                        version.collectedLate == (if (row.unpaid) 0L else row.late) && version.paidOn == row.paidOn) { "La cuota cambió. Revisa su estado antes de guardar" }
                }
            } }.orEmpty()
            val expectedLoans = body.optString("loanId").takeIf { it.isNotBlank() }?.let { listOf(view.loan(it)) }.orEmpty()
            if (body.getString("action") in listOf("deleteLoan", "editLoanTerms", "amountPayment") && body.has("version")) {
                require(loanDeletionVersion(expectedLoans.single()) == body.getString("version")) { "El préstamo cambió. Revisa de nuevo los datos" }
            }
            if (body.getString("action") == "editLoanTerms" && body.has("confirmation")) {
                val loan = expectedLoans.single()
                require(planLoanTerms(loan, view.calendar(loan.id), loanTermsInput(body.getJSONObject("terms")), today(), view.data.summary.cash).confirmation() == body.getString("confirmation")) {
                    "La vista previa cambió. Revisa otra vez el acuerdo"
                }
            }
            val operation = LocalOperation(body, expectedLoans, expectedRows, view.data.summary.cash, view.data.summary.initial)
            val after = applyLocalOperation(view, body, book)
            state = state.copy(view = after, operations = state.operations + operation)
            added += operation
        }
        write(state)
        added.first()
    }
    /** Freeze the exact network payload before the first send; retries never regenerate it. */
    fun prepare(fresh: LocalBookView, repository: SheetsRepository): PendingRemoteWrite? =
        prepare(fresh) { loan -> repository.calendar(loan, refresh = true) }

    fun prepare(fresh: LocalBookView, calendar: (SheetLoan) -> LoanCalendar): PendingRemoteWrite? {
        val state = read(); if (state.conflict != null) return null
        val entry = state.operations.firstOrNull() ?: return null
        entry.prepared?.let { return it }
        val body = JSONObject(entry.body.toString())
        fun resolve(id: String) = state.aliases[id] ?: id
        try {
            when (body.getString("action")) {
                "cashMovement" -> validateCashMovement(movementCommand(body), fresh.cash, fresh.data.summary.cash, today())
                "createLoan" -> {
                    val date = LocalDate.parse(body.getString("disbursedOn"))
                    val actual = cashForNewLoan(fresh.cash, date, fresh.data.summary.cash)
                    val input = directLoanRequest(body.getJSONObject("loan"))
                    require(Rules.fundLoan(input, actual).contribution <= body.getLong("maxContributionCents")) { "La caja cambió y este préstamo requiere un aporte mayor al confirmado" }
                }
                "setCapital" -> require(fresh.data.summary.initial == entry.initialBefore && fresh.data.summary.cash == entry.cashBefore) { "El capital o la caja cambiaron en la hoja" }
                "deleteLoan", "editLoanTerms", "editLoanText", "amountPayment" -> {
                    val expected = entry.expectedLoans.single(); val loan = fresh.loan(resolve(expected.id))
                    if (body.getString("action") == "editLoanText") {
                        body.optJSONArray("groupLoanIds")?.let { values ->
                            val groupedIds = List(values.length()) { resolve(values.getString(it)) }.toSet()
                            require(fresh.catalog.none { it.id !in groupedIds && normalizedClient(it.client) == normalizedClient(body.getString("client")) }) {
                                "El nombre elegido ahora tiene otros préstamos en Google. Revisa la agrupación antes de sincronizar"
                            }
                            body.put("groupLoanIds", JSONArray(groupedIds.toList()))
                        }
                        val edit = LoanTextEdit(loan.id, expected.client, expected.guarantee, body.getString("client"), body.getString("guarantee"))
                        edit.fields.forEach { field ->
                            val current = if (field.columnIndex == 1) loan.client else loan.guarantee
                            require(current == field.before || current == field.after) { "El nombre o la garantía cambiaron en la hoja" }
                        }
                    } else require(stableLoan(loan) == stableLoan(expected)) { "El préstamo cambió en la hoja" }
                    body.put("loanId", loan.id).put("year", loan.year).put("version", loanDeletionVersion(loan))
                    if (body.getString("action") == "editLoanTerms") {
                        val plan = planLoanTerms(loan, calendar(loan), loanTermsInput(body.getJSONObject("terms")), today(), fresh.data.summary.cash)
                        body.put("confirmation", plan.confirmation())
                    }
                    if(body.getString("action")=="amountPayment") {
                        val plan=planAmountPayment(recordForAmount(loan,calendar(loan),today()),body.getLong("amountCents"),amountPaymentDate(body,today()),body.optBoolean("waiveLate"))
                        body.put("confirmation",plan.confirmation())
                    }
                }
                "pay" -> {
                    val edits = body.getJSONArray("edits")
                    val calendars = mutableMapOf<String, LoanCalendar>()
                    entry.expectedRows.forEachIndexed { i, expected ->
                        val loan = fresh.loan(resolve(expected.loan.id))
                        val current = calendars.getOrPut(loan.id) { calendar(loan) }.days.single { it.date == expected.due }.payment
                            ?: error("La cuota ya no está disponible")
                        require(samePayment(expected, current)) { "La cuota cambió en la hoja" }
                        edits.getJSONObject(i).put("version", JSONObject(requireNotNull(current.version)))
                    }
                }
            }
            val pending = PendingRemoteWrite(entry.id, subject, book, body.toString())
            return synchronized(lock) {
                val current = read(); val head = current.operations.firstOrNull() ?: return@synchronized null
                check(head.id == entry.id)
                head.prepared ?: pending.also { write(current.copy(remoteBase = fresh, operations = listOf(head.copy(prepared = it)) + current.operations.drop(1))) }
            }
        } catch (failure: IllegalArgumentException) {
            synchronized(lock) { val current = read(); write(current.copy(remoteBase = fresh, conflict = failure.message ?: "Revisa el cambio local")) }
            throw failure
        }
    }
    fun confirmed(pending: PendingRemoteWrite, receipt: JSONObject, fresh: LocalBookView) = synchronized(lock) {
        val state = read(); val entry = state.operations.first()
        require(entry.prepared == pending)
        var aliases = state.aliases
        if (entry.body.getString("action") == "createLoan") aliases = aliases + (entry.body.getString("localLoanId") to receipt.getString("loanId"))
        var view = fresh
        val remaining = state.operations.drop(1).map { old ->
            val body = JSONObject(old.body.toString())
            body.optString("loanId").takeIf { it in aliases }?.let { body.put("loanId", aliases.getValue(it)) }
            body.optJSONArray("edits")?.let { edits -> for (i in 0 until edits.length()) {
                val v = edits.getJSONObject(i).getJSONObject("version"); val id = v.getString("loanId")
                if (id in aliases) v.put("loanId", aliases.getValue(id))
            } }
            val next = old.copy(body = body)
            view = applyLocalOperation(view, body, book)
            next
        }
        write(LocalOperationState(view, remaining, aliases, remoteBase = fresh))
    }
    fun reject(message: String) = synchronized(lock) { write(read().copy(conflict = message)) }
    fun discardRejected() = synchronized(lock) {
        val state = read(); require(state.conflict != null)
        var view = requireNotNull(state.remoteBase) { "Conecta para consultar los datos de la hoja" }
        val remaining = state.operations.drop(1)
        remaining.forEach { entry -> view = applyLocalOperation(view, entry.body, book) }
        write(state.copy(view = view, operations = remaining, conflict = null))
    }
    fun resolveId(id: String) = read().aliases[id] ?: id
    companion object { private val lock = Any() }
}

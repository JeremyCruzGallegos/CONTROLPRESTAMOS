package com.example.controlprestamos.data

import com.example.controlprestamos.domain.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal
import java.security.MessageDigest

interface LoanRepository {
    fun read(): Snapshot
    fun refresh(): Snapshot
    fun create(transactionId: String, request: LoanRequest): Snapshot
    fun pay(transactionId: String, edits: List<PaymentEdit>): Snapshot
}
interface SnapshotStore {
    fun load(): Snapshot?
    fun save(snapshot: Snapshot)
}

/** Demo transactions commit the snapshot, retry keys and audit together. */
class DemoLoanRepository(private val store: SnapshotStore, private val clock: Clock = Clock.system(LIMA)) : LoanRepository {
    private var state = store.load() ?: demo(today(clock)).also { store.save(it) }
    @Synchronized override fun read() = state
    @Synchronized override fun refresh() = commit(state.copy(refreshedAt = Instant.now(clock)))
    private fun commit(next: Snapshot): Snapshot {
        store.save(next) // A failed disk write leaves the previous state intact.
        state = next
        return next
    }
    private fun fingerprint(payload: String) = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun alreadyApplied(key: String, hash: String): Boolean {
        require(key.isNotBlank()) { "Falta la identidad de la operación" }
        val previous = state.transactions[key] ?: return false
        require(previous == hash) { "La identidad de la operación ya se usó para otros cambios" }
        return true
    }
    @Synchronized override fun create(transactionId: String, request: LoanRequest): Snapshot {
        val hash = fingerprint("create|$request")
        if (alreadyApplied(transactionId, hash)) return state
        val day = today(clock)
        val loan = Rules.createLoan("DEMO-$transactionId", Rules.fundLoan(request, Rules.summary(state, day).cash), day)
        require(Rules.cashAfter(Rules.summary(state, day).cash, loan.principal, loan.contribution) >= 0) { "Fondos insuficientes" }
        return commit(state.copy(loans = state.loans + loan,
            transactions = state.transactions + (transactionId to hash),
            audits = state.audits + Audit(transactionId, Instant.now(clock),
                "Alta de ${loan.client} · ${soles(loan.principal)} · aporte ${soles(loan.contribution)} · ${loan.id}")))
    }
    @Synchronized override fun pay(transactionId: String, edits: List<PaymentEdit>): Snapshot {
        require(edits.isNotEmpty()) { "No hay cambios" }
        require(edits.map { it.installmentId }.distinct().size == edits.size) { "Cuotas repetidas en el lote" }
        val ordered = edits.sortedBy { it.installmentId }
        val hash = fingerprint("pay|$ordered")
        if (alreadyApplied(transactionId, hash)) return state
        val day = today(clock)
        // Validate the entire batch before replacing any installment.
        ordered.forEach { edit ->
            val loan = state.loans.singleOrNull { it.id == edit.loanId } ?: error("Préstamo inexistente")
            val cuota = loan.installments.singleOrNull { it.id == edit.installmentId } ?: error("Cuota inexistente")
            require(cuota.revision == edit.expectedRevision) { "Conflicto: otra edición cambió una cuota. Actualiza y revisa el lote." }
            require(cuota.due <= day) { "No se puede cobrar una cuota futura" }
            require(edit.collectedLate in 0..cuota.maximumLate(day)) { "Revisa la mora cobrada" }
            require(!edit.unpaid || edit.collectedLate == 0L) { "Una cuota impaga no tiene mora cobrada" }
            require(edit.unpaid != !cuota.paid(day) || edit.collectedLate != cuota.collectedLate) { "La cuota no tiene cambios" }
        }
        val indexed = ordered.associateBy { it.installmentId }
        val nextLoans = state.loans.map { loan -> loan.copy(installments = loan.installments.map { cuota ->
            val edit = indexed[cuota.id]
            if (edit == null) cuota else cuota.copy(paidOn = if (edit.unpaid) null else cuota.paidOn ?: day,
                collectedLate = if (edit.unpaid) 0 else edit.collectedLate, revision = cuota.revision + 1)
        }) }
        val audits = ordered.map { edit ->
            val loan = state.loans.single { it.id == edit.loanId }
            val cuota = loan.installments.single { it.id == edit.installmentId }
            Audit(transactionId, Instant.now(clock), "${loan.client} · ${dateLabel(cuota.due)} · " +
                "${if (cuota.paid(day)) "Pagó" else "No pagó"} → ${if (edit.unpaid) "No pagó" else "Pagó"} · " +
                "base ${soles(cuota.base)} · mora ${soles(cuota.collectedLate)} → ${soles(edit.collectedLate)} · revisión ${cuota.revision + 1}")
        }
        return commit(state.copy(loans = nextLoans, audits = state.audits + audits,
            transactions = state.transactions + (transactionId to hash)))
    }
}

fun demo(day: LocalDate): Snapshot {
    fun loan(id: String, name: String, amount: Long, start: LocalDate, paid: Int, months: Int = 1): Loan =
        Rules.createLoan(id, LoanRequest(name, amount, BigDecimal("2"), months,
            "Garantía de demostración", Funding.CAPITAL, 0), start).let { loan ->
            loan.copy(installments = loan.installments.mapIndexed { index, cuota ->
                if (index < paid && cuota.due <= day) cuota.copy(paidOn = cuota.due) else cuota
            })
        }
    return Snapshot(listOf(
        loan("DEMO-ANA-1", "Ana Torres", 90_000, day.minusDays(12), 8),
        loan("DEMO-LUIS", "Luis Ramos", 150_000, day.minusDays(5), 3),
        loan("DEMO-ANA-2", "Ana Torres", 50_000, day.minusDays(65), 30),
        loan("DEMO-TRASLADO", "María Flores", 80_000, LocalDate.of(day.year - 1, 12, 20), 4, 2)
    ))
}

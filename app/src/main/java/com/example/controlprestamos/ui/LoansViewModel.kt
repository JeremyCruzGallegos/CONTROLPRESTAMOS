package com.example.controlprestamos.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.example.controlprestamos.data.*
import com.example.controlprestamos.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

 data class AppState(val snapshot: Snapshot? = null, val busy: Boolean = false,
    val error: String? = null, val notice: String? = null, val day: LocalDate = today(),
    val edits: Map<String, PaymentEdit> = emptyMap())

class LoansViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(AppState(busy = true))
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var repository: LoanRepository? = null
    private var paymentKey = UUID.randomUUID().toString()
    init { load() }
    fun load() {
        if (repository != null) { refresh(); return }
        mutable.value = mutable.value.copy(busy = true, error = null)
        executor.execute {
            val result = runCatching { DemoLoanRepository(JsonSnapshotStore(File(getApplication<Application>().filesDir, "demo-v1.json"))) }
            main.post {
                result.fold({ repository = it; mutable.value = mutable.value.copy(snapshot = it.read(), busy = false, day = today()) },
                    { mutable.value = mutable.value.copy(busy = false, error = "No se pudo abrir la caché: ${it.message}") })
            }
        }
    }
    private fun runOperation(message: String, action: (LoanRepository) -> Snapshot, onSuccess: () -> Unit = {}) {
        if (mutable.value.busy) return
        val repo = repository ?: return load()
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        executor.execute {
            val result = runCatching { action(repo) }
            main.post {
                result.fold({ snapshot ->
                    mutable.value = mutable.value.copy(snapshot = snapshot, busy = false, notice = message, day = today())
                    onSuccess()
                }, { mutable.value = mutable.value.copy(busy = false, error = it.message ?: "Error al guardar. Puedes reintentar.") })
            }
        }
    }
    fun refresh() = runOperation("Datos de demostración actualizados", { it.refresh() })
    fun tick() { if (mutable.value.day != today()) mutable.value = mutable.value.copy(day = today()) }
    fun create(key: String, request: LoanRequest, onSuccess: () -> Unit) =
        runOperation("Préstamo guardado en demostración." + if (request.contribution > 0) " Se ingresaron ${soles(request.contribution)} a caja como aporte de capital." else "", { it.create(key, request) }, onSuccess)
    fun stage(loan: Loan, installment: Installment, unpaid: Boolean, late: Long? = null) {
        if (mutable.value.busy || installment.due > mutable.value.day) return
        val day = mutable.value.day
        val old = mutable.value.edits[installment.id]
        val amount = if (unpaid) 0 else late ?: installment.late(day)
        val edit = PaymentEdit(loan.id, installment.id, old?.expectedRevision ?: installment.revision, unpaid, amount)
        val unchanged = unpaid == !installment.paid(day) && amount == installment.collectedLate
        mutable.value = mutable.value.copy(edits = if (unchanged) mutable.value.edits - installment.id else mutable.value.edits + (installment.id to edit), error = null)
        paymentKey = UUID.randomUUID().toString()
    }
    fun discard() {
        if (!mutable.value.busy) {
            mutable.value = mutable.value.copy(edits = emptyMap(), error = null)
            paymentKey = UUID.randomUUID().toString()
        }
    }
    fun savePayments(onSuccess: () -> Unit) {
        val edits = mutable.value.edits.values.toList()
        runOperation("${edits.size} cuotas actualizadas en demostración", { it.pay(paymentKey, edits) }) {
            discard(); onSuccess()
        }
    }
    override fun onCleared() { executor.shutdown(); super.onCleared() }
}

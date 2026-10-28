package com.example.controlprestamos.data

import android.util.AtomicFile
import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Only demo data; a separate file/schema will be required for authenticated remote data. */
class JsonSnapshotStore(file: File) : SnapshotStore {
    private val atomic = AtomicFile(file)
    override fun load(): Snapshot? {
        if (!atomic.baseFile.exists()) return null
        val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("version") == 1) { "Versión de caché no compatible" }
        val loans = root.getJSONArray("loans").objects().map { obj ->
            Loan(obj.getString("id"), obj.getString("client"), obj.getLong("principal"),
                BigDecimal(obj.getString("rate")), obj.getInt("months"), obj.getString("guarantee"),
                LocalDate.parse(obj.getString("disbursed")), Funding.valueOf(obj.getString("funding")),
                obj.getLong("contribution"), obj.getJSONArray("installments").objects().map { cuota ->
                    Installment(cuota.getString("id"), LocalDate.parse(cuota.getString("due")), cuota.getLong("base"),
                        cuota.optString("paidOn").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                        cuota.getLong("late"), cuota.getLong("revision"))
                })
        }
        val transactions = root.getJSONObject("transactions").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        return Snapshot(loans, root.getJSONArray("audits").objects().map {
            Audit(it.getString("transaction"), Instant.parse(it.getString("at")), it.getString("description"))
        }, transactions, root.optString("refreshedAt").takeIf { it.isNotEmpty() }?.let(Instant::parse))
    }
    override fun save(snapshot: Snapshot) {
        val root = JSONObject().put("version", 1).put("refreshedAt", snapshot.refreshedAt?.toString() ?: "")
            .put("transactions", JSONObject(snapshot.transactions))
            .put("audits", JSONArray(snapshot.audits.map {
                JSONObject().put("transaction", it.transactionId).put("at", it.at.toString()).put("description", it.description)
            }))
            .put("loans", JSONArray(snapshot.loans.map { loan ->
                JSONObject().put("id", loan.id).put("client", loan.client).put("principal", loan.principal)
                    .put("rate", loan.ratePercent.toPlainString()).put("months", loan.months).put("guarantee", loan.guarantee)
                    .put("disbursed", loan.disbursed.toString()).put("funding", loan.funding.name).put("contribution", loan.contribution)
                    .put("installments", JSONArray(loan.installments.map {
                        JSONObject().put("id", it.id).put("due", it.due.toString()).put("base", it.base)
                            .put("paidOn", it.paidOn?.toString() ?: "").put("late", it.collectedLate).put("revision", it.revision)
                    }))
            }))
        val stream = atomic.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}

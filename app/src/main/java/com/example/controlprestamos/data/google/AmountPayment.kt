package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class FeeAdvance(val source: LocalDate, val target: LocalDate, val amount: Long,
    val sourceRef: String = "", val sourcePaid: Boolean = true) {
    fun json() = JSONObject().put("source",source.toString()).put("target",target.toString()).put("amount",amount)
        .put("sourceRef",sourceRef).put("sourcePaid",sourcePaid)
}
internal fun feeAdvances(value: JSONArray?): List<FeeAdvance> = value?.let { array -> List(array.length()) { i ->
    val row=array.getJSONObject(i)
    FeeAdvance(LocalDate.parse(row.getString("source")),LocalDate.parse(row.getString("target")),row.getLong("amount"),
        row.optString("sourceRef"),row.optBoolean("sourcePaid",true)).also { require(it.amount>0 && it.target>it.source) }
} }.orEmpty()

internal fun simpleAdvanceFormula(row: Int, target: LocalDate, advances: List<FeeAdvance>): String? {
    val credits=advances.filter { it.target==target }
    return if(credits.isEmpty())null else "="+credits.joinToString("+") {
        "IF(${columnName(simpleDayColumn(it.source)+1)}$row=FALSE,${decimalMoney(it.amount)},0)"
    }
}
internal fun amountPaymentDate(body: JSONObject, asOf: LocalDate): LocalDate =
    LocalDate.parse(body.optString("paidOn").ifBlank { asOf.toString() }).also {
        require(it<=asOf) { "La fecha real del pago no puede ser futura" }
    }

internal data class AmountPaymentPlan(val before: SimpleRecord, val after: SimpleRecord, val amount: Long,
    val paidDates: List<LocalDate>, val partialDate: LocalDate?, val partialAmount: Long, val day: LocalDate) {
    fun confirmation() = directPaymentHash(listOf(before.revision,amount,paidDates,partialDate,partialAmount,day,
        after.loan.installments.map { listOf(it.due,it.base,it.paidOn,it.collectedLate) },after.advances.map { listOf(it.source,it.target,it.amount) }).toString())
}

/** Move prepaid base to the last collected installment so receipts and the contract total stay exact.
 * A fee advance is charged once there and deducted from the next installment's pending fee.
 */
internal fun planAmountPayment(record: SimpleRecord, amount: Long, day: LocalDate, waiveLate: Boolean = false): AmountPaymentPlan {
    require(!record.deleted && amount>0) { "Ingresa un monto mayor que cero" }
    require(record.unverifiedPaid.none { it<=day }) { "Confirma las fechas reales de los cobros anteriores" }
    val view=record.view(day)
    val pending=record.loan.installments.filter { it.due<=day && !record.isPaid(it,day) }.sortedBy { it.due }
    require(pending.isNotEmpty()) { "No hay cuotas vencidas pendientes para este monto" }
    fun fee(due:LocalDate)=if(waiveLate)0L else view.lateAt(due,day)
    val maximum=pending.sumOf { it.base+fee(it.due) }
    require(amount<=maximum) { "El monto supera las cuotas vencidas: máximo ${soles(maximum)}" }
    var rest=amount;val paid=mutableListOf<LocalDate>();var partial:LocalDate?=null;var partialAmount=0L
    val updated=record.loan.installments.associateBy { it.due }.toMutableMap()
    val advances=record.advances.toMutableList()
    for(item in pending) {
        val dueFee=fee(item.due);val total=item.base+dueFee
        if(rest>=total) {
            updated[item.due]=item.copy(paidOn=day,collectedLate=dueFee);paid+=item.due;rest-=total
        } else {
            if(rest>0) {
                require(paid.isNotEmpty()) { "El monto debe cubrir la primera cuota con la mora elegida. Puedes elegir Sin mora." }
                partial=item.due;partialAmount=rest
                val feeCredit=minOf(rest,dueFee);val baseCredit=rest-feeCredit
                require(baseCredit<item.base)
                updated[item.due]=item.copy(base=item.base-baseCredit)
                val anchor=updated.getValue(paid.last())
                updated[anchor.due]=anchor.copy(base=anchor.base+baseCredit,collectedLate=anchor.collectedLate+feeCredit)
                if(feeCredit>0)advances+=FeeAdvance(anchor.due,item.due,feeCredit)
                rest=0
            }
            break
        }
    }
    require(rest==0L && paid.isNotEmpty())
    val after=record.copy(loan=record.loan.copy(installments=record.loan.installments.map { updated.getValue(it.due) }),advances=advances)
    check(after.loan.agreed==record.loan.agreed)
    check(after.loan.receipts(day)-record.loan.receipts(day)==amount)
    return AmountPaymentPlan(record,after,amount,paid,partial,partialAmount,day)
}

internal fun recordForAmount(loan: SheetLoan, calendar: LoanCalendar, day: LocalDate): SimpleRecord {
    require(calendar.loanId==loan.id && calendar.days.map { it.date }==loanDates(loan)) { "Carga el calendario completo del préstamo" }
    val unpaidDates=mutableSetOf<LocalDate>()
    val installments=calendar.days.mapIndexed { index,d ->
        val row=d.payment
        require(d.date>day || row!=null) { "Faltan cuotas por descargar" }
        if(row?.unpaid==false && row.paidOn==null)unpaidDates+=d.date
        Installment("${loan.id}:${index+1}",d.date,row?.base?:loan.baseOn(d.date),row?.paidOn,if(row?.unpaid==false)row.late else 0)
    }
    return SimpleRecord(loan.row,Loan(loan.id,loan.client,loan.principal,loan.ratePercent,loan.months,loan.guarantee,
        loan.firstDue.minusDays(1),installments=installments),loan.dailyLate,false,loan.termsRevision,unpaidDates,loan.advances)
}

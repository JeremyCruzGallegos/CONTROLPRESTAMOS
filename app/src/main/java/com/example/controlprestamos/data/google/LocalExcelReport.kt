package com.example.controlprestamos.data.google

import com.example.controlprestamos.data.ExcelBackup.Sheet
import com.example.controlprestamos.domain.today
import java.math.BigDecimal
import java.time.Instant

/** All inputs come from local storage. Missing installment records stay explicitly unknown. */
internal fun localExcelReport(view: LocalBookView, edits: PaymentChanges, saved: Map<String, String>): List<Sheet> {
    fun amount(cents: Long) = BigDecimal.valueOf(cents, 2)
    val day=today()
    val loans=view.catalog.map { localLoanView(it, edits, day) }
    val rows=view.rows.associateBy { it.id }.toMutableMap()
    edits.values.forEach { (row, edit) -> rows[row.id]=row.copy(unpaid=edit.unpaid,late=edit.collectedLate,paidOn=edit.paidOn) }
    val summary=effectiveSheetSummary(view.data.summary, edits, view.cash)
    return listOf(
        Sheet("Información", listOf(listOf("Dato", "Valor"), listOf("Generado",Instant.now().toString()),
            listOf("Origen","Datos locales del teléfono · ${view.data.title}"),listOf("Última actualización local",view.data.syncedAt.toString()),
            listOf("Moneda","Soles (PEN). Importes en soles, no en céntimos."),
            listOf("Alcance","Libro seleccionado. Incluye registros disponibles y cambios locales pendientes de sincronizar."),
            listOf("Cuotas sin registro local","Se indican como Sin datos locales; no se asumen pagadas."),
            listOf("Respaldo local","Contiene los datos originales en fragmentos consecutivos por archivo. No incluye credenciales."),
            listOf("Restauración","Este archivo es un respaldo para consulta. La app no dispone de importación automática."))),
        Sheet("Préstamos",listOf(listOf("ID","Cliente","Año","Monto prestado","Tasa mensual %","Meses","Garantía","Total pactado","Cobrado","Deuda","Cuotas pagadas","Cuotas totales","Primera cuota","Última cuota","Mora diaria","Aporte externo"))+
            loans.map { l -> listOf(l.id,l.client,l.year,amount(l.principal),l.ratePercent,l.months,l.guarantee,amount(l.agreed),amount(l.collected),amount(l.debt),l.paid,l.total,l.firstDue.toString(),l.lastDue.toString(),amount(l.dailyLate),amount(view.contributions[l.id]?:0)) }),
        Sheet("Cuotas y pagos",listOf(listOf("Préstamo","Cliente","N° cuota","Vencimiento","Cuota","Estado","Mora registrada","Fecha de pago","Pago registrado","Cambio local pendiente"))+
            loans.flatMap { loan -> loanDates(loan).mapIndexed { index, due ->
                val row=rows["${loan.id}:$due"]
                listOf(loan.id,loan.client,index+1,due.toString(),amount(row?.base?:loan.baseOn(due)),
                    when{due>day->"Próxima";row==null->"Sin datos locales";!row.unpaid->"Pagada";due<day->"Vencida";else->"Pendiente"},
                    if(row!=null && !row.unpaid)amount(row.late) else null,row?.paidOn?.toString().orEmpty(),
                    if(row!=null && !row.unpaid)amount(row.base+row.late)else null,if(row?.id in edits)"Sí" else "")
            } }),
        Sheet("Caja diaria",listOf(listOf("Fecha","Aporte","Cobros","Desembolsos","Saldo","Aporte asumido"))+
            view.cash.map{listOf(it.date.toString(),amount(it.contribution),amount(it.receipts),amount(it.disbursed),amount(it.cash),amount(it.assumedContribution))}),
        Sheet("Resumen",listOf(listOf("Concepto","Valor"),listOf("Año",summary.year),listOf("Fecha de corte",summary.cutoff.toString()),
            listOf("Capital inicial",amount(summary.initial)),listOf("Saldo de apertura",amount(summary.opening)),listOf("Aportes",amount(summary.contribution)),
            listOf("Desembolsos",amount(summary.disbursed)),listOf("Cobros",amount(summary.receipts)),listOf("Intereses",amount(summary.interest)),
            listOf("Moras cobradas",amount(summary.late)),listOf("Capital recuperado",amount(summary.recovered)),listOf("Caja",amount(summary.cash)),listOf("Capital pendiente",amount(summary.pendingCapital)))),
        Sheet("Movimientos",listOf(listOf("ID","Fecha","Tipo","Monto","Descripción"))+view.movements.map{listOf(it.id,it.date.toString(),it.type.label,amount(it.amount),it.description)}),
        Sheet("Respaldo local", listOf(listOf("Archivo local","Parte","Contenido original"))+saved.flatMap{(name, content)->content.chunked(30000).mapIndexed{index,part->listOf(name,index+1,part)}})
    )
}

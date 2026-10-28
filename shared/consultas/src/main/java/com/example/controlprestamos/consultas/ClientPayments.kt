package com.example.controlprestamos.consultas

import java.time.LocalDate

internal data class ClientDueTotals(val base:Long=0,val late:Long=0,val count:Int=0) {
    val total get()=base+late
}
internal fun ConsultationLoan.pendingThrough(day:LocalDate)=dues.filter{!it.settled && it.due<=day}.let{rows->ClientDueTotals(rows.sumOf{it.base},rows.sumOf{it.late},rows.size)}
internal fun ConsultationLoan.pendingToday(day:LocalDate)=dues.filter{!it.settled && it.due==day}.let{rows->ClientDueTotals(rows.sumOf{it.base},rows.sumOf{it.late},rows.size)}
internal fun ConsultationLoan.registeredToday(day:LocalDate)=dues.filter{it.settled && it.paidOn==day}.sumOf{it.paid}
internal enum class ClientPaymentFilter(val label:String){TODAY("Hoy"),THROUGH_TODAY("Hasta hoy"),REGISTERED("Registrados")}
internal fun ConsultationLoan.paymentRows(filter:ClientPaymentFilter,day:LocalDate):List<Due> = dues.filter{due->when(filter){
    ClientPaymentFilter.TODAY->!due.settled && due.due==day || due.settled && due.paidOn==day
    ClientPaymentFilter.THROUGH_TODAY->!due.settled && due.due<=day
    ClientPaymentFilter.REGISTERED->due.settled
}}.sortedBy{it.due}

internal enum class ClientLoanFilter(val label:String){
    ALL("Todos"),PAID("Pagados"),LATE("Moras"),UPCOMING("Próximos");
    fun matches(due:Due,day:LocalDate):Boolean=when(this){
        ALL->true
        PAID->due.settled
        LATE->!due.settled && (due.status==DueStatus.OVERDUE || due.late>0)
        UPCOMING->!due.settled && due.due>=day
    }
}

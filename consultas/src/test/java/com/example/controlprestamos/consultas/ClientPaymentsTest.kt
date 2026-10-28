package com.example.controlprestamos.consultas
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class ClientPaymentsTest {
    private val day=LocalDate.of(2026,10,27)
    private val loan=exampleLoans().first().copy(dues=listOf(
        Due(1,day.minusDays(1),1000,200,DueStatus.OVERDUE,null,0),
        Due(2,day,1000,0,DueStatus.PENDING,null,0),
        Due(3,day.plusDays(1),1000,0,DueStatus.FUTURE,null,0),
        Due(4,day.minusDays(2),1000,50,DueStatus.PAID,day,1050),
        Due(5,day.minusDays(3),1000,200,DueStatus.REVIEW,null,1200)))
    @Test fun pendingTotalsExcludeFutureAndAlreadyRegisteredPayments(){val total=loan.pendingThrough(day);assertEquals(2000L,total.base);assertEquals(200L,total.late);assertEquals(2200L,total.total);assertEquals(2,total.count)}
    @Test fun todaySeparatesNewAmountDueFromPaymentsActuallyRegisteredToday(){assertEquals(1000L,loan.pendingToday(day).total);assertEquals(1050L,loan.registeredToday(day));assertEquals(listOf(2,4),loan.paymentRows(ClientPaymentFilter.TODAY,day).map{it.number}.sorted())}
    @Test fun historyIncludesUnconfirmedPaymentWithoutInventingItsDate(){val history=loan.paymentRows(ClientPaymentFilter.REGISTERED,day);assertEquals(listOf(4,5),history.map{it.number}.sorted());assertNull(history.first{it.number==5}.paidOn)}
    @Test fun reminderRollsToNextDayWhenTodaysTimeHasPassed(){val settings=ClientReminderSettings(hour=8,minute=30);val before=day.atTime(8,0).atZone(ZoneId.of("America/Lima"));assertEquals(day,settings.next(before).toLocalDate());assertEquals(day.plusDays(1),settings.next(before.plusHours(1)).toLocalDate())}
}

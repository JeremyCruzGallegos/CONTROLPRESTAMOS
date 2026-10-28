package com.example.controlprestamos.consultas

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ClientLoanFilterTest {
    @Test fun filtersSeparateSettledOverdueAndUpcomingDues() {
        val day=LocalDate.of(2026,10,28)
        val paid=Due(1,day.minusDays(2),1000,200,DueStatus.PAID,day.minusDays(1),1200)
        val overdue=Due(2,day.minusDays(1),1000,0,DueStatus.OVERDUE,null,0)
        val today=Due(3,day,1000,0,DueStatus.PENDING,null,0)
        val future=Due(4,day.plusDays(1),1000,0,DueStatus.FUTURE,null,0)
        val review=paid.copy(status=DueStatus.REVIEW,paidOn=null)
        val dues=listOf(paid,overdue,today,future,review)
        assertEquals(dues,dues.filter{ClientLoanFilter.ALL.matches(it,day)})
        assertEquals(listOf(paid,review),dues.filter{ClientLoanFilter.PAID.matches(it,day)})
        assertEquals(listOf(overdue),dues.filter{ClientLoanFilter.LATE.matches(it,day)})
        assertEquals(listOf(today,future),dues.filter{ClientLoanFilter.UPCOMING.matches(it,day)})
    }
}

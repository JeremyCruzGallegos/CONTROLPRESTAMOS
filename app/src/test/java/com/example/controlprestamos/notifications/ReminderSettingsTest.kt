package com.example.controlprestamos.notifications

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReminderSettingsTest {
    private val zone = ZoneId.of("America/Lima")
    @Test fun remindersAreSpreadAcrossTheSelectedHours() {
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(13, 0), LocalTime.of(18, 0)),
            ReminderSettings(count = 3).times())
    }
    @Test fun oneReminderUsesTheFirstTimeOnly() {
        assertEquals(listOf(LocalTime.of(9, 45)), ReminderSettings(count = 1, hour = 9, minute = 45).times())
    }
    @Test fun fourRemindersKeepMinutesAndStayWithinTheWindow() {
        val times = ReminderSettings(count = 4, hour = 8, minute = 30, endHour = 20).times()
        assertEquals(4, times.size); assertEquals(LocalTime.of(8, 30), times.first())
        assertEquals(LocalTime.of(20, 0), times.last()); assertEquals(times.sorted(), times)
    }
    @Test fun afterTheLastTimeSchedulesTomorrow() {
        val now = ZonedDateTime.of(2026, 10, 18, 22, 0, 0, 0, zone)
        assertEquals(now.toLocalDate().plusDays(1).atTime(8, 0).atZone(zone), ReminderSettings().next(now))
    }
    @Test fun onlySelectedWeekdaysAreScheduled() {
        val friday = ZonedDateTime.of(2026, 10, 16, 19, 0, 0, 0, zone)
        val next = ReminderSettings(days = (1..5).toSet()).next(friday)!!
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek); assertEquals(LocalTime.of(8, 0), next.toLocalTime())
    }
    @Test fun noSelectedDaysPausesReminders() {
        assertNull(ReminderSettings(days = emptySet()).next(ZonedDateTime.now(zone)))
    }
    @Test fun deliveredSlotIsNotScheduledAgain() {
        val now = ZonedDateTime.of(2026, 10, 18, 8, 0, 0, 0, zone)
        assertEquals(LocalTime.of(18, 0), ReminderSettings().next(now)!!.toLocalTime())
    }
}

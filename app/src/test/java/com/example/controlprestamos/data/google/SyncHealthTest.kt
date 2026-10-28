package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test

class SyncHealthTest {
    @Test fun noAlertIsAllowedBeforeTwentyFourHoursOfFailedAttempts() {
        val health = SyncHealth(MemorySheetCache())
        assertFalse(health.failed(1_000).shouldNotify(1_000))
        assertFalse(health.failed(2_000).shouldNotify(1_000 + SYNC_FAILURE_DELAY - 1))
        assertTrue(health.read()!!.shouldNotify(1_000 + SYNC_FAILURE_DELAY))
    }
    @Test fun aSingleFailedAttemptDoesNotTriggerAnAlertEvenAfterOneDay() {
        assertFalse(SyncFailurePeriod(1_000, 1_000, 1).shouldNotify(1_000 + SYNC_FAILURE_DELAY))
    }
    @Test fun anAlertIsIssuedOncePerFailurePeriodAndSurvivesRestart() {
        val cache = MemorySheetCache(); val health = SyncHealth(cache)
        health.failed(1_000); health.failed(1_000 + SYNC_FAILURE_DELAY); health.notified()
        assertFalse(SyncHealth(cache).failed(1_000 + SYNC_FAILURE_DELAY * 2).shouldNotify(1_000 + SYNC_FAILURE_DELAY * 2))
        health.success(); assertNull(SyncHealth(cache).read())
        assertFalse(health.failed(1_000 + SYNC_FAILURE_DELAY * 3).shouldNotify(1_000 + SYNC_FAILURE_DELAY * 3))
    }
}

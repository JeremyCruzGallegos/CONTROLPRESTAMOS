package com.example.controlprestamos.ui

import org.junit.Assert.*
import org.junit.Test

class BackNavigationTest {
    @Test fun rootRequiresTwoBackPressesWithinTheExitWindow() {
        val guard = ExitBackGuard()
        assertFalse(guard.press(0))
        assertTrue(guard.press(1_500))
        assertFalse(guard.press(1_600))
    }

    @Test fun anExpiredFirstPressDoesNotCauseAnUnexpectedExit() {
        val guard = ExitBackGuard()
        assertFalse(guard.press(1_000))
        assertFalse(guard.press(4_000))
        assertTrue(guard.press(5_000))
    }

    @Test fun navigatingOrReturningFromADetailResetsTheExitRequest() {
        val guard = ExitBackGuard()
        assertFalse(guard.press(1_000))
        guard.reset()
        assertFalse(guard.press(1_100))
        assertTrue(guard.press(1_200))
    }

    @Test fun menuHistoryReturnsToTheActualPreviousMenuIncludingRepeatedVisits() {
        var history = listOf(1)
        listOf(0, 2, 2, 1).forEach { history = recordMenu(history, it) }
        assertEquals(listOf(1, 0, 2, 1), history)
        history = history.dropLast(1)
        assertEquals(2, history.last())
        history = history.dropLast(1)
        assertEquals(0, history.last())
        assertEquals(listOf(1), history.dropLast(1))
    }
}

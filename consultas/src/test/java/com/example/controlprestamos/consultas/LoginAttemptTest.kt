package com.example.controlprestamos.consultas

import org.junit.Assert.*
import org.junit.Test

class LoginAttemptTest {
    @Test fun pkceMatchesThePublishedRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun wrongOrExpiredCallbackCannotCompleteSignIn() {
        val attempt = LoginAttempt("verifier", "correct-state", 1_000_000)
        assertTrue(attempt.matches("correct-state", 1_000_001))
        assertTrue(attempt.matches("correct-state", 1_600_000))
        assertFalse(attempt.matches("correct-state", 1_600_001))
        assertFalse(attempt.matches("wrong-state!!", 1_000_001))
        assertFalse(attempt.matches("correct-state", 999_999))
    }
    @Test fun retriesHaveDifferentStateAndVerifier() {
        val a = LoginAttempt.create(); val b = LoginAttempt.create()
        assertNotEquals(a.state, b.state); assertNotEquals(a.verifier, b.verifier)
        assertEquals(43, a.state.length); assertEquals(43, a.verifier.length)
    }
}

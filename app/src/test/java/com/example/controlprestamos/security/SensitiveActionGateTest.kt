package com.example.controlprestamos.security

import org.junit.Assert.*
import org.junit.Test

class SensitiveActionGateTest {
    @Test fun noMutationBeforeAuthenticationAndCancellationDoesNotWrite() {
        var writes=0;val gate=SensitiveActionGate()
        gate.request("1","Eliminar","account|book") { writes++ }
        assertEquals(0,writes);assertFalse(gate.resolve("1","account|book",false));assertEquals(0,writes)
    }
    @Test fun successfulAuthenticationAuthorizesExactlyOneAction() {
        var writes=0;val gate=SensitiveActionGate()
        gate.request("1","Guardar","owner") { writes++ }
        assertTrue(gate.resolve("1","owner",true));assertFalse(gate.resolve("1","owner",true));assertEquals(1,writes)
    }
    @Test fun anotherAccountOrBookCannotReuseThePrompt() {
        var writes=0;val gate=SensitiveActionGate()
        gate.request("1","Guardar","first|book") { writes++ }
        assertFalse(gate.resolve("1","other|book",true));assertEquals(0,writes)
    }
    @Test fun staleCallbackCannotAuthorizeANewerAction() {
        var writes=0;val gate=SensitiveActionGate()
        gate.request("old","Guardar","owner") { writes++ };gate.invalidate()
        gate.request("new","Eliminar","owner") { writes+=10 }
        assertFalse(gate.resolve("old","owner",true));assertEquals(0,writes)
        assertTrue(gate.resolve("new","owner",true));assertEquals(10,writes)
    }
    @Test fun expiredConfirmationAndProcessRecreationCannotWrite() {
        var time=0L;var writes=0;val gate=SensitiveActionGate { time }
        gate.request("1","Guardar","owner") { writes++ };time=120001
        assertFalse(gate.resolve("1","owner",true))
        assertFalse(SensitiveActionGate().resolve("1","owner",true));assertEquals(0,writes)
    }
    @Test fun parallelPromptsCannotReplaceThePayloadBeingConfirmed() {
        val gate=SensitiveActionGate();gate.request("1","Guardar","owner") {}
        assertThrows(IllegalStateException::class.java) { gate.request("2","Eliminar","owner") {} }
    }
}

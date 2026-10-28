package com.example.controlprestamos.consultas
import org.junit.Assert.*
import org.junit.Test
class AccessApprovalGateTest {
    @Test fun successRunsOnlyOneCapturedAction(){var count=0;val gate=AccessApprovalGate{100};assertTrue(gate.request("a","session"){count++});assertTrue(gate.resolve("a","session",true));assertFalse(gate.resolve("a","session",true));assertEquals(1,count)}
    @Test fun cancellationAndChangedSessionDoNotWrite(){var count=0;val gate=AccessApprovalGate{100};gate.request("a","session"){count++};assertFalse(gate.resolve("a","session",false));gate.request("b","session"){count++};assertFalse(gate.resolve("b","other",true));assertEquals(0,count)}
    @Test fun expiryAndInvalidationRejectLateAuthentication(){var time=100L;var count=0;val gate=AccessApprovalGate{time};gate.request("a","session"){count++};time+=120001;assertFalse(gate.resolve("a","session",true));gate.request("b","session"){count++};gate.cancel();assertFalse(gate.resolve("b","session",true));assertEquals(0,count)}
    @Test fun unrelatedCallbackCannotConsumeCurrentPrompt(){var count=0;val gate=AccessApprovalGate{100};gate.request("a","session"){count++};assertFalse(gate.resolve("b","session",true));assertTrue(gate.resolve("a","session",true));assertEquals(1,count)}
}

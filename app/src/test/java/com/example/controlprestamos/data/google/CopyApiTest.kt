package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test

class CopyApiTest {
    private val production = SheetsIdentity.PRODUCTION_ID
    private val copy = "EXAMPLE_FILE_39c1392094058ef3ccbf99d44f062604"
    private val deployment = "AKfycbxm68pxxZFElPBSMbejOG9_Yoqv7A_QNyEBeOTm4I6oL7fUF-538ew4a1oKnprS3q26"
    private val scopes = listOf("https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/script.scriptapp", "https://www.googleapis.com/auth/script.container.ui")
    private fun allowed(book: String = copy, prod: String = production, test: String = copy,
        id: String = deployment, permissions: List<String> = scopes) = copyApiConfigured(book, prod, test, id, permissions)

    @Test fun configuredCopyAllowsItsFirstControlledTest() { assertTrue(allowed()) }
    @Test fun productionAndAnotherBookRemainBlocked() {
        assertFalse(allowed(book = production))
        assertFalse(allowed(book = "another_book_with_valid_id_12345"))
    }
    @Test fun paymentApiAllowsConfiguredPrincipalAndCopy() {
        assertTrue(paymentApiConfigured(production, production, copy, deployment, scopes))
        assertTrue(paymentApiConfigured(copy, production, copy, deployment, scopes))
        assertFalse(paymentApiConfigured("another_book_with_valid_id_12345", production, copy, deployment, scopes))
        assertFalse(paymentApiConfigured(production, production, copy, "", scopes))
        assertFalse(paymentApiConfigured(production, production, copy, deployment, emptyList()))
        assertFalse(paymentApiConfigured(production, production, copy, deployment, listOf("openid")))
    }
    @Test fun copyCannotBeProductionEvenWithWrongProductionConfiguration() {
        assertFalse(allowed(book = production, test = production))
        assertFalse(allowed(book = production, test = production, prod = copy))
    }
    @Test fun missingDeploymentOrScopesNeverEnablesWrites() {
        assertFalse(allowed(id = "")); assertFalse(allowed(permissions = emptyList()))
    }
    @Test fun malformedIdsOrScopesNeverEnableWrites() {
        assertFalse(allowed(test = "short", book = "short"))
        assertFalse(allowed(id = "https://script.google.com/deployment"))
        assertFalse(allowed(permissions = listOf("openid")))
        assertFalse(allowed(permissions = listOf("https://example.com/auth/spreadsheets")))
    }
    @Test fun executionErrorExplainsMissingDeployedFunction() {
        val message = scriptFailureMessage(200, "Script function not found: controlPrestamosApi")
        assertTrue(message.contains("nueva versión")); assertTrue(message.contains("controlPrestamosApi"))
    }
    @Test fun authorizationAndDeploymentFailuresHaveActionableMessages() {
        assertTrue(scriptFailureMessage(401, null).contains("Reconecta"))
        assertTrue(scriptFailureMessage(403, "The caller does not have permission").contains("mismo proyecto"))
        assertTrue(scriptFailureMessage(404, null).contains("tipo de implementación"))
        assertTrue(scriptFailureMessage(429, null).contains("Espera"))
    }
    @Test fun serverErrorKeepsItsSpecificReasonWithoutUnboundedOutput() {
        assertTrue(scriptFailureMessage(200, "Escrituras de prueba deshabilitadas.").contains("Escrituras de prueba deshabilitadas."))
        assertTrue(scriptFailureMessage(200, "Error\n" + "x".repeat(1000)).length < 400)
        assertFalse(scriptFailureMessage(200, "Error\ncon líneas").contains("\n"))
    }
}

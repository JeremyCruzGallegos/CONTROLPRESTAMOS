package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test

class ClientConnectionTest {
    private val client = GoogleConnection(SheetsIdentity.CLIENT_ID, "", "", emptyList(), false)
    private val existing = GoogleConnection(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "", emptyList(), false)

    @Test fun oldBookPreferenceCannotSelectTheRetiredBook(){assertFalse(client.effectiveCopy(true));assertFalse(client.effectiveCopy(false))}

    @Test fun clientWritesUseSheetsWithoutAnyScriptDeploymentOrScopes() {
        assertTrue(client.clientInstallation)
        assertTrue(client.usesDirectPayments(SheetsIdentity.CLIENT_ID))
        assertTrue(client.canWrite(SheetsIdentity.CLIENT_ID))
        assertTrue(client.canCreateLoan(SheetsIdentity.CLIENT_ID))
        assertTrue(client.canDeleteLoan(SheetsIdentity.CLIENT_ID))
        assertTrue(client.canEditCapital(SheetsIdentity.CLIENT_ID))
        assertNull(client.financialReadOnlyReason(SheetsIdentity.CLIENT_ID))
        assertEquals(listOf(WRITE_SCOPE, EMAIL_SCOPE, "openid"), client.scopes(false))
    }

    @Test fun eachInstallationCanOnlyWriteItsExplicitlyConfiguredBooks() {
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "unconfigured_valid_book_123456")) {
            assertFalse(client.canWrite(book))
            assertFalse(client.canCreateLoan(book))
            assertFalse(client.canDeleteLoan(book))
        }
        assertFalse(existing.canWrite(SheetsIdentity.CLIENT_ID))
        assertTrue(existing.canWrite(SheetsIdentity.PRODUCTION_ID))
        assertTrue(existing.canWrite(SheetsIdentity.EMPTY_TEST_ID))
    }

    @Test fun onlyTheTwoApprovedSimpleBooksUseTheSimpleSchema() {
        assertTrue(SheetsIdentity.isSimpleBook(SheetsIdentity.CLIENT_ID))
        assertTrue(SheetsIdentity.isSimpleBook(SheetsIdentity.EMPTY_TEST_ID))
        assertFalse(SheetsIdentity.isSimpleBook(SheetsIdentity.PRODUCTION_ID))
        assertFalse(SheetsIdentity.isSimpleBook("unconfigured_valid_book_123456"))
    }
}

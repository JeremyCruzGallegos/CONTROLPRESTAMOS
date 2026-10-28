package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class DirectTestBookTest {
    private val prod = SheetsIdentity.PRODUCTION_ID
    private val test = SheetsIdentity.EMPTY_TEST_ID
    private val legacy = "EXAMPLE_FILE_39c1392094058ef3ccbf99d44f062604"
    private val config = GoogleConnection(prod, test, "", emptyList(), false)
    @Test fun onlyThePinnedTestBookAndOfficialBookUseDirectWrites() {
        assertNotEquals(prod, test)
        for (book in listOf(prod, test)) {
            assertTrue(config.usesDirectPayments(book)); assertTrue(config.canWrite(book))
            assertTrue(config.canCreateLoan(book)); assertTrue(config.canDeleteLoan(book))
            assertNull(config.financialReadOnlyReason(book))
        }
        for (book in listOf(legacy, "UNKNOWN_BOOK_ID_1234567890")) {
            assertFalse(config.usesDirectPayments(book)); assertFalse(config.canWrite(book))
            assertFalse(config.canCreateLoan(book)); assertFalse(config.canDeleteLoan(book))
        }
    }
    @Test fun changingConfigurationCannotAuthorizeAnArbitraryBookForDirectWrites() {
        assertFalse(config.copy(testBookId = legacy).usesDirectPayments(test))
        assertFalse(config.copy(testBookId = legacy).usesDirectPayments(legacy))
        assertFalse(config.copy(productionBookId = test).usesDirectPayments(prod))
        assertFalse(config.copy(productionBookId = test).usesDirectPayments(test))
    }
    @Test fun replacingTheTestBookAllowsTheNewEmptyBookAndRetiresThePreviousConnection() {
        val replacement = config.copy(testBookId = SheetsIdentity.CLIENT_ID)
        for (book in listOf(prod, SheetsIdentity.CLIENT_ID)) {
            assertTrue(replacement.canWrite(book))
            assertTrue(replacement.canCreateLoan(book))
            assertTrue(replacement.canDeleteLoan(book))
            assertTrue(replacement.canEditCapital(book))
            assertNull(replacement.financialReadOnlyReason(book))
        }
        for (book in listOf(test, legacy, "UNKNOWN_BOOK_ID_1234567890")) {
            assertFalse(replacement.canWrite(book))
            assertFalse(replacement.canEditLoanText(book))
            assertFalse(replacement.canCreateLoan(book))
            assertFalse(replacement.canDeleteLoan(book))
        }
        assertEquals(listOf(WRITE_SCOPE, EMAIL_SCOPE, "openid"), replacement.scopes(true))
        assertNotEquals(remoteCacheKey("account", test), remoteCacheKey("account", SheetsIdentity.CLIENT_ID))
        val previous = PendingRemoteWrite("previous-test-operation", "account", test, "{}")
        assertThrows(IllegalArgumentException::class.java) { previous.requireOwner("account", SheetsIdentity.CLIENT_ID) }
    }
    @Test fun directTestAuthorizationDoesNotNeedTheOldAppsScriptDeployment() {
        val expected = listOf(WRITE_SCOPE, EMAIL_SCOPE, "openid")
        assertEquals(expected, config.scopes(true)); assertEquals(expected, config.scopes(false))
        assertEquals(expected, config.copy(deploymentId = "OLD_DEPLOYMENT_ID_1234567890", scriptScopes = listOf("https://www.googleapis.com/auth/script.scriptapp")).scopes(true))
    }
    @Test fun zeroCapitalAutomaticallyFundsTheFirstTestLoan() {
        val day = LocalDate.of(2026, 10, 15)
        val request = LoanRequest("Cliente de prueba", 10000, BigDecimal("2"), 1, "Prueba", Funding.EXTERNAL, 10000)
        val plan = planDirectLoan(test, request, day, day, 0, emptyList(), emptyList())
        assertEquals(0L, plan.cashAfter); assertEquals("2026-001", plan.loan.id)
        assertEquals(day.plusDays(1), plan.firstDue)
        val automatic = planDirectLoan(test, request.copy(funding = Funding.CAPITAL, contribution = 0), day, day, 0, emptyList(), emptyList())
        assertEquals(10000L, automatic.loan.contribution)
        assertEquals(0L, automatic.cashAfter)
    }
    @Test fun cachePendingAndPaymentVersionsCannotCrossBetweenBooks() {
        assertNotEquals(remoteCacheKey("account", prod), remoteCacheKey("account", test))
        val pending = PendingRemoteWrite("test-operation-1", "account", test, "{}")
        assertThrows(IllegalArgumentException::class.java) { pending.requireOwner("account", prod) }
        assertFalse(SheetsIdentity.isDirectBook(legacy))
        SheetsIdentity.requireCopy(test)
    }
}

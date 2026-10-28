package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class LoanTermsRepositoryTest {
    private val first = LocalDate.of(2026, 9, 2)
    private class Cache : SheetCache {
        var value: JSONObject? = null
        var deleted = false
        override fun read(): JSONObject? = value
        override fun write(value: JSONObject) { this.value = value }
        override fun delete() { deleted = true; value = null }
    }
    private fun server(book: String = SheetsIdentity.PRODUCTION_ID): LoanDeletionTest.FakeSheets {
        assumeTrue(today() in LocalDate.of(2026, 9, 15)..LocalDate.of(2026, 10, 31))
        return LoanDeletionTest.FakeSheets(first, book).apply {
            sheets["_CAPITAL"] = 6 to 8
            put("_CAPITAL", 1, 2, 1000); put("_CAPITAL", 4, 10, "CAJA")
            listOf<Any>(today().year, sheetSerial(today()), 1000, 0, 0, 0, 0, 0, 0, 1000, 0, 1000, 0)
                .forEachIndexed { col, value -> put("_CAPITAL", 5, col + 1, value) }
            put("2026", 6, 3, "1/30", "=STATE")
            put("2026", 6, 9, 20.4, "=COLLECTED")
            repeat(169) { index ->
                val due = LocalDate.of(2026, 7, 16).plusDays(index.toLong())
                val col = 18 + index * 5
                val valid = due in first..first.plusDays(29) && due <= today()
                val unpaid = valid && due != first
                val base = if (due == first) 19.9 else 16.9
                put("2026", 6, col, valid, "=VALID")
                put("2026", 6, col + 1, ChronoUnit.DAYS.between(first, due).toInt() + 1, "=ORDINAL")
                put("2026", 6, col + 2, unpaid)
                put("2026", 6, col + 3, if (valid) base else 0, "=AMOUNT")
                put("2026", 6, col + 4, if (unpaid) calculatedLate(due, today()) / 100.0 else 0.5, "=LATE")
                put("_MORA_COBRADA_2026", 6, index + 2, if (due == first) 0.5 else 0)
            }
        }
    }
    private fun repository(server: LoanDeletionTest.FakeSheets, cache: Cache = Cache()) = SheetsRepository(server.book, "account", server, cache,
        GoogleConnection(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID, "", emptyList(), false), GoogleTokenProvider { "fake" })
    private data class Prepared(val loan: SheetLoan, val calendar: LoanCalendar, val plan: LoanTermsPlan, val operation: PendingRemoteWrite)
    private fun prepare(repo: SheetsRepository, server: LoanDeletionTest.FakeSheets, months: Int = 2): Prepared {
        val data = repo.preload(2026, today()) { _, _ -> }
        val loan = data.loans.single { it.id == "2026-001" }
        val calendar = repo.calendar(loan)
        val input = LoanTermsInput("Ana actualizada", "Nueva garantía", 60000, BigDecimal("3"), months, first.minusDays(2), 25)
        val plan = planLoanTerms(loan, calendar, input, today(), data.summary.cash)
        val operation = PendingRemoteWrite("loan-terms-operation-001", "account", server.book,
            JSONObject().put("bookId", server.book).put("operationId", "loan-terms-operation-001").put("action", "editLoanTerms")
                .put("transport", "sheets").put("loanId", loan.id).put("year", loan.year).put("version", loanDeletionVersion(loan))
                .put("terms", input.json()).put("confirmation", plan.confirmation()).put("actor", "Test account").toString())
        return Prepared(loan, calendar, plan, operation)
    }
    private fun requests(server: LoanDeletionTest.FakeSheets) = server.posted!!.getJSONArray("requests").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    @Test fun reopeningARegularPaymentCancelsItsFeeAdvanceInTheSameAtomicBatch() {
        val server=server();val original=server.loan()
        val check=20+ChronoUnit.DAYS.between(LocalDate.of(2026,7,16),first).toInt()*5
        val config=LoanConditions(100,loanDates(original).associateWith(original::baseOn),"advance-config",
            listOf(FeeAdvance(first,first.plusDays(1),50,"'2026'!${columnName(check)}6")))
        server.sheets["_PRESTAMOS_CONDICIONES"]=8 to 1000
        listOf("PRESTAMO ID","CONDICIONES JSON","OPERACION").forEachIndexed { i,v->server.put("_PRESTAMOS_CONDICIONES",1,i+1,v) }
        listOf(original.id,config.json().toString(),config.revision).forEachIndexed { i,v->server.put("_PRESTAMOS_CONDICIONES",2,i+1,v) }
        val repo=repository(server);val loan=repo.preload(2026,today()){_,_->}.loans.first()
        assertTrue(loan.advances.single().sourcePaid)
        val row=repo.calendar(loan).days.first().payment!!
        val body=JSONObject().put("action","pay").put("bookId",server.book).put("operationId","undo-advance-primary-001")
            .put("transport","sheets").put("edits",org.json.JSONArray().put(JSONObject().put("version",JSONObject(row.version!!))
                .put("unpaid",true).put("collectedLate",0).put("paidOn","")))
        repo.write(PendingRemoteWrite(body.getString("operationId"),"account",server.book,body.toString()),true)
        val after=loanConditions(JSONObject(server.cells[Triple("_PRESTAMOS_CONDICIONES",3,2)]!!.first.toString()))
        assertTrue(after.advances.isEmpty());assertEquals(1,server.posts)
        assertEquals(true,server.cells[Triple("2026",6,check)]!!.first)
    }
    @Test fun amountPaymentWritesChecksExactBasesFeeCreditAndHistoryInOneBatchAndRetriesSafely() {
        val server=server()
        server.put("2026",6,3,"0/30","=STATE");server.put("2026",6,9,0,"=COLLECTED")
        val sourceColumn=20+ChronoUnit.DAYS.between(LocalDate.of(2026,7,16),first).toInt()*5
        server.put("2026",6,sourceColumn,true)
        server.put("_MORA_COBRADA_2026",6,ChronoUnit.DAYS.between(LocalDate.of(2026,7,16),first).toInt()+2,0)
        val repo=repository(server);val loan=repo.preload(2026,today()){_,_->}.loans.first()
        val calendar=repo.calendar(loan)
        val amount=loan.first+loan.lateAt(first,today())+50
        val plan=planAmountPayment(recordForAmount(loan,calendar,today()),amount,today())
        val body=JSONObject().put("action","amountPayment").put("bookId",server.book).put("transport","sheets")
            .put("operationId","amount-payment-primary-001").put("loanId",loan.id).put("year",loan.year)
            .put("version",loanDeletionVersion(loan)).put("amountCents",amount).put("waiveLate",false).put("confirmation",plan.confirmation())
        val operation=PendingRemoteWrite(body.getString("operationId"),"account",server.book,body.toString())
        server.commitThenTimeout=true
        val result=repo.write(operation,true);assertEquals(amount,result.getLong("amountCents"))
        assertEquals(100_000L+amount,result.getLong("cashAfterCents"))
        assertEquals(false,server.cells[Triple("2026",6,sourceColumn)]!!.first)
        val config=loanConditions(JSONObject(server.cells[Triple("_PRESTAMOS_CONDICIONES",2,2)]!!.first.toString()))
        assertEquals(50L,config.advances.single().amount)
        assertTrue(config.advances.single().sourceRef.startsWith("'2026'!"))
        assertTrue(server.sheets.containsKey("_PAGOS_HISTORIAL"))
        val formula=requests(server).mapNotNull { it.optJSONObject("updateCells")?.optJSONArray("rows")?.optJSONObject(0)?.optJSONArray("values")?.optJSONObject(0)?.optJSONObject("userEnteredValue")?.optString("formulaValue") }
        assertTrue(formula.any { "IF('2026'!" in it && "0.50" in it })
        assertEquals(amount,repository(server).write(operation).getLong("amountCents"));assertEquals(1,server.posts)
    }

    @Test fun cachedCalendarCannotKeepOldDatesAfterTheSameLoanIsEdited() {
        val server = server(); val repo = repository(server)
        val loan = repo.preload(2026, today()) { _, _ -> }.loans.first()
        val before = repo.calendar(loan)
        val edited = loan.copy(firstDue = loan.firstDue.minusDays(1), lastDue = loan.lastDue.minusDays(1), dailyLate = 25)
        val after = repo.calendar(edited)
        assertNotEquals(before.days.first().date, after.days.first().date)
        assertEquals(edited.firstDue, after.days.first().date)
        assertEquals(paymentTerms(edited), after.terms)
        assertTrue(after.days.mapNotNull { it.payment }.all { it.loan.dailyLate == 25L })
    }

    @Test fun officialAndTestBooksPreserveCollectedMoneyAndWriteTheNewAgreementAtomically() {
        for (book in listOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.EMPTY_TEST_ID)) {
            val server = server(book); val cache = Cache(); val repo = repository(server, cache)
            val prepared = prepare(repo, server)
            val originalPaidLate = server.cells[Triple("_MORA_COBRADA_2026", 6,
                ChronoUnit.DAYS.between(LocalDate.of(2026, 7, 16), first).toInt() + 2)]!!.first
            val result = repo.write(prepared.operation, true)
            assertTrue(result.getBoolean("termsChanged")); assertEquals(1, server.posts); assertTrue(cache.deleted)
            assertEquals(90000L, result.getLong("cashAfterCents"))
            assertEquals("Ana actualizada", server.cells[Triple("2026", 6, 2)]!!.first)
            assertEquals(600, (server.cells[Triple("2026", 6, 4)]!!.first as Number).toInt())
            val conditions = loanConditions(JSONObject(server.cells[Triple("_PRESTAMOS_CONDICIONES", 2, 2)]!!.first.toString()))
            assertEquals(25L, conditions.dailyLate); assertEquals(63600L, conditions.bases.values.sum())
            assertEquals(1990L, conditions.bases.getValue(first))
            val check = 20 + ChronoUnit.DAYS.between(LocalDate.of(2026, 7, 16), first).toInt() * 5
            assertEquals(false, server.cells[Triple("2026", 6, check)]!!.first)
            assertEquals(originalPaidLate, server.cells[Triple("_MORA_COBRADA_2026", 6,
                ChronoUnit.DAYS.between(LocalDate.of(2026, 7, 16), first).toInt() + 2)]!!.first)
            assertTrue(server.cells.keys.any { it.first == "_PRESTAMOS_EDICIONES" && it.second > 2 })
            assertTrue(server.names.contains(loanTermsRevisionKey(book, prepared.loan)))
            val formulas = requests(server).mapNotNull { it.optJSONObject("updateCells")?.optJSONArray("rows")?.optJSONObject(0)
                ?.optJSONArray("values")?.optJSONObject(0)?.optJSONObject("userEnteredValue")?.optString("formulaValue") }
            assertTrue(formulas.any { "TODAY()-" in it && "*0.25" in it })
            assertTrue(formulas.any { "ROUND(19.90+" in it })
        }
    }
    @Test fun acknowledgedRetryAndLostResponseNeverWriteTheAgreementTwice() {
        val server = server(); val repo = repository(server); val prepared = prepare(repo, server)
        server.commitThenTimeout = true
        val firstResult = repo.write(prepared.operation, true)
        val retry = repository(server).write(prepared.operation)
        assertEquals(firstResult.getString("operationId"), retry.getString("operationId")); assertEquals(1, server.posts)
    }
    @Test fun unknownOutcomeRetainsTheSameOperationForVerificationAfterRestart() {
        val server = server(); val repo = repository(server); val prepared = prepare(repo, server)
        server.commitThenTimeout = true; server.failVerification = true
        assertThrows(SocketTimeoutException::class.java) { repo.write(prepared.operation, true) }
        server.failVerification = false
        assertTrue(repository(server).write(prepared.operation).getBoolean("confirmed")); assertEquals(1, server.posts)
    }
    @Test fun staleLoanOrChangedCashCannotSaveAnOldPreview() {
        val server = server(); val repo = repository(server); val prepared = prepare(repo, server)
        server.put("_CAPITAL", 5, 10, 999)
        assertThrows(LoanTermsRejected::class.java) { repo.write(prepared.operation, true) }
        assertEquals(0, server.posts)
        server.put("_CAPITAL", 5, 10, 1000); server.put("2026", 6, 7, "Otra garantía")
        assertThrows(LoanTermsRejected::class.java) { repo.write(prepared.operation, true) }
        assertEquals(0, server.posts)
    }
    @Test fun concurrentPaymentRevisionRejectsTheWholeLoanEdit() {
        val server = server(); val repo = repository(server); val prepared = prepare(repo, server)
        val version = directPaymentVersion(JSONObject(prepared.calendar.days.first().payment!!.version!!))
        server.names.add(paymentRevisionKey(version))
        assertThrows(LoanTermsRejected::class.java) { repo.write(prepared.operation, true) }
        assertEquals(500, (server.cells[Triple("2026", 6, 4)]!!.first as Number).toInt())
        assertFalse(server.sheets.containsKey("_PRESTAMOS_CONDICIONES"))
    }
    @Test fun formulasInEditableInputsAreProtected() {
        val server = server(); val repo = repository(server); val prepared = prepare(repo, server)
        server.put("2026", 6, 5, 0.02, "=0.02")
        assertThrows(LoanTermsRejected::class.java) { repo.write(prepared.operation, true) }
        assertEquals(0, server.posts)
    }
    @Test fun configuredTariffAndScheduleSurviveCacheWithoutFallingBackToOneSol() {
        val server = server(); val original = server.loan()
        val config = LoanConditions(25, loanDates(original).associateWith(original::baseOn), "configured-operation")
        server.sheets["_PRESTAMOS_CONDICIONES"] = 8 to 1000
        listOf("PRESTAMO ID", "CONDICIONES JSON", "OPERACION").forEachIndexed { i, v -> server.put("_PRESTAMOS_CONDICIONES", 1, i + 1, v) }
        listOf(original.id, config.json().toString(), config.revision).forEachIndexed { i, v -> server.put("_PRESTAMOS_CONDICIONES", 2, i + 1, v) }
        val cache = Cache(); val repo = repository(server, cache)
        val loaded = repo.preload(2026, today()) { _, _ -> }
        val cached = repository(server, cache).cached(2026)!!
        assertEquals(25L, loaded.loans.first().dailyLate); assertEquals(25L, cached.loans.first().dailyLate)
        assertEquals(config.bases, cached.loans.first().scheduledBases)
        assertEquals(config.revision, cached.loans.first().termsRevision)
    }
    @Test fun termOperationLocksNewPaymentDraftsUntilItsOutcomeIsKnown() {
        val server = server(); val prepared = prepare(repository(server), server)
        assertTrue(pendingLocksPayments(prepared.operation))
    }
    @Test fun extendingIntoAnotherYearUpdatesCarriedCalendarWithoutMovingTheOriginalIdentity() {
        val server = server()
        server.put("2027", 406, 2, "", "=IF(AND('2026'!\u0024B6<>\"\",ISNUMBER('2026'!\u0024P6),'2026'!\u0024P6<DATE(2027,1,1),'2026'!\u0024Q6>=DATE(2027,1,1)),'2026'!\u0024B6,\"\")")
        repeat(365) { index ->
            val col = 22 + index * 5
            server.put("2027", 406, col, false, "=VALID")
            server.put("2027", 406, col + 1, 0, "=ORDINAL")
            server.put("2027", 406, col + 2, false)
            server.put("2027", 406, col + 3, 0, "=AMOUNT")
            server.put("2027", 406, col + 4, 0, "=LATE")
            server.put("_MORA_COBRADA_2027", 6, index + 2, 0)
        }
        val repo = repository(server); val prepared = prepare(repo, server, months = 6)
        val result = repo.write(prepared.operation, true)
        assertEquals("2026-001", result.getString("loanId")); assertEquals(1, server.posts)
        val config = loanConditions(JSONObject(server.cells[Triple("_PRESTAMOS_CONDICIONES", 2, 2)]!!.first.toString()))
        assertTrue(config.bases.keys.any { it.year == 2027 })
        val changes = requests(server).mapNotNull { it.optJSONObject("updateCells") }
        assertTrue(changes.any { it.getJSONObject("range").getInt("sheetId") == 2 && it.getJSONObject("range").getInt("startRowIndex") == 405 })
        assertTrue(changes.any { it.getJSONArray("rows").getJSONObject(0).getJSONArray("values").getJSONObject(0)
            .optJSONObject("userEnteredValue")?.optString("formulaValue")?.contains("'_MORA_COBRADA_2027'") == true })
    }
}

package com.example.controlprestamos.data.google

import com.example.controlprestamos.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate

@RunWith(Parameterized::class)
class SimpleSheetsRepositoryTest(private val bookId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "libro={0}")
        fun books(): List<Array<String>> = listOf(arrayOf(SheetsIdentity.EMPTY_TEST_ID), arrayOf(SheetsIdentity.CLIENT_ID))
    }
    private class Cache : SheetCache {
        var value: JSONObject?=null
        override fun read()=value?.let { JSONObject(it.toString()) }
        override fun write(value: JSONObject) { this.value=JSONObject(value.toString()) }
        override fun delete() { value=null }
    }
    private inner class Google : SheetsHttp {
        val day=today(); val until=LocalDate.of(maxOf(2026,day.year),12,31)
        val form=mutableListOf<MutableList<Any?>>()
        val loans=mutableListOf<MutableList<Any?>>()
        val names=mutableMapOf<String,JSONObject>()
        val clients=mutableListOf<MutableList<Any?>>()
        var clientsId:Int?=null;var clientRows=100
        var reads=0;var writes=0;var timeoutAfterCommit=false;var rejectNextWrite=false;var loseVerification=false;var failReads=false
        var formColumns=6;var rows=100;var columns=simpleDayColumn(until)+4
        init {
            repeat(100) { form+=mutableListOf<Any?>() }
            listOf(listOf(SIMPLE_SCHEMA,"Pruebas"),listOf("CAPITAL INICIAL",1000),listOf("REVISION","initial"),
                listOf("CALENDARIO DESDE",SIMPLE_START.toString()),listOf("CALENDARIO HASTA",until.toString())).forEachIndexed { i,r -> form[i]=r.toMutableList() }
            loans+=simpleHeadings(until).toMutableList<Any?>()
            val loan=Rules.createLoan("2026-001",LoanRequest("Cliente",50_000,BigDecimal("2"),1,"Garantía",Funding.CAPITAL,0),day.minusDays(3))
            loans+=SimpleRecord(2,loan,100,false,"first").cells(until).toMutableList()
        }
        override fun get(url: String): JSONObject {
            reads++
            if (failReads) throw SocketTimeoutException("Sin conexión")
            if (!url.contains("values:batchGet")) return JSONObject().put("spreadsheetId",bookId)
                .put("properties",JSONObject().put("title","Pruebas").put("timeZone","America/Lima"))
                .put("sheets",JSONArray((listOf("NUEVO","PRESTAMOS")+if(clientsId!=null)listOf("CLIENTES")else emptyList()).mapIndexed { i,title -> JSONObject().put("properties",JSONObject()
                    .put("title",title).put("sheetId",if(title=="CLIENTES")clientsId else i+1).put("gridProperties",JSONObject().put("rowCount",if(title=="CLIENTES")clientRows else rows).put("columnCount",if(i==0) formColumns else if(title=="CLIENTES")8 else columns))) }))
                .put("namedRanges",JSONArray(names.map { (name,range) -> JSONObject().put("name",name).put("range",range) }))
            val ranges=url.substringAfter('?').split('&').filter { it.startsWith("ranges=") }.map { URLDecoder.decode(it.substringAfter('='),"UTF-8") }
            return JSONObject().put("spreadsheetId",bookId).put("valueRanges",JSONArray(ranges.map { range ->
                val table=if(range.startsWith("'NUEVO'")) form else if(range.startsWith("'CLIENTES'"))clients else loans
                val coords=range.substringAfter('!').split(':')
                fun coord(value:String):Pair<Int,Int> {
                    val match=Regex("([A-Z]+)([0-9]+)").matchEntire(value)!!
                    val col=match.groupValues[1].fold(0) { a,c -> a*26+(c-'A'+1) }-1
                    return match.groupValues[2].toInt()-1 to col
                }
                val start=coord(coords[0]);val end=coord(coords.getOrElse(1){coords[0]})
                val data=(start.first..end.first).map { row -> (start.second..end.second).map { col -> table.getOrNull(row)?.getOrNull(col) ?: "" }
                    .dropLastWhile { it=="" } }.dropLastWhile { it.isEmpty() }
                JSONObject().put("range",range).put("values",JSONArray(data))
            }))
        }
        override fun post(url: String, body: JSONObject): JSONObject {
            writes++
            if(rejectNextWrite) { rejectNextWrite=false;throw GoogleHttpException(400,"Conflicto") }
            val requests=body.getJSONArray("requests")
            val additions=(0 until requests.length()).mapNotNull { requests.getJSONObject(it).optJSONObject("addNamedRange")?.getJSONObject("namedRange") }
            if(additions.any { it.getString("name") in names } || additions.map { it.getString("name") }.distinct().size!=additions.size) throw GoogleHttpException(400,"Revisión ya utilizada")
            for(i in 0 until requests.length()) {
                val req=requests.getJSONObject(i)
                req.optJSONObject("addSheet")?.getJSONObject("properties")?.let{props->
                    require(props.getString("title")=="CLIENTES" && clientsId==null)
                    clientsId=props.getInt("sheetId");clientRows=props.getJSONObject("gridProperties").getInt("rowCount")
                }
                req.optJSONObject("updateSheetProperties")?.getJSONObject("properties")?.getJSONObject("gridProperties")?.let {
                    rows=it.getInt("rowCount");columns=it.getInt("columnCount")
                }
                req.optJSONObject("appendDimension")?.let { value ->
                    if (value.getInt("sheetId") == 1 && value.getString("dimension") == "COLUMNS") formColumns += value.getInt("length")
                    if(value.getInt("sheetId")==clientsId && value.getString("dimension")=="ROWS")clientRows+=value.getInt("length")
                }
                req.optJSONObject("updateCells")?.let { update ->
                    val start=update.getJSONObject("start");val table=if(start.getInt("sheetId")==1) form else if(start.getInt("sheetId")==clientsId)clients else loans
                    val row=start.getInt("rowIndex");val col=start.getInt("columnIndex")
                    while(table.size<=row)table+=mutableListOf<Any?>()
                    val values=update.getJSONArray("rows").getJSONObject(0).getJSONArray("values")
                    while(table[row].size<col+values.length())table[row]+=""
                    for(j in 0 until values.length()) {
                        val entered=values.getJSONObject(j).optJSONObject("userEnteredValue")
                        table[row][col+j]=entered?.let { v -> v.opt("stringValue") ?: v.opt("numberValue") ?: v.opt("boolValue") } ?: ""
                    }
                }
            }
            additions.forEach { names[it.getString("name")]=it.getJSONObject("range") }
            if(timeoutAfterCommit) { timeoutAfterCommit=false;failReads=loseVerification;throw SocketTimeoutException("Respuesta perdida") }
            return JSONObject().put("spreadsheetId",bookId)
        }
    }
    private fun repo(http: Google, cache: Cache=Cache())=SimpleSheetsRepository(bookId,"account",http,cache)
    private fun pending(body: JSONObject,id:String="operation-123"):PendingRemoteWrite {
        body.put("bookId",bookId).put("operationId",id)
        return PendingRemoteWrite(id,"account",bookId,body.toString())
    }
    private fun paymentBody(payment: SheetPayment)=JSONObject().put("action","pay").put("edits",JSONArray().put(JSONObject()
        .put("version",JSONObject(payment.version!!)).put("unpaid",false).put("collectedLate",0).put("paidOn",today().toString())))
    private fun movementBody(type: CashMovementType = CashMovementType.DEPOSIT, amount: Long = 10000) =
        JSONObject().put("action", "cashMovement").put("type", type.name).put("amountCents", amount)
            .put("date", today().toString()).put("description", "Descripción opcional")
    @Test fun exactCashTimesSurviveRemoteReloadForLoansAndPaymentsInASixColumnJournal() {
        val http = Google(); val repo = repo(http); repo.preload(2026) { _, _ -> }
        val timestamp = dayTimestamp()
        val body = JSONObject().put("action", "createLoan").put("disbursedOn", today().minusDays(1).toString())
            .put("recordedAt", timestamp.toString()).put("pastPaymentsPaid", true)
            .put("loan", JSONObject().put("client", "Ana").put("principal", 600).put("rate", 0.02)
                .put("months", 1).put("guarantee", "").put("origin", "Del capital").put("contribution", 0))
        val receipt = repo.write(pending(body))
        val id = receipt.getString("loanId")
        val reloaded = repo(http); reloaded.preload(2026) { _, _ -> }
        assertEquals(6, http.formColumns)
        assertEquals(timestamp, reloaded.cashEventTimes()["loan:$id"])
        assertEquals(timestamp, reloaded.cashEventTimes()["fund:$id"])
        assertEquals(timestamp, reloaded.cashEventTimes()["pay:$id:${today()}"])
    }
    @Test fun creatingLoansPersistsOneVisibleClientCodeWithTheFinancialReceipt() {
        val http=Google();val repo=repo(http);repo.preload(2026){_,_->}
        fun body()=JSONObject().put("action","createLoan").put("disbursedOn",today().toString())
            .put("loan",JSONObject().put("client","Ana Cruz").put("principal",100).put("rate",0.02).put("months",1)
                .put("guarantee","").put("origin","Del capital").put("contribution",0))
        val first=repo.write(pending(body(),"client-code-loan-1"))
        if(bookId!=SheetsIdentity.CLIENT_ID){assertFalse(first.has("customerCode"));return}
        val code=first.getString("customerCode");assertTrue(CLIENT_CODE_PATTERN.matches(code))
        assertEquals(1,http.clients.count{it.firstOrNull()==code})
        val second=repo.write(pending(body(),"client-code-loan-2"));assertEquals(code,second.getString("customerCode"))
        val row=http.clients.single{it.firstOrNull()==code};assertTrue(row[3].toString().contains(first.getString("loanId")))
        assertTrue(row[3].toString().contains(second.getString("loanId")));assertEquals("DESHABILITADA",row[4])
    }
    private fun dayTimestamp() = today().atTime(9, 15, 30).atZone(LIMA).toInstant().plusNanos(123456789)

    @Test fun cashMovementsRoundTripThroughTheNativeJournalAndOfflineCache() {
        val http = Google(); val cache = Cache(); val repo = repo(http, cache); repo.preload(2026) { _, _ -> }
        repo.write(pending(movementBody()))
        repo.write(pending(movementBody(CashMovementType.WITHDRAWAL, 4000), "withdrawal-operation-1"))
        val reloaded = repo(http); val data = reloaded.preload(2026) { _, _ -> }
        assertEquals(56000L, data.summary.cash); assertEquals(100000L, data.summary.initial)
        assertEquals(50000L, data.summary.disbursed); assertEquals(0L, data.summary.earnings)
        assertEquals(2, reloaded.movements().size); assertEquals("Descripción opcional", reloaded.movements().last().description)
        val cached = repo(http, cache); assertEquals(56000L, cached.cached(2026)!!.summary.cash)
        assertEquals(2, cached.movements().size)
        assertEquals(11, http.formColumns)
    }
    @Test fun aCashRetryAfterLostAcknowledgmentNeverDepositsTwice() {
        val http = Google(); val repo = repo(http); repo.preload(2026) { _, _ -> }
        val operation = pending(movementBody()); http.timeoutAfterCommit = true
        repo.write(operation); repo.write(operation)
        assertEquals(1, http.writes)
        assertEquals(60000L, repo.current().summary.cash)
        assertEquals(1, repo.movements().size)
    }
    @Test fun aWithdrawalWithoutEnoughCashNeverWritesOrInfersExtraCapital() {
        val http = Google(); val repo = repo(http); repo.preload(2026) { _, _ -> }
        assertThrows(DirectPaymentRejected::class.java) { repo.write(pending(movementBody(CashMovementType.WITHDRAWAL, 50001))) }
        assertEquals(0, http.writes); assertEquals(50000L, repo.current().summary.cash)
    }
    @Test fun aManualChangeToCashJournalAmountsIsDetectedInsteadOfSilentlyChangingCash() {
        val http = Google(); val repo = repo(http); repo.preload(2026) { _, _ -> }; repo.write(pending(movementBody()))
        http.form[23][6] = 999.0
        assertThrows(IllegalArgumentException::class.java) { repo(http).preload(2026) { _, _ -> } }
    }
    @Test fun groupingNameEditPreservesMultiplePaymentBasesMoraCreditsAndCash() {
        val http = Google(); val repository = repo(http); val data = repository.preload(2026) { _, _ -> }
        val loan = data.loans.single(); val calendar = repository.calendar(loan, false)
        val amount = loan.first + loan.lateAt(loan.firstDue, today()) + loan.lateAt(loan.firstDue.plusDays(1), today()) + 50
        val plan = planAmountPayment(recordForAmount(loan, calendar, today()), amount, today())
        repository.write(pending(JSONObject().put("action", "amountPayment").put("loanId", loan.id)
            .put("version", loanDeletionVersion(loan)).put("amountCents", amount).put("waiveLate", false).put("confirmation", plan.confirmation())))
        val before = repository.current(); val source = before.loans.single()
        val beforeRows = repository.offlineCalendarRows(source)
        val edit = LoanTextEdit(source.id, source.client, source.guarantee, "Cliente_Demo único", source.guarantee)
        repository.editText(edit); repository.editText(edit)
        val after = repository.current(); val renamed = after.loans.single()
        assertEquals(2, http.writes)
        assertEquals(before.summary, after.summary)
        assertEquals(source.advances, renamed.advances)
        assertEquals(source.scheduledBases, renamed.scheduledBases)
        assertEquals(source.debt, renamed.debt)
        assertEquals("Cliente_Demo único", renamed.client)
        val afterRows = repository.offlineCalendarRows(renamed)
        assertEquals(beforeRows.map { listOf(it.due, it.base, it.unpaid, it.late, it.paidOn) },
            afterRows.map { listOf(it.due, it.base, it.unpaid, it.late, it.paidOn) })
    }
    @Test fun groupingNameEditDoesNotRequireOrInventDatesForLegacyPaidInstallments() {
        val http = Google(); val repository = repo(http); val loan = repository.preload(2026) { _, _ -> }.loans.single()
        val column = simpleDayColumn(loan.firstDue)
        http.loans[1][column] = false
        http.loans[1][column + 2] = ""
        val before = repository.preload(2026) { _, _ -> }; val source = before.loans.single()
        assertEquals(1, source.paid)
        assertNull(repository.offlineCalendarRows(source).first().paidOn)
        repository.editText(LoanTextEdit(source.id, source.client, source.guarantee, "Cliente_Demo unificado", source.guarantee))
        val after = repository.current()
        assertEquals(before.summary, after.summary)
        assertEquals("Cliente_Demo unificado", after.loans.single().client)
        assertEquals(1, after.loans.single().paid)
        assertNull(repository.offlineCalendarRows(after.loans.single()).first().paidOn)
    }
    @Test fun separateGroupingCommandsCanRepeatTheSameRenameWithoutReusingAnOldReceipt() {
        val http = Google(); val repository = repo(http); val source = repository.preload(2026) { _, _ -> }.loans.single()
        val edit = LoanTextEdit(source.id, source.client, source.guarantee, "Cliente_Demo único", source.guarantee)
        repository.editText(edit, "first-group-command")
        http.loans[1][1] = source.client
        repository.editText(edit, "second-group-command")
        assertEquals(2, http.writes)
        assertEquals("Cliente_Demo único", repository.current().loans.single().client)
    }
    @Test fun aGroupedNameRetryKeepsItsReceiptWhenAnUnchangedGuaranteeEvolvesExternally() {
        val http = Google(); val repository = repo(http); val source = repository.preload(2026) { _, _ -> }.loans.single()
        val edit = LoanTextEdit(source.id, source.client, source.guarantee, "Cliente_Demo único", source.guarantee)
        repository.editText(edit, "durable-group-command")
        http.loans[1][5] = "Garantía nueva en Google"
        val current = repository.preload(2026) { _, _ -> }.loans.single()
        repository.editText(rebasedLoanTextEdit(source, current, edit.client, edit.guarantee), "durable-group-command")
        assertEquals(1, http.writes)
        assertEquals("Garantía nueva en Google", repository.current().loans.single().guarantee)
    }

    @Test fun conflictingNameEditsDoNotOverwriteAnotherClientInTheSimpleBook() {
        val http = Google(); val repository = repo(http); val source = repository.preload(2026) { _, _ -> }.loans.single()
        http.loans[1][1] = "Cliente distinto"
        assertThrows(DirectPaymentRejected::class.java) {
            repository.editText(LoanTextEdit(source.id, source.client, source.guarantee, "Cliente_Demo único", source.guarantee))
        }
        assertEquals(0, http.writes)
        assertEquals("Cliente distinto", http.loans[1][1])
    }

    @Test fun startupUsesTwoRequestsAndBrowsingUsesTheLocalSnapshot() {
        val http=Google();val repo=repo(http);val data=repo.preload(2026) { _,_-> }
        assertEquals(2,http.reads);assertEquals(50_000L,data.summary.cash)
        repo.loans(2026);repo.allLoans();repo.cash();repo.calendar(data.loans.single(),false)
        repo.payments(today().minusDays(2),today(),null,null)
        assertEquals(2,http.reads)
    }
    @Test fun amountPaymentIsAtomicDurableAndIdempotentWithAFeeRemainder() {
        val http=Google();val repository=repo(http);val original=repository.preload(2026){_,_->}
        val loan=original.loans.single();val calendar=repository.calendar(loan,false)
        val amount=loan.first+loan.lateAt(loan.firstDue,today())+50
        val plan=planAmountPayment(recordForAmount(loan,calendar,today()),amount,today())
        val operation=pending(JSONObject().put("action","amountPayment").put("loanId",loan.id).put("year",loan.year)
            .put("version",loanDeletionVersion(loan)).put("amountCents",amount).put("waiveLate",false).put("confirmation",plan.confirmation()))
        http.timeoutAfterCommit=true
        repository.write(operation);repository.write(operation)
        assertEquals(1,http.writes)
        val fresh=repository.current()
        assertEquals(original.summary.cash+amount,fresh.summary.cash)
        val reloaded=repo(http).preload(2026){_,_->}
        assertEquals(fresh.summary.cash,reloaded.summary.cash)
        assertEquals(1,reloaded.loans.single().paid)
        val next=repository.calendar(fresh.loans.single(),false).days[1].payment!!
        assertTrue(next.unpaid);assertEquals(loan.lateAt(next.due,today())-50,next.late)
        val source=repository.calendar(fresh.loans.single(),false).days.first().payment!!
        val undo=paymentBody(source);undo.getJSONArray("edits").getJSONObject(0).put("unpaid",true).put("paidOn","")
        repository.write(pending(undo,"undo-amount-payment"))
        val reopened=repository.current();assertTrue(reopened.loans.single().advances.isEmpty())
        assertEquals(original.summary.cash,reopened.summary.cash)
        val sourceAgain=repository.calendar(reopened.loans.single(),false).days.first().payment!!
        val payAgain=paymentBody(sourceAgain);payAgain.getJSONArray("edits").getJSONObject(0).put("collectedLate",loan.lateAt(sourceAgain.due,today())/100.0)
        repository.write(pending(payAgain,"pay-amount-again"))
        val final=repository.current()
        assertTrue(final.loans.single().advances.isEmpty())
        assertEquals(loan.lateAt(next.due,today()),repository.calendar(final.loans.single(),false).days[1].payment!!.late)
    }
    @Test fun simplePresentationRefreshesRemoteRowsWithoutChangingTheFinancialJournal() {
        val http=Google(); val repository=repo(http)
        repository.preload(2026) { _,_-> }
        val beforeLoans=http.loans.map { it.toList() }; val beforeForm=http.form.map { it.toList() }
        repository.syncPresentation()
        assertEquals(1,http.writes)
        assertEquals(beforeLoans,http.loans); assertEquals(beforeForm,http.form)
        assertTrue(http.names.isEmpty())
    }
    @Test fun cachedStartupDoesNotContactGoogleAndRejectsAnotherAccount() {
        val http=Google();val cache=Cache();repo(http,cache).preload(2026) { _,_-> }
        val restored=repo(http,cache).cached(2026)!!
        assertEquals(1,restored.loans.size);assertEquals(2,http.reads)
        assertThrows(IllegalArgumentException::class.java) { SimpleSheetsRepository(bookId,"other",http,cache).cached(2026) }
    }
    @Test fun paymentRetryAfterLostResponseDoesNotWriteTwice() {
        val http=Google();val repo=repo(http);repo.preload(2026) { _,_-> }
        val payment=repo.payments(today().minusDays(2),today().minusDays(2),null,null).single()
        val pending=pending(paymentBody(payment));http.timeoutAfterCommit=true
        val receipt=repo.write(pending)
        assertEquals(1,receipt.getInt("updated"));assertEquals(1,http.writes)
        repo.write(pending)
        assertEquals(1,http.writes)
        val data=repo.current();assertEquals(50_000L+payment.base,data.summary.cash)
        assertFalse(repo.payments(payment.due,payment.due,null,null).single().unpaid)
    }
    @Test fun stalePaymentCannotOverwriteAnotherChange() {
        val http=Google();val repo=repo(http);repo.preload(2026) { _,_-> }
        val payment=repo.payments(today().minusDays(2),today().minusDays(2),null,null).single()
        http.loans[1][15]="another-operation"
        assertThrows(DirectPaymentRejected::class.java) { repo.write(pending(paymentBody(payment))) }
        assertEquals(0,http.writes)
    }
    @Test fun insufficientFundsAddCapitalOnceAndNewLoanKeepsOneRowAcrossYears() {
        val http=Google();val repo=repo(http);repo.preload(2026) { _,_-> }
        val body=JSONObject().put("action","createLoan").put("disbursedOn",today().toString()).put("loan",JSONObject()
            .put("client","Segundo").put("principal",600).put("rate",0.02).put("months",3).put("guarantee","")
            .put("origin","Del capital").put("contribution",0))
        val operation = pending(body)
        val receipt=repo.write(operation)
        assertEquals(10_000L, receipt.getLong("contributionCents"))
        repo.write(operation)
        assertEquals(1,http.writes)
        assertEquals("2026-002",receipt.getString("loanId"));assertEquals(3,http.loans.size)
        assertEquals(0L,repo.current().summary.cash)
        assertEquals(1,repo.loans(2027).size)
    }
    @Test fun deleteRetainsTheCompleteRecordAndRecalculatesCash() {
        val http=Google();val repo=repo(http);val data=repo.preload(2026) { _,_-> }
        val loan=data.loans.single()
        repo.write(pending(JSONObject().put("action","deleteLoan").put("loanId",loan.id).put("version",loanDeletionVersion(loan))))
        assertEquals("ELIMINADO",http.loans[1][14]);assertEquals("Cliente",http.loans[1][1])
        assertTrue(repo.current().loans.isEmpty());assertEquals(100_000L,repo.current().summary.cash)
    }
    @Test fun changingTermsPreservesPaidAmountsAndTheRecordedPaymentDate() {
        val http=Google();val repo=repo(http);repo.preload(2026) { _,_-> }
        val payment=repo.payments(today().minusDays(2),today().minusDays(2),null,null).single()
        repo.write(pending(paymentBody(payment)))
        val view=repo.current().loans.single();val calendar=repo.calendar(view,false)
        val input=LoanTermsInput(view.client,view.guarantee,view.principal,BigDecimal("3"),2,
            view.firstDue.minusDays(1),50)
        val plan=planLoanTerms(view,calendar,input,today(),repo.current().summary.cash)
        repo.write(pending(JSONObject().put("action","editLoanTerms").put("loanId",view.id)
            .put("version",loanDeletionVersion(view)).put("terms",input.json()).put("confirmation",plan.confirmation()),"operation-terms"))
        val updated=repo.payments(payment.due,payment.due,null,null).single()
        assertEquals(payment.base,updated.base);assertEquals(today(),updated.paidOn)
        assertEquals(60,repo.current().loans.single().total)
        assertEquals(50L,repo.current().loans.single().dailyLate)
    }
    @Test fun changingCapitalRequiresTheConfirmedCashAndSupportsAnEmptyBook() {
        val http=Google();val repo=repo(http);repo.preload(2026) { _,_-> }
        val body=JSONObject().put("action","setCapital").put("year",2026).put("beforeCents",100000)
            .put("afterCents",120000).put("cashBeforeCents",50000)
        repo.write(pending(body));assertEquals(70000L,repo.current().summary.cash)
        http.loans.removeAt(1)
        repo.preload(2026) { _,_-> }
        repo.write(pending(JSONObject().put("action","setCapital").put("year",2026).put("beforeCents",120000)
            .put("afterCents",100000).put("cashBeforeCents",120000),"operation-empty"))
        assertEquals(100000L,repo.current().summary.cash)
    }
    @Test fun aFormulaInAnInputCannotBeOverwrittenByAPayment() {
        val http=Google();val repo=repo(http);repo.preload(2026) { _,_-> }
        val payment=repo.payments(today().minusDays(2),today().minusDays(2),null,null).single()
        http.loans[1][2]="=500"
        assertThrows(NumberFormatException::class.java) { repo.write(pending(paymentBody(payment))) }
        assertEquals(0,http.writes)
    }
    @Test fun productionBookCannotUseTheExperimentalWriter() {
        assertThrows(IllegalArgumentException::class.java) { SimpleSheetsRepository(SheetsIdentity.PRODUCTION_ID,"account",Google(),Cache()) }
    }

    @Test fun delayedReceiptReloadsBalancesAndEditableVersionsWithoutDoubleCollecting() {
        val http=Google(); val repo=repo(http); val initial=repo.preload(2026) { _,_-> }
        val first=repo.calendar(initial.loans.single(),false).days.first().payment!!
        val send=pending(paymentBody(first))
        http.timeoutAfterCommit=true; http.loseVerification=true
        assertThrows(SocketTimeoutException::class.java) { repo.write(send) }
        http.failReads=false
        repo.write(send)
        val updated=repo.current()
        assertEquals(initial.summary.cash+first.base,updated.summary.cash)
        assertEquals(first.base*1000/51000.0,updated.summary.interest.toDouble(),1.0)
        val fresh=repo.calendar(updated.loans.single(),false).days.first().payment!!
        assertFalse(fresh.unpaid); assertNotEquals(first.version,fresh.version)
        assertEquals(1,http.writes)
        assertEquals(updated.summary.cash,repo.cash().last().cash)
    }

    @Test fun firstAndSecondDaysCanBePaidAndReopenedRepeatedlyWithCashAndInterestReconciled() {
        val http=Google(); val repo=repo(http); val initial=repo.preload(2026) { _,_-> }
        for(index in 0..1) repeat(3) { cycle ->
            val before=repo.current(); val row=repo.calendar(before.loans.single(),false).days[index].payment!!
            val fee=50L
            val body=paymentBody(row);body.getJSONArray("edits").getJSONObject(0).put("collectedLate",fee/100.0)
            repo.write(pending(body,"pay-$index-$cycle".padEnd(12,'x')))
            val paid=repo.current();assertEquals(initial.summary.cash+row.base+fee,paid.summary.cash)
            assertTrue(paid.summary.interest>0);assertEquals(fee,paid.summary.late)
            assertEquals(paid.summary.cash,repo.cash().last().cash)
            val calendar=repo.calendar(paid.loans.single(),false)
            assertTrue(calendarMatchesLoan(calendar,paid.loans.single()))
            val fresh=calendar.days[index].payment!!
            assertFalse(fresh.unpaid);assertNotNull(fresh.version)
            val reopen=paymentBody(fresh);reopen.getJSONArray("edits").getJSONObject(0)
                .put("unpaid",true).put("paidOn","").put("collectedLate",0)
            repo.write(pending(reopen,"undo-$index-$cycle".padEnd(12,'x')))
            assertEquals(initial.summary.cash,repo.current().summary.cash)
            assertEquals(0L,repo.current().summary.interest);assertEquals(0L,repo.current().summary.late)
        }
    }

    @Test fun initialHistoricalPaidSelectionCollectsOnlyDueDaysAndCanBeReopened() {
        val http=Google();val repo=repo(http);val initial=repo.preload(2026) { _,_-> }
        val body=JSONObject().put("action","createLoan").put("disbursedOn",today().minusDays(10).toString())
            .put("pastPaymentsPaid",true).put("loan",JSONObject().put("client","Anterior").put("principal",100)
                .put("rate",0.02).put("months",1).put("guarantee","").put("origin","Del capital").put("contribution",0))
        val created=repo.write(pending(body)).getString("loanId")
        val view=repo.current().loans.single { it.id==created }
        assertEquals(10,view.paid)
        val calendar=repo.calendar(view,false)
        assertEquals(20,calendar.days.count { it.status==CalendarStatus.FUTURE })
        val paid=calendar.days.mapNotNull { it.payment }
        assertTrue(paid.all { !it.unpaid && it.paidOn==it.due && it.late==0L })
        assertEquals(initial.summary.cash-10_000+paid.sumOf { it.base },repo.current().summary.cash)
        assertEquals(repo.current().summary.cash,repo.cash().last().cash)
        assertTrue(repo.current().summary.interest>0)
        val reopen=stageBulkPayments(emptyMap(),paid,true,today())
        repo.write(pending(JSONObject().put("action","pay").put("edits",JSONArray(reopen.values.map { (row,edit) ->
            JSONObject().put("version",JSONObject(row.version!!)).put("unpaid",edit.unpaid).put("collectedLate",0).put("paidOn","")
        })),"reopen-initial"))
        assertEquals(initial.summary.cash-10_000,repo.current().summary.cash)
        assertEquals(0L,repo.current().summary.interest)
    }

    @Test fun batchesOfOneHundredRebaseTheRemainingDaysAndKeepEveryCent() {
        val http=Google();http.loans.removeAt(1)
        val loan=Rules.createLoan("2026-001",LoanRequest("Largo",50_000,BigDecimal("2"),4,"",Funding.CAPITAL,0),today().minusDays(120))
        http.loans+=SimpleRecord(2,loan,100,false,"first").cells(http.until).toMutableList()
        val repo=repo(http);val initial=repo.preload(2026) { _,_-> }
        var draft=stageBulkPayments(emptyMap(),repo.calendar(initial.loans.single(),false).days.mapNotNull { it.payment },false,today())
        assertEquals(120,draft.size)
        for(batch in 0..1) {
            val selected=draft.values.take(100)
            val body=JSONObject().put("action","pay").put("edits",JSONArray(selected.map { (row,edit) ->
                JSONObject().put("version",JSONObject(row.version!!)).put("unpaid",false).put("collectedLate",0).put("paidOn",edit.paidOn.toString())
            }))
            repo.write(pending(body,"batch-00$batch"))
            val fresh=repo.calendar(repo.current().loans.single(),false).days.mapNotNull { it.payment }
            draft=rebasePaymentDrafts(remainingPaymentDrafts(draft,selected.map { it.first.id }.toSet()),fresh)
        }
        assertTrue(draft.isEmpty());assertEquals(120,repo.current().loans.single().paid)
        assertEquals(initial.summary.cash+loan.agreed,repo.current().summary.cash)
        assertEquals(loan.agreed-loan.principal,repo.current().summary.interest)
        assertEquals(repo.current().summary.cash,repo.cash().last().cash)
    }

    private fun localView(repository: SimpleSheetsRepository): LocalBookView {
        val data = repository.preload(today().year) { _, _ -> }
        val loans = repository.allLoans()
        return LocalBookView(data, loans, loans.flatMap(repository::offlineCalendarRows), repository.cash(), repository.contributions(), true)
    }
    @Test fun summaryRowsDoNotBreakStartupCreationOrCalendarExtension() {
        val http = Google()
        repeat(6) { http.loans.add(0, mutableListOf("Resumen")) }
        val repository = repo(http)
        val data = repository.preload(today().year) { _, _ -> }
        assertEquals(8, data.loans.single().row)
        val request = JSONObject().put("action", "createLoan").put("disbursedOn", today().toString()).put("loan", JSONObject()
            .put("client", "Cliente nuevo").put("principal", 100).put("rate", 0.02).put("months", 12)
            .put("guarantee", "").put("origin", "Del capital").put("contribution", 0))
        val receipt = repository.write(pending(request))
        assertEquals(9, repository.allLoans().single { it.id == receipt.getString("loanId") }.row)
        assertEquals("Resumen", http.loans[0][0])
        assertEquals("ID", http.loans[6][0])
        assertTrue(http.columns > 1476)
    }
    @Test fun queuedCreationPaymentAndCapitalReachTheRealWriterInOrderWithoutDuplicateWrites() {
        val google = Google(); val repository = repo(google)
        val queue = LocalOperations(MemorySheetCache(), "account", bookId)
        val base = localView(repository); queue.refresh(base)
        val create = JSONObject().put("action", "createLoan").put("disbursedOn", today().minusDays(1).toString()).put("loan", JSONObject()
            .put("client", "Cliente sin internet").put("principal", 100).put("rate", 0.02).put("months", 1)
            .put("guarantee", "").put("origin", "Del capital").put("contribution", 0))
        val created = queue.enqueue(create, "actor")
        val localId = created.body.getString("localLoanId")
        val row = queue.read().view!!.rows.single { it.loan.id == localId && it.due == today() }
        queue.enqueue(paymentBody(row), "actor")
        val localSummary = queue.read().view!!.data.summary
        queue.enqueue(JSONObject().put("action", "setCapital").put("year", today().year).put("beforeCents", localSummary.initial)
            .put("afterCents", localSummary.initial + 10_000).put("cashBeforeCents", localSummary.cash), "actor")
        val expectedCash = queue.read().view!!.data.summary.cash
        assertEquals(0, google.writes)
        repeat(3) {
            val fresh = localView(repository)
            val request = queue.prepare(fresh) { repository.calendar(it, true) }!!
            val receipt = repository.write(request)
            assertEquals(receipt.toString(), repository.write(request).toString())
            queue.confirmed(request, receipt, localView(repository))
        }
        assertEquals(3, google.writes)
        assertTrue(queue.read().operations.isEmpty())
        assertEquals(2, repository.allLoans().size)
        assertEquals(expectedCash, repository.current().summary.cash)
        assertEquals(1, repository.allLoans().single { it.client == "Cliente sin internet" }.paid)
    }
}

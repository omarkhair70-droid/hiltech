package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class FinanceSettlementLedgerApiClientTest {
    private val org="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val id="11111111-2222-4333-8444-555555555555"
    private val debit="22222222-3333-4444-8555-666666666666"
    private val credit="33333333-4444-4555-8666-777777777777"
    private val period=FinanceDeskPeriodDto("44444444-5555-4666-8777-888888888888","2026-11-01","2026-11-30","OPEN")
    private val source=FinanceSettlementSourceDto("BANK_ALLOCATION",id,"AR",credit,credit,"100.000000","EGP",
        "2026-11-25T12:00:00Z","Synthetic bank","Synthetic customer",null,debit,"Bank",credit,"Receivables")
    @Test fun onlyExactMappedSourceCanBePreparedAndConfirmed() {
        assertNull(financeSettlementIntent(source.copy(debitAccountId=null),period,id))
        assertNull(financeSettlementIntent(source,period.copy(state="CLOSED"),id))
        assertNull(financeSettlementIntent(source.copy(creditAccountId=debit),period,id))
        val input=assertNotNull(financeSettlementIntent(source,period,id))
        val receipt=FinanceSettlementReceiptDto(org,id,credit,source.sourceType,id,"AR",period.id,debit,credit,"100","EGP",false,false)
        assertTrue(financeSettlementReceiptMatches(org,input,receipt))
        assertFalse(financeSettlementReceiptMatches(org,input,receipt.copy(amount="100.01")))
        assertFalse(financeSettlementReceiptMatches(org,input,receipt.copy(moneyTransferredByHiltech=true)))
        assertFalse(financeSettlementReceiptMatches(org,input,receipt.copy(debitAccountId=credit)))
        assertFalse(financeSettlementReceiptMatches(org,input,receipt.copy(organizationId=credit)))
    }
    @Test fun employeeFundingAndPaidCostRequireTheirOwnAccountTreatment() {
        val choice=FinanceSettlementAccountChoiceDto(debit,"CUSTODY","Custody asset","EMPLOYEE_IMPREST_ASSET")
        val funding=source.copy(sourceType="EMPLOYEE_FUNDING",sourceKind="EI",sourceJournalId=null,
            employeeFundingRecordId=id,requiredDebitKind="EMPLOYEE_IMPREST_ASSET",debitAccountId=null,debitAccountName=null,
            debitChoices=listOf(choice))
        assertNull(financeSettlementIntent(funding,period,id))
        assertNull(financeSettlementIntent(funding.copy(debitChoices=listOf(choice.copy(accountKind="EXPENSE"))),period,id,debit))
        val approved=assertNotNull(financeSettlementIntent(funding,period,id,debit))
        assertNull(approved.accountingTreatmentRef)
        val paidCost=funding.copy(sourceType="IMPREST_EXPENSE",requiredDebitKind="COST",
            debitChoices=listOf(choice.copy(accountKind="EXPENSE")))
        assertNull(financeSettlementIntent(paidCost,period,id,debit))
        assertNull(financeSettlementIntent(paidCost,period,id,debit,"short"))
        val intent=assertNotNull(financeSettlementIntent(paidCost,period,id,debit,"SYNTHETIC-TREATMENT"))
        val receipt=FinanceSettlementReceiptDto(org,id,credit,intent.sourceType,id,"EI",period.id,debit,credit,"100","EGP",
            false,false,accountingTreatmentRef="SYNTHETIC-TREATMENT")
        assertTrue(financeSettlementReceiptMatches(org,intent,receipt))
        assertFalse(financeSettlementReceiptMatches(org,intent,receipt.copy(accountingTreatmentRef=null)))
        assertNull(financeSettlementIntent(source,period,id,accountingTreatmentRef="UNEXPECTED-TREATMENT"))
    }
    @Test fun exactSourceIntentIsSentWithIdempotencyKey()=runBlocking {
        val input=assertNotNull(financeSettlementIntent(source,period,id))
        val receipt=FinanceSettlementReceiptDto(org,id,credit,source.sourceType,id,"AR",period.id,debit,credit,"100","EGP",false,false)
        val http=HttpClient(MockEngine {request->
            assertEquals(HttpMethod.Post,request.method)
            assertEquals("/v1/finance/organizations/$org/settlement-ledger",request.url.encodedPath)
            assertEquals(id,request.headers["Idempotency-Key"])
            respond(Json.encodeToString(FinanceSettlementReceiptDto.serializer(),receipt),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {assertEquals(receipt,FinanceSettlementLedgerApiClient(HiltechApiClient(http,"https://finance.test",{"test-token"},{"test-correlation"}))
            .post(org,input,"synthetic-install"))}
        finally {http.close()}
    }
}

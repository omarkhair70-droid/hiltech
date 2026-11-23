package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinanceCashboxAndAllocationCompletionTest {
    private val org="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val proposal="11111111-2222-4333-8444-555555555555"
    private val source="22222222-3333-4444-8555-666666666666"
    private val approval="33333333-4444-4555-8666-777777777777"
    private val op="44444444-5555-4666-8777-888888888888"
    private val recorded="55555555-6666-4777-8888-999999999999"
    private val install="finance-install"
    private fun apis(client:HttpClient)=FinanceEvidenceReadApis(HiltechApiClient(
        client,"https://finance.test",accessTokenProvider={"finance-operator"},
        correlationIdProvider={"finance-cashbox-and-allocation-test"},
    ))

    @Test
    fun openingRequiresExactApprovedSourceAndZeroOpeningBalance()=runBlocking {
        var requests=0
        val approved="""{"proposalId":"$proposal","organizationId":"$org","cashboxCode":"SAFE-1","currency":"EGP","custodianUserId":"$source","state":"APPROVED","approvalRequestId":"$approval","openingBalance":"0.000000"}"""
        val active=approved.dropLast(1).replace("\"APPROVED\"","\"ACTIVE\"")+
            ""","cashboxId":"$proposal"}"""
        val client=HttpClient(MockEngine {req->
            requests++
            assertEquals("/v1/finance/organizations/$org/cashboxes/$proposal"+
                if(requests==3) "/activate" else "",req.url.encodedPath)
            assertEquals(if(requests==3)"POST" else "GET",req.method.value)
            if(requests==3)assertEquals(op,req.headers["Idempotency-Key"])
            respond(if(requests==3)active else approved,HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try{
            val cash=apis(client).cash
            val expected=cash.cashboxStatus(org,proposal,install)
            val result=activateApprovedCashbox(cash,org,install,expected,op)
            assertEquals("ACTIVE",result.state)
            assertEquals(proposal,result.cashboxId)
            assertEquals(3,requests)
            assertTrue(financeCashboxOpeningDraftValid("SAFE-1","EGP",source,"COMPANY-REF"))
            assertFalse(financeCashboxOpeningDraftValid("SAFE-1","EGP","unknown","COMPANY-REF"))
        }finally{client.close()}
    }

    @Test
    fun allocationAndCorrectionRecheckSourceBeforeRecording()=runBlocking {
        for(kind in AllocationOperatorKind.entries){
            val route=if(kind==AllocationOperatorKind.ALLOCATION)
                "cash-allocation-proposals" else "cash-allocation-corrections"
            val finalState=if(kind==AllocationOperatorKind.ALLOCATION)"RECORDED" else "CORRECTED"
            val idField=if(kind==AllocationOperatorKind.ALLOCATION)"recordedAllocationId" else "correctionRecordId"
            val base="""{"proposalId":"$proposal","organizationId":"$org","sourceKind":"AR","sourceJournalId":"$source","bankStatementLineId":"$source","amount":"100.000000","currency":"EGP","state":"APPROVED","approvalRequestId":"$approval""""
            val approved=base+(if(kind==AllocationOperatorKind.CORRECTION)
                ""","originalAllocationId":"$source"}""" else "}")
            val final=approved.dropLast(1).replace("\"APPROVED\"","\"$finalState\"")+
                ""","$idField":"$recorded"}"""
            var requests=0
            val client=HttpClient(MockEngine {req->
                requests++
                assertEquals("/v1/finance/organizations/$org/$route/$proposal"+
                    if(requests==2) "/record" else "",req.url.encodedPath)
                assertEquals(if(requests==2)"POST" else "GET",req.method.value)
                if(requests==2)assertEquals(op,req.headers["Idempotency-Key"])
                respond(if(requests==2)final else approved,HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType,"application/json"))
            })
            try{
                val expected=AllocationOperatorSource(kind,proposal,org,"APPROVED",approval,
                    "AR",source,source,"100.000000","EGP",
                    if(kind==AllocationOperatorKind.CORRECTION)source else null,null)
                assertEquals(recorded,recordApprovedAllocation(apis(client),org,install,expected,op).recordedId)
                assertEquals(2,requests)
            }finally{client.close()}
        }
    }

    @Test
    fun pendingAllocationNeverPosts()=runBlocking {
        var requests=0
        val client=HttpClient(MockEngine {req->
            requests++
            assertEquals("GET",req.method.value)
            respond("""{"proposalId":"$proposal","organizationId":"$org","sourceKind":"AR","sourceJournalId":"$source","bankStatementLineId":"$source","amount":"100","currency":"EGP","state":"PENDING","approvalRequestId":"$approval"}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try{
            val expected=AllocationOperatorSource(AllocationOperatorKind.ALLOCATION,proposal,org,
                "APPROVED",approval,"AR",source,source,"100","EGP",null,null)
            assertFailsWith<IllegalStateException>{
                recordApprovedAllocation(apis(client),org,install,expected,op)
            }
            assertEquals(1,requests)
        }finally{client.close()}
    }
}

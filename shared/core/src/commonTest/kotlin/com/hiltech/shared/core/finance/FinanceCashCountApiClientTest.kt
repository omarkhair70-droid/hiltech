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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class FinanceCashCountApiClientTest {
 private val org="11111111-1111-1111-1111-111111111111"
 private val box="22222222-2222-2222-2222-222222222222"
 private val operation="33333333-3333-3333-3333-333333333333"
 private val proposal="44444444-4444-4444-4444-444444444444"
 private fun client(http:HttpClient)=FinanceCashCountApiClient(
   HiltechApiClient(http,"https://finance.hiltech.test",
     accessTokenProvider={"company-cash-session-token"},
     correlationIdProvider={"cash-witness-trace"}),
 )
 @Test fun countIsWitnessedObservationNotAnAdjustment()=runBlocking {
   var calls=0
   val http=HttpClient(MockEngine{request->
     calls++
     assertEquals("POST",request.method.value)
     assertEquals("/v1/finance/organizations/$org/cash-counts/prepare",
       request.url.encodedPath)
     assertEquals("Bearer company-cash-session-token",
       request.headers[HttpHeaders.Authorization])
     assertEquals(operation,request.headers["Idempotency-Key"])
     respond(
       """{"proposalId":"$proposal","organizationId":"$org",
           "cashboxId":"$box","currency":"EGP",
           "countedAmount":"5.00","ledgerAmount":"0.00",
           "varianceAmount":"5.00","voucherCount":0,
           "state":"PROPOSED","ledgerAdjusted":false,
           "bankTransferInitiated":false}""",
       HttpStatusCode.OK,
       headersOf(HttpHeaders.ContentType,"application/json"),
     )
   })
   val result=client(http).prepare(org,FinanceCashCountPrepareDto(
     operation,box,"5.00","SIGNED-CASH-COUNT-2026"),"installation-2026")
   assertEquals(1,calls)
   assertEquals("5.00",result.varianceAmount)
   assertFalse(result.ledgerAdjusted)
   assertFalse(result.bankTransferInitiated)
 }
 @Test fun invalidCountCannotIssueNetworkCommand()=runBlocking {
   var calls=0
   val http=HttpClient(MockEngine{calls++;error("Unexpected finance request")})
   assertFailsWith<IllegalArgumentException>{
     client(http).prepare(org,FinanceCashCountPrepareDto(
       operation,box,"-100.00","SIGNED-CASH-COUNT-2026"),"installation-2026")
   }
   assertEquals(0,calls)
 }
 @Test fun signedEvidenceMustBeUuidBeforeRecord()=runBlocking {
   var calls=0
   val http=HttpClient(MockEngine{calls++;error("Unexpected finance request")})
   assertFailsWith<IllegalArgumentException>{
     client(http).record(org,proposal,operation,"NOT-EVIDENCE",
       "installation-2026")
   }
   assertEquals(0,calls)
 }
}

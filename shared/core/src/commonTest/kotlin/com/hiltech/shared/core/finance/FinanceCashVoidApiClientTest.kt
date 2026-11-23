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
import kotlin.test.assertTrue

class FinanceCashVoidApiClientTest {
 private val org="11111111-1111-1111-1111-111111111111"
 private val original="22222222-2222-2222-2222-222222222222"
 private val operation="33333333-3333-3333-3333-333333333333"
 private val installation="44444444-4444-4444-4444-444444444444"
 private val proposal="55555555-5555-5555-5555-555555555555"
 private fun client(http:HttpClient)=FinanceCashVoidApiClient(
   HiltechApiClient(http,"https://finance.hiltech.test",
     accessTokenProvider={"cash-void-finance-token"},
     correlationIdProvider={"cash-void-correlation"}),
 )
 @Test fun correctionNeverClaimsRefundOrGeneralLedgerReversal()=runBlocking {
    var calls=0
    val http=HttpClient(MockEngine {req->
      calls++
      assertEquals("POST",req.method.value)
      assertEquals(
         "/v1/finance/organizations/$org/cash-allocations/$original/corrections/prepare",
         req.url.encodedPath,
      )
      assertEquals("Bearer cash-void-finance-token",
         req.headers[HttpHeaders.Authorization])
      respond(
        """{"proposalId":"$proposal","organizationId":"$org",
         "originalAllocationId":"$original","sourceKind":"AR",
         "sourceJournalId":"66666666-6666-6666-6666-666666666666",
         "bankStatementLineId":"77777777-7777-7777-7777-777777777777",
         "amount":"20.000000","currency":"EGP","state":"PROPOSED",
         "originalRecordPreserved":true,"originalMatchStillEffective":true,
         "bankRefundInitiated":false,"generalLedgerReversed":false}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType,"application/json"),
      )
    })
    val response=client(http).prepare(org,original,
        FinanceCashVoidPrepareDto(operation,"Wrong cash source"),
        installation)
    assertEquals(1,calls)
    assertTrue(response.originalRecordPreserved)
    assertTrue(response.originalMatchStillEffective)
    assertFalse(response.bankRefundInitiated)
    assertFalse(response.generalLedgerReversed)
 }
 @Test fun missingCorrectionReasonIsRejectedBeforeHttp()=runBlocking {
    var calls=0
    val http=HttpClient(MockEngine {calls++;error("Unexpected HTTP")})
    assertFailsWith<IllegalArgumentException> {
      client(http).prepare(org,original,
         FinanceCashVoidPrepareDto(operation,"bad"),installation)
    }
    assertEquals(0,calls)
 }
}

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

class FinanceCashAllocationApiClientTest {
 private val org="11111111-1111-1111-1111-111111111111"
 private val journal="22222222-2222-2222-2222-222222222222"
 private val bankLine="33333333-3333-3333-3333-333333333333"
 private val operation="44444444-4444-4444-4444-444444444444"
 private val proposal="55555555-5555-5555-5555-555555555555"
 private val installation="66666666-6666-6666-6666-666666666666"
 private fun service(http:HttpClient)=FinanceCashAllocationApiClient(
     HiltechApiClient(
        http,"https://finance.hiltech.test",
        accessTokenProvider={"real-finance-token"},
        correlationIdProvider={"real-finance-correlation"},
     ),
 )
 @Test fun sourceBoundProposalCannotClaimBankTransfer()=runBlocking {
    var count=0
    val http=HttpClient(MockEngine {req->
      count++
      assertEquals("POST",req.method.value)
      assertEquals(
        "/v1/finance/organizations/$org/posted-journals/AR/$journal/cash-allocation-proposals",
        req.url.encodedPath)
      assertEquals("Bearer real-finance-token",req.headers[HttpHeaders.Authorization])
      respond(
        """{"proposalId":"$proposal","organizationId":"$org","sourceKind":"AR",
        "sourceJournalId":"$journal","bankStatementLineId":"$bankLine",
        "amount":"100.000000","currency":"EGP","state":"PROPOSED",
        "cashAllocatedToAccountingSource":false,
        "moneyTransferredByHiltech":false,"bankReconciled":false}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType,"application/json"),
      )
    })
    val receipt=service(http).propose(
      org,"AR",journal,bankLine,operation,"100.000000",
      "VERIFIED-RECEIPT-SOURCE",installation,
    )
    assertEquals(1,count)
    assertFalse(receipt.cashAllocatedToAccountingSource)
    assertFalse(receipt.moneyTransferredByHiltech)
    assertFalse(receipt.bankReconciled)
 }
}

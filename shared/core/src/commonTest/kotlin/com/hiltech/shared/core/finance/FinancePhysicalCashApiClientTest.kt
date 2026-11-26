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

class FinancePhysicalCashApiClientTest {
 private val org="11111111-1111-1111-1111-111111111111"
 private val cashbox="22222222-2222-2222-2222-222222222222"
 private val journal="33333333-3333-3333-3333-333333333333"
 private val operation="44444444-4444-4444-4444-444444444444"
 private val installation="55555555-5555-5555-5555-555555555555"
 private val custodian="66666666-6666-6666-6666-666666666666"
 private val evidence="77777777-7777-7777-7777-777777777777"
 private fun client(http:HttpClient)=FinancePhysicalCashApiClient(
   HiltechApiClient(http,"https://finance.hiltech.test",
     accessTokenProvider={"company-cash-session-token"},
     correlationIdProvider={"cash-custody-trace"}),
 )
 @Test fun physicalCustomerReceiptDoesNotBecomeBankTransaction()=runBlocking {
    var calls=0
    val http=HttpClient(MockEngine{req->
       calls++
       assertEquals("POST",req.method.value)
       assertEquals(
         "/v1/finance/organizations/$org/cash-vouchers/AR/$journal/prepare",
         req.url.encodedPath)
       assertEquals("Bearer company-cash-session-token",
         req.headers[HttpHeaders.Authorization])
       respond(
         """{"proposalId":"$operation","organizationId":"$org",
         "cashboxId":"$cashbox","sourceKind":"AR",
         "sourceJournalId":"$journal","direction":"RECEIPT",
         "amount":"125.000000","currency":"EGP","state":"PROPOSED",
         "physicalCustodyConfirmed":false,
         "bankTransactionInitiated":false,
         "signedCashboxCountReconciled":false}""",
         HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"),
       )
    })
    val response=client(http).prepareVoucher(
       org,"AR",journal,
       FinanceCashVoucherPrepareDto(
          operation,cashbox,"125.000000","SIGNED-RECEIPT-2026",
          "Partial physical customer collection"),
       installation)
    assertEquals(1,calls)
    assertFalse(response.physicalCustodyConfirmed)
    assertFalse(response.bankTransactionInitiated)
    assertFalse(response.signedCashboxCountReconciled)
 }
 @Test fun zeroOpeningProposalAndFinancialDrawerBalanceAreDistinct()=runBlocking {
    var calls=0
    val http=HttpClient(MockEngine{req->
      calls++
      assertEquals(
        "/v1/finance/organizations/$org/cashboxes/prepare-opening",
        req.url.encodedPath)
      respond(
         """{"proposalId":"$cashbox","organizationId":"$org",
         "cashboxCode":"HQ-CASH-01","currency":"EGP",
         "custodianUserId":"$custodian","state":"PROPOSED",
         "openingBalance":"0.000000"}""",
        HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"),
      )
    })
    val result=client(http).prepareCashbox(org,
      FinanceCashboxOpeningPrepareDto(
        operation,"HQ-CASH-01","EGP",custodian,
        "COMPANY-APPROVED-CASH-CUSTODY"),installation)
    assertEquals(1,calls)
    assertEquals("0.000000",result.openingBalance)
    assertEquals(null,result.cashboxId)
 }
 @Test fun malformedCashPaymentAmountNeverCallsHttp()=runBlocking {
    var calls=0
    val http=HttpClient(MockEngine{calls++;error("unexpected command")})
    assertFailsWith<IllegalArgumentException>{
      client(http).prepareVoucher(org,"AP",journal,
        FinanceCashVoucherPrepareDto(operation,cashbox,"-30.00",
          "SIGNED-PAYMENT-2026","Confirmed supplier receipt"),installation)
    }
    assertEquals(0,calls)
 }
 @Test fun physicalCashRecordRequiresEvidenceIdentifier()=runBlocking{
    var calls=0
    val http=HttpClient(MockEngine{calls++;error("unexpected command")})
    assertFailsWith<IllegalArgumentException>{
      client(http).recordVoucher(org,operation,operation,"NOT-AN-EVIDENCE-ID",
         installation)
    }
    assertEquals(0,calls)
 }
}

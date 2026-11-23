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

class FinanceExpenseReimbursementApiClientTest {
 private val org="11111111-1111-1111-1111-111111111111"
 private val expense="22222222-2222-2222-2222-222222222222"
 private val doc="33333333-3333-3333-3333-333333333333"
 private val bank="44444444-4444-4444-4444-444444444444"
 private val op="55555555-5555-5555-5555-555555555555"
 private val installation="66666666-6666-6666-6666-666666666666"
 private fun client(http:HttpClient)=FinanceExpenseReimbursementApiClient(
   HiltechApiClient(http,"https://finance.hiltech.test",
     accessTokenProvider={"verified-finance-token"},
     correlationIdProvider={"finance-correlation"}),
 )
 @Test fun personalExpenseProposalDoesNotSendMoney()=runBlocking{
    var calls=0
    val http=HttpClient(MockEngine{req->
      calls++
      assertEquals(
        "/v1/finance/organizations/$org/expenses/$expense/reimbursements/prepare",
        req.url.encodedPath)
      assertEquals("Bearer verified-finance-token",
        req.headers[HttpHeaders.Authorization])
      respond(
        """{"proposalId":"77777777-7777-7777-7777-777777777777",
         "organizationId":"$org","expenseDraftId":"$expense",
         "employeeId":"$doc","bankStatementLineId":"$bank",
         "amount":"55.000000","currency":"EGP","state":"PROPOSED",
         "bankDebitAllocatedToEmployeeExpense":false,
         "bankTransferInitiated":false,"expenseRecognizedInGl":false}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType,"application/json"),
      )
    })
    val x=client(http).prepare(org,expense,
      FinanceExpenseReimbursementPrepareDto(op,doc,bank,"55.000000"),installation)
    assertEquals(1,calls)
    assertFalse(x.bankDebitAllocatedToEmployeeExpense)
    assertFalse(x.bankTransferInitiated)
    assertFalse(x.expenseRecognizedInGl)
 }
 @Test fun malformedExpenseAmountFailsBeforeHttp()=runBlocking{
    var calls=0
    val http=HttpClient(MockEngine{calls++;error("Unexpected Finance call")})
    assertFailsWith<IllegalArgumentException>{
      client(http).prepare(org,expense,
        FinanceExpenseReimbursementPrepareDto(op,doc,bank,"-100.00"),installation)
    }
    assertEquals(0,calls)
 }
}

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

class FinanceBankReconciliationSignoffApiClientTest {
 private val org="11111111-1111-1111-1111-111111111111"
 private val intake="22222222-2222-2222-2222-222222222222"
 private val operation="33333333-3333-3333-3333-333333333333"
 private val proposal="44444444-4444-4444-4444-444444444444"
 private fun client(http:HttpClient)=FinanceBankReconciliationSignoffApiClient(
   HiltechApiClient(http,"https://finance.hiltech.test",
     accessTokenProvider={"internal-finance-token"},
     correlationIdProvider={"signed-statement-trace"}),
 )
 @Test fun prepareRequiresReviewedStatementAndNeverPretendsToSendMoney()=runBlocking {
   var calls=0
   val http=HttpClient(MockEngine{request->
     calls++
     assertEquals("POST",request.method.value)
     assertEquals("/v1/finance/organizations/$org/bank-reconciliations/prepare",
       request.url.encodedPath)
     assertEquals(operation,request.headers["Idempotency-Key"])
     assertEquals("Bearer internal-finance-token",
       request.headers[HttpHeaders.Authorization])
     respond("""{"proposalId":"$proposal","organizationId":"$org",
        "intakeId":"$intake","accountId":"$intake",
        "statementSha256":"${"a".repeat(64)}",
        "matchingSha256":"${"b".repeat(64)}","currency":"EGP",
        "lineCount":1,"totalCredit":"100.00","totalDebit":"0.00",
        "totalMatched":"100.00","state":"PROPOSED",
        "internallyReconciled":false,
        "verifiedByBankProvider":false,"bankTransferInitiated":false}""",
       HttpStatusCode.OK,
       headersOf(HttpHeaders.ContentType,"application/json"))
   })
   val result=client(http).prepare(org,FinanceBankReconciliationPrepareDto(
     operation,intake,"COMPANY-RECON-2026"),"installation-2026")
   assertEquals(1,calls)
   assertEquals("PROPOSED",result.state)
   assertFalse(result.internallyReconciled)
   assertFalse(result.verifiedByBankProvider)
   assertFalse(result.bankTransferInitiated)
 }
 @Test fun invalidStatementAndEvidenceAreRejectedBeforeNetwork()=runBlocking {
   var calls=0
   val http=HttpClient(MockEngine{calls++;error("Unexpected finance request")})
   assertFailsWith<IllegalArgumentException>{
     client(http).prepare(org,FinanceBankReconciliationPrepareDto(
       operation,"INVALID","COMPANY-RECON-2026"),"installation-2026")
   }
   assertFailsWith<IllegalArgumentException>{
     client(http).sign(org,proposal,operation,"NOT-EVIDENCE","installation-2026")
   }
   assertEquals(0,calls)
 }
}

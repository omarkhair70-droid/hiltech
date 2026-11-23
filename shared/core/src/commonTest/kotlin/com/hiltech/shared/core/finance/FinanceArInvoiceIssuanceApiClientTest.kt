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

class FinanceArInvoiceIssuanceApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val candidate="22222222-2222-2222-2222-222222222222"
    private val evidence="33333333-3333-3333-3333-333333333333"
    private val operation="44444444-4444-4444-4444-444444444444"
    private val proposal="55555555-5555-5555-5555-555555555555"
    private val installation="66666666-6666-6666-6666-666666666666"
    private fun service(http:HttpClient)=FinanceArInvoiceIssuanceApiClient(
        HiltechApiClient(http,"https://finance.hiltech.test",
            accessTokenProvider={"finance-test-token"},
            correlationIdProvider={"finance-test-correlation"},
        ),
    )
    @Test fun candidateRequiresIndependentIssuerAndNeverSendsExternally()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { req ->
            calls++
            assertEquals("POST",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/invoice-candidates/$candidate/issuance-proposals",
                req.url.encodedPath,
            )
            assertEquals("Bearer finance-test-token",req.headers[HttpHeaders.Authorization])
            respond(
                """{"proposalId":"$proposal","candidateId":"$candidate",
                   "organizationId":"$org","invoiceNumber":"EG-COMP-0009",
                   "state":"PROPOSED","recordedInternally":false,
                   "invoiceSentExternally":false,"receivableRecognized":false,
                   "paymentCollected":false}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val r=service(http).prepare(org,candidate,operation,
            "EG-COMP-0009",evidence,installation)
        assertEquals(1,calls)
        assertEquals(proposal,r.proposalId)
        assertFalse(r.recordedInternally)
        assertFalse(r.invoiceSentExternally)
        assertFalse(r.receivableRecognized)
        assertFalse(r.paymentCollected)
    }
    @Test fun invalidCompanyNumberFailsBeforeNetwork()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { calls++; error("Unexpected Finance request") })
        assertFailsWith<IllegalArgumentException> {
            service(http).prepare(org,candidate,operation,"x",evidence,installation)
        }
        assertEquals(0,calls)
    }
}

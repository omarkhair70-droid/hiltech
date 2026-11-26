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

class FinanceGlPeriodCloseApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val period="22222222-2222-2222-2222-222222222222"
    private val operation="33333333-3333-3333-3333-333333333333"
    private val approval="44444444-4444-4444-4444-444444444444"
    private val event="55555555-5555-5555-5555-555555555555"
    private val installation="66666666-6666-6666-6666-666666666666"

    private fun client(http:HttpClient)=FinanceGlPeriodCloseApiClient(
        HiltechApiClient(
            http,"https://finance.hiltech.test",
            accessTokenProvider={"authorized-accountant"},
            correlationIdProvider={"finance-gl-close"},
        )
    )
    @Test fun requestReviewOnlyCreatesPendingPeriodApproval()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { req->
            calls++
            assertEquals("POST",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/periods/$period/request-close-review",
                req.url.encodedPath,
            )
            assertEquals(
                "Bearer authorized-accountant",req.headers[HttpHeaders.Authorization],
            )
            respond(
                """
                {"organizationId":"$org","periodId":"$period",
                 "ledgerSnapshotSha256":"${"a".repeat(64)}",
                 "journalCount":12,"sourceVersion":12345,
                 "state":"PENDING","approvalRequestId":"$approval",
                 "periodClosed":false,"moneyTransferred":false,
                 "bankReconciled":false}
                """.trimIndent(),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=client(http).requestReview(
            org,period,operation,installation,
        )
        assertEquals(1,calls)
        assertEquals("PENDING",result.state)
        assertFalse(result.periodClosed)
        assertFalse(result.moneyTransferred)
        assertFalse(result.bankReconciled)
    }

    @Test fun closedPeriodIsPermanentRecordNotBankReconciliation()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { req->
            calls++
            assertEquals("GET",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/periods/$period/close",
                req.url.encodedPath,
            )
            respond(
                """
                {"organizationId":"$org","periodId":"$period",
                 "ledgerSnapshotSha256":"${"a".repeat(64)}",
                 "journalCount":12,"sourceVersion":12345,
                 "state":"CLOSED","approvalRequestId":"$approval",
                 "periodClosureId":"$event","periodClosed":true,
                 "moneyTransferred":false,"bankReconciled":false}
                """.trimIndent(),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=client(http).status(org,period,installation)
        assertEquals(1,calls)
        assertTrue(result.periodClosed)
        assertEquals(event,result.periodClosureId)
        assertFalse(result.moneyTransferred)
        assertFalse(result.bankReconciled)
    }

    @Test fun malformedPeriodIdCannotEscapeFinanceRoute()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { calls++;error("No request") })
        assertFailsWith<IllegalArgumentException> {
            client(http).close(org,"../../journals",operation,installation)
        }
        assertEquals(0,calls)
    }
}

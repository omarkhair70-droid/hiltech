package com.hiltech.shared.core.payroll

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PayrollRunReviewApiClientTest {
    private val run = "11111111-1111-4111-8111-111111111111"
    private val requestId = "22222222-2222-4222-8222-222222222222"
    private val operation = "33333333-3333-4333-8333-333333333333"
    private val installation = "44444444-4444-4444-8444-444444444444"
    private fun client(http: HttpClient) = Payroll9b9cApiClient(HiltechApiClient(http, "https://synthetic.invalid",
        { "synthetic-session" }, { "synthetic-run-review" }))

    @Test
    fun lostDecisionResponseRetainsExactRunApprovalReasonAndOperation() = runBlocking {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            assertEquals("/v1/payroll/runs/$run/approval-decisions", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            requests += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (requests.size == 1) error("Synthetic committed response lost")
            respond(Json.encodeToString(PayrollRunDecisionResultDto(run, requestId, "CHANGE_REQUESTED", true)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val action = PayrollNativeAction.DecideRun(run, "period", requestId, "REQUEST_CHANGE", "Review the source amount")
        val session = PayrollCommandSession()
        val command = assertNotNull(session.begin(action) { operation })
        val body = PayrollRunDecisionRequestDto(command.operationId, action.approvalRequestId, action.decision, action.comment)
        val failure = assertFailsWith<HiltechApiException> { client(http).decideRun(run, body, installation) }
        session.fail(command, failure)
        assertSame(command, session.retry())
        val result = client(http).decideRun(run, body, installation)
        assertTrue(result.replayed)
        assertEquals(requests[0], requests[1])
        assertTrue(session.succeed(command))
        http.close()
    }

    @Test
    fun wrongRunOrApprovalReceiptNeverCompletesDecisionAndChangesRequireReason() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond(Json.encodeToString(PayrollRunDecisionResultDto(run, operation, "APPROVED", false)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> {
            client(http).decideRun(run, PayrollRunDecisionRequestDto(operation, requestId, "REQUEST_CHANGE"), installation)
        }
        assertEquals(0, calls)
        assertFailsWith<IllegalArgumentException> {
            client(http).decideRun(run, PayrollRunDecisionRequestDto(operation, requestId, "APPROVE"), installation)
        }
        assertEquals(1, calls)
        http.close()
    }
}

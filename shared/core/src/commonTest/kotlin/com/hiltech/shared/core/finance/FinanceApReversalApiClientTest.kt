package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceApReversalApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val journal = "22222222-2222-2222-2222-222222222222"
    private val proposal = "33333333-3333-3333-3333-333333333333"
    private val period = "44444444-4444-4444-4444-444444444444"
    private val operation = "55555555-5555-5555-5555-555555555555"
    private val installation = "66666666-6666-6666-6666-666666666666"
    private val approval = "77777777-7777-7777-7777-777777777777"
    private val reversal = "88888888-8888-8888-8888-888888888888"
    private val other = "99999999-9999-9999-9999-999999999999"
    private val reason = "SYNTHETIC_CORRECTION_REFERENCE"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(http: HttpClient) = FinanceApReversalApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://finance.hiltech.test",
            accessTokenProvider = { "synthetic-finance-token" },
            correlationIdProvider = { "ap-reversal-client-test" },
        ),
    )

    private fun receipt(state: String = "PROPOSED") = FinanceApReversalReceiptDto(
        proposalId = proposal,
        originalJournalId = journal,
        targetPeriodId = period,
        sourceVersion = 321,
        state = state,
        currency = "EGP",
        grossAmount = "250.000000",
        approvalRequestId = if (state == "PROPOSED") null else approval,
        reversalJournalId = if (state == "REVERSED") reversal else null,
        financialLedgerReversed = state == "REVERSED",
    )

    private fun body(value: FinanceApReversalReceiptDto) =
        Json.encodeToString(FinanceApReversalReceiptDto.serializer(), value)

    @Test
    fun proposalUsesExactAuthenticatedSourcePeriodReasonAndOperation() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/finance/organizations/$org/ap-journals/$journal/reversal-proposals", call.url.encodedPath)
            assertEquals("Bearer synthetic-finance-token", call.headers[HttpHeaders.Authorization])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            assertEquals(operation, call.headers["Idempotency-Key"])
            val payload = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(FinanceApReversalProposeDto(operation, period, reason),
                Json.decodeFromString<FinanceApReversalProposeDto>(payload))
            respond(body(receipt()), HttpStatusCode.OK, jsonHeaders)
        })
        val actual = client(http).propose(org, journal, period, "  $reason  ", operation, installation)
        assertEquals("PROPOSED", actual.state)
        assertNull(actual.approvalRequestId)
        assertNull(actual.reversalJournalId)
        assertFalse(actual.financialLedgerReversed)
        assertFalse(actual.bankPaymentReversed)
        assertFalse(actual.moneyTransferred)
    }

    @Test
    fun requestingIndependentApprovalOnlyAcknowledgesThePendingProposal() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/finance/organizations/$org/ap-reversal-proposals/$proposal/request-approval", call.url.encodedPath)
            assertEquals("Bearer synthetic-finance-token", call.headers[HttpHeaders.Authorization])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            assertEquals(operation, call.headers["Idempotency-Key"])
            val payload = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(FinanceApReversalOperationDto(operation), Json.decodeFromString<FinanceApReversalOperationDto>(payload))
            respond(body(receipt("PENDING")), HttpStatusCode.OK, jsonHeaders)
        })
        val actual = client(http).requestApproval(org, proposal, operation, installation)
        assertEquals("PENDING", actual.state)
        assertEquals(approval, actual.approvalRequestId)
        assertFalse(actual.financialLedgerReversed)
    }

    @Test
    fun postingAcknowledgesTheActualLedgerReversalWithoutClaimingABankRefund() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/finance/organizations/$org/ap-reversal-proposals/$proposal/post", call.url.encodedPath)
            assertEquals("Bearer synthetic-finance-token", call.headers[HttpHeaders.Authorization])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            assertEquals(operation, call.headers["Idempotency-Key"])
            val payload = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(FinanceApReversalOperationDto(operation), Json.decodeFromString<FinanceApReversalOperationDto>(payload))
            respond(body(receipt("REVERSED")), HttpStatusCode.OK, jsonHeaders)
        })
        val actual = client(http).post(org, proposal, operation, installation)
        assertEquals("REVERSED", actual.state)
        assertEquals(reversal, actual.reversalJournalId)
        assertTrue(actual.financialLedgerReversed)
        assertFalse(actual.bankPaymentReversed)
        assertFalse(actual.moneyTransferred)
    }

    @Test
    fun statusReadsTheCurrentServerStatesWithoutSendingACommand() = runBlocking {
        val states = listOf("PROPOSED", "PENDING", "APPROVED", "REJECTED", "CHANGE_REQUESTED", "SUPERSEDED", "CANCELLED", "REVERSED")
        for (state in states) {
            val expected = receipt(state)
            val http = HttpClient(MockEngine { call ->
                assertEquals("GET", call.method.value)
                assertEquals("/v1/finance/organizations/$org/ap-reversal-proposals/$proposal", call.url.encodedPath)
                assertEquals("Bearer synthetic-finance-token", call.headers[HttpHeaders.Authorization])
                assertEquals(installation, call.headers["X-Device-Installation-Id"])
                assertNull(call.headers["Idempotency-Key"])
                respond(body(expected), HttpStatusCode.OK, jsonHeaders)
            })
            assertEquals(expected, client(http).status(org, proposal, installation))
        }
    }

    @Test
    fun refusalAndStaleSourceRemainTypedFailuresWithoutAutomaticReplay() = runBlocking {
        for ((status, code) in listOf(
            HttpStatusCode.Forbidden to "PERMISSION_DENIED",
            HttpStatusCode.NotFound to "FINANCE_AP_REVERSAL_UNAUTHORIZED",
            HttpStatusCode.Conflict to "FINANCE_AP_REVERSAL_STALE",
        )) {
            var calls = 0
            val http = HttpClient(MockEngine {
                calls++
                respond("""{"code":"$code","message":"Synthetic refusal","correlationId":"reversal-denied","retryable":false}""",
                    status, jsonHeaders)
            })
            val error = assertFailsWith<HiltechApiException> { client(http).post(org, proposal, operation, installation) }
            assertEquals(code, error.code)
            assertEquals(status.value, error.httpStatus)
            assertEquals("reversal-denied", error.correlationId)
            assertFalse(error.retryable)
            assertEquals(1, calls)
        }
    }

    @Test
    fun malformedReferencesAndReasonDoNotReachTheNetwork() = runBlocking {
        var called = false
        val http = HttpClient(MockEngine { called = true; error("Invalid request reached network") })
        val api = client(http)
        assertFailsWith<IllegalArgumentException> { api.propose("../org", journal, period, reason, operation, installation) }
        assertFailsWith<IllegalArgumentException> { api.propose(org, "../journal", period, reason, operation, installation) }
        assertFailsWith<IllegalArgumentException> { api.propose(org, journal, "../period", reason, operation, installation) }
        assertFailsWith<IllegalArgumentException> { api.propose(org, journal, period, reason, "../operation", installation) }
        for (invalidReason in listOf(" ", "short", "x".repeat(181))) {
            assertFailsWith<IllegalArgumentException> { api.propose(org, journal, period, invalidReason, operation, installation) }
        }
        assertFailsWith<IllegalArgumentException> { api.requestApproval(org, "../proposal", operation, installation) }
        assertFailsWith<IllegalArgumentException> { api.post(org, proposal, "../operation", installation) }
        assertFailsWith<IllegalArgumentException> { api.status(org, "../proposal", installation) }
        assertFalse(called)
    }

    @Test
    fun proposalReceiptMustMatchTheOriginalJournalAndTargetPeriod() = runBlocking {
        for (wrong in listOf(receipt().copy(originalJournalId = other), receipt().copy(targetPeriodId = other))) {
            val http = HttpClient(MockEngine { respond(body(wrong), HttpStatusCode.OK, jsonHeaders) })
            assertFailsWith<IllegalArgumentException> { client(http).propose(org, journal, period, reason, operation, installation) }
        }
    }

    @Test
    fun approvalPostingAndStatusReceiptsMustMatchTheRequestedProposal() = runBlocking {
        for (action in listOf("request", "post", "status")) {
            val wrong = receipt(if (action == "request") "PENDING" else "REVERSED").copy(proposalId = other)
            val http = HttpClient(MockEngine { respond(body(wrong), HttpStatusCode.OK, jsonHeaders) })
            val api = client(http)
            assertFailsWith<IllegalArgumentException> {
                when (action) {
                    "request" -> api.requestApproval(org, proposal, operation, installation)
                    "post" -> api.post(org, proposal, operation, installation)
                    else -> api.status(org, proposal, installation)
                }
            }
        }
    }

    @Test
    fun aDifferentActionStateCannotAcknowledgeTheRequestedCommand() = runBlocking {
        for (action in listOf("propose", "request", "post")) {
            val wrong = receipt(if (action == "request") "PROPOSED" else "APPROVED")
            val http = HttpClient(MockEngine { respond(body(wrong), HttpStatusCode.OK, jsonHeaders) })
            val api = client(http)
            assertFailsWith<IllegalArgumentException> {
                when (action) {
                    "propose" -> api.propose(org, journal, period, reason, operation, installation)
                    "request" -> api.requestApproval(org, proposal, operation, installation)
                    else -> api.post(org, proposal, operation, installation)
                }
            }
        }
    }

    @Test
    fun obsoleteOrUnknownStateLabelsAreNotTreatedAsSuccessfulServerStates() = runBlocking {
        for (state in listOf("SEALED_AWAITING_APPROVAL", "POSTED_REVERSAL", "UNKNOWN_SUCCESS")) {
            val http = HttpClient(MockEngine { respond(body(receipt(state)), HttpStatusCode.OK, jsonHeaders) })
            assertFailsWith<IllegalArgumentException> { client(http).status(org, proposal, installation) }
        }
    }

    @Test
    fun contradictoryLedgerApprovalOrBankEffectFlagsAreRejected() = runBlocking {
        val invalid = listOf(
            receipt("PENDING").copy(financialLedgerReversed = true),
            receipt("PENDING").copy(reversalJournalId = reversal),
            receipt("PENDING").copy(approvalRequestId = null),
            receipt().copy(approvalRequestId = approval),
            receipt("REVERSED").copy(financialLedgerReversed = false),
            receipt("REVERSED").copy(reversalJournalId = null),
            receipt("REVERSED").copy(approvalRequestId = null),
            receipt("REVERSED").copy(bankPaymentReversed = true),
            receipt("REVERSED").copy(moneyTransferred = true),
        )
        for (wrong in invalid) {
            val http = HttpClient(MockEngine { respond(body(wrong), HttpStatusCode.OK, jsonHeaders) })
            assertFailsWith<IllegalArgumentException> { client(http).status(org, proposal, installation) }
        }
    }

    @Test
    fun malformedSourceAndMoneyFieldsCannotBecomeAValidReceipt() = runBlocking {
        val invalid = listOf(
            receipt().copy(proposalId = "not-a-reference"),
            receipt().copy(originalJournalId = "not-a-reference"),
            receipt().copy(targetPeriodId = "not-a-reference"),
            receipt().copy(sourceVersion = 0),
            receipt().copy(currency = "egp"),
            receipt().copy(grossAmount = "0.000000"),
            receipt().copy(grossAmount = "-250"),
            receipt().copy(grossAmount = "250e3"),
            receipt("PENDING").copy(approvalRequestId = "not-a-reference"),
            receipt("REVERSED").copy(reversalJournalId = "not-a-reference"),
        )
        for (wrong in invalid) {
            val http = HttpClient(MockEngine { respond(body(wrong), HttpStatusCode.OK, jsonHeaders) })
            assertFailsWith<IllegalArgumentException> { client(http).status(org, proposal, installation) }
        }
    }

    @Test
    fun explicitRetryAfterUnknownTransportOutcomeKeepsTheExactOperationAndBody() = runBlocking {
        val operations = mutableListOf<String?>()
        val payloads = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            operations += call.headers["Idempotency-Key"]
            payloads += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (payloads.size == 1) throw IllegalStateException("Synthetic response lost after request dispatch")
            respond(body(receipt().copy(replayed = true)), HttpStatusCode.OK, jsonHeaders)
        })
        val api = client(http)
        val failure = assertFailsWith<HiltechApiException> {
            api.propose(org, journal, period, reason, operation, installation)
        }
        assertEquals("NETWORK_UNAVAILABLE", failure.code)
        assertEquals(1, payloads.size)
        val recovered = api.propose(org, journal, period, reason, operation, installation)
        assertTrue(recovered.replayed)
        assertEquals(2, payloads.size)
        assertEquals(payloads[0], payloads[1])
        assertTrue(operations.all { it == operation })
        assertFalse(recovered.financialLedgerReversed)
    }

    @Test
    fun cancelledRequestRemainsCancellationWithoutAutomaticReplay() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; throw CancellationException("Synthetic caller cancellation") })
        assertFailsWith<CancellationException> { client(http).post(org, proposal, operation, installation) }
        assertEquals(1, calls)
    }
}

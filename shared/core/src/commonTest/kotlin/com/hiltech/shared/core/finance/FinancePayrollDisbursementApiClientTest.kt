package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinancePayrollDisbursementApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val target = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val device = "44444444-4444-4444-4444-444444444444"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(engine: MockEngine) = FinancePayrollDisbursementApiClient(HiltechApiClient(
        HttpClient(engine), "https://api.hiltech.test", { "synthetic-finance-token" }, { "finance-payroll-test" }))

    @Test fun exactResultRequestIsRetainedAcrossUnknownTransportAndReplay() = runBlocking {
        val proposal = FinancePayrollPrepareResultDto(operation, 3, "RETURNED", target, beneficiaryConfirmed = true, reason = "Confirmed returned transfer")
        val intent = FinancePayrollIntent(operation, org, "PROPOSE_RESULT", target, "Synthetic employee", result = proposal)
        val restored = Json.decodeFromString<FinancePayrollIntent>(Json.encodeToString(FinancePayrollIntent.serializer(), intent))
        val bodies = mutableListOf<String>()
        val api = client(MockEngine { call ->
            assertEquals("/v1/finance/payroll/organizations/$org/disbursements/instructions/$target/result-proposals", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(device, call.headers["X-Device-Installation-Id"])
            bodies += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) respond("unavailable", HttpStatusCode.ServiceUnavailable)
            else respond(receipt("SUBMITTED_FOR_REVIEW", replayed = true), HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> { api.apply(restored, device) }
        val result = api.apply(restored, device)
        assertEquals(bodies[0], bodies[1])
        assertEquals(proposal, Json.decodeFromString<FinancePayrollPrepareResultDto>(bodies[1]))
        assertTrue(result.replayed)
        assertFalse(result.bankExecutionInitiated)
        assertFalse(result.ledgerPosted)
    }

    @Test fun receiptCannotCrossScopeOrClaimPaymentExecution() = runBlocking {
        val intent = FinancePayrollIntent(operation, org, "REVIEW_RESULT", target, "Synthetic employee", review = FinancePayrollReviewResultDto(operation, "APPROVE"))
        val executed = client(MockEngine { respond(receipt("RECORDED").replace("\"bankExecutionInitiated\":false", "\"bankExecutionInitiated\":true"), HttpStatusCode.OK, headers) })
        assertFailsWith<Exception> { executed.apply(intent, device) }
        val wrongScope = client(MockEngine { respond(receipt("RECORDED").replace("\"organizationId\":\"$org\"", "\"organizationId\":\"$device\""), HttpStatusCode.OK, headers) })
        assertFailsWith<Exception> { wrongScope.apply(intent, device) }
    }

    @Test fun closingAnOutdatedReviewRequiresASupersededReceiptAndCannotMasqueradeAsPayment() = runBlocking {
        val intent = FinancePayrollIntent(operation, org, "REVIEW_RESULT", target, "Synthetic employee",
            review = FinancePayrollReviewResultDto(operation, "CLOSE_STALE"))
        val api = client(MockEngine { call ->
            val request = Json.decodeFromString<FinancePayrollReviewResultDto>((call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("CLOSE_STALE", request.decision)
            respond(receipt("SUPERSEDED"), HttpStatusCode.OK, headers)
        })
        assertEquals("SUPERSEDED", api.apply(intent, device).state)
        val wrong = client(MockEngine { respond(receipt("RECORDED"), HttpStatusCode.OK, headers) })
        assertFailsWith<Exception> { wrong.apply(intent, device) }
    }

    @Test fun laterAuthorizationFailureDoesNotDiscardAnUnconfirmedFinancialRequest() {
        val code = "FINANCE_PAYROLL_UNAVAILABLE"
        val refusal = HiltechApiException(code, "synthetic", 404, false, ErrorEnvelope(code, "unavailable", "synthetic"), "unavailable")
        assertTrue(financePayrollFailureDisposition(false, refusal).rejected)
        assertFalse(financePayrollFailureDisposition(true, refusal).rejected)
        assertTrue(financePayrollFailureDisposition(true, refusal).unknown)
        assertTrue(financePayrollFailureDisposition(false, IllegalStateException("response decode failed")).unknown)
    }

    @Test fun protectedOriginalBytesStayBoundToTheSelectedProposalEvidenceAndOrganization() = runBlocking {
        val csv = "external_transaction_id,booked_on,flow,currency,amount\nSYNTHETIC-001,2026-11-23,DEBIT,EGP,100.00\n"
        val source = FinancePayrollEvidenceDocumentDto(org, target, operation, device, "statement.csv", "text/csv",
            csv.encodeToByteArray().size.toLong(), "a".repeat(64), "SYNTHETIC-001", csv)
        val api = client(MockEngine { call ->
            assertEquals(HttpMethod.Post, call.method)
            assertEquals("/v1/finance/payroll/organizations/$org/disbursements/result-proposals/$target/evidence-content", call.url.encodedPath)
            assertEquals(device, call.headers["X-Device-Installation-Id"])
            respond(Json.encodeToString(FinancePayrollEvidenceDocumentDto.serializer(), source), HttpStatusCode.OK, headers)
        })
        assertEquals(csv, api.evidenceContent(org, target, operation, device).contentUtf8)
        for (invalid in listOf(source.copy(organizationId = device), source.copy(proposalId = device),
            source.copy(evidenceId = device), source.copy(sizeBytes = source.sizeBytes + 1), source.copy(contentType = "text/html"))) {
            val foreign = client(MockEngine { respond(Json.encodeToString(FinancePayrollEvidenceDocumentDto.serializer(), invalid), HttpStatusCode.OK, headers) })
            assertFailsWith<Exception> { foreign.evidenceContent(org, target, operation, device) }
        }
    }

    private fun receipt(state: String, replayed: Boolean = false) = """{"operationId":"$operation","organizationId":"$org","targetId":"$target",
        "state":"$state","replayed":$replayed,"bankExecutionInitiated":false,"ledgerPosted":false}"""
}

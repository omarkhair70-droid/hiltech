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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinanceCashboxOpeningFlowTest {
    private val org = "11111111-1111-4111-8111-111111111111"
    private val proposal = "22222222-2222-4222-8222-222222222222"
    private val operation = "33333333-3333-4333-8333-333333333333"
    private val custodian = "44444444-4444-4444-8444-444444444444"
    private val installation = "55555555-5555-4555-8555-555555555555"
    private val approval = "66666666-6666-4666-8666-666666666666"
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val receipt = FinanceCashboxReceiptDto(proposal, org, "CBX-000001", "EGP", custodian, "PROPOSED",
        custodianDisplayName = "Synthetic custodian", custodianEmployeeCode = "EMP-CASH",
        companyCustodyReference = "Synthetic custody assignment", createdAt = "2026-11-25T12:00:00Z", sourceVersion = 80,
        codeSource = "AUTOMATIC", canRequestReview = true, confirmedOperationId = operation)
    private fun client(http: HttpClient) = FinancePhysicalCashApiClient(HiltechApiClient(http, "https://synthetic.invalid",
        accessTokenProvider = { "synthetic-session" }, correlationIdProvider = { "synthetic-cashbox" }))
    private fun prepareIntent() = FinanceCashboxOpeningIntent(operation, org, FinanceCashboxOpeningAction.PREPARE,
        FinanceCashboxOpeningPrepareDto(operation, currency = "EGP", custodianUserId = custodian,
            companyCustodyReference = receipt.companyCustodyReference, expectedCustodianVersion = 7, expectedReviewVersion = 9),
        custodianDisplayName = "Synthetic custodian", custodianEmployeeCode = "EMP-CASH")

    @Test fun automaticPreparationUsesNamedSourceVersionsAndNoInventedManualCode() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/finance/organizations/$org/cashboxes/prepare-opening", request.url.encodedPath)
            assertEquals(operation, request.headers["Idempotency-Key"])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertFalse("cashboxCode" in body)
            assertTrue("expectedCustodianVersion" in body && "expectedReviewVersion" in body)
            respond(json.encodeToString(FinanceCashboxReceiptDto.serializer(), receipt), HttpStatusCode.OK, headers)
        })
        val result = client(http).submitOpeningIntent(prepareIntent(), installation)
        assertEquals("CBX-000001", result.cashboxCode)
        assertEquals("Synthetic custodian", result.custodianDisplayName)
        assertEquals("0.000000", result.openingBalance)
        http.close()
    }

    @Test fun lostPreparationOutcomeRetainsExactlyTheSameSavedCommand() = runBlocking {
        val bodies = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            keys += request.headers["Idempotency-Key"]
            if (bodies.size == 1) throw IllegalStateException("Synthetic transport loss after commit")
            respond(json.encodeToString(FinanceCashboxReceiptDto.serializer(), receipt.copy(replayed = true)), HttpStatusCode.OK, headers)
        })
        val api = client(http)
        val intent = prepareIntent()
        assertFailsWith<HiltechApiException> { api.submitOpeningIntent(intent, installation) }
        val restored = Json.decodeFromString(FinanceCashboxOpeningIntent.serializer(), Json.encodeToString(FinanceCashboxOpeningIntent.serializer(), intent))
        assertTrue(api.submitOpeningIntent(restored, installation).replayed)
        assertEquals(1, bodies.distinct().size)
        assertEquals(listOf<String?>(operation, operation), keys)
        http.close()
    }

    @Test fun foreignOrUnmatchedCommandReceiptIsNotReportedAsConfirmed() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(json.encodeToString(FinanceCashboxReceiptDto.serializer(), receipt.copy(confirmedOperationId = approval)), HttpStatusCode.OK, headers)
        })
        assertFailsWith<IllegalStateException> { client(http).submitOpeningIntent(prepareIntent(), installation) }
        http.close()
    }

    @Test fun historySearchIsEscapedAndKeepsStaleRecordsVisible() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/finance/organizations/$org/cashboxes/opening-workbench", request.url.encodedPath)
            assertEquals("Custodian & store", request.url.parameters["query"])
            val response = FinanceCashboxOpeningWorkbenchDto(org, true, false, true, 9, null,
                listOf(receipt.copy(state = "REVIEW_REQUIRED", canRequestReview = false, sourceIssueCode = "FINANCE_CASHBOX_SOURCE_CHANGED")), false)
            respond(json.encodeToString(FinanceCashboxOpeningWorkbenchDto.serializer(), response), HttpStatusCode.OK, headers)
        })
        val current = client(http).openingWorkbench(org, installation, "Custodian & store")
        assertEquals("REVIEW_REQUIRED", current.items.single().state)
        assertFalse(current.items.single().canRequestReview)
        http.close()
    }

    @Test fun aLostReviewSubmissionCanBeRetriedWhenTheExactProposalIsAlreadyPending() = runBlocking {
        var writes = 0
        val pending = receipt.copy(state = "PENDING", approvalRequestId = approval, canRequestReview = false)
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "POST") {
                writes++
                assertTrue(request.url.encodedPath.endsWith("/request-opening-review"))
                assertEquals(operation, request.headers["Idempotency-Key"])
            }
            respond(json.encodeToString(FinanceCashboxReceiptDto.serializer(), pending), HttpStatusCode.OK, headers)
        })
        val result = client(http).submitOpeningIntent(FinanceCashboxOpeningIntent(operation, org,
            FinanceCashboxOpeningAction.REQUEST_REVIEW, source = receipt), installation)
        assertEquals("PENDING", result.state)
        assertEquals(1, writes)
        http.close()
    }

    @Test fun activationRetryConfirmsTheOriginalOperationAfterTheCashboxLeavesThePendingQueue() = runBlocking {
        var writes = 0
        val approved = receipt.copy(state = "APPROVED", approvalRequestId = approval, canRequestReview = false, canActivate = true)
        val active = approved.copy(state = "ACTIVE", cashboxId = proposal, canActivate = false, replayed = true)
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "POST") { writes++; assertTrue(request.url.encodedPath.endsWith("/activate")) }
            respond(json.encodeToString(FinanceCashboxReceiptDto.serializer(), active), HttpStatusCode.OK, headers)
        })
        val result = client(http).submitOpeningIntent(FinanceCashboxOpeningIntent(operation, org,
            FinanceCashboxOpeningAction.ACTIVATE, source = approved), installation)
        assertEquals("ACTIVE", result.state)
        assertEquals(proposal, result.cashboxId)
        assertEquals(1, writes)
        http.close()
    }

    @Test fun changedOpeningSourceStopsBeforeSendingAnActivation() = runBlocking {
        var dispatched = false
        var writes = 0
        val approved = receipt.copy(state = "APPROVED", approvalRequestId = approval, canRequestReview = false, canActivate = true)
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "POST") writes++
            respond(json.encodeToString(FinanceCashboxReceiptDto.serializer(), approved.copy(sourceVersion = 81)), HttpStatusCode.OK, headers)
        })
        assertFailsWith<IllegalStateException> { client(http).submitOpeningIntent(FinanceCashboxOpeningIntent(operation, org,
            FinanceCashboxOpeningAction.ACTIVATE, source = approved), installation) { dispatched = true } }
        assertFalse(dispatched)
        assertEquals(0, writes)
        http.close()
    }
}

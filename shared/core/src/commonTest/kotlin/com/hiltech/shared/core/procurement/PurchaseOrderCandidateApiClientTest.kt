package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PurchaseOrderCandidateApiClientTest {
    private val rfq = "11111111-1111-1111-1111-111111111111"
    private val quote = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val installation = "44444444-4444-4444-4444-444444444444"

    private fun client(http: HttpClient) = PurchaseOrderCandidateApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "buyer-token" },
            correlationIdProvider = { "s03-contract" },
        ),
    )

    @Test fun preflightProvidesExplicitReadOnlyBlockersWithoutAward() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals("/v1/sourcing/rfqs/$rfq/po-candidate-preflight", call.url.encodedPath)
            assertEquals(quote, call.url.parameters["quoteId"])
            assertEquals("Bearer buyer-token", call.headers[HttpHeaders.Authorization])
            respond("""{"rfqId":"$rfq","quoteId":"$quote",
                "supplierOrganizationId":null,"sourceCurrent":false,"quoteLatest":false,
                "quotedTotal":null,"currency":null,
                "blockers":["SOURCE_NOT_CURRENT","QUOTE_NOT_LATEST"],"canPropose":false,
                "purchaseAuthorized":false,"purchaseOrderIssued":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).preview(rfq, quote, installation)
        assertFalse(result.canPropose)
        assertFalse(result.purchaseOrderIssued)
        assertEquals(2, result.blockers.size)
    }

    @Test fun proposalUsesIdempotencyAndNeverClaimsPurchaseApproval() = runBlocking {
        val command = ProposePurchaseOrderCandidateDto(
            operationId = operation,
            quoteEvidenceId = quote,
            expectedQuoteRevision = 2,
            rationale = "Technical match confirmed for the authorized project requirement",
        )
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/sourcing/rfqs/$rfq/po-candidates", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            assertEquals("Bearer buyer-token", call.headers[HttpHeaders.Authorization])
            val body = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(command, Json.decodeFromString<ProposePurchaseOrderCandidateDto>(body))
            respond("""{"candidateId":"$operation","rfqId":"$rfq","quoteId":"$quote",
                "requirementVersion":3,"candidateVersion":1,
                "state":"DRAFT_FOR_APPROVAL","replayed":false,
                "purchaseAuthorized":false,"purchaseOrderIssued":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).propose(rfq, command, installation)
        assertEquals("DRAFT_FOR_APPROVAL", result.state)
        assertEquals(1L, result.candidateVersion)
        assertFalse(result.purchaseAuthorized)
        assertFalse(result.purchaseOrderIssued)
    }
}

package com.hiltech.shared.core.procurement

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupplierSourcingApiClientTest {
    private val requirement = "11111111-1111-1111-1111-111111111111"
    private val rfq = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val supplier = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    private fun client(http: HttpClient) = SupplierSourcingApiClient(
        HiltechApiClient(
            client = http, baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "restricted-buyer-token" },
            correlationIdProvider = { "sourcing-checkpoint" },
        ),
    )
    private fun rfqJson() = """
        {"id":"$rfq","requirementId":"$requirement","capturedRequirementVersion":3,
        "capturedQuantity":"100","unitCode":"M","sourceCurrent":true,
        "quoteCount":1,"purchaseAuthorized":false}
        """.trimIndent()

    @Test fun authenticatedRfqDraftIsVersionBoundAndUsesIdempotency() = runBlocking {
        val request = OpenSupplierRfqDto(operation, 3)
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/purchase-requirements/$requirement/supplier-rfqs", call.url.encodedPath)
            assertEquals("Bearer restricted-buyer-token", call.headers[HttpHeaders.Authorization])
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            val body = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(request, Json.decodeFromString<OpenSupplierRfqDto>(body))
            respond("""{"rfq":${rfqJson()},"replayed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = client(http).openRfq(requirement, request, installation)
        assertEquals(3L, receipt.rfq.capturedRequirementVersion)
        assertFalse(receipt.rfq.purchaseAuthorized)
        assertFalse(receipt.replayed)
    }

    @Test fun quoteSubmissionRetainsEvidenceAndDoesNotIssueAPurchaseOrder() = runBlocking {
        val request = RecordSupplierQuoteDto(
            operationId = operation, supplierOrganizationId = supplier,
            expectedRevisionNumber = 2, supplierQuoteReference = "Q-S2-002",
            currency = "EGP", quotedQuantity = "100", quotedUnitCode = "M",
            unitPrice = "12.50", taxAmount = "0.00", deliveryDays = 5,
            sourceEvidenceRef = "internal:quote:002", sourceSha256 = "f".repeat(64),
        )
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/sourcing/rfqs/$rfq/quote-evidence", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            val payload = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(request, Json.decodeFromString<RecordSupplierQuoteDto>(payload))
            respond("""{"quoteId":"$operation","rfqId":"$rfq",
                "revisionNumber":2,"replayed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).recordQuote(rfq, request, installation)
        assertEquals(2, result.revisionNumber)
        assertFalse(result.replayed)
    }

    @Test fun technicalAssessmentUsesSeparateTypedEndpointAndIdempotency() = runBlocking {
        val quoteId = "66666666-6666-6666-6666-666666666666"
        val request = RecordSupplierQuoteTechnicalAssessmentDto(
            operationId = operation,
            expectedReviewRevision = 1,
            decision = "NEEDS_CLARIFICATION",
            justification = "Missing fiber performance documentation",
        )
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/sourcing/quotes/$quoteId/technical-assessments", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals("Bearer restricted-buyer-token", call.headers[HttpHeaders.Authorization])
            val payload = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(request,
                Json.decodeFromString<RecordSupplierQuoteTechnicalAssessmentDto>(payload))
            respond("""{"id":"$operation","quoteEvidenceId":"$quoteId",
                "reviewRevision":1,"decision":"NEEDS_CLARIFICATION","replayed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).recordTechnicalAssessment(quoteId, request, installation)
        assertEquals(quoteId, result.quoteEvidenceId)
        assertEquals("NEEDS_CLARIFICATION", result.decision)
        assertFalse(result.replayed)
    }

    @Test fun confidentialComparisonParsesHumanWarningsWithoutChoosingAVendor() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals("/v1/sourcing/rfqs/$rfq/comparison", call.url.encodedPath)
            assertEquals("Bearer restricted-buyer-token", call.headers[HttpHeaders.Authorization])
            respond("""
            {"rfq":${rfqJson()},"allRevisionCount":2,"recordedSupplierCount":1,
             "comparison":{"expectedQuantity":"100","expectedUnitCode":"M",
             "evaluatedAt":"2026-11-22",
             "latestBySupplier":[{"quoteId":"$operation",
             "supplierOrganizationId":"$supplier","revisionNumber":2,
             "quoteReference":"Q002","currency":"USD","quantity":"100",
             "unitCode":"M","unitPrice":"12.50","taxAmount":"0",
             "total":"1250","evidenceRef":"internal:quote:002",
             "evidenceSha256":"${"f".repeat(64)}",
             "technicalCompliance":"NOT_ASSESSED",
             "issues":["TECHNICAL_COMPLIANCE_UNCONFIRMED"]}],
             "currencies":["USD"],"allQuotesDirectlyComparable":false,
             "supplierSelected":false,"purchaseAuthorized":false}}
            """, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val comparison = client(http).comparison(rfq, installation)
        assertEquals("USD", comparison.comparison.currencies.single())
        assertEquals("1250", comparison.comparison.latestBySupplier.single().total)
        assertTrue("TECHNICAL_COMPLIANCE_UNCONFIRMED" in
            comparison.comparison.latestBySupplier.single().issues)
        assertFalse(comparison.comparison.supplierSelected)
        assertFalse(comparison.comparison.purchaseAuthorized)
    }

    @Test fun restrictedBuyerCanLoadEligibleNeedsWithoutHandTypedRequirementIds() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals("/v1/sourcing/organizations/$supplier/eligible-needs", call.url.encodedPath)
            assertEquals("Bearer restricted-buyer-token", call.headers[HttpHeaders.Authorization])
            respond("""[{"requirementId":"$requirement","technicalVersion":3,
                "stockItemId":"$rfq","requestedQuantity":"100","unitCode":"M",
                "technicalState":"READY_FOR_SOURCING","sourceCurrent":true,
                "sourcingRoundExists":false}]""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).eligibleNeeds(supplier, installation)
        assertEquals(1, result.size)
        assertEquals(requirement, result.single().requirementId)
        assertFalse(result.single().sourcingRoundExists)
    }

    @Test fun scopedRfqRoundListNeedsTheCurrentAuthenticatedBuyerIdentity() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals("/v1/purchase-requirements/$requirement/supplier-rfqs", call.url.encodedPath)
            assertEquals("Bearer restricted-buyer-token", call.headers[HttpHeaders.Authorization])
            respond("[${rfqJson()}]", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).roundsForRequirement(requirement, installation)
        assertEquals(1, result.size)
        assertEquals(rfq, result.single().id)
        assertFalse(result.single().purchaseAuthorized)
    }

    @Test fun deniedBuyerReadsStayDeniedInsteadOfAppearingAsEmptyPriceLists() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"code":"SOURCING_UNAVAILABLE","message":"Unavailable",
                "correlationId":"buyer-denied","retryable":false}""",
                HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val error = assertFailsWith<HiltechApiException> {
            client(http).comparison(rfq, installation)
        }
        assertEquals("SOURCING_UNAVAILABLE", error.code)
    }

    @Test fun malformedIdentityAndOperationIdsCannotEscapeTheirRoute() = runBlocking {
        var called = false
        val http = HttpClient(MockEngine { called = true; error("Invalid UUID sent to network") })
        assertFailsWith<IllegalArgumentException> {
            client(http).comparison("../supplier", installation)
        }
        assertFailsWith<IllegalArgumentException> {
            client(http).openRfq(requirement, OpenSupplierRfqDto("../operator", 3), installation)
        }
        assertFalse(called)
    }
}

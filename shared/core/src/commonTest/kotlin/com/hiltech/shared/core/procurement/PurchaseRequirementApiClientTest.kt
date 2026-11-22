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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurchaseRequirementApiClientTest {
    private val work = "11111111-1111-1111-1111-111111111111"
    private val item = "22222222-2222-2222-2222-222222222222"
    private val draft = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    private fun demand(state: String = "DRAFT", version: Int = 1, canWithdraw: Boolean = true) =
        """{"id":"$draft","workOrderId":"$work","workOrderVersionAtCreation":5,
            "stockItemId":"$item","stockItemVersionAtCreation":3,"requestedQuantity":"3",
            "unitCode":"PCS","justification":"Three cable units","state":"$state",
            "assessmentCode":"WAREHOUSE_REVIEW_REQUIRED","observedFreeQuantity":null,
            "assessedAt":"2026-11-22T08:00:00Z","createdBy":"$work",
            "createdAt":"2026-11-22T08:00:00Z","sourceCurrent":${state == "DRAFT"},
            "assessmentRequiresRefresh":true,"version":$version,"canWithdraw":$canWithdraw}"""

    private fun client(http: HttpClient) = PurchaseRequirementApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "procurement-token" },
            correlationIdProvider = { "procurement-trace" },
        ),
    )

    @Test
    fun createUsesAuthenticatedIdempotentCommandWithoutInventingStockAuthority() = runBlocking {
        val command = CreatePurchaseRequirementDraftDto(operation, 5, item, 3, "3", "PCS", "Three cable units")
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/work-orders/$work/purchase-requirements", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals("Bearer procurement-token", request.headers[HttpHeaders.Authorization])
            assertEquals(operation, request.headers["Idempotency-Key"])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(command, Json.decodeFromString<CreatePurchaseRequirementDraftDto>(body))
            respond("""{"draft":${demand()},"replayed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = client(http).create(work, command, installation)
        assertEquals("WAREHOUSE_REVIEW_REQUIRED", receipt.draft.assessmentCode)
        assertNull(receipt.draft.observedFreeQuantity)
        assertTrue(receipt.draft.requiresWarehouseReview())
        assertTrue(receipt.draft.hasCurrentDraftSource())
        assertTrue(receipt.draft.assessmentRequiresRefresh)
        assertFalse(receipt.replayed)
    }

    @Test
    fun listAndDetailReadTheSameServerOwnedStateWithoutOfflineMutation() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("GET", request.method.value)
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            val content = when (request.url.encodedPath) {
                "/v1/work-orders/$work/purchase-requirements" -> "[${demand()}]"
                "/v1/purchase-requirements/$draft" -> demand()
                else -> error("Unexpected procurement route")
            }
            respond(content, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        val itemFromList = api.forWorkOrder(work, installation).single()
        val itemFromDetail = api.detail(draft, installation)
        assertEquals(itemFromList, itemFromDetail)
        assertEquals("DRAFT", itemFromDetail.state)
        assertEquals(1L, itemFromDetail.version)
    }

    @Test
    fun withdrawalIsVersionBoundAndReturnsImmutableHistoryProjection() = runBlocking {
        val request = WithdrawPurchaseRequirementDraftDto(operation, 1, "No longer needed")
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/purchase-requirements/$draft/withdraw", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals("Bearer procurement-token", call.headers[HttpHeaders.Authorization])
            val payload = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(request, Json.decodeFromString<WithdrawPurchaseRequirementDraftDto>(payload))
            respond("""{"draft":${demand("WITHDRAWN", 2, false)},"replayed":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = client(http).withdraw(draft, request, installation)
        assertEquals("WITHDRAWN", receipt.draft.state)
        assertEquals(2L, receipt.draft.version)
        assertFalse(receipt.draft.hasCurrentDraftSource())
        assertFalse(receipt.draft.canWithdraw)
        assertTrue(receipt.replayed)
    }

    @Test
    fun staleSourceConflictRetainsTheServerCodeAndCorrelationId() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"code":"PROCUREMENT_SOURCE_CHANGED",
                "message":"Reload work and stock before retrying",
                "correlationId":"procurement-source-1","retryable":false}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val error = assertFailsWith<HiltechApiException> {
            client(http).create(work,
                CreatePurchaseRequirementDraftDto(operation, 4, item, 2, "3", "PCS", "Old snapshot"),
                installation)
        }
        assertEquals("PROCUREMENT_SOURCE_CHANGED", error.code)
        assertEquals("procurement-source-1", error.correlationId)
        assertFalse(error.retryable)
    }

    @Test
    fun invalidIdsFailBeforeNetworkOrCommandSubmission() = runBlocking {
        var called = false
        val http = HttpClient(MockEngine {
            called = true
            error("Invalid UUID must not reach the server")
        })
        assertFailsWith<IllegalArgumentException> {
            client(http).detail("../other/stock", installation)
        }
        assertFailsWith<IllegalArgumentException> {
            client(http).withdraw(draft,
                WithdrawPurchaseRequirementDraftDto("../operation", 1), installation)
        }
        assertFalse(called)
    }

    @Test
    fun aDeniedReadDoesNotInventAnEmptyPurchaseHistory() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"code":"PROCUREMENT_UNAVAILABLE","message":"Unavailable",
                "correlationId":"procurement-hidden","retryable":false}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val error = assertFailsWith<HiltechApiException> {
            client(http).forWorkOrder(work, installation)
        }
        assertEquals("PROCUREMENT_UNAVAILABLE", error.code)
        assertEquals("procurement-hidden", error.correlationId)
    }
}

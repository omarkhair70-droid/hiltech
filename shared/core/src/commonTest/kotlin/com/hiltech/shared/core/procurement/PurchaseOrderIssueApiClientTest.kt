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

class PurchaseOrderIssueApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val candidate = "22222222-2222-2222-2222-222222222222"
    private val op = "33333333-3333-3333-3333-333333333333"
    private val destination = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    private fun client(http: HttpClient) = PurchaseOrderIssueApiClient(
        HiltechApiClient(
            client = http, baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "issuer-only-token" },
            correlationIdProvider = { "po-issued-test" },
        ),
    )

    @Test fun readsIssuerOnlyQueueWithCanonicalApprovalAndNoSupplierDispatch() = runBlocking {
        val http = HttpClient(MockEngine { req ->
            assertEquals("GET", req.method.value)
            assertEquals("/v1/procurement/organizations/$org/po-issuer-queue", req.url.encodedPath)
            assertEquals("Bearer issuer-only-token", req.headers[HttpHeaders.Authorization])
            respond("""[{"candidateId":"$candidate","supplierOrganizationId":"$org",
                "supplierName":"Supplier A","stockItemId":"$org",
                "quantity":"12","unitCode":"PCS","currency":"EGP","total":"600.000000",
                "approvalRequestId":"$op","approvalPolicyVersion":2}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals(2, client(http).queue(org, installation).single().approvalPolicyVersion)
    }

    @Test fun issuerSelectsOnlyServerScopedDestination() = runBlocking {
        val http = HttpClient(MockEngine { req ->
            assertEquals("GET", req.method.value)
            assertEquals("/v1/procurement/po-candidates/$candidate/delivery-destinations",
                req.url.encodedPath)
            respond("""[{"storageLocationId":"$destination","code":"W-01",
                "name":"Main warehouse","kind":"MAIN_WAREHOUSE"}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("MAIN_WAREHOUSE",
            client(http).destinations(candidate, installation).single().kind)
    }

    @Test fun idempotentIssueIsInternallyIssuedNotSentOrPaid() = runBlocking {
        val command = IssuePurchaseOrderDto(op, 1L, destination)
        val http = HttpClient(MockEngine { req ->
            assertEquals("POST", req.method.value)
            assertEquals("/v1/procurement/po-candidates/$candidate/issue", req.url.encodedPath)
            assertEquals(op, req.headers["Idempotency-Key"])
            assertEquals(installation, req.headers["X-Device-Installation-Id"])
            val bytes = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(command, Json.decodeFromString<IssuePurchaseOrderDto>(bytes))
            respond("""{"purchaseOrderId":"$op","code":"PO-000001","serial":1,
                "state":"ISSUED","supplierDispatchState":"NOT_SENT","candidateId":"$candidate",
                "approvalRequestId":"$op","total":"600.000000","currency":"EGP",
                "replayed":false,"stockReceived":false,"supplierNotified":false,
                "paymentAuthorized":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = client(http).issue(candidate, command, installation)
        assertEquals("PO-000001", receipt.code)
        assertEquals("NOT_SENT", receipt.supplierDispatchState)
        assertFalse(receipt.stockReceived)
        assertFalse(receipt.supplierNotified)
        assertFalse(receipt.paymentAuthorized)
    }
}

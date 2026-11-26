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

class SupplierMasterDataApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val supplier = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val installation = "44444444-4444-4444-4444-444444444444"

    private fun client(http: HttpClient) = SupplierMasterDataApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "master-data-custodian-token" },
            correlationIdProvider = { "supplier-registry-test" },
        ),
    )

    @Test fun authorizedSearchCanReadCurrentRevokedSupplierWithoutTypedIds() = runBlocking {
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals("/v1/sourcing/organizations/$org/supplier-registry", call.url.encodedPath)
            assertEquals("شركة", call.url.parameters["query"])
            assertEquals("Bearer master-data-custodian-token",
                call.headers[HttpHeaders.Authorization])
            respond("""[{"supplierOrganizationId":"$supplier",
                "displayName":"شركة مورد","supplierStatus":"ACTIVE",
                "relationshipRevision":3,"relationshipState":"INACTIVE"}]""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val candidates = client(http).search(org, "شركة", installation)
        assertEquals(1, candidates.size)
        assertEquals("INACTIVE", candidates.single().relationshipState)
        assertEquals(3, candidates.single().relationshipRevision)
    }

    @Test fun activationAndRevocationDoNotUseBuyerQuoteEndpoint() = runBlocking {
        val request = ChangeSupplierLinkDto(
            operationId = operation,
            supplierOrganizationId = supplier,
            expectedRevisionNumber = 4,
            state = "ACTIVE",
            sourceReference = "SUPPLIER-REGISTER-2026-024",
        )
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals("/v1/sourcing/organizations/$org/supplier-links", call.url.encodedPath)
            assertEquals("Bearer master-data-custodian-token",
                call.headers[HttpHeaders.Authorization])
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            val requestBody = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertEquals(request, Json.decodeFromString<ChangeSupplierLinkDto>(requestBody))
            respond("""{"eventId":"$operation","organizationId":"$org",
                "supplierOrganizationId":"$supplier","revisionNumber":4,
                "state":"ACTIVE","replayed":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = client(http).changeSupplierLink(org, request, installation)
        assertEquals(4, receipt.revisionNumber)
        assertEquals("ACTIVE", receipt.state)
        assertFalse(receipt.replayed)
    }
}

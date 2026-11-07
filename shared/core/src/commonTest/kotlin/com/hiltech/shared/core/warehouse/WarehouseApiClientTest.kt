package com.hiltech.shared.core.warehouse

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarehouseApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val warehouseId = "22222222-2222-2222-2222-222222222222"
    private val batchId = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    @Test
    fun warehouseDirectoryAndCreateUseExistingAuthenticatedTransport() = runBlocking {
        val visited = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer warehouse-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            visited += request.url.encodedPath to request.headers["Idempotency-Key"]
            val body = when (request.method.value) {
                "GET" -> """
                    {
                      "items":[{"warehouseId":"$warehouseId","organizationId":"$org",
                        "code":"WH-1","name":"Main","active":true,"version":1}],
                      "correlationId":"corr-list"
                    }
                """.trimIndent()
                "POST" -> """
                    {"warehouseId":"$warehouseId","organizationId":"$org",
                      "code":"WH-1","name":"Main","active":true,"version":1,
                      "replayed":false,"correlationId":"corr-create"}
                """.trimIndent()
                else -> error("Unexpected request")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = client(http)
        val directory = client.warehouses(org, installation)
        assertEquals(1, directory.items.size)
        assertEquals("WH-1", directory.items.single().code)

        val created = client.createWarehouse(
            WarehouseCreateRequestDto(
                operationId = operation, organizationId = org,
                code = "WH-1", name = "Main",
                clientOccurredAt = "2026-11-07T12:00:00Z",
            ),
            installation,
        )
        assertEquals(warehouseId, created.warehouseId)
        assertEquals(
            listOf(
                "/v1/warehouses" to null,
                "/v1/warehouses" to operation,
            ),
            visited,
        )
    }

    @Test
    fun importReviewResponseDoesNotSurfaceRawOrFinancialColumnsInSharedDto() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/inventory-imports/$batchId", request.url.encodedPath)
            respond(
                content = """
                    {
                      "batchId":"$batchId","organizationId":"$org",
                      "sourceFileName":"legacy.xlsx","sourceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "sourceType":"LEGACY_EXCEL","cutoverDate":null,
                      "state":"IN_REVIEW","version":2,
                      "rows":[{
                        "rowId":"66666666-6666-6666-6666-666666666666",
                        "batchId":"$batchId","sourceSheet":"Accessories",
                        "sourceRowNumber":2,"candidateClass":"UNCLASSIFIED",
                        "normalizedBrand":null,"normalizedPartNumber":null,
                        "normalizedDescription":null,"candidateUom":null,
                        "candidateQuantity":null,
                        "issueCodes":["LEGACY_COLUMN_SEMANTICS_AMBIGUOUS"],
                        "reviewState":"PENDING","version":1
                      }],
                      "correlationId":"corr-import"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val imported = client(http).importBatch(batchId, installation)
        assertEquals("Accessories", imported.rows.single().sourceSheet)
        assertTrue(imported.rows.single().issueCodes.contains("LEGACY_COLUMN_SEMANTICS_AMBIGUOUS"))
        val exported = Json.encodeToString(InventoryImportBatchDto.serializer(), imported)
        assertFalse(exported.contains("rawPayload"))
        assertFalse(exported.contains("candidateValue"))
        assertFalse(exported.contains("candidateCurrencyCode"))
    }

    @Test
    fun invalidIdentifiersCannotBecomeRequests() = runBlocking {
        var called = false
        val http = HttpClient(MockEngine {
            called = true
            error("Unexpected network request")
        })
        val client = client(http)
        assertFailsWith<IllegalArgumentException> {
            client.warehouse("../../other-resource", installation)
        }
        assertFalse(called)
    }

    @Test
    fun deniedWarehouseScopePropagatesAccessError() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                content = """
                    {"code":"OBJECT_NOT_VISIBLE","message":"Not available",
                      "correlationId":"corr-guard","retryable":false}
                """.trimIndent(),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val error = assertFailsWith<HiltechApiException> {
            client(http).currentWarehouseAuthority(warehouseId, installation)
        }
        assertEquals("OBJECT_NOT_VISIBLE", error.code)
        assertEquals("corr-guard", error.correlationId)
    }

    private fun client(http: HttpClient) = WarehouseApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "warehouse-token" },
            correlationIdProvider = { "corr-client" },
        ),
    )
}

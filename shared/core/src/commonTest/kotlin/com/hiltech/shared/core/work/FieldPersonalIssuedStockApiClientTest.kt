package com.hiltech.shared.core.work

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FieldPersonalIssuedStockApiClientTest {
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `only own issued stock GET with identity bearer and installation scope`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { req ->
            calls++
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("/v1/work-orders/$workId/my-issued-stock", req.url.encodedPath)
            assertEquals("Bearer field-token", req.headers[HttpHeaders.Authorization])
            assertEquals(deviceId, req.headers["X-Device-Installation-Id"])
            respond(
                content = """{
                    "workOrderId":"$workId",
                    "workOrderVersion":12,
                    "status":"SOURCE_CURRENT",
                    "asOf":"2026-11-21T18:00:00Z",
                    "items":[{
                        "requirementId":"33333333-3333-4333-8333-333333333333",
                        "stockItemId":"44444444-4444-4444-8444-444444444444",
                        "stockItemCode":"CAT6-001",
                        "stockItemName":"UTP Cable",
                        "unitCode":"M",
                        "outstandingQuantity":"30.250000",
                        "sourceVersion":4,
                        "sourceUpdatedAt":"2026-11-21T17:00:00Z"
                    }]
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = FieldAssignedWorkApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "field-token" },
            correlationIdProvider = { "corr-field" },
        ))
        val received = client.personalIssuedStock(workId, deviceId)
        assertEquals(1, calls)
        assertEquals("SOURCE_CURRENT", received.status)
        assertEquals("30.250000", received.items.single().outstandingQuantity)
        assertEquals(12L, received.workOrderVersion)
    }

    @Test
    fun `reject invalid work id without any network request`() = runBlocking {
        val client = FieldAssignedWorkApiClient(HiltechApiClient(
            client = HttpClient(MockEngine { error("Transport must not run") }),
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "field-token" },
            correlationIdProvider = { "corr" },
        ))
        assertFailsWith<IllegalArgumentException> {
            client.personalIssuedStock("../secret", deviceId)
        }
    }
}

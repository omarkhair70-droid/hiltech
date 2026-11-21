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
import kotlin.test.assertNull

class FieldAssignedAssetLookupApiClientTest {
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val deviceId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `lookup is scoped to selected WorkOrder and installation`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { req ->
            calls++
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("/v1/field/work-orders/$workId/assets/by-tag/opaque_01", req.url.encodedPath)
            assertEquals("Bearer field-token", req.headers[HttpHeaders.Authorization])
            assertEquals(deviceId, req.headers["X-Device-Installation-Id"])
            respond(
                content = """{
                    "assetCode":"AS-01",
                    "name":"OTDR",
                    "typeName":"Test equipment",
                    "condition":"GOOD",
                    "assetId":"33333333-3333-4333-8333-333333333333"
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = FieldAssignedWorkApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "field-token" },
            correlationIdProvider = { "corr-field-asset" },
        ))

        val received = client.assignedAssetTag(workId, "opaque_01", deviceId)
        assertEquals(1, calls)
        assertEquals("AS-01", received.assetCode)
        assertEquals("33333333-3333-4333-8333-333333333333", received.assetId)
        assertNull(received.locationCode)
        assertNull(received.employeeCode)
    }

    @Test
    fun `invalid WorkOrder or tag fails before transport`() = runBlocking {
        val client = FieldAssignedWorkApiClient(HiltechApiClient(
            client = HttpClient(MockEngine { error("Transport must not run") }),
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "field-token" },
            correlationIdProvider = { "corr" },
        ))
        assertFailsWith<IllegalArgumentException> {
            client.assignedAssetTag("../work", "opaque_01", deviceId)
        }
        assertFailsWith<IllegalArgumentException> {
            client.assignedAssetTag(workId, "../tag", deviceId)
        }
    }
}

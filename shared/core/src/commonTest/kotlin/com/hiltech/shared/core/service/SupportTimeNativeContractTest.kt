package com.hiltech.shared.core.service

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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SupportTimeNativeContractTest {
    private val project = "11111111-1111-4111-8111-111111111111"
    private val ticket = "22222222-2222-4222-8222-222222222222"
    private val operation = "44444444-4444-4444-8444-444444444444"
    private val installation = "88888888-8888-4888-8888-888888888888"
    private val root = "/v1/projects/$project/support-tickets/$ticket/internal-time"

    @Test
    fun timeObservationDoesNotInventClientResponseOrSlaPause() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { req ->
            calls++
            assertEquals(HttpMethod.Get, req.method)
            assertEquals(root, req.url.encodedPath)
            assertEquals("Bearer phase11-token", req.headers[HttpHeaders.Authorization])
            assertEquals(installation, req.headers["X-Device-Installation-Id"])
            respond(
                content = """{"ticketId":"22222222-2222-4222-8222-222222222222","openedAt":"2026-11-23T08:00:00Z","observedAt":"2026-11-23T08:03:00Z","caseState":"INVESTIGATING","latestEventNumber":1,"firstInternalAttentionAt":"2026-11-23T08:02:00Z","firstInternalAttentionElapsedSeconds":120,"elapsedWallSeconds":180,"notedInternalHoldSeconds":0,"internalHoldActive":false,"internalHoldStartedAt":null,"contractualResponseDeadline":null,"contractualResolutionDeadline":null,"contractualSlaState":"NOT_CONFIGURED","holdSlaPauseApprovalState":"NOT_APPROVED","customerResponseConfirmed":false,"clientVisibility":"INTERNAL_ONLY"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = SupportTimeApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-support-clock" },
        ))
        val result = client.observation(project,ticket,installation)
        assertEquals(1,calls)
        assertEquals(120L,result.firstInternalAttentionElapsedSeconds)
        assertEquals(180L,result.elapsedWallSeconds)
        assertNull(result.contractualResponseDeadline)
        assertNull(result.contractualResolutionDeadline)
        assertFalse(result.customerResponseConfirmed)
        assertFalse(result.internalHoldActive)
        assertEquals("NOT_CONFIGURED",result.contractualSlaState)
        assertEquals("NOT_APPROVED",result.holdSlaPauseApprovalState)
        assertEquals("INTERNAL_ONLY",result.clientVisibility)
    }

    @Test
    fun pairedHoldEventUsesAuthenticatedIdempotentCommand() = runBlocking {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            paths += req.url.encodedPath
            assertEquals(HttpMethod.Post, req.method)
            assertEquals(operation, req.headers["Idempotency-Key"])
            assertEquals("Bearer phase11-token", req.headers[HttpHeaders.Authorization])
            assertEquals(installation, req.headers["X-Device-Installation-Id"])
            respond(
                content = """{"eventId":"33333333-3333-4333-8333-333333333333","ticketId":"22222222-2222-4222-8222-222222222222","eventNumber":2,"eventType":"INTERNAL_HOLD_STARTED","reason":"Waiting for original site evidence","recordedAt":"2026-11-23T08:03:00Z","visibility":"INTERNAL_ONLY","duplicateReplay":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val client = SupportTimeApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-support-clock" },
        ))
        val actual = client.record(
            project,ticket,
            RecordSupportTimeDto(
                operationId=operation,expectedLatestEventNumber=1,
                eventType="INTERNAL_HOLD_STARTED",reason="Waiting for original site evidence",
            ),installation,
        )
        assertEquals("INTERNAL_HOLD_STARTED",actual.eventType)
        assertEquals("INTERNAL_ONLY",actual.visibility)
        assertEquals(root + "/events", paths.single())
        assertFailsWith<IllegalArgumentException> {
            client.record(
                project,ticket,
                RecordSupportTimeDto(operation,2,"SLA_BREACH_RECORDED","Unapproved breach"),installation,
            )
        }
        assertEquals(1,paths.size)
    }
}

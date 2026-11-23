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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenanceReviewNativeContractTest {
    private val project = "11111111-1111-4111-8111-111111111111"
    private val visit = "77777777-7777-4777-8777-777777777777"
    private val operation = "44444444-4444-4444-8444-444444444444"
    private val evidence = "66666666-6666-4666-8666-666666666666"
    private val installation = "88888888-8888-4888-8888-888888888888"

    @Test
    fun managerCanReadActualSourceBlockersWithoutInventedWarranty() = runBlocking {
        val captured = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("Bearer phase11-token", req.headers[HttpHeaders.Authorization])
            captured += req.url.encodedPath
            respond(
                content = """{"visitId":"77777777-7777-4777-8777-777777777777","workOrderId":"55555555-5555-4555-8555-555555555555","currentWorkVersion":2,"currentWorkState":"IN_PROGRESS","reviewState":"REVIEWING","latestEventNumber":1,"requiredChecklistTotal":1,"requiredChecklistPending":1,"readyVisitEvidenceCount":0,"missingReasons":["CANONICAL_WORK_NOT_TECHNICALLY_ACCEPTED","CURRENT_VISIT_READY_EVIDENCE_MISSING"],"canConfirmInternalTechnicalCompletion":false,"clientAcceptanceRecorded":false,"warrantyEntitlementConfirmed":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = MaintenanceReviewApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-review" },
        ))
        val ready = client.readiness(project, visit, installation)
        assertFalse(ready.canConfirmInternalTechnicalCompletion)
        assertFalse(ready.clientAcceptanceRecorded)
        assertFalse(ready.warrantyEntitlementConfirmed)
        assertTrue("CANONICAL_WORK_NOT_TECHNICALLY_ACCEPTED" in ready.missingReasons)
        assertEquals(1, captured.size)
        assertEquals("/v1/projects/$project/maintenance-visits/$visit/review/readiness",
            captured.single())
    }

    @Test
    fun technicalCompletionUsesAuthenticatedIdempotentEventRoute() = runBlocking {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals("Bearer phase11-token", req.headers[HttpHeaders.Authorization])
            assertEquals(operation, req.headers["Idempotency-Key"])
            assertEquals(installation, req.headers["X-Device-Installation-Id"])
            paths += req.url.encodedPath
            respond(
                content = """{"eventId":"33333333-3333-4333-8333-333333333333","visitId":"77777777-7777-4777-8777-777777777777","eventNumber":2,"eventType":"INTERNAL_TECHNICAL_COMPLETION","resultingState":"COMPLETED_INTERNAL","sourceWorkVersion":9,"evidenceId":"66666666-6666-4666-8666-666666666666","reason":"Reviewed actual site proof","visibility":"INTERNAL_ONLY","recordedAt":"2026-11-23T10:00:00Z","duplicateReplay":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = MaintenanceReviewApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-review" },
        ))
        val event = client.record(project, visit,
            RecordMaintenanceReviewDto(
                operationId = operation, expectedLatestEventNumber = 1,
                sourceWorkVersion = 9, eventType = "INTERNAL_TECHNICAL_COMPLETION",
                evidenceId = evidence, reason = "Reviewed actual site proof",
            ), installation)
        assertEquals("COMPLETED_INTERNAL", event.resultingState)
        assertEquals("INTERNAL_ONLY", event.visibility)
        assertEquals("/v1/projects/$project/maintenance-visits/$visit/review/events",
            paths.single())
        assertFailsWith<IllegalArgumentException> {
            client.record(project, visit,
                RecordMaintenanceReviewDto(operation, 1, 9,
                    "INTERNAL_TECHNICAL_COMPLETION", null, "Forged proof"), installation)
        }
    }
}

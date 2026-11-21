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

class FieldEngineeringTestApiClientTest {
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val operationId = "22222222-2222-4222-8222-222222222222"
    private val installationId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun `capture uses selected WorkOrder idempotency and configurable values`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/work-orders/$workId/engineering-tests", request.url.encodedPath)
            assertEquals(operationId, request.headers["Idempotency-Key"])
            assertEquals(installationId, request.headers["X-Device-Installation-Id"])
            respond(
                content = """{
                    "resultId":"$operationId",
                    "workOrderId":"$workId",
                    "requirementKey":"fiber_acceptance",
                    "testSubjectRef":"LINK-A01",
                    "evaluatedStatus":"PASS",
                    "reviewerRelationshipCode":"FIELD_ENGINEER",
                    "replayed":false
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = FieldAssignedWorkApiClient(
            HiltechApiClient(
                client = http,
                baseUrl = "https://api.hiltech.test",
                accessTokenProvider = { "token" },
                correlationIdProvider = { "corr" },
            ),
        )
        val result = client.captureEngineeringTest(
            workId,
            FieldEngineeringTestCaptureDto(
                operationId = operationId,
                assignmentId = "44444444-4444-4444-8444-444444444444",
                assignmentVersion = 4,
                workOrderVersion = 7,
                evidencePolicyId = "55555555-5555-4555-8555-555555555555",
                evidencePolicyRevision = 3,
                requirementKey = "fiber_acceptance",
                schemaTemplateId = "66666666-6666-4666-8666-666666666666",
                schemaTemplateRevision = 2,
                schemaVersion = 1,
                testSubjectRef = "LINK-A01",
                values = mapOf("loss" to "0.20", "length" to "150.25"),
                capturedAt = "2026-11-22T00:00:00Z",
            ),
            installationId,
        )
        assertEquals("PASS", result.evaluatedStatus)
        assertEquals("FIELD_ENGINEER", result.reviewerRelationshipCode)
    }
}

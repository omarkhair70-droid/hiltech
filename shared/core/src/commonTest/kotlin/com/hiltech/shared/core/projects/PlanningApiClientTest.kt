package com.hiltech.shared.core.projects

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

class PlanningApiClientTest {
    @Test
    fun planningReadAndReadyCommandKeepTypedRouteAndIdempotencyContract() = runBlocking {
        val projectId = "11111111-1111-4111-8111-111111111111"
        val operationId = "22222222-2222-4222-8222-222222222222"
        val installationId = "33333333-3333-4333-8333-333333333333"
        val seen = mutableListOf<Triple<HttpMethod, String, String?>>()
        val response =
            """
            {
              "plan": {
                "project": {
                  "projectId":"$projectId", "projectCode":"PRJ-001", "name":"Plan",
                  "lifecycleState":"READY", "version":7, "baselineVersion":1
                },
                "projectSites":[], "areas":[], "milestones":[], "workPackages":[], "dependencies":[],
                "validationValid":true, "validationReasonCodes":[], "canEditPlan":false,
                "readyGateReady":false, "readyGateReasonCodes":["PROJECT_SITE_REQUIRED","PLANNING_NODE_REQUIRED","PROJECT_NOT_PLANNING"],
                "correlationId":"corr-server"
              },
              "targetType":"PROJECT", "targetId":"$projectId", "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()

        val http = HttpClient(MockEngine { request ->
            seen += Triple(request.method, request.url.encodedPath, request.headers["Idempotency-Key"])
            assertEquals("Bearer planning-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installationId, request.headers["X-Device-Installation-Id"])
            respond(
                content = if (request.method == HttpMethod.Get) {
                    response.substringAfter("\"plan\": ").substringBeforeLast(
                        ",\n  \"targetType\"",
                    )
                } else response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = PlanningApiClient(
            HiltechApiClient(
                client = http,
                baseUrl = "https://api.hiltech.test",
                accessTokenProvider = { "planning-token" },
                correlationIdProvider = { "corr-client" },
            ),
        )

        val plan = client.plan(projectId, installationId)
        assertEquals("PRJ-001", plan.project.projectCode)
        val ready = client.markReady(
            projectId,
            MarkProjectReadyRequestDto(
                operationId = operationId,
                baseProjectVersion = 7,
                expectedBaselineVersion = 1,
                clientOccurredAt = "2026-09-20T12:00:00Z",
            ),
            installationId,
        )
        assertEquals("READY", ready.plan.project.lifecycleState)
        assertEquals(
            listOf(
                Triple(HttpMethod.Get, "/v1/projects/$projectId/plan", null),
                Triple(HttpMethod.Post, "/v1/projects/$projectId/mark-ready", operationId),
            ),
            seen,
        )
    }
}

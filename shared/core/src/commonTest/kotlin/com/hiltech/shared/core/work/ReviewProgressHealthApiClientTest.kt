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

class ReviewProgressHealthApiClientTest {
    @Test
    fun reviewAndCommandCenterRoutesCarryNativeContracts() = runBlocking {
        val projectId = "11111111-1111-4111-8111-111111111111"
        val workOrderId = "22222222-2222-4222-8222-222222222222"
        val installationId = "33333333-3333-4333-8333-333333333333"
        val acceptOperation = "44444444-4444-4444-8444-444444444444"
        val reworkOperation = "55555555-5555-4555-8555-555555555555"
        val holdOperation = "66666666-6666-4666-8666-666666666666"
        val resumeOperation = "77777777-7777-4777-8777-777777777777"
        val seen = mutableListOf<Triple<HttpMethod, String, String?>>()

        val progress =
            """
            {
              "projectId":"$projectId",
              "projectVersion":9,
              "baselineVersion":2,
              "acceptedWeight":"3.000000",
              "totalWeight":"5.000000",
              "progressPercent":"60.0000",
              "includedWorkCount":3,
              "acceptedWorkCount":2,
              "asOf":"2026-09-21T01:00:00Z"
            }
            """.trimIndent()
        val health =
            """
            {
              "projectId":"$projectId",
              "projectVersion":9,
              "baselineVersion":2,
              "state":"ATTENTION",
              "healthPolicyId":null,
              "healthPolicyRevision":null,
              "signals":[],
              "reasonCodes":["REWORK_BACKLOG"],
              "asOf":"2026-09-21T01:00:00Z"
            }
            """.trimIndent()
        val reviewItem =
            """
            {
              "workOrderId":"$workOrderId",
              "workOrderCode":"WO-005",
              "projectId":"$projectId",
              "projectCode":"P-001",
              "projectName":"HQ rollout",
              "siteId":"88888888-8888-4888-8888-888888888888",
              "title":"Review rack",
              "submittedAt":"2026-09-21T00:50:00Z",
              "submittedVersion":4,
              "currentVersion":4,
              "reviewPolicy":{
                "configId":"99999999-9999-4999-8999-999999999999",
                "revision":1,
                "code":"TECH-REVIEW",
                "name":"Technical review",
                "mode":"ANY_ONE",
                "bindExactSubmittedVersion":true,
                "clientAcceptanceSeparate":false,
                "allowDelegation":false,
                "steps":[]
              },
              "decisions":[],
              "reviewer":{
                "eligible":true,
                "reviewStepKey":"technical",
                "sourceType":"PROJECT_RELATIONSHIP",
                "sourceRef":"PROJECT_MANAGER",
                "reasonCodes":[]
              },
              "beforeAcceptEvidenceSatisfied":true,
              "evidenceReasonCodes":[]
            }
            """.trimIndent()
        val reviewQueue = "[$reviewItem]"
        val commandCenter =
            """
            {
              "projectId":"$projectId",
              "projectCode":"P-001",
              "projectName":"HQ rollout",
              "lifecycleState":"ACTIVE",
              "projectManagerLabel":"PM",
              "baselineVersion":2,
              "progress":$progress,
              "health":$health,
              "nextMilestone":null,
              "waitingOn":[],
              "submittedReviewItems":$reviewQueue,
              "reworkItems":[],
              "projectSiteIds":[],
              "workPackageIds":[]
            }
            """.trimIndent()
        val decision =
            """
            {
              "decisionId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              "submittedWorkVersion":4,
              "reviewStepKey":"technical",
              "reviewerUserId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
              "decision":"ACCEPT",
              "reason":null,
              "decidedAt":"2026-09-21T01:01:00Z",
              "reviewerSourceType":"PROJECT_RELATIONSHIP",
              "reviewerSourceRef":"PROJECT_MANAGER"
            }
            """.trimIndent()
        val reviewMutation =
            """
            {
              "workOrderId":"$workOrderId",
              "lifecycleState":"ACCEPTED",
              "version":5,
              "decision":$decision,
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()
        val holdMutation =
            """
            {
              "projectId":"$projectId",
              "lifecycleState":"ON_HOLD",
              "projectVersion":10,
              "health":{
                "projectId":"$projectId",
                "projectVersion":10,
                "baselineVersion":2,
                "state":"ON_HOLD",
                "healthPolicyId":null,
                "healthPolicyRevision":null,
                "signals":[],
                "reasonCodes":[],
                "asOf":"2026-09-21T01:02:00Z"
              },
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()

        val http =
            HttpClient(
                MockEngine { request ->
                    seen +=
                        Triple(
                            request.method,
                            request.url.encodedPath,
                            request.headers["Idempotency-Key"],
                        )
                    assertEquals(
                        "Bearer review-token",
                        request.headers[HttpHeaders.Authorization],
                    )
                    assertEquals(
                        installationId,
                        request.headers["X-Device-Installation-Id"],
                    )
                    val path = request.url.encodedPath
                    respond(
                        content =
                            when {
                                path == "/v1/review/work-items" -> reviewQueue
                                path.endsWith("/command-center") -> commandCenter
                                path.endsWith("/progress") -> progress
                                path.endsWith("/health") -> health
                                path.endsWith("/accept") ||
                                    path.endsWith("/request-rework") -> reviewMutation
                                path.endsWith("/put-on-hold") ||
                                    path.endsWith("/resume") -> holdMutation
                                else -> error("Unexpected path $path")
                            },
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                            ),
                    )
                },
            )

        val client =
            ReviewProgressHealthApiClient(
                HiltechApiClient(
                    client = http,
                    baseUrl = "https://api.hiltech.test",
                    accessTokenProvider = { "review-token" },
                    correlationIdProvider = { "corr-client" },
                ),
            )

        assertEquals(
            workOrderId,
            client.reviewWorkItems(
                installationId,
            ).single().workOrderId,
        )
        assertEquals(
            "60.0000",
            client.progress(
                projectId,
                installationId,
            ).progressPercent,
        )
        assertEquals(
            "ATTENTION",
            client.health(
                projectId,
                installationId,
            ).state,
        )
        assertEquals(
            "P-001",
            client.commandCenter(
                projectId,
                installationId,
            ).projectCode,
        )
        assertEquals(
            "ACCEPTED",
            client.accept(
                workOrderId,
                ReviewDecisionRequestDto(
                    operationId = acceptOperation,
                    baseVersion = 4,
                    clientOccurredAt = "2026-09-21T01:01:00Z",
                ),
                installationId,
            ).lifecycleState,
        )
        client.requestRework(
            workOrderId,
            ReviewDecisionRequestDto(
                operationId = reworkOperation,
                baseVersion = 4,
                reason = "Fix termination",
                clientOccurredAt = "2026-09-21T01:01:30Z",
            ),
            installationId,
        )
        assertEquals(
            "ON_HOLD",
            client.putOnHold(
                projectId,
                ProjectHoldRequestDto(
                    operationId = holdOperation,
                    baseVersion = 9,
                    reason = "Client site unavailable",
                    clientOccurredAt = "2026-09-21T01:02:00Z",
                ),
                installationId,
            ).lifecycleState,
        )
        client.resume(
            projectId,
            ProjectResumeRequestDto(
                operationId = resumeOperation,
                baseVersion = 10,
                resolution = "Site reopened",
                clientOccurredAt = "2026-09-21T01:03:00Z",
            ),
            installationId,
        )

        assertEquals(
            listOf(
                Triple(HttpMethod.Get, "/v1/review/work-items", null),
                Triple(HttpMethod.Get, "/v1/projects/$projectId/progress", null),
                Triple(HttpMethod.Get, "/v1/projects/$projectId/health", null),
                Triple(HttpMethod.Get, "/v1/projects/$projectId/command-center", null),
                Triple(HttpMethod.Post, "/v1/work-orders/$workOrderId/accept", acceptOperation),
                Triple(HttpMethod.Post, "/v1/work-orders/$workOrderId/request-rework", reworkOperation),
                Triple(HttpMethod.Post, "/v1/projects/$projectId/put-on-hold", holdOperation),
                Triple(HttpMethod.Post, "/v1/projects/$projectId/resume", resumeOperation),
            ),
            seen,
        )
    }

    @Test
    fun rejectsNonCanonicalSlice05IdentifiersBeforeTransport() = runBlocking {
        val client =
            ReviewProgressHealthApiClient(
                HiltechApiClient(
                    client =
                        HttpClient(
                            MockEngine {
                                error("Transport must not be reached.")
                            },
                        ),
                    baseUrl = "https://api.hiltech.test",
                    accessTokenProvider = { "token" },
                    correlationIdProvider = { "corr" },
                ),
            )

        assertFailsWith<IllegalArgumentException> {
            client.progress(
                "not-a-uuid",
                "33333333-3333-4333-8333-333333333333",
            )
        }
    }
}

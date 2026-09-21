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

class ReadinessAssignmentApiClientTest {
    @Test
    fun readinessAssignmentRoutesCarryDeviceAndIdempotencyContracts() = runBlocking {
        val projectId = "11111111-1111-4111-8111-111111111111"
        val workOrderId = "22222222-2222-4222-8222-222222222222"
        val requirementId = "33333333-3333-4333-8333-333333333333"
        val targetId = "44444444-4444-4444-8444-444444444444"
        val installationId = "55555555-5555-4555-8555-555555555555"
        val evaluateOperation = "66666666-6666-4666-8666-666666666666"
        val assignOperation = "77777777-7777-4777-8777-777777777777"
        val waiveOperation = "88888888-8888-4888-8888-888888888888"
        val assignmentId = "99999999-9999-4999-8999-999999999999"
        val seen = mutableListOf<Triple<HttpMethod, String, String?>>()

        val readiness =
            """
            {
              "workOrderId":"$workOrderId",
              "workOrderVersion":4,
              "lifecycleState":"PLANNED",
              "readinessState":"READY",
              "assignmentMode":"MANUAL_ELIGIBLE",
              "requirements":[],
              "blockers":[],
              "eligibleTargets":[
                {
                  "targetType":"USER",
                  "targetId":"$targetId",
                  "displayLabel":"Field technician",
                  "eligible":true,
                  "reasonCodes":[],
                  "sourceAsOf":"2026-09-21T00:00:00Z",
                  "sourceFreshness":"CURRENT",
                  "requiredRoleChecks":[],
                  "requiredCertificationChecks":[],
                  "currentAssignmentConflict":null
                }
              ],
              "currentAssignment":null,
              "assignmentHistory":[],
              "waitingOnReasonCodes":["ASSIGNMENT_CONFIRMATION_REQUIRED"],
              "evaluatedAt":"2026-09-21T00:00:00Z"
            }
            """.trimIndent()
        val readinessMutation =
            """
            {
              "readiness":$readiness,
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()
        val assignmentMutation =
            """
            {
              "readiness":$readiness,
              "assignmentId":"$assignmentId",
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()
        val queue =
            """
            [
              {
                "workOrderId":"$workOrderId",
                "workOrderCode":"WO-001",
                "title":"Install rack",
                "actionCode":"ASSIGN_WORK",
                "reasonCodes":["READY_UNASSIGNED"],
                "ownerUserId":null
              }
            ]
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
                        "Bearer readiness-token",
                        request.headers[HttpHeaders.Authorization],
                    )
                    assertEquals(
                        installationId,
                        request.headers["X-Device-Installation-Id"],
                    )
                    respond(
                        content =
                            when {
                                request.url.encodedPath.endsWith("/work-queue-context") ->
                                    queue
                                request.url.encodedPath.endsWith("/eligible-targets") ->
                                    readiness
                                        .substringAfter(""eligibleTargets":")
                                        .substringBefore(",
  "currentAssignment"")
                                request.url.encodedPath.endsWith("/assign") ->
                                    assignmentMutation
                                request.url.encodedPath.endsWith("/evaluate-readiness") ||
                                    request.url.encodedPath.endsWith("/waive") ->
                                    readinessMutation
                                else -> readiness
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
            ReadinessAssignmentApiClient(
                HiltechApiClient(
                    client = http,
                    baseUrl = "https://api.hiltech.test",
                    accessTokenProvider = {
                        "readiness-token"
                    },
                    correlationIdProvider = {
                        "corr-client"
                    },
                ),
            )

        assertEquals(
            "READY",
            client.readiness(
                workOrderId,
                installationId,
            ).readinessState,
        )
        assertEquals(
            targetId,
            client.eligibleTargets(
                workOrderId,
                installationId,
            ).single().targetId,
        )
        assertEquals(
            "ASSIGN_WORK",
            client.workQueueContext(
                projectId,
                installationId,
            ).single().actionCode,
        )
        assertEquals(
            "READY",
            client.evaluate(
                workOrderId,
                EvaluateReadinessRequestDto(
                    operationId = evaluateOperation,
                    baseVersion = 3,
                    clientOccurredAt =
                        "2026-09-21T00:00:00Z",
                ),
                installationId,
            ).readiness.readinessState,
        )
        assertEquals(
            assignmentId,
            client.assign(
                workOrderId,
                AssignWorkRequestDto(
                    operationId = assignOperation,
                    targetType = "USER",
                    targetId = targetId,
                    baseVersion = 4,
                    clientOccurredAt =
                        "2026-09-21T00:01:00Z",
                ),
                installationId,
            ).assignmentId,
        )
        assertEquals(
            "READY",
            client.waive(
                workOrderId,
                requirementId,
                WaiveReadinessRequirementRequestDto(
                    operationId = waiveOperation,
                    baseWorkOrderVersion = 4,
                    baseRequirementVersion = 1,
                    reason = null,
                    clientOccurredAt =
                        "2026-09-21T00:02:00Z",
                ),
                installationId,
            ).readiness.readinessState,
        )

        assertEquals(
            listOf(
                Triple(
                    HttpMethod.Get,
                    "/v1/work-orders/$workOrderId/readiness",
                    null,
                ),
                Triple(
                    HttpMethod.Get,
                    "/v1/work-orders/$workOrderId/eligible-targets",
                    null,
                ),
                Triple(
                    HttpMethod.Get,
                    "/v1/projects/$projectId/work-queue-context",
                    null,
                ),
                Triple(
                    HttpMethod.Post,
                    "/v1/work-orders/$workOrderId/evaluate-readiness",
                    evaluateOperation,
                ),
                Triple(
                    HttpMethod.Post,
                    "/v1/work-orders/$workOrderId/assign",
                    assignOperation,
                ),
                Triple(
                    HttpMethod.Post,
                    "/v1/work-orders/$workOrderId/readiness/$requirementId/waive",
                    waiveOperation,
                ),
            ),
            seen,
        )
    }

    @Test
    fun rejectsNonCanonicalIdentifiersBeforeTransport() = runBlocking {
        val client =
            ReadinessAssignmentApiClient(
                HiltechApiClient(
                    client =
                        HttpClient(
                            MockEngine {
                                error(
                                    "Transport must not be reached.",
                                )
                            },
                        ),
                    baseUrl =
                        "https://api.hiltech.test",
                    accessTokenProvider = {
                        "token"
                    },
                    correlationIdProvider = {
                        "corr"
                    },
                ),
            )

        assertFailsWith<IllegalArgumentException> {
            client.readiness(
                "not-a-uuid",
                "55555555-5555-4555-8555-555555555555",
            )
        }
    }
}

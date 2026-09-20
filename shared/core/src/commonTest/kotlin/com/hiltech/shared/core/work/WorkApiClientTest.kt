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

class WorkApiClientTest {
    @Test
    fun workPlanningCommandsKeepTypedRoutesAndIdempotencyKeys() = runBlocking {
        val projectId = "11111111-1111-4111-8111-111111111111"
        val workOrderId = "22222222-2222-4222-8222-222222222222"
        val siteId = "33333333-3333-4333-8333-333333333333"
        val projectSiteId = "44444444-4444-4444-8444-444444444444"
        val installationId = "55555555-5555-4555-8555-555555555555"
        val createOperation = "66666666-6666-4666-8666-666666666666"
        val planOperation = "77777777-7777-4777-8777-777777777777"
        val activateOperation = "88888888-8888-4888-8888-888888888888"
        val taskId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val taskOperation = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val dependencyId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val removeDependencyOperation = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val seen = mutableListOf<Triple<HttpMethod, String, String?>>()

        val workMutation =
            """
            {
              "workOrder": {
                "workOrderId":"$workOrderId",
                "organizationId":"99999999-9999-4999-8999-999999999999",
                "workOrderCode":"WO-2026-001",
                "projectId":"$projectId",
                "siteId":"$siteId",
                "projectSiteId":"$projectSiteId",
                "areaId":null,
                "workPackageId":null,
                "title":"Install backbone",
                "description":null,
                "lifecycleState":"PLANNED",
                "readinessState":"NOT_EVALUATED",
                "plannedStart":null,
                "plannedEnd":null,
                "priorityCode":"NORMAL",
                "countsTowardProjectProgress":true,
                "progressWeight":"2.0",
                "baselineVersion":1,
                "createdAt":"2026-09-21T00:00:00Z",
                "updatedAt":"2026-09-21T00:00:00Z",
                "version":2,
                "binding":null,
                "instruction":null,
                "checklist":[],
                "requirements":[],
                "tasks":[],
                "dependencies":[]
              },
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()
        val taskMutation =
            """
            {
              "workOrder": ${workMutation.substringAfter("\"workOrder\": ").substringBeforeLast(",\n  \"replayed\"")},
              "taskId":"$taskId",
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()
        val dependencyMutation =
            """
            {
              "workOrder": ${workMutation.substringAfter("\"workOrder\": ").substringBeforeLast(",\n  \"replayed\"")},
              "dependencyId":"$dependencyId",
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()
        val activation =
            """
            {
              "projectId":"$projectId",
              "lifecycleState":"ACTIVE",
              "version":14,
              "replayed":false,
              "correlationId":"corr-server"
            }
            """.trimIndent()

        val http = HttpClient(
            MockEngine { request ->
                seen += Triple(
                    request.method,
                    request.url.encodedPath,
                    request.headers["Idempotency-Key"],
                )
                assertEquals(
                    "Bearer work-token",
                    request.headers[HttpHeaders.Authorization],
                )
                assertEquals(
                    installationId,
                    request.headers["X-Device-Installation-Id"],
                )
                respond(
                    content =
                        when {
                            request.url.encodedPath.endsWith("/activate") ->
                                activation
                            request.url.encodedPath.startsWith("/v1/work-tasks/") ->
                                taskMutation
                            request.method == HttpMethod.Delete &&
                                request.url.encodedPath.contains("/dependencies/") ->
                                dependencyMutation
                            else ->
                                workMutation
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
            WorkApiClient(
                HiltechApiClient(
                    client = http,
                    baseUrl = "https://api.hiltech.test",
                    accessTokenProvider = { "work-token" },
                    correlationIdProvider = { "corr-client" },
                ),
            )

        val created =
            client.create(
                projectId,
                CreateWorkOrderRequestDto(
                    operationId = createOperation,
                    siteId = siteId,
                    projectSiteId = projectSiteId,
                    workTypeCode = "INSTALL",
                    workTypeRevision = 1,
                    title = "Install backbone",
                    baseProjectVersion = 12,
                    expectedBaselineVersion = 1,
                    clientOccurredAt = "2026-09-21T00:00:00Z",
                ),
                installationId,
            )
        assertEquals("WO-2026-001", created.workOrder.workOrderCode)

        val planned =
            client.plan(
                workOrderId,
                PlanWorkRequestDto(
                    operationId = planOperation,
                    workTypeCode = "INSTALL",
                    workTypeRevision = 1,
                    payloadSchemaVersion = 1,
                    baseVersion = 1,
                    expectedBaselineVersion = 1,
                    clientOccurredAt = "2026-09-21T00:01:00Z",
                ),
                installationId,
            )
        assertEquals("PLANNED", planned.workOrder.lifecycleState)

        val active =
            client.activate(
                projectId,
                ActivateProjectRequestDto(
                    operationId = activateOperation,
                    baseVersion = 13,
                    expectedBaselineVersion = 1,
                    clientOccurredAt = "2026-09-21T00:02:00Z",
                ),
                installationId,
            )
        assertEquals("ACTIVE", active.lifecycleState)

        val taskUpdated =
            client.updateTask(
                taskId,
                UpdateWorkTaskRequestDto(
                    operationId = taskOperation,
                    title = "Verify rack position",
                    sortOrder = 10,
                    mandatory = true,
                    state = "CANCELLED",
                    baseTaskVersion = 1,
                    baseWorkOrderVersion = 2,
                    clientOccurredAt = "2026-09-21T00:03:00Z",
                ),
                installationId,
            )
        assertEquals(taskId, taskUpdated.taskId)

        val dependencyRemoved =
            client.removeDependency(
                workOrderId,
                dependencyId,
                RemoveWorkDependencyRequestDto(
                    operationId = removeDependencyOperation,
                    baseVersion = 3,
                    baseDependencyVersion = 1,
                    clientOccurredAt = "2026-09-21T00:04:00Z",
                ),
                installationId,
            )
        assertEquals(
            dependencyId,
            dependencyRemoved.dependencyId,
        )

        val expected: List<Triple<HttpMethod, String, String?>> =
            listOf(
                Triple(
                    HttpMethod.Post,
                    "/v1/projects/$projectId/work-orders",
                    createOperation,
                ),
                Triple(
                    HttpMethod.Post,
                    "/v1/work-orders/$workOrderId/plan",
                    planOperation,
                ),
                Triple(
                    HttpMethod.Post,
                    "/v1/projects/$projectId/activate",
                    activateOperation,
                ),
                Triple(
                    HttpMethod.Put,
                    "/v1/work-tasks/$taskId",
                    taskOperation,
                ),
                Triple(
                    HttpMethod.Delete,
                    "/v1/work-orders/$workOrderId/dependencies/$dependencyId",
                    removeDependencyOperation,
                ),
            )
        assertEquals(expected, seen)
    }
}

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
import kotlin.test.assertFalse

class FieldAssignedWorkApiClientTest {
    @Test
    fun todayAndJobBundleUseAuthenticatedSafeReadRoutes() = runBlocking {
        val workOrderId =
            "11111111-1111-4111-8111-111111111111"
        val installationId =
            "22222222-2222-4222-8222-222222222222"
        val seen =
            mutableListOf<Pair<HttpMethod, String>>()

        val today =
            """
            [
              {
                "workOrderId":"$workOrderId",
                "workOrderCode":"WO-2026-010",
                "title":"تركيب راك الموقع",
                "lifecycleState":"ASSIGNED",
                "readinessState":"READY",
                "plannedStart":"2026-09-21T07:00:00Z",
                "plannedEnd":"2026-09-21T10:00:00Z",
                "priorityCode":"HIGH",
                "project":{
                  "projectId":"33333333-3333-4333-8333-333333333333",
                  "projectCode":"P-010",
                  "name":"Beni Suef rollout",
                  "lifecycleState":"ACTIVE",
                  "baselineVersion":2
                },
                "site":{
                  "siteId":"44444444-4444-4444-8444-444444444444",
                  "siteCode":"BS-01",
                  "name":"Site 01",
                  "projectSiteId":"55555555-5555-4555-8555-555555555555",
                  "projectSiteCode":"PS-01",
                  "lifecycleState":"ACTIVE",
                  "accessInstructions":"اتصل بمسؤول الموقع",
                  "timezone":"Africa/Cairo"
                },
                "area":null,
                "assignment":{
                  "assignmentId":"66666666-6666-4666-8666-666666666666",
                  "targetType":"USER",
                  "targetId":"77777777-7777-4777-8777-777777777777",
                  "targetLabel":"Technician",
                  "validFrom":"2026-09-21T06:00:00Z",
                  "validUntil":null,
                  "version":1
                },
                "waitingReasonCodes":[],
                "importantBlocker":null,
                "instructionRevision":2,
                "workOrderVersion":8,
                "bundleAsOf":"2026-09-21T06:30:00Z",
                "currentlyActionable":true
              }
            ]
            """.trimIndent()

        val bundle =
            """
            {
              "workOrderId":"$workOrderId",
              "workOrderCode":"WO-2026-010",
              "title":"تركيب راك الموقع",
              "description":"Read-only field context",
              "lifecycleState":"ASSIGNED",
              "readinessState":"READY",
              "workOrderVersion":8,
              "project":{
                "projectId":"33333333-3333-4333-8333-333333333333",
                "projectCode":"P-010",
                "name":"Beni Suef rollout",
                "lifecycleState":"ACTIVE",
                "baselineVersion":2
              },
              "site":{
                "siteId":"44444444-4444-4444-8444-444444444444",
                "siteCode":"BS-01",
                "name":"Site 01",
                "projectSiteId":"55555555-5555-4555-8555-555555555555",
                "projectSiteCode":"PS-01",
                "lifecycleState":"ACTIVE",
                "accessInstructions":"اتصل بمسؤول الموقع",
                "timezone":"Africa/Cairo"
              },
              "area":null,
              "assignment":{
                "assignmentId":"66666666-6666-4666-8666-666666666666",
                "targetType":"USER",
                "targetId":"77777777-7777-4777-8777-777777777777",
                "targetLabel":"Technician",
                "validFrom":"2026-09-21T06:00:00Z",
                "validUntil":null,
                "version":1
              },
              "binding":null,
              "instruction":null,
              "tasks":[],
              "checklist":[],
              "readinessRequirements":[],
              "evidenceRequirements":[],
              "existingEvidence":[],
              "resourceRequirements":[
                {
                  "requirementId":"88888888-8888-4888-8888-888888888888",
                  "family":"MATERIAL",
                  "key":"FIBER",
                  "typeCode":"MATERIAL",
                  "required":true,
                  "state":"PENDING",
                  "integrationState":"SOURCE_PENDING_PHASE5",
                  "reasonCode":"PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                  "version":1
                }
              ],
              "safeDocumentRefs":[],
              "blockers":[],
              "waitingReasonCodes":[],
              "executionCapabilities":{
                "startOffline":false,
                "blockOffline":false,
                "resumeOffline":false,
                "evidenceCaptureOffline":false,
                "submitOffline":false,
                "authoritativeOfflineQueue":false
              },
              "asOf":"2026-09-21T06:30:00Z"
            }
            """.trimIndent()

        val http =
            HttpClient(
                MockEngine { request ->
                    seen +=
                        request.method to
                            request.url.encodedPath
                    assertEquals(
                        "Bearer field-token",
                        request.headers[
                            HttpHeaders.Authorization
                        ],
                    )
                    assertEquals(
                        installationId,
                        request.headers[
                            "X-Device-Installation-Id"
                        ],
                    )
                    respond(
                        content =
                            if (
                                request.url.encodedPath ==
                                "/v1/field/today"
                            ) {
                                today
                            } else {
                                bundle
                            },
                        status =
                            HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                            ),
                    )
                },
            )

        val client =
            FieldAssignedWorkApiClient(
                HiltechApiClient(
                    client = http,
                    baseUrl =
                        "https://api.hiltech.test",
                    accessTokenProvider = {
                        "field-token"
                    },
                    correlationIdProvider = {
                        "corr-field"
                    },
                ),
            )

        val todayResult =
            client.today(
                installationId,
            )
        assertEquals(
            workOrderId,
            todayResult.single()
                .workOrderId,
        )
        assertEquals(
            "SOURCE_PENDING_PHASE5",
            client.jobBundle(
                workOrderId,
                installationId,
            ).resourceRequirements
                .single()
                .integrationState,
        )
        val capabilities =
            client.jobBundle(
                workOrderId,
                installationId,
            ).executionCapabilities
        assertFalse(capabilities.startOffline)
        assertFalse(
            capabilities
                .authoritativeOfflineQueue,
        )

        assertEquals(
            listOf(
                HttpMethod.Get to
                    "/v1/field/today",
                HttpMethod.Get to
                    "/v1/work-orders/" +
                    workOrderId +
                    "/job-bundle",
                HttpMethod.Get to
                    "/v1/work-orders/" +
                    workOrderId +
                    "/job-bundle",
            ),
            seen,
        )
    }

    @Test
    fun rejectsInvalidWorkOrderBeforeTransport() = runBlocking {
        val client =
            FieldAssignedWorkApiClient(
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

        assertFailsWith<
            IllegalArgumentException,
        > {
            client.jobBundle(
                "not-a-uuid",
                "22222222-2222-4222-8222-222222222222",
            )
        }
    }
}

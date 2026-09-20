package com.hiltech.shared.core.projects

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectsApiClientTest {
    @Test
    fun projectSiteAttachSupportsExactlyOneExistingOrCreateMode() =
        runBlocking {
            val projectId =
                "11111111-1111-1111-1111-111111111111"
            val siteId =
                "22222222-2222-2222-2222-222222222222"
            val organizationId =
                "33333333-3333-3333-3333-333333333333"
            val clientOrganizationId =
                "44444444-4444-4444-4444-444444444444"
            val installationId =
                "55555555-5555-5555-5555-555555555555"
            val existingOperation =
                "66666666-6666-6666-6666-666666666666"
            val createOperation =
                "77777777-7777-7777-7777-777777777777"
            val seen =
                mutableListOf<Pair<String, String?>>()

            val http =
                HttpClient(
                    MockEngine { request ->
                        seen +=
                            request.url.encodedPath to
                                request.headers[
                                    "Idempotency-Key"
                                ]
                        assertEquals(
                            "Bearer project-token",
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
                                """
                                {
                                  "project":{
                                    "projectId":"$projectId",
                                    "organizationId":"$organizationId",
                                    "projectCode":"PRJ-2026-001",
                                    "name":"Project",
                                    "clientOrganizationId":"$clientOrganizationId",
                                    "clientDisplayName":"Client",
                                    "sourceType":"INTERNAL",
                                    "sourceExternalReference":null,
                                    "lifecycleState":"PLANNING",
                                    "currentResponsibility":{
                                      "responsibilityId":null,
                                      "principalType":null,
                                      "principalId":null,
                                      "principalLabel":null,
                                      "resolutionState":"UNASSIGNED",
                                      "effectiveFrom":null,
                                      "version":null
                                    },
                                    "startDatePlanned":"2026-10-01",
                                    "endDatePlanned":"2026-11-30",
                                    "baselineVersion":1,
                                    "siteCount":1,
                                    "createdAt":"2026-09-20T10:00:00Z",
                                    "updatedAt":"2026-09-20T10:05:00Z",
                                    "version":2
                                  },
                                  "projectSite":{
                                    "projectSiteId":"88888888-8888-8888-8888-888888888888",
                                    "organizationId":"$organizationId",
                                    "projectId":"$projectId",
                                    "site":{
                                      "siteId":"$siteId",
                                      "organizationId":"$organizationId",
                                      "clientOrganizationId":"$clientOrganizationId",
                                      "clientDisplayName":"Client",
                                      "siteCode":"SITE-01",
                                      "name":"Site",
                                      "addressText":"Restricted",
                                      "latitude":30.0,
                                      "longitude":31.0,
                                      "timezone":"Africa/Cairo",
                                      "status":"ACTIVE",
                                      "createdAt":"2026-09-20T10:00:00Z",
                                      "updatedAt":"2026-09-20T10:00:00Z",
                                      "version":1
                                    },
                                    "projectSiteCode":"MAIN",
                                    "lifecycleState":"PLANNED",
                                    "accessInstructions":"Restricted",
                                    "projectSpecificNotes":null,
                                    "activeFrom":null,
                                    "activeUntil":null,
                                    "version":1
                                  },
                                  "replayed":false,
                                  "correlationId":"corr-project-site"
                                }
                                """.trimIndent(),
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
                ProjectsApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "project-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )

            val existing =
                client.attachSite(
                    projectId = projectId,
                    request =
                        AttachProjectSiteRequestDto(
                            operationId =
                                existingOperation,
                            baseProjectVersion = 1,
                            siteId = siteId,
                            projectSiteCode = "MAIN",
                            clientOccurredAt =
                                "2026-09-20T10:00:00Z",
                        ),
                    installationId =
                        installationId,
                )
            assertEquals(
                siteId,
                existing.projectSite.site.siteId,
            )

            val created =
                client.attachSite(
                    projectId = projectId,
                    request =
                        AttachProjectSiteRequestDto(
                            operationId =
                                createOperation,
                            baseProjectVersion = 1,
                            createSite =
                                CreateProjectSiteSiteRequestDto(
                                    siteCode =
                                        "SITE-01",
                                    name = "Site",
                                    addressText =
                                        "Restricted",
                                    timezone =
                                        "Africa/Cairo",
                                ),
                            projectSiteCode =
                                "MAIN",
                            clientOccurredAt =
                                "2026-09-20T10:00:00Z",
                        ),
                    installationId =
                        installationId,
                )
            assertEquals(
                "PLANNED",
                created.projectSite.lifecycleState,
            )

            assertEquals(
                listOf(
                    "/v1/projects/$projectId/sites" to
                        existingOperation,
                    "/v1/projects/$projectId/sites" to
                        createOperation,
                ),
                seen,
            )

            assertFailsWith<
                IllegalArgumentException
            > {
                client.attachSite(
                    projectId = projectId,
                    request =
                        AttachProjectSiteRequestDto(
                            operationId =
                                "99999999-9999-9999-9999-999999999999",
                            baseProjectVersion = 1,
                            siteId = siteId,
                            createSite =
                                CreateProjectSiteSiteRequestDto(
                                    siteCode =
                                        "SITE-02",
                                    name =
                                        "Invalid dual mode",
                                ),
                            clientOccurredAt =
                                "2026-09-20T10:00:00Z",
                        ),
                    installationId =
                        installationId,
                )
            }
        }
}

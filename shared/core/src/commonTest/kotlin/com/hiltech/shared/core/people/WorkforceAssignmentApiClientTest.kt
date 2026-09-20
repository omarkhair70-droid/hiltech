package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkforceAssignmentApiClientTest {
    @Test
    fun assignmentReadsAndCreateReuseSharedAuthenticatedContract() =
        runBlocking {
            val seen =
                mutableListOf<
                    Pair<String, String?>
                >()
            val organizationId =
                "11111111-1111-1111-1111-111111111111"
            val employeeId =
                "22222222-2222-2222-2222-222222222222"
            val assignmentId =
                "33333333-3333-3333-3333-333333333333"
            val teamId =
                "44444444-4444-4444-4444-444444444444"
            val operationId =
                "55555555-5555-5555-5555-555555555555"
            val installationId =
                "66666666-6666-6666-6666-666666666666"

            val assignment =
                """
                {
                  "assignmentId":"$assignmentId",
                  "organizationId":"$organizationId",
                  "employeeId":"$employeeId",
                  "employeeCode":"EMP-001",
                  "employeeDisplayName":"Test Worker",
                  "teamId":"$teamId",
                  "teamCode":"FIELD-A",
                  "teamName":"Field Crew A",
                  "roleCode":"TECHNICIAN",
                  "roleLabel":"Technician",
                  "reportsToEmployeeId":null,
                  "reportsToEmployeeCode":null,
                  "reportsToDisplayName":null,
                  "state":"ACTIVE",
                  "effectiveFrom":"2026-09-20T00:00:00Z",
                  "effectiveTo":null,
                  "version":1
                }
                """.trimIndent()

            val http =
                HttpClient(
                    MockEngine { request ->
                        seen +=
                            pathOf(request) to
                                request.headers[
                                    "Idempotency-Key"
                                ]

                        assertEquals(
                            "Bearer workforce-token",
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

                        val body =
                            when (
                                request.url
                                    .encodedPath
                            ) {
                                "/v1/workforce-assignments" ->
                                    """
                                    {
                                      "items":[$assignment],
                                      "correlationId":"corr-structure"
                                    }
                                    """.trimIndent()

                                else ->
                                    if (
                                        request.method
                                            .value ==
                                        "POST"
                                    ) {
                                        """
                                        {
                                          "assignment":$assignment,
                                          "replayed":false,
                                          "correlationId":"corr-create"
                                        }
                                        """.trimIndent()
                                    } else {
                                        assignment
                                    }
                            }

                        respond(
                            content = body,
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
                WorkforceAssignmentApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "workforce-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )

            val current =
                client.currentForEmployee(
                    employeeId =
                        employeeId,
                    installationId =
                        installationId,
                )
            val own =
                client.own(
                    organizationId =
                        organizationId,
                    installationId =
                        installationId,
                )
            val structure =
                client.structure(
                    organizationId =
                        organizationId,
                    installationId =
                        installationId,
                    limit = 50,
                )
            val created =
                client.create(
                    employeeId =
                        employeeId,
                    request =
                        CreateWorkforceAssignmentRequestDto(
                            operationId =
                                operationId,
                            baseEmployeeVersion = 1,
                            teamId = teamId,
                            roleCode =
                                "TECHNICIAN",
                            effectiveFrom =
                                "2026-09-20T00:00:00Z",
                        ),
                    installationId =
                        installationId,
                )

            assertEquals(
                "TECHNICIAN",
                current.roleCode,
            )
            assertEquals(
                teamId,
                own.teamId,
            )
            assertEquals(
                employeeId,
                structure.items
                    .single()
                    .employeeId,
            )
            assertEquals(
                assignmentId,
                created.assignment
                    .assignmentId,
            )

            assertEquals(
                listOf(
                    "/v1/employees/$employeeId/workforce-assignment" to null,
                    "/v1/me/workforce-assignment?organizationId=$organizationId" to null,
                    "/v1/workforce-assignments?organizationId=$organizationId&limit=50" to null,
                    "/v1/employees/$employeeId/workforce-assignment" to operationId,
                ),
                seen,
            )
        }

    private fun pathOf(
        request: HttpRequestData,
    ): String =
        buildString {
            append(
                request.url.encodedPath,
            )
            if (
                request.url.encodedQuery
                    .isNotBlank()
            ) {
                append("?")
                append(
                    request.url
                        .encodedQuery,
                )
            }
        }
}

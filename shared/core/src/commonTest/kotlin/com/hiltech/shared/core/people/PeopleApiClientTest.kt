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

class PeopleApiClientTest {
    @Test
    fun peopleReadsAndCommandsUseSharedAuthenticatedIdempotentContract() =
        runBlocking {
            val seen =
                mutableListOf<
                    Pair<String, String?>
                >()
            val organizationId =
                "11111111-1111-1111-1111-111111111111"
            val employeeId =
                "22222222-2222-2222-2222-222222222222"
            val identityId =
                "33333333-3333-3333-3333-333333333333"
            val installationId =
                "44444444-4444-4444-4444-444444444444"
            val createOperation =
                "55555555-5555-5555-5555-555555555555"
            val linkOperation =
                "66666666-6666-6666-6666-666666666666"
            val updateOperation =
                "77777777-7777-7777-7777-777777777777"

            val http =
                HttpClient(
                    MockEngine { request ->
                        seen +=
                            pathOf(request) to
                                request.headers[
                                    "Idempotency-Key"
                                ]

                        assertEquals(
                            "Bearer people-token",
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

                        val detail =
                            """
                            {
                              "employeeId":"$employeeId",
                              "organizationId":"$organizationId",
                              "employeeCode":"EMP-001",
                              "employeeState":"PREBOARDING",
                              "displayName":"Test Worker",
                              "legalName":"Restricted Name",
                              "mobile":"01000000000",
                              "email":"worker@example.test",
                              "hireDate":"2026-09-01",
                              "endDate":null,
                              "currentEmploymentId":"88888888-8888-8888-8888-888888888888",
                              "employmentTypeCode":"FIELD",
                              "employmentStartDate":"2026-09-01",
                              "employmentEndDate":null,
                              "linkedIdentityId":"$identityId",
                              "version":2
                            }
                            """.trimIndent()

                        val body =
                            when {
                                request.url.encodedPath ==
                                    "/v1/employees" &&
                                    request.method.value ==
                                    "GET" ->
                                    """
                                    {
                                      "items":[
                                        {
                                          "employeeId":"$employeeId",
                                          "employeeCode":"EMP-001",
                                          "displayName":"Test Worker",
                                          "employeeState":"PREBOARDING",
                                          "linkedIdentity":true,
                                          "currentEmploymentTypeCode":"FIELD",
                                          "hireDate":"2026-09-01",
                                          "version":2
                                        }
                                      ],
                                      "canManagePeople":true,
                                      "correlationId":"corr-directory"
                                    }
                                    """.trimIndent()

                                request.url.encodedPath ==
                                    "/v1/me/employee" ->
                                    detail.replace(
                                        ""linkedIdentityId":"$identityId",",
                                        ""linkedIdentityId":null,",
                                    )

                                request.method.value in
                                    setOf(
                                        "POST",
                                        "PUT",
                                    ) ->
                                    """
                                    {
                                      "employee":$detail,
                                      "replayed":false,
                                      "correlationId":"corr-command"
                                    }
                                    """.trimIndent()

                                else ->
                                    detail
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
                PeopleApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "people-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )

            val directory =
                client.directory(
                    organizationId =
                        organizationId,
                    installationId =
                        installationId,
                    limit = 25,
                )
            val detail =
                client.detail(
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
            val created =
                client.create(
                    request =
                        CreateEmployeeRequestDto(
                            operationId =
                                createOperation,
                            organizationId =
                                organizationId,
                            displayName =
                                "Test Worker",
                            employeeCode =
                                "EMP-001",
                            startDate =
                                "2026-09-01",
                        ),
                    installationId =
                        installationId,
                )
            val linked =
                client.linkIdentity(
                    employeeId =
                        employeeId,
                    request =
                        LinkEmployeeIdentityRequestDto(
                            operationId =
                                linkOperation,
                            baseVersion = 1,
                            userIdentityId =
                                identityId,
                        ),
                    installationId =
                        installationId,
                )
            val updated =
                client.updateProfile(
                    employeeId =
                        employeeId,
                    request =
                        UpdateEmployeeProfileRequestDto(
                            operationId =
                                updateOperation,
                            baseVersion = 2,
                            displayName =
                                "Updated Worker",
                        ),
                    installationId =
                        installationId,
                )

            assertEquals(
                employeeId,
                directory.items
                    .single()
                    .employeeId,
            )
            assertEquals(
                true,
                directory.canManagePeople,
            )
            assertEquals(
                "Restricted Name",
                detail.legalName,
            )
            assertEquals(
                null,
                own.linkedIdentityId,
            )
            assertEquals(
                employeeId,
                created.employee.employeeId,
            )
            assertEquals(
                employeeId,
                linked.employee.employeeId,
            )
            assertEquals(
                employeeId,
                updated.employee.employeeId,
            )
            assertEquals(
                listOf(
                    "/v1/employees?organizationId=$organizationId&limit=25" to null,
                    "/v1/employees/$employeeId" to null,
                    "/v1/me/employee?organizationId=$organizationId" to null,
                    "/v1/employees" to createOperation,
                    "/v1/employees/$employeeId/identity-link" to linkOperation,
                    "/v1/employees/$employeeId/profile" to updateOperation,
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
                    request.url.encodedQuery,
                )
            }
        }
}

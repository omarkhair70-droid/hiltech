package com.hiltech.shared.core.approval

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

class ApprovalApiClientTest {
    @Test
    fun assignedReadAndDecisionUseOneSharedAuthenticatedContract() =
        runBlocking {
            val seen =
                mutableListOf<
                    Pair<String, String?>
                >()
            val requestId =
                "11111111-1111-1111-1111-111111111111"
            val operationId =
                "22222222-2222-2222-2222-222222222222"
            val cursor =
                "YWJj.ZGVm"

            val http =
                HttpClient(
                    MockEngine { request ->
                        seen +=
                            pathOf(request) to
                                request.headers[
                                    "Idempotency-Key"
                                ]

                        assertEquals(
                            "Bearer approval-token",
                            request.headers[
                                HttpHeaders.Authorization
                            ],
                        )
                        assertEquals(
                            "33333333-3333-3333-3333-333333333333",
                            request.headers[
                                "X-Device-Installation-Id"
                            ],
                        )

                        val body =
                            when {
                                request.url
                                    .encodedPath
                                    .endsWith(
                                        "/decisions",
                                    ) ->
                                    """
                                    {
                                      "approvalRequestId":"$requestId",
                                      "state":"APPROVED",
                                      "replayed":false,
                                      "correlationId":"corr-decision"
                                    }
                                    """.trimIndent()

                                request.url
                                    .encodedPath ==
                                    "/v1/approvals/assigned" ->
                                    """
                                    {
                                      "items":[
                                        {
                                          "approvalRequestId":"$requestId",
                                          "subjectType":"CONTRACT_EXCEPTION",
                                          "subjectId":"44444444-4444-4444-4444-444444444444",
                                          "subjectVersion":1,
                                          "policyKey":"EXCEPTION_OWNER_FINAL",
                                          "policyVersion":1,
                                          "state":"PENDING",
                                          "reasonCode":"NON_ROUTINE_EXCEPTION",
                                          "safeReasonSummary":"Needs final authority.",
                                          "createdAt":"2026-09-19T18:30:00.123456Z",
                                          "authorityKey":"OWNER_FINAL"
                                        }
                                      ],
                                      "nextCursor":"bmV4dA.sig",
                                      "asOf":"2026-09-19T18:31:00.123456Z",
                                      "correlationId":"corr-page"
                                    }
                                    """.trimIndent()

                                else ->
                                    """
                                    {
                                      "approvalRequestId":"$requestId",
                                      "subjectType":"CONTRACT_EXCEPTION",
                                      "subjectId":"44444444-4444-4444-4444-444444444444",
                                      "subjectVersion":1,
                                      "policyKey":"EXCEPTION_OWNER_FINAL",
                                      "policyVersion":1,
                                      "state":"PENDING",
                                      "reasonCode":"NON_ROUTINE_EXCEPTION",
                                      "safeReasonSummary":"Needs final authority.",
                                      "createdAt":"2026-09-19T18:30:00.123456Z",
                                      "authorityKey":"OWNER_FINAL"
                                    }
                                    """.trimIndent()
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
                ApprovalApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "approval-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )
            val installationId =
                "33333333-3333-3333-3333-333333333333"

            val page =
                client.assigned(
                    installationId =
                        installationId,
                    limit = 25,
                    cursor = cursor,
                )
            val one =
                client.one(
                    approvalRequestId =
                        requestId,
                    installationId =
                        installationId,
                )
            val decision =
                client.decide(
                    approvalRequestId =
                        requestId,
                    request =
                        ApprovalDecisionRequestDto(
                            operationId =
                                operationId,
                            decision =
                                "APPROVE",
                        ),
                    installationId =
                        installationId,
                )

            assertEquals(
                "PENDING",
                page.items.single().state,
            )
            assertEquals(
                requestId,
                one.approvalRequestId,
            )
            assertEquals(
                "APPROVED",
                decision.state,
            )
            assertEquals(
                listOf(
                    "/v1/approvals/assigned?limit=25&cursor=$cursor" to null,
                    "/v1/approvals/$requestId" to null,
                    "/v1/approvals/$requestId/decisions" to operationId,
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

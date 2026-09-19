package com.hiltech.shared.core.inbox

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

class InboxApiClientTest {
    @Test
    fun sharedClientCoversWorkQueueInboxAndReadState() =
        runBlocking {
            val itemId =
                "11111111-1111-1111-1111-111111111111"
            val sourceId =
                "22222222-2222-2222-2222-222222222222"
            val readOperation =
                "33333333-3333-3333-3333-333333333333"
            val unreadOperation =
                "44444444-4444-4444-4444-444444444444"
            val installationId =
                "55555555-5555-5555-5555-555555555555"
            val cursor =
                "YWJj.ZGVm"
            val seen =
                mutableListOf<
                    Pair<String, String?>
                >()

            val http =
                HttpClient(
                    MockEngine { request ->
                        seen +=
                            pathOf(request) to
                                request.headers[
                                    "Idempotency-Key"
                                ]

                        assertEquals(
                            "Bearer inbox-token",
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

                        val path =
                            request.url.encodedPath
                        val body =
                            when {
                                path.endsWith(
                                    "/read",
                                ) ->
                                    """
                                    {
                                      "inboxItemId":"$itemId",
                                      "read":true,
                                      "replayed":false,
                                      "correlationId":"corr-read"
                                    }
                                    """.trimIndent()

                                path.endsWith(
                                    "/unread",
                                ) ->
                                    """
                                    {
                                      "inboxItemId":"$itemId",
                                      "read":false,
                                      "replayed":false,
                                      "correlationId":"corr-unread"
                                    }
                                    """.trimIndent()

                                path ==
                                    "/v1/inbox/$itemId" ->
                                    itemJson(
                                        itemId,
                                        sourceId,
                                        true,
                                    )

                                else ->
                                    """
                                    {
                                      "items":[
                                        ${itemJson(itemId, sourceId, false)}
                                      ],
                                      "nextCursor":"bmV4dA.sig",
                                      "asOf":"2026-09-19T19:45:00.123456789Z",
                                      "correlationId":"corr-page"
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
                InboxApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "inbox-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )

            val queue =
                client.workQueue(
                    installationId =
                        installationId,
                    limit = 10,
                    cursor = cursor,
                )
            val inbox =
                client.inbox(
                    installationId =
                        installationId,
                    limit = 20,
                    cursor = cursor,
                    state = "OPEN",
                    read = "UNREAD",
                )
            val one =
                client.one(
                    inboxItemId = itemId,
                    installationId =
                        installationId,
                )
            val read =
                client.markRead(
                    inboxItemId = itemId,
                    request =
                        InboxReadStateRequestDto(
                            operationId =
                                readOperation,
                        ),
                    installationId =
                        installationId,
                )
            val unread =
                client.markUnread(
                    inboxItemId = itemId,
                    request =
                        InboxReadStateRequestDto(
                            operationId =
                                unreadOperation,
                        ),
                    installationId =
                        installationId,
                )

            assertEquals(
                itemId,
                queue.items.single()
                    .inboxItemId,
            )
            assertEquals(
                "OPEN",
                inbox.items.single()
                    .state,
            )
            assertEquals(
                true,
                one.read,
            )
            assertEquals(
                true,
                read.read,
            )
            assertEquals(
                false,
                unread.read,
            )
            assertEquals(
                listOf(
                    "/v1/work-queue?limit=10&cursor=$cursor" to null,
                    "/v1/inbox?limit=20&cursor=$cursor&state=OPEN&read=UNREAD" to null,
                    "/v1/inbox/$itemId" to null,
                    "/v1/inbox/$itemId/read" to readOperation,
                    "/v1/inbox/$itemId/unread" to unreadOperation,
                ),
                seen,
            )
        }

    private fun itemJson(
        itemId: String,
        sourceId: String,
        read: Boolean,
    ): String =
        """
        {
          "inboxItemId":"$itemId",
          "sourceType":"APPROVAL_REQUEST",
          "sourceId":"$sourceId",
          "sourceVersion":1,
          "actionKey":"DECIDE_APPROVAL",
          "attentionClass":"ACTION_REQUIRED",
          "state":"OPEN",
          "read":$read,
          "actionable":true,
          "safeTitleCode":"APPROVAL_DECISION_REQUIRED",
          "safeSummary":"Exception requires final authority.",
          "createdAt":"2026-09-19T19:40:00.123456789Z",
          "updatedAt":"2026-09-19T19:40:00.123456789Z",
          "resolvedAt":null
        }
        """.trimIndent()

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

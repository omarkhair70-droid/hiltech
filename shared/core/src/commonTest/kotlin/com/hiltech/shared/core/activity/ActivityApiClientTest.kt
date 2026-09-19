package com.hiltech.shared.core.activity

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
import kotlin.test.assertNull

class ActivityApiClientTest {
    @Test
    fun workOrderActivityUsesSharedAuthenticatedReadContract() =
        runBlocking {
            val cursor =
                "YWJj.ZGVm"
            var seenPath = ""

            val http =
                HttpClient(
                    MockEngine { request ->
                        seenPath =
                            request.url.encodedPath +
                                "?" +
                                request.url.encodedQuery

                        assertEquals(
                            "Bearer activity-token",
                            request.headers[
                                HttpHeaders.Authorization
                            ],
                        )
                        assertEquals(
                            "11111111-1111-1111-1111-111111111111",
                            request.headers[
                                "X-Device-Installation-Id"
                            ],
                        )
                        assertNull(
                            request.headers[
                                "Idempotency-Key"
                            ],
                        )

                        respond(
                            content =
                                """
                                {
                                  "items":[
                                    {
                                      "activityId":"activity-1",
                                      "activityType":"EVIDENCE_READY",
                                      "contextType":"WORK_ORDER",
                                      "contextId":"work-1",
                                      "sourceType":"EVIDENCE",
                                      "sourceId":"evidence-1",
                                      "occurredAt":"2026-09-19T10:00:00Z",
                                      "safeSummary":{
                                        "storageState":"READY",
                                        "evidenceTypeCode":"PHOTO"
                                      },
                                      "classificationCode":"INTERNAL",
                                      "correlationId":"corr-source"
                                    }
                                  ],
                                  "nextCursor":"bmV4dA.sig",
                                  "asOf":"2026-09-19T10:01:00Z",
                                  "correlationId":"corr-read"
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
                ActivityApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "activity-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )

            val page =
                client.workOrderActivity(
                    workOrderId =
                        "work-1",
                    installationId =
                        "11111111-1111-1111-1111-111111111111",
                    limit = 25,
                    cursor = cursor,
                )

            assertEquals(
                "/v1/work-orders/work-1/activity?limit=25&cursor=$cursor",
                seenPath,
            )
            assertEquals(
                "EVIDENCE_READY",
                page.items.single()
                    .activityType,
            )
            assertEquals(
                "READY",
                page.items.single()
                    .safeSummary[
                        "storageState"
                    ],
            )
            assertEquals(
                "bmV4dA.sig",
                page.nextCursor,
            )
        }
}

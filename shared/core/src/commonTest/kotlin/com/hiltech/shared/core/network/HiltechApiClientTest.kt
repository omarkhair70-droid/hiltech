package com.hiltech.shared.core.network

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
import kotlin.test.assertTrue

class HiltechApiClientTest {
    @Test
    fun authenticatedCommandCarriesCanonicalHeaders() =
        runBlocking {
            lateinit var captured:
                io.ktor.client.request.HttpRequestData

            val client = HttpClient(
                MockEngine { request ->
                    captured = request
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test/",
                accessTokenProvider = {
                    "access-token"
                },
                correlationIdProvider = {
                    "corr-command-1"
                },
                traceParentProvider = {
                    "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"
                },
            )

            val result = api.request(
                method = HttpMethod.Post,
                path = "/v1/probe",
                options =
                    HiltechRequestOptions(
                        installationId =
                            "11111111-1111-1111-1111-111111111111",
                        idempotencyKey =
                            "22222222-2222-2222-2222-222222222222",
                    ),
                requestBody =
                    """{"operationId":"22222222-2222-2222-2222-222222222222"}""",
                decode = { it },
            )

            assertEquals(
                """{"ok":true}""",
                result,
            )
            assertEquals(
                "Bearer access-token",
                captured.headers[
                    HttpHeaders.Authorization
                ],
            )
            assertEquals(
                "corr-command-1",
                captured.headers[
                    "X-Correlation-Id"
                ],
            )
            assertEquals(
                "11111111-1111-1111-1111-111111111111",
                captured.headers[
                    "X-Device-Installation-Id"
                ],
            )
            assertEquals(
                "22222222-2222-2222-2222-222222222222",
                captured.headers[
                    "Idempotency-Key"
                ],
            )
            assertEquals(
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                captured.headers["traceparent"],
            )
        }

    @Test
    fun canonicalServiceUnavailableEnvelopeIsRetryable() =
        runBlocking {
            val client = HttpClient(
                MockEngine {
                    respond(
                        content = """
                        {
                          "code":"TEMPORARY_UNAVAILABLE",
                          "message":"Try again later.",
                          "correlationId":"corr-server",
                          "retryable":true
                        }
                        """.trimIndent(),
                        status =
                            HttpStatusCode
                                .ServiceUnavailable,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    "token"
                },
                correlationIdProvider = {
                    "corr-client"
                },
            )

            val failure =
                assertFailsWith<
                    HiltechApiException
                > {
                    api.request(
                        method = HttpMethod.Get,
                        path = "/v1/probe",
                        decode = { it },
                    )
                }

            assertEquals(
                "TEMPORARY_UNAVAILABLE",
                failure.code,
            )
            assertEquals(
                "corr-server",
                failure.correlationId,
            )
            assertEquals(
                503,
                failure.httpStatus,
            )
            assertTrue(failure.retryable)
        }

    @Test
    fun malformedRetryableResponseStillUsesSafeFallback() =
        runBlocking {
            val client = HttpClient(
                MockEngine {
                    respond(
                        content =
                            "provider exploded: secret=hidden",
                        status =
                            HttpStatusCode
                                .ServiceUnavailable,
                        headers = headersOf(
                            "X-Correlation-Id",
                            "corr-header",
                        ),
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    "token"
                },
                correlationIdProvider = {
                    "corr-client"
                },
            )

            val failure =
                assertFailsWith<
                    HiltechApiException
                > {
                    api.request(
                        method = HttpMethod.Get,
                        path = "/v1/probe",
                        decode = { it },
                    )
                }

            assertEquals(
                "TEMPORARY_UNAVAILABLE",
                failure.code,
            )
            assertEquals(
                "corr-header",
                failure.correlationId,
            )
            assertTrue(failure.retryable)
            assertFalse(
                failure.message
                    .orEmpty()
                    .contains("secret"),
            )
        }

    @Test
    fun networkFailureIsTypedRetryableAndPayloadSafe() =
        runBlocking {
            val client = HttpClient(
                MockEngine {
                    error(
                        "socket failure token=do-not-leak",
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    "token"
                },
                correlationIdProvider = {
                    "corr-network"
                },
            )

            val failure =
                assertFailsWith<
                    HiltechApiException
                > {
                    api.request(
                        method = HttpMethod.Get,
                        path = "/v1/probe",
                        decode = { it },
                    )
                }

            assertEquals(
                "NETWORK_UNAVAILABLE",
                failure.code,
            )
            assertEquals(
                "corr-network",
                failure.correlationId,
            )
            assertTrue(failure.retryable)
            assertFalse(
                failure.message
                    .orEmpty()
                    .contains("token"),
            )
        }

    @Test
    fun missingTokenFailsBeforeNetwork() =
        runBlocking {
            var networkCalled = false
            val client = HttpClient(
                MockEngine {
                    networkCalled = true
                    respond(
                        "",
                        HttpStatusCode.OK,
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    null
                },
                correlationIdProvider = {
                    "corr-auth"
                },
            )

            val failure =
                assertFailsWith<
                    HiltechApiException
                > {
                    api.request(
                        method = HttpMethod.Get,
                        path = "/v1/probe",
                        decode = { it },
                    )
                }

            assertEquals(
                "REAUTH_REQUIRED",
                failure.code,
            )
            assertFalse(failure.retryable)
            assertFalse(networkCalled)
        }

    @Test
    fun reauthRetryReusesSameOperationAndFreshTokenOnce() =
        runBlocking {
            var requestCount = 0
            var token = "token-before-reauth"
            var reauthCount = 0
            val seenAuthorization =
                mutableListOf<String?>()
            val seenIdempotency =
                mutableListOf<String?>()

            val client = HttpClient(
                MockEngine { request ->
                    requestCount += 1
                    seenAuthorization +=
                        request.headers[
                            HttpHeaders.Authorization
                        ]
                    seenIdempotency +=
                        request.headers[
                            "Idempotency-Key"
                        ]

                    if (requestCount == 1) {
                        respond(
                            content = """
                            {
                              "code":"REAUTH_REQUIRED",
                              "message":"Fresh authentication required.",
                              "correlationId":"corr-reauth",
                              "retryable":false
                            }
                            """.trimIndent(),
                            status =
                                HttpStatusCode(428, "Precondition Required"),
                            headers = headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                            ),
                        )
                    } else {
                        respond(
                            content = """{"ok":true}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                            ),
                        )
                    }
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    token
                },
                correlationIdProvider = {
                    "corr-client-reauth"
                },
            )

            val operationId =
                "33333333-3333-3333-3333-333333333333"

            val result =
                api.requestWithReauthentication(
                    method = HttpMethod.Post,
                    path = "/v1/probe",
                    options =
                        HiltechRequestOptions(
                            idempotencyKey =
                                operationId,
                        ),
                    requestBody =
                        """{"operationId":"$operationId"}""",
                    reauthenticate = {
                        reauthCount += 1
                        token =
                            "token-after-reauth"
                    },
                    decode = { it },
                )

            assertEquals(
                """{"ok":true}""",
                result,
            )
            assertEquals(2, requestCount)
            assertEquals(1, reauthCount)
            assertEquals(
                listOf<String?>(
                    "Bearer token-before-reauth",
                    "Bearer token-after-reauth",
                ),
                seenAuthorization,
            )
            assertEquals(
                listOf<String?>(
                    operationId,
                    operationId,
                ),
                seenIdempotency,
            )
        }

    @Test
    fun secondReauthRequiredDoesNotLoop() =
        runBlocking {
            var requestCount = 0
            var reauthCount = 0

            val client = HttpClient(
                MockEngine {
                    requestCount += 1
                    respond(
                        content = """
                        {
                          "code":"REAUTH_REQUIRED",
                          "message":"Fresh authentication required.",
                          "correlationId":"corr-reauth-loop",
                          "retryable":false
                        }
                        """.trimIndent(),
                        status =
                            HttpStatusCode(428, "Precondition Required"),
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    "token"
                },
                correlationIdProvider = {
                    "corr-client-loop"
                },
            )

            val failure =
                assertFailsWith<
                    HiltechApiException
                > {
                    api.requestWithReauthentication(
                        method = HttpMethod.Post,
                        path = "/v1/probe",
                        options =
                            HiltechRequestOptions(
                                idempotencyKey =
                                    "44444444-4444-4444-4444-444444444444",
                            ),
                        requestBody =
                            """{"operationId":"44444444-4444-4444-4444-444444444444"}""",
                        reauthenticate = {
                            reauthCount += 1
                        },
                        decode = { it },
                    )
                }

            assertEquals(
                "REAUTH_REQUIRED",
                failure.code,
            )
            assertEquals(2, requestCount)
            assertEquals(1, reauthCount)
        }

    @Test
    fun stateChangingRequestWithoutIdempotencyIsNotAutoReplayed() =
        runBlocking {
            var requestCount = 0
            var reauthCount = 0

            val client = HttpClient(
                MockEngine {
                    requestCount += 1
                    respond(
                        content = "",
                        status =
                            HttpStatusCode(428, "Precondition Required"),
                        headers = headersOf(
                            "X-Correlation-Id",
                            "corr-428-fallback",
                        ),
                    )
                },
            )

            val api = HiltechApiClient(
                client = client,
                baseUrl =
                    "https://api.hiltech.test",
                accessTokenProvider = {
                    "token"
                },
                correlationIdProvider = {
                    "corr-client-no-replay"
                },
            )

            val failure =
                assertFailsWith<
                    HiltechApiException
                > {
                    api.requestWithReauthentication(
                        method = HttpMethod.Post,
                        path = "/v1/probe",
                        reauthenticate = {
                            reauthCount += 1
                        },
                        decode = { it },
                    )
                }

            assertEquals(
                "REAUTH_REQUIRED",
                failure.code,
            )
            assertEquals(1, requestCount)
            assertEquals(0, reauthCount)
        }

}

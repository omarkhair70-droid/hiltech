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
}

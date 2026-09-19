package com.hiltech.shared.core.identity

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
import kotlin.test.assertTrue

class IdentityApiClientTest {
    @Test
    fun bootstrapCarriesBearerCorrelationAndInstallationId() =
        runBlocking {
            lateinit var captured:
                io.ktor.client.request.HttpRequestData

            val client = HttpClient(
                MockEngine { request ->
                    captured = request
                    respond(
                        content = """
                        {
                          "identityId":"identity-1",
                          "identityStatus":"ACTIVE",
                          "identityVersion":3,
                          "primaryOrganizationId":"org-1",
                          "organizations":[{
                            "membershipId":"membership-1",
                            "organizationId":"org-1",
                            "organizationCode":"HILTECH",
                            "displayName":"HILTECH",
                            "organizationType":"HILTECH",
                            "membershipType":"EMPLOYEE",
                            "roleLabel":"Technician",
                            "primary":true,
                            "membershipVersion":2
                          }],
                          "teams":[]
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )
                },
            )

            val api = IdentityApiClient(
                client = client,
                baseUrl = "https://api.hiltech.test/",
                accessTokenProvider = { "token-42" },
                correlationIdProvider = { "corr-42" },
            )

            val result = api.bootstrap(
                installationId =
                    "11111111-1111-1111-1111-111111111111",
            )

            assertEquals("identity-1", result.identityId)
            assertEquals(
                "Bearer token-42",
                captured.headers[HttpHeaders.Authorization],
            )
            assertEquals(
                "corr-42",
                captured.headers["X-Correlation-Id"],
            )
            assertEquals(
                "11111111-1111-1111-1111-111111111111",
                captured.headers[
                    "X-Device-Installation-Id"
                ],
            )
            assertTrue(
                captured.url.toString().endsWith(
                    "/v1/me/bootstrap",
                ),
            )
        }

    @Test
    fun noTokenFailsLocallyWithoutNetworkRequest() =
        runBlocking {
            var networkCalled = false
            val client = HttpClient(
                MockEngine {
                    networkCalled = true
                    respond("", HttpStatusCode.OK)
                },
            )
            val api = IdentityApiClient(
                client = client,
                baseUrl = "https://api.hiltech.test",
                accessTokenProvider = { null },
                correlationIdProvider = { "corr-reauth" },
            )

            val failure =
                assertFailsWith<IdentityApiException> {
                    api.bootstrap(null)
                }

            assertEquals("REAUTH_REQUIRED", failure.code)
            assertEquals(false, networkCalled)
        }

    @Test
    fun serverIdentityErrorIsPreserved() =
        runBlocking {
            val client = HttpClient(
                MockEngine {
                    respond(
                        content = """
                        {
                          "code":"IDENTITY_NOT_PROVISIONED",
                          "message":"Authenticated identity is not provisioned in HILTECH.",
                          "correlationId":"corr-server",
                          "retryable":false
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )
                },
            )
            val api = IdentityApiClient(
                client = client,
                baseUrl = "https://api.hiltech.test",
                accessTokenProvider = { "token-42" },
                correlationIdProvider = { "corr-client" },
            )

            val failure =
                assertFailsWith<IdentityApiException> {
                    api.bootstrap(null)
                }

            assertEquals(
                "IDENTITY_NOT_PROVISIONED",
                failure.code,
            )
            assertEquals("corr-server", failure.correlationId)
            assertEquals(403, failure.httpStatus)
        }
}

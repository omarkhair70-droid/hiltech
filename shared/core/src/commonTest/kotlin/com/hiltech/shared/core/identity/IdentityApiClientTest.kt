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
    fun meSessionsSecurityRoutesUseSameAuthenticatedDeviceContext() =
        runBlocking {
            val paths = mutableListOf<String>()
            val installationId =
                "11111111-1111-1111-1111-111111111111"

            val client = HttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    assertEquals(
                        "Bearer token-security",
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
                        when (request.url.encodedPath) {
                            "/v1/me/sessions" ->
                                """
                                [{
                                  "sessionId":"session-1",
                                  "deviceId":"device-1",
                                  "createdAt":"2026-09-19T07:00:00Z",
                                  "lastSeenAt":"2026-09-19T08:00:00Z",
                                  "expiresAt":"2026-09-19T19:00:00Z",
                                  "authenticationStrength":"OIDC",
                                  "current":true,
                                  "version":1
                                }]
                                """.trimIndent()

                            "/v1/me/devices" ->
                                """
                                [{
                                  "deviceId":"device-1",
                                  "installationId":"$installationId",
                                  "platform":"ANDROID",
                                  "deviceName":"Field Phone",
                                  "appVersion":"0.1.0",
                                  "current":true,
                                  "version":1
                                }]
                                """.trimIndent()

                            "/v1/me/reauth/complete" ->
                                """
                                {
                                  "sessionId":"session-1",
                                  "deviceId":"device-1",
                                  "createdAt":"2026-09-19T07:00:00Z",
                                  "lastSeenAt":"2026-09-19T08:00:00Z",
                                  "expiresAt":"2026-09-19T19:00:00Z",
                                  "authenticationStrength":"OIDC",
                                  "reauthSatisfiedUntil":"2026-09-19T08:15:00Z",
                                  "current":true,
                                  "version":2
                                }
                                """.trimIndent()

                            "/v1/me/sessions/session-2/revoke" ->
                                """
                                {
                                  "sessionId":"session-2",
                                  "deviceId":"device-2",
                                  "createdAt":"2026-09-18T07:00:00Z",
                                  "lastSeenAt":"2026-09-19T06:00:00Z",
                                  "expiresAt":"2026-09-19T18:00:00Z",
                                  "revokedAt":"2026-09-19T08:01:00Z",
                                  "authenticationStrength":"OIDC",
                                  "current":false,
                                  "version":3
                                }
                                """.trimIndent()

                            else ->
                                error(
                                    "Unexpected path: " +
                                        request.url.encodedPath,
                                )
                        }

                    respond(
                        content = body,
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
                baseUrl = "https://api.hiltech.test",
                accessTokenProvider = {
                    "token-security"
                },
                correlationIdProvider = {
                    "corr-security"
                },
            )

            val sessions =
                api.sessions(installationId)
            val devices =
                api.devices(installationId)
            val reauth =
                api.completeReauthentication(
                    idToken = "signed-id-token",
                    installationId = installationId,
                )
            val revoked =
                api.revokeSession(
                    sessionId = "session-2",
                    installationId = installationId,
                )

            assertEquals(1, sessions.size)
            assertEquals(true, sessions.single().current)
            assertEquals(
                "Field Phone",
                devices.single().deviceName,
            )
            assertEquals(
                "2026-09-19T08:15:00Z",
                reauth.reauthSatisfiedUntil,
            )
            assertEquals(
                "2026-09-19T08:01:00Z",
                revoked.revokedAt,
            )
            assertEquals(
                listOf(
                    "/v1/me/sessions",
                    "/v1/me/devices",
                    "/v1/me/reauth/complete",
                    "/v1/me/sessions/session-2/revoke",
                ),
                paths,
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

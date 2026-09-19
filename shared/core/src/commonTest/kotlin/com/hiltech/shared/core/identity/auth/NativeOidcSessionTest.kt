package com.hiltech.shared.core.identity.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeOidcSessionTest {
    @Test
    fun authorizationUsesCodePkceS256AndNeverRequestsOfflineAccess() =
        runBlocking {
            val client = clientWith(
                MockEngine {
                    respond(
                        discoveryJson(),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                },
            )
            val manager = NativeOidcSessionManager(
                client = client,
                config = config(),
                tokenStore = InMemoryOidcTokenStore(),
            )

            val attempt = manager.beginAuthorization(
                redirectUri =
                    "com.hiltech.app:/oauth2redirect",
            )
            val url = Url(attempt.authorizationUrl)

            assertEquals(
                "hiltech-native",
                url.parameters["client_id"],
            )
            assertEquals(
                "code",
                url.parameters["response_type"],
            )
            assertEquals(
                "S256",
                url.parameters["code_challenge_method"],
            )
            assertTrue(
                url.parameters["code_challenge"]
                    ?.isNotBlank() == true,
            )
            assertEquals(
                attempt.state,
                url.parameters["state"],
            )
            assertEquals(
                attempt.nonce,
                url.parameters["nonce"],
            )
            assertEquals(
                "openid",
                url.parameters["scope"],
            )
            assertFalse(
                url.toString().contains("offline_access"),
            )
            assertNotEquals(
                attempt.codeVerifier,
                url.parameters["code_challenge"],
            )
        }

    @Test
    fun stateMismatchFailsBeforeCodeExchange() =
        runBlocking {
            var tokenEndpointCalled = false
            val client = clientWith(
                MockEngine { request ->
                    if (
                        request.url.encodedPath.endsWith(
                            "openid-configuration",
                        )
                    ) {
                        respond(
                            discoveryJson(),
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    } else {
                        tokenEndpointCalled = true
                        respond(
                            tokenJson("access"),
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                },
            )
            val manager = NativeOidcSessionManager(
                client = client,
                config = config(),
                tokenStore = InMemoryOidcTokenStore(),
            )
            val attempt = manager.beginAuthorization(
                "com.hiltech.app:/oauth2redirect",
            )

            val failure =
                assertFailsWith<NativeOidcException> {
                    manager.completeAuthorization(
                        callbackUri =
                            "com.hiltech.app:/oauth2redirect?code=abc&state=wrong",
                        attempt = attempt,
                    )
                }

            assertEquals(
                "OIDC_STATE_MISMATCH",
                failure.code,
            )
            assertFalse(tokenEndpointCalled)
        }

    @Test
    fun codeExchangeStoresSessionAndRefreshRotatesTokens() =
        runBlocking {
            val requests =
                mutableListOf<HttpRequestData>()
            var tokenCalls = 0
            val store = InMemoryOidcTokenStore()
            val client = clientWith(
                MockEngine { request ->
                    requests += request
                    when {
                        request.url.encodedPath.endsWith(
                            "openid-configuration",
                        ) ->
                            respond(
                                discoveryJson(),
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )

                        request.url.encodedPath.endsWith(
                            "/token",
                        ) -> {
                            tokenCalls += 1
                            if (tokenCalls == 1) {
                                respond(
                                    tokenJson(
                                        access = "access-1",
                                        refresh = "refresh-1",
                                        expires = 1,
                                    ),
                                    HttpStatusCode.OK,
                                    jsonHeaders(),
                                )
                            } else {
                                respond(
                                    tokenJson(
                                        access = "access-2",
                                        refresh = "refresh-2",
                                        expires = 300,
                                    ),
                                    HttpStatusCode.OK,
                                    jsonHeaders(),
                                )
                            }
                        }

                        else ->
                            error(
                                "Unexpected request: ${request.url}",
                            )
                    }
                },
            )
            val manager = NativeOidcSessionManager(
                client = client,
                config = config(),
                tokenStore = store,
                refreshSkewMs = Long.MAX_VALUE,
            )
            val attempt = manager.beginAuthorization(
                "com.hiltech.app:/oauth2redirect",
            )

            val first = manager.completeAuthorization(
                callbackUri =
                    "com.hiltech.app:/oauth2redirect?code=abc&state=${attempt.state}",
                attempt = attempt,
            )
            assertEquals("access-1", first.accessToken)
            assertTrue(manager.hasSession())

            val access = manager.currentAccessToken()
            assertEquals("access-2", access)

            val stored = store.load()
            assertEquals("refresh-2", stored?.refreshToken)
            assertEquals(2, tokenCalls)
            assertTrue(
                requests.any {
                    it.url.encodedPath.endsWith("/token")
                },
            )
        }

    @Test
    fun logoutCallsProviderAndAlwaysClearsLocalSession() =
        runBlocking {
            var logoutCalled = false
            val store = InMemoryOidcTokenStore()
            store.save(
                NativeOidcTokenSet(
                    accessToken = "access",
                    refreshToken = "refresh",
                    tokenType = "Bearer",
                    scope = "openid",
                    obtainedAtEpochMs = 1,
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )

            val client = clientWith(
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith(
                            "openid-configuration",
                        ) ->
                            respond(
                                discoveryJson(),
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )

                        request.url.encodedPath.endsWith(
                            "/logout",
                        ) -> {
                            logoutCalled = true
                            respond(
                                "",
                                HttpStatusCode.NoContent,
                            )
                        }

                        else ->
                            error(
                                "Unexpected request: ${request.url}",
                            )
                    }
                },
            )
            val manager = NativeOidcSessionManager(
                client = client,
                config = config(),
                tokenStore = store,
            )

            manager.logout()

            assertTrue(logoutCalled)
            assertFalse(manager.hasSession())
        }

    private fun config() =
        NativeOidcConfig(
            issuer =
                "https://id.hiltech.test/realms/hiltech/",
        )

    private fun clientWith(
        engine: MockEngine,
    ): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }
        }

    private fun jsonHeaders() =
        headersOf(
            HttpHeaders.ContentType,
            "application/json",
        )

    private fun discoveryJson(): String =
        """
        {
          "issuer":"https://id.hiltech.test/realms/hiltech",
          "authorization_endpoint":"https://id.hiltech.test/realms/hiltech/protocol/openid-connect/auth",
          "token_endpoint":"https://id.hiltech.test/realms/hiltech/protocol/openid-connect/token",
          "end_session_endpoint":"https://id.hiltech.test/realms/hiltech/protocol/openid-connect/logout",
          "grant_types_supported":["authorization_code","refresh_token"],
          "code_challenge_methods_supported":["S256"]
        }
        """.trimIndent()

    private fun tokenJson(
        access: String,
        refresh: String = "refresh",
        expires: Long = 300,
    ): String =
        """
        {
          "access_token":"$access",
          "refresh_token":"$refresh",
          "token_type":"Bearer",
          "expires_in":$expires,
          "scope":"openid"
        }
        """.trimIndent()
}

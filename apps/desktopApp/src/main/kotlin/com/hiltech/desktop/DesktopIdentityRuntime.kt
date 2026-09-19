package com.hiltech.desktop

import com.hiltech.shared.core.identity.DeviceRegistrationDto
import com.hiltech.shared.core.identity.IdentityApiClient
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.InMemoryOidcTokenStore
import com.hiltech.shared.core.identity.auth.NativeOidcConfig
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.identity.auth.NativeOidcSessionManager
import com.hiltech.shared.core.network.createPlatformHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

class DesktopIdentityRuntime(
    private val apiBaseUrl: String,
    oidcIssuer: String,
    oidcClientId: String,
    private val clientVersion: String = "0.1.0",
) : AutoCloseable {
    private val httpClient = createPlatformHttpClient()
    private val tokenStore = InMemoryOidcTokenStore()

    private val session = NativeOidcSessionManager(
        client = httpClient,
        config = NativeOidcConfig(
            issuer = oidcIssuer,
            clientId = oidcClientId,
        ),
        tokenStore = tokenStore,
    )

    private val installationId =
        DesktopInstallationIdStore().getOrCreate()

    private val identityApi = IdentityApiClient(
        client = httpClient,
        baseUrl = apiBaseUrl,
        accessTokenProvider = {
            session.currentAccessToken()
        },
        correlationIdProvider = {
            UUID.randomUUID().toString()
        },
    )

    val configured: Boolean =
        apiBaseUrl.isNotBlank() &&
            oidcIssuer.isNotBlank() &&
            oidcClientId.isNotBlank()

    suspend fun signIn(
        forceReauthentication: Boolean = false,
    ): IdentityBootstrapDto {
        ensureConfigured()

        val callbackFuture =
            CompletableFuture<String>()

        val server = HttpServer.create(
            InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                0,
            ),
            0,
        )
        val port = server.address.port
        val redirectUri =
            "http://127.0.0.1:$port/callback"

        server.createContext("/callback") { exchange ->
            handleCallback(
                exchange = exchange,
                port = port,
                callbackFuture = callbackFuture,
            )
        }
        server.start()

        try {
            val attempt = session.beginAuthorization(
                redirectUri = redirectUri,
                forceReauthentication =
                    forceReauthentication,
            )

            openSystemBrowser(
                attempt.authorizationUrl,
            )

            val callbackUri = withContext(Dispatchers.IO) {
                callbackFuture.get(
                    CALLBACK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }

            session.completeAuthorization(
                callbackUri = callbackUri,
                attempt = attempt,
            )

            return bootstrapCurrentIdentity()
        } finally {
            server.stop(0)
        }
    }

    suspend fun restoreIdentity():
        IdentityBootstrapDto? {
        ensureConfigured()

        if (session.currentAccessToken() == null) {
            return null
        }
        return bootstrapCurrentIdentity()
    }

    suspend fun logout() {
        if (!configured) {
            tokenStore.clear()
            return
        }
        session.logout()
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun bootstrapCurrentIdentity():
        IdentityBootstrapDto {
        identityApi.registerDevice(
            DeviceRegistrationDto(
                installationId = installationId,
                platform = "WINDOWS",
                deviceName = System.getenv(
                    "COMPUTERNAME",
                ) ?: "Windows Desktop",
                appVersion = clientVersion,
                osVersion =
                    System.getProperty("os.version"),
            ),
        )

        return identityApi.bootstrap(
            installationId = installationId,
        )
    }

    private fun openSystemBrowser(
        authorizationUrl: String,
    ) {
        if (
            !Desktop.isDesktopSupported() ||
            !Desktop.getDesktop().isSupported(
                Desktop.Action.BROWSE,
            )
        ) {
            throw NativeOidcException(
                code = "OIDC_SYSTEM_BROWSER_UNAVAILABLE",
                message = "The Windows system browser is unavailable.",
            )
        }

        Desktop.getDesktop().browse(
            URI.create(authorizationUrl),
        )
    }

    private fun handleCallback(
        exchange: HttpExchange,
        port: Int,
        callbackFuture: CompletableFuture<String>,
    ) {
        try {
            if (
                exchange.requestMethod != "GET" ||
                exchange.requestURI.path != "/callback"
            ) {
                exchange.sendResponseHeaders(
                    404,
                    -1,
                )
                return
            }

            val body = """
                <!doctype html>
                <html>
                  <head><meta charset="utf-8"></head>
                  <body>
                    <h1>HILTECH sign-in received</h1>
                    <p>You can return to the HILTECH application.</p>
                  </body>
                </html>
            """.trimIndent().toByteArray(
                StandardCharsets.UTF_8,
            )

            exchange.responseHeaders.add(
                "Content-Type",
                "text/html; charset=utf-8",
            )
            exchange.sendResponseHeaders(
                200,
                body.size.toLong(),
            )
            exchange.responseBody.use {
                it.write(body)
            }

            val callbackUri =
                "http://127.0.0.1:$port" +
                    exchange.requestURI.toString()
            callbackFuture.complete(callbackUri)
        } catch (failure: Throwable) {
            callbackFuture.completeExceptionally(
                failure,
            )
        } finally {
            exchange.close()
        }
    }

    private fun ensureConfigured() {
        if (!configured) {
            throw IllegalStateException(
                "HILTECH API/OIDC runtime is not configured.",
            )
        }
    }

    companion object {
        private const val CALLBACK_TIMEOUT_SECONDS =
            120L

        fun isAccessDenied(
            failure: Throwable,
        ): Boolean =
            failure is IdentityApiException &&
                failure.httpStatus in
                    setOf(403, 404, 409)
    }
}

private class DesktopInstallationIdStore {
    private val preferences =
        Preferences.userRoot().node(
            "com/hiltech/desktop/installation",
        )

    fun getOrCreate(): String {
        val existing =
            preferences.get(
                INSTALLATION_ID_KEY,
                "",
            )
        if (existing.isNotBlank()) {
            return existing
        }

        val created = UUID.randomUUID().toString()
        preferences.put(
            INSTALLATION_ID_KEY,
            created,
        )
        preferences.flush()
        return created
    }

    private companion object {
        const val INSTALLATION_ID_KEY =
            "installation_id"
    }
}

package com.hiltech.desktop.evidence

import com.hiltech.shared.core.identity.auth.InMemoryOidcTokenStore
import com.hiltech.shared.core.identity.auth.NativeOidcConfig
import com.hiltech.shared.core.identity.auth.NativeOidcSessionManager
import com.hiltech.shared.core.network.createPlatformHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val ANDROID_REDIRECT_URI =
    "com.hiltech.app:/oauth2redirect"

fun main() = runBlocking {
    val issuer = requireEnvironment("HILTECH_OIDC_ISSUER_URI")
    val mode = requireEnvironment("HILTECH_OIDC_SMOKE_MODE")
        .lowercase()
    val smokeDirectory = Path.of(
        requireEnvironment("HILTECH_OIDC_SMOKE_DIR"),
    )
    Files.createDirectories(smokeDirectory)

    val httpClient = createPlatformHttpClient()
    val tokenStore = InMemoryOidcTokenStore()
    val session = NativeOidcSessionManager(
        client = httpClient,
        config = NativeOidcConfig(
            issuer = issuer,
            clientId = "hiltech-native",
        ),
        tokenStore = tokenStore,
    )

    try {
        when (mode) {
            "android" ->
                runAndroidPrivateRedirectSmoke(
                    session = session,
                    smokeDirectory = smokeDirectory,
                )

            "windows" ->
                runWindowsLoopbackSmoke(
                    session = session,
                    smokeDirectory = smokeDirectory,
                )

            else ->
                error(
                    "Unsupported HILTECH_OIDC_SMOKE_MODE=$mode",
                )
        }

        val refreshed = session.refresh()
        require(refreshed.accessToken.isNotBlank()) {
            "OIDC refresh did not return an access token."
        }
        require(!refreshed.refreshToken.isNullOrBlank()) {
            "OIDC refresh did not preserve a refresh token."
        }

        session.logout()
        require(!session.hasSession()) {
            "OIDC logout did not clear the local session."
        }

        Files.writeString(
            smokeDirectory.resolve("$mode-pass.txt"),
            "PASS\n",
        )
        println(
            "HILTECH_PHASE1_NATIVE_OIDC_PROVIDER_PASS " +
                "mode=$mode pkce=PASS refresh=PASS logout=PASS",
        )
    } finally {
        httpClient.close()
    }
}

private suspend fun runAndroidPrivateRedirectSmoke(
    session: NativeOidcSessionManager,
    smokeDirectory: Path,
) {
    val attempt = session.beginAuthorization(
        redirectUri = ANDROID_REDIRECT_URI,
    )
    Files.writeString(
        smokeDirectory.resolve("android-auth-url.txt"),
        attempt.authorizationUrl,
    )

    val callback = awaitFile(
        smokeDirectory.resolve("android-callback.txt"),
    )
    val tokens = session.completeAuthorization(
        callbackUri = callback,
        attempt = attempt,
    )

    require(tokens.accessToken.isNotBlank())
    require(!tokens.refreshToken.isNullOrBlank())
}

private suspend fun runWindowsLoopbackSmoke(
    session: NativeOidcSessionManager,
    smokeDirectory: Path,
) {
    val callbackFuture = CompletableFuture<String>()
    val server = HttpServer.create(
        InetSocketAddress(
            InetAddress.getByName("127.0.0.1"),
            0,
        ),
        0,
    )
    val port = server.address.port
    val redirectUri = "http://127.0.0.1:$port/callback"

    server.createContext("/callback") { exchange ->
        handleLoopbackCallback(
            exchange = exchange,
            port = port,
            callbackFuture = callbackFuture,
        )
    }
    server.start()

    try {
        val attempt = session.beginAuthorization(
            redirectUri = redirectUri,
        )
        Files.writeString(
            smokeDirectory.resolve("windows-auth-url.txt"),
            attempt.authorizationUrl,
        )

        val callbackUri = callbackFuture.get(
            90,
            TimeUnit.SECONDS,
        )
        val tokens = session.completeAuthorization(
            callbackUri = callbackUri,
            attempt = attempt,
        )

        require(tokens.accessToken.isNotBlank())
        require(!tokens.refreshToken.isNullOrBlank())
    } finally {
        server.stop(0)
    }
}

private fun handleLoopbackCallback(
    exchange: HttpExchange,
    port: Int,
    callbackFuture: CompletableFuture<String>,
) {
    try {
        if (
            exchange.requestMethod != "GET" ||
            exchange.requestURI.path != "/callback"
        ) {
            exchange.sendResponseHeaders(404, -1)
            return
        }

        val body = """
            <!doctype html>
            <html>
              <head><meta charset="utf-8"></head>
              <body>
                <h1>HILTECH authentication received</h1>
                <p>You can return to the HILTECH application.</p>
              </body>
            </html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

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

        callbackFuture.complete(
            "http://127.0.0.1:$port" +
                exchange.requestURI.toString(),
        )
    } catch (failure: Throwable) {
        callbackFuture.completeExceptionally(failure)
    } finally {
        exchange.close()
    }
}

private suspend fun awaitFile(
    path: Path,
): String {
    repeat(900) {
        if (Files.isRegularFile(path)) {
            val value = Files.readString(path).trim()
            if (value.isNotBlank()) {
                return value
            }
        }
        delay(100)
    }
    error("Timed out waiting for $path")
}

private fun requireEnvironment(
    name: String,
): String =
    requireNotNull(System.getenv(name))
        .takeIf { it.isNotBlank() }
        ?: error("$name is required.")

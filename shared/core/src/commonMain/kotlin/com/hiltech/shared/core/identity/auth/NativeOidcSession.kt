package com.hiltech.shared.core.identity.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OidcDiscoveryDocument(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String,
    @SerialName("end_session_endpoint")
    val endSessionEndpoint: String? = null,
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String> = emptyList(),
)

@Serializable
data class OidcTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_in")
    val expiresInSeconds: Long,
    val scope: String? = null,
)

@Serializable
data class NativeOidcTokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scope: String?,
    val obtainedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

data class NativeOidcConfig(
    val issuer: String,
    val clientId: String = "hiltech-native",
    val scopes: List<String> = listOf("openid"),
) {
    init {
        require(issuer.isNotBlank())
        require(clientId.isNotBlank())
        require(scopes.isNotEmpty())
        require("openid" in scopes) {
            "Native OIDC scope must include openid."
        }
        require("offline_access" !in scopes) {
            "offline_access is not part of the HILTECH native-session baseline."
        }
    }

    val normalizedIssuer: String
        get() = issuer.trimEnd('/')
}

data class NativeAuthorizationAttempt(
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val authorizationUrl: String,
)

class NativeOidcException(
    val code: String,
    message: String,
) : RuntimeException(message)

interface OidcTokenStore {
    suspend fun load(): NativeOidcTokenSet?
    suspend fun save(tokens: NativeOidcTokenSet)
    suspend fun clear()
}

class InMemoryOidcTokenStore : OidcTokenStore {
    private var tokens: NativeOidcTokenSet? = null

    override suspend fun load(): NativeOidcTokenSet? = tokens

    override suspend fun save(tokens: NativeOidcTokenSet) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

expect object NativeOidcPlatform {
    fun randomUrlToken(byteCount: Int): String
    fun s256Challenge(verifier: String): String
    fun currentTimeMillis(): Long
}

class NativeOidcSessionManager(
    private val client: HttpClient,
    private val config: NativeOidcConfig,
    private val tokenStore: OidcTokenStore,
    private val refreshSkewMs: Long = 30_000,
) {
    private var discoveryCache: OidcDiscoveryDocument? = null

    suspend fun discover(): OidcDiscoveryDocument {
        discoveryCache?.let { return it }

        val document = client.get(
            config.normalizedIssuer +
                "/.well-known/openid-configuration",
        ).body<OidcDiscoveryDocument>()

        if (document.issuer.trimEnd('/') != config.normalizedIssuer) {
            throw NativeOidcException(
                code = "OIDC_ISSUER_MISMATCH",
                message = "OIDC discovery issuer does not match configured issuer.",
            )
        }

        if ("authorization_code" !in document.grantTypesSupported) {
            throw NativeOidcException(
                code = "OIDC_AUTHORIZATION_CODE_UNAVAILABLE",
                message = "OIDC provider does not advertise authorization_code.",
            )
        }

        if ("S256" !in document.codeChallengeMethodsSupported) {
            throw NativeOidcException(
                code = "OIDC_PKCE_S256_UNAVAILABLE",
                message = "OIDC provider does not advertise PKCE S256.",
            )
        }

        discoveryCache = document
        return document
    }

    suspend fun beginAuthorization(
        redirectUri: String,
        forceReauthentication: Boolean = false,
    ): NativeAuthorizationAttempt {
        require(redirectUri.isNotBlank())

        val discovery = discover()
        val verifier = NativeOidcPlatform.randomUrlToken(64)
        val state = NativeOidcPlatform.randomUrlToken(24)
        val nonce = NativeOidcPlatform.randomUrlToken(24)
        val challenge = NativeOidcPlatform.s256Challenge(verifier)

        val authorizationUrl = URLBuilder(
            discovery.authorizationEndpoint,
        ).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("redirect_uri", redirectUri)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            if (forceReauthentication) {
                parameters.append("prompt", "login")
            }
        }.buildString()

        return NativeAuthorizationAttempt(
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
            authorizationUrl = authorizationUrl,
        )
    }

    suspend fun completeAuthorization(
        callbackUri: String,
        attempt: NativeAuthorizationAttempt,
    ): NativeOidcTokenSet {
        val callback = runCatching {
            Url(callbackUri)
        }.getOrElse {
            throw NativeOidcException(
                code = "OIDC_CALLBACK_INVALID",
                message = "OIDC callback URI is invalid.",
            )
        }

        callback.parameters["error"]?.let { error ->
            throw NativeOidcException(
                code = "OIDC_AUTHORIZATION_ERROR",
                message = callback.parameters["error_description"] ?: error,
            )
        }

        val returnedState = callback.parameters["state"]
        if (returnedState == null || returnedState != attempt.state) {
            throw NativeOidcException(
                code = "OIDC_STATE_MISMATCH",
                message = "OIDC callback state does not match the active authorization attempt.",
            )
        }

        val code = callback.parameters["code"]
            ?.takeIf { it.isNotBlank() }
            ?: throw NativeOidcException(
                code = "OIDC_CODE_MISSING",
                message = "OIDC callback did not contain an authorization code.",
            )

        val discovery = discover()
        val response = client.submitForm(
            url = discovery.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("client_id", config.clientId)
                append("redirect_uri", attempt.redirectUri)
                append("code", code)
                append("code_verifier", attempt.codeVerifier)
            },
        )

        if (response.status != HttpStatusCode.OK) {
            throw NativeOidcException(
                code = "OIDC_CODE_EXCHANGE_FAILED",
                message = "Authorization-code exchange failed.",
            )
        }

        val tokenSet = response.body<OidcTokenResponse>().toTokenSet()
        tokenStore.save(tokenSet)
        return tokenSet
    }

    suspend fun currentAccessToken(): String? {
        val current = tokenStore.load() ?: return null
        val now = NativeOidcPlatform.currentTimeMillis()

        if (
            current.accessToken.isNotBlank() &&
            now + refreshSkewMs < current.expiresAtEpochMs
        ) {
            return current.accessToken
        }

        val refreshToken = current.refreshToken
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return try {
            refresh(refreshToken).accessToken
        } catch (_: NativeOidcException) {
            tokenStore.clear()
            null
        }
    }

    suspend fun refresh(
        refreshToken: String? = null,
    ): NativeOidcTokenSet {
        val token = refreshToken
            ?: tokenStore.load()?.refreshToken
            ?: throw NativeOidcException(
                code = "OIDC_REFRESH_UNAVAILABLE",
                message = "No refresh token is available.",
            )

        val discovery = discover()
        val response = client.submitForm(
            url = discovery.tokenEndpoint,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("client_id", config.clientId)
                append("refresh_token", token)
            },
        )

        if (response.status != HttpStatusCode.OK) {
            throw NativeOidcException(
                code = "OIDC_REFRESH_FAILED",
                message = "OIDC refresh failed.",
            )
        }

        val refreshed = response.body<OidcTokenResponse>()
            .toTokenSet(fallbackRefreshToken = token)
        tokenStore.save(refreshed)
        return refreshed
    }

    suspend fun logout() {
        val refreshToken = tokenStore.load()?.refreshToken

        try {
            if (!refreshToken.isNullOrBlank()) {
                val endpoint = discover().endSessionEndpoint
                if (!endpoint.isNullOrBlank()) {
                    val response = client.submitForm(
                        url = endpoint,
                        formParameters = parameters {
                            append("client_id", config.clientId)
                            append("refresh_token", refreshToken)
                        },
                    )

                    if (
                        response.status.value !in 200..299 &&
                        response.status != HttpStatusCode.BadRequest
                    ) {
                        throw NativeOidcException(
                            code = "OIDC_LOGOUT_FAILED",
                            message = "OIDC logout request failed.",
                        )
                    }
                }
            }
        } finally {
            tokenStore.clear()
        }
    }

    suspend fun hasSession(): Boolean =
        tokenStore.load() != null

    private fun OidcTokenResponse.toTokenSet(
        fallbackRefreshToken: String? = null,
    ): NativeOidcTokenSet {
        if (!tokenType.equals("Bearer", ignoreCase = true)) {
            throw NativeOidcException(
                code = "OIDC_TOKEN_TYPE_UNSUPPORTED",
                message = "OIDC token response is not Bearer.",
            )
        }
        if (accessToken.isBlank() || expiresInSeconds <= 0) {
            throw NativeOidcException(
                code = "OIDC_TOKEN_RESPONSE_INVALID",
                message = "OIDC token response is incomplete.",
            )
        }

        val now = NativeOidcPlatform.currentTimeMillis()
        return NativeOidcTokenSet(
            accessToken = accessToken,
            refreshToken = refreshToken ?: fallbackRefreshToken,
            tokenType = tokenType,
            scope = scope,
            obtainedAtEpochMs = now,
            expiresAtEpochMs = now + expiresInSeconds * 1_000,
        )
    }
}

package com.hiltech.android.identity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.hiltech.shared.core.identity.DeviceRegistrationDto
import com.hiltech.shared.core.identity.IdentityApiClient
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.NativeOidcConfig
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.identity.auth.NativeOidcSessionManager
import io.ktor.client.HttpClient
import java.util.UUID

class AndroidIdentityRuntime(
    private val context: Context,
    httpClient: HttpClient,
    private val apiBaseUrl: String,
    oidcIssuer: String,
    oidcClientId: String,
    private val installationId: String,
    private val clientVersion: String,
) {
    private val store = AndroidEncryptedOidcStore(context)

    val configured: Boolean =
        apiBaseUrl.isNotBlank() &&
            oidcIssuer.isNotBlank() &&
            oidcClientId.isNotBlank()

    private val session =
        if (configured) {
            NativeOidcSessionManager(
                client = httpClient,
                config = NativeOidcConfig(
                    issuer = oidcIssuer,
                    clientId = oidcClientId,
                ),
                tokenStore = store,
            )
        } else {
            null
        }

    private val identityApi = IdentityApiClient(
        client = httpClient,
        baseUrl = apiBaseUrl,
        accessTokenProvider = {
            session?.currentAccessToken()
        },
        correlationIdProvider = {
            UUID.randomUUID().toString()
        },
    )

    suspend fun currentAccessToken(): String? =
        session?.currentAccessToken()

    suspend fun restoreIdentity(): IdentityBootstrapDto? {
        ensureConfigured()
        val activeSession = session
            ?: return null
        if (activeSession.currentAccessToken() == null) return null
        return bootstrapCurrentIdentity()
    }

    suspend fun beginSignIn(
        forceReauthentication: Boolean = false,
    ): String {
        ensureConfigured()
        val activeSession = requireNotNull(session)
        val attempt = activeSession.beginAuthorization(
            redirectUri = ANDROID_REDIRECT_URI,
            forceReauthentication = forceReauthentication,
        )
        store.savePendingAttempt(attempt)
        return attempt.authorizationUrl
    }

    suspend fun completeSignIn(
        callbackUri: String,
    ): IdentityBootstrapDto {
        ensureConfigured()
        val attempt = store.loadPendingAttempt()
            ?: throw NativeOidcException(
                code = "OIDC_ATTEMPT_MISSING",
                message = "No active HILTECH sign-in attempt exists.",
            )

        try {
            requireNotNull(session).completeAuthorization(
                callbackUri = callbackUri,
                attempt = attempt,
            )
        } finally {
            store.clearPendingAttempt()
        }

        return bootstrapCurrentIdentity()
    }

    suspend fun logout() {
        if (!configured) {
            store.clear()
            return
        }
        requireNotNull(session).logout()
    }

    fun browserIntent(authorizationUrl: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    private suspend fun bootstrapCurrentIdentity():
        IdentityBootstrapDto {
        identityApi.registerDevice(
            DeviceRegistrationDto(
                installationId = installationId,
                platform = "ANDROID",
                deviceName = Build.MANUFACTURER + " " + Build.MODEL,
                appVersion = clientVersion,
                osVersion = Build.VERSION.RELEASE,
            ),
        )
        return identityApi.bootstrap(installationId)
    }

    private fun ensureConfigured() {
        if (!configured) {
            throw IllegalStateException(
                "HILTECH API/OIDC runtime is not configured.",
            )
        }
    }

    companion object {
        const val ANDROID_REDIRECT_URI =
            "com.hiltech.app:/oauth2redirect"

        fun isCallbackUri(uri: Uri?): Boolean =
            uri != null &&
                uri.scheme == "com.hiltech.app" &&
                uri.path == "/oauth2redirect"

        fun isAccessDenied(failure: Throwable): Boolean =
            failure is IdentityApiException &&
                failure.httpStatus in setOf(403, 404, 409)
    }
}

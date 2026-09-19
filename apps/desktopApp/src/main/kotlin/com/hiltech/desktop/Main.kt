package com.hiltech.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.NativeOidcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

fun main() {
    val runtime = DesktopIdentityRuntime(
        apiBaseUrl =
            System.getenv("HILTECH_API_BASE_URL")
                ?: "",
        oidcIssuer =
            System.getenv("HILTECH_OIDC_ISSUER_URI")
                ?: "",
        oidcClientId =
            System.getenv("HILTECH_OIDC_CLIENT_ID")
                ?: "hiltech-native",
    )

    val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default,
        )

    val shellState =
        mutableStateOf<HiltechShellState>(
            if (runtime.configured) {
                HiltechShellState.SignedOut
            } else {
                HiltechShellState.ConfigurationRequired(
                    "Set HILTECH_API_BASE_URL and HILTECH_OIDC_ISSUER_URI before sign-in.",
                )
            },
        )

    fun setState(
        state: HiltechShellState,
    ) {
        if (
            SwingUtilities.isEventDispatchThread()
        ) {
            shellState.value = state
        } else {
            SwingUtilities.invokeLater {
                shellState.value = state
            }
        }
    }

    fun showFailure(
        failure: Throwable,
    ) {
        val state =
            when {
                DesktopIdentityRuntime
                    .isAccessDenied(failure) -> {
                    val api =
                        failure as IdentityApiException
                    HiltechShellState.AccessDenied(
                        code = api.code,
                        message =
                            api.message
                                ?: "HILTECH access is unavailable.",
                    )
                }

                failure is IdentityApiException ->
                    HiltechShellState.Failure(
                        code = failure.code,
                        message =
                            failure.message
                                ?: "HILTECH security request failed.",
                    )

                failure is NativeOidcException ->
                    HiltechShellState.Failure(
                        code = failure.code,
                        message =
                            failure.message
                                ?: "HILTECH sign-in failed.",
                    )

                else ->
                    HiltechShellState.Failure(
                        code = "IDENTITY_RUNTIME_FAILURE",
                        message =
                            failure.message
                                ?: "HILTECH identity runtime failed.",
                    )
            }
        setState(state)
    }

    fun refreshSecurity() {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return

        scope.launch {
            runCatching {
                runtime.loadSecuritySnapshot()
            }.onSuccess { security ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (
                    current != null &&
                    current.identity.identityId ==
                    signed.identity.identityId
                ) {
                    setState(
                        current.copy(
                            security = security,
                        ),
                    )
                }
            }.onFailure(::showFailure)
        }
    }

    fun showSignedIn(
        identity: IdentityBootstrapDto,
    ) {
        setState(
            HiltechShellState.SignedIn(
                identity = identity,
            ),
        )
        refreshSecurity()
    }

    fun signIn(
        forceReauthentication: Boolean = false,
    ) {
        if (!runtime.configured) {
            setState(
                HiltechShellState.ConfigurationRequired(
                    "Set HILTECH_API_BASE_URL and HILTECH_OIDC_ISSUER_URI before sign-in.",
                ),
            )
            return
        }

        setState(
            HiltechShellState.Working(
                if (forceReauthentication) {
                    "Confirm your HILTECH identity in the browser…"
                } else {
                    "Complete secure HILTECH sign-in in your browser…"
                },
            ),
        )
        scope.launch {
            runCatching {
                runtime.signIn(
                    forceReauthentication =
                        forceReauthentication,
                )
            }.onSuccess { identity ->
                showSignedIn(identity)
            }.onFailure(::showFailure)
        }
    }

    fun revokeSession(
        sessionId: String,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return

        scope.launch {
            runCatching {
                runtime.revokeSession(sessionId)
                runtime.loadSecuritySnapshot()
            }.onSuccess { security ->
                setState(
                    signed.copy(
                        security = security,
                    ),
                )
            }.onFailure(::showFailure)
        }
    }

    fun revokeDevice(
        deviceId: String,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return

        scope.launch {
            runCatching {
                runtime.revokeDevice(deviceId)
                runtime.loadSecuritySnapshot()
            }.onSuccess { security ->
                setState(
                    signed.copy(
                        security = security,
                    ),
                )
            }.onFailure(::showFailure)
        }
    }

    fun signOut() {
        setState(
            HiltechShellState.Working(
                "Signing out…",
            ),
        )
        scope.launch {
            runCatching {
                runtime.logout()
            }
            setState(
                HiltechShellState.SignedOut,
            )
        }
    }

    application {
        Window(
            onCloseRequest = {
                scope.cancel()
                runtime.close()
                exitApplication()
            },
            title = "HILTECH",
        ) {
            HiltechShell(
                state = shellState.value,
                onSignIn = { signIn(false) },
                onSignOut = ::signOut,
                onRetry = { signIn(false) },
                onRefreshSecurity = ::refreshSecurity,
                onReauthenticate = { signIn(true) },
                onRevokeSession = ::revokeSession,
                onRevokeDevice = ::revokeDevice,
            )
        }
    }
}

package com.hiltech.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityApiException
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

    fun signIn() {
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
                "Complete secure HILTECH sign-in in your browser…",
            ),
        )
        scope.launch {
            runCatching {
                runtime.signIn()
            }.onSuccess { identity ->
                setState(
                    HiltechShellState.SignedIn(
                        identity,
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
                onSignIn = ::signIn,
                onSignOut = ::signOut,
                onRetry = ::signIn,
            )
        }
    }
}

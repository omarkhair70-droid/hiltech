package com.hiltech.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.android.identity.AndroidIdentityRuntime
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

class MainActivity : ComponentActivity() {
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate,
        )

    private val identityRuntime:
        AndroidIdentityRuntime
        get() =
            (application as HiltechApplication)
                .identityRuntime

    private var shellState:
        HiltechShellState by mutableStateOf(
            HiltechShellState.Working(
                "Checking your HILTECH session…",
            ),
        )

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            HiltechShell(
                state = shellState,
                onSignIn = ::startSignIn,
                onSignOut = ::signOut,
                onRetry = ::restoreOrSignIn,
                onRefreshSecurity = ::refreshSecurity,
                onReauthenticate = ::startReauthentication,
                onRevokeSession = ::revokeSession,
                onRevokeDevice = ::revokeDevice,
            )
        }

        if (
            !handleCallbackIntent(intent) &&
            savedInstanceState == null
        ) {
            restoreOrSignIn()
        }
    }

    override fun onNewIntent(
        intent: Intent,
    ) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallbackIntent(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun restoreOrSignIn() {
        if (!identityRuntime.configured) {
            shellState =
                HiltechShellState.ConfigurationRequired(
                    "Set HILTECH API and OIDC issuer configuration before sign-in.",
                )
            return
        }

        shellState =
            HiltechShellState.Working(
                "Checking your HILTECH session…",
            )

        scope.launch {
            runCatching {
                identityRuntime.restoreIdentity()
            }.onSuccess { identity ->
                if (identity == null) {
                    shellState =
                        HiltechShellState.SignedOut
                } else {
                    showSignedIn(identity)
                }
            }.onFailure(::showFailure)
        }
    }

    private fun startSignIn() {
        shellState =
            HiltechShellState.Working(
                "Opening secure HILTECH sign-in…",
            )

        scope.launch {
            runCatching {
                identityRuntime.beginSignIn()
            }.onSuccess { authorizationUrl ->
                startActivity(
                    identityRuntime.browserIntent(
                        authorizationUrl,
                    ),
                )
                shellState =
                    HiltechShellState.Working(
                        "Complete sign-in in your browser…",
                    )
            }.onFailure(::showFailure)
        }
    }

    private fun handleCallbackIntent(
        intent: Intent?,
    ): Boolean {
        val uri = intent?.data
        if (
            !AndroidIdentityRuntime
                .isCallbackUri(uri)
        ) {
            return false
        }

        shellState =
            HiltechShellState.Working(
                "Verifying HILTECH identity…",
            )

        scope.launch {
            runCatching {
                identityRuntime.completeSignIn(
                    uri.toString(),
                )
            }.onSuccess { identity ->
                showSignedIn(identity)
            }.onFailure(::showFailure)
        }
        return true
    }

    private fun showSignedIn(
        identity: IdentityBootstrapDto,
    ) {
        shellState =
            HiltechShellState.SignedIn(
                identity = identity,
            )
        refreshSecurity()
    }

    private fun refreshSecurity() {
        val signed =
            shellState as? HiltechShellState.SignedIn
                ?: return

        scope.launch {
            runCatching {
                identityRuntime.loadSecuritySnapshot()
            }.onSuccess { security ->
                val current =
                    shellState as? HiltechShellState.SignedIn
                if (
                    current != null &&
                    current.identity.identityId ==
                    signed.identity.identityId
                ) {
                    shellState =
                        current.copy(
                            security = security,
                        )
                }
            }.onFailure(::showFailure)
        }
    }

    private fun startReauthentication() {
        shellState =
            HiltechShellState.Working(
                "Confirm your HILTECH identity in the browser…",
            )

        scope.launch {
            runCatching {
                identityRuntime.beginSignIn(
                    forceReauthentication = true,
                )
            }.onSuccess { authorizationUrl ->
                startActivity(
                    identityRuntime.browserIntent(
                        authorizationUrl,
                    ),
                )
                shellState =
                    HiltechShellState.Working(
                        "Complete re-authentication in your browser…",
                    )
            }.onFailure(::showFailure)
        }
    }

    private fun revokeSession(
        sessionId: String,
    ) {
        val signed =
            shellState as? HiltechShellState.SignedIn
                ?: return

        scope.launch {
            runCatching {
                identityRuntime.revokeSession(
                    sessionId,
                )
                identityRuntime.loadSecuritySnapshot()
            }.onSuccess { security ->
                shellState =
                    signed.copy(
                        security = security,
                    )
            }.onFailure(::showFailure)
        }
    }

    private fun revokeDevice(
        deviceId: String,
    ) {
        val signed =
            shellState as? HiltechShellState.SignedIn
                ?: return

        scope.launch {
            runCatching {
                identityRuntime.revokeDevice(
                    deviceId,
                )
                identityRuntime.loadSecuritySnapshot()
            }.onSuccess { security ->
                shellState =
                    signed.copy(
                        security = security,
                    )
            }.onFailure(::showFailure)
        }
    }

    private fun signOut() {
        shellState =
            HiltechShellState.Working(
                "Signing out…",
            )

        scope.launch {
            runCatching {
                identityRuntime.logout()
            }
            shellState =
                HiltechShellState.SignedOut
        }
    }

    private fun showFailure(
        failure: Throwable,
    ) {
        shellState =
            when {
                AndroidIdentityRuntime
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
    }
}

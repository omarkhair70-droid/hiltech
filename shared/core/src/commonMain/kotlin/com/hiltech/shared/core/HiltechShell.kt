package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.identity.IdentityBootstrapDto

sealed interface HiltechShellState {
    data object SignedOut : HiltechShellState
    data class Working(val message: String) : HiltechShellState
    data class SignedIn(val identity: IdentityBootstrapDto) : HiltechShellState
    data class AccessDenied(
        val code: String,
        val message: String,
    ) : HiltechShellState
    data class ConfigurationRequired(
        val message: String,
    ) : HiltechShellState
    data class Failure(
        val code: String,
        val message: String,
    ) : HiltechShellState
}

@Composable
fun HiltechShell(
    state: HiltechShellState = HiltechShellState.SignedOut,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "HILTECH OS",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "Identity / Organization / Permissions",
                style = MaterialTheme.typography.labelLarge,
            )

            when (state) {
                HiltechShellState.SignedOut -> {
                    Text("Sign in with your HILTECH identity to continue.")
                    Button(onClick = onSignIn) { Text("Sign in") }
                }

                is HiltechShellState.Working -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(state.message)
                    }
                }

                is HiltechShellState.SignedIn -> {
                    val primary =
                        state.identity.organizations.firstOrNull { it.primary }
                            ?: state.identity.organizations.firstOrNull()

                    Text(
                        primary?.displayName ?: "HILTECH",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Identity ${state.identity.identityId.take(8)} · " +
                            "${state.identity.identityStatus}",
                    )
                    if (state.identity.teams.isNotEmpty()) {
                        Text(
                            "Teams: " +
                                state.identity.teams.joinToString { it.name },
                        )
                    }
                    Text(
                        "Your workspace only exposes actions authorized " +
                            "for this identity and context.",
                    )
                    OutlinedButton(onClick = onSignOut) {
                        Text("Sign out")
                    }
                }

                is HiltechShellState.AccessDenied -> {
                    Text(
                        "Access unavailable",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(state.message)
                    Text("Reference: ${state.code}")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onRetry) { Text("Try again") }
                        OutlinedButton(onClick = onSignOut) {
                            Text("Sign out")
                        }
                    }
                }

                is HiltechShellState.ConfigurationRequired -> {
                    Text(
                        "HILTECH is not configured",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(state.message)
                }

                is HiltechShellState.Failure -> {
                    Text(
                        "Something went wrong",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(state.message)
                    Text("Reference: ${state.code}")
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentitySecuritySnapshot
import com.hiltech.shared.core.people.EmployeeDetailDto
import com.hiltech.shared.core.people.EmployeeDirectoryDto

sealed interface HiltechShellState {
    data object SignedOut : HiltechShellState
    data class Working(val message: String) : HiltechShellState
    data class SignedIn(
        val identity: IdentityBootstrapDto,
        val security: IdentitySecuritySnapshot? = null,
        val people: HiltechPeopleState? = null,
    ) : HiltechShellState
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

data class HiltechPeopleState(
    val loading: Boolean = false,
    val ownProfile: EmployeeDetailDto? = null,
    val directory: EmployeeDirectoryDto? = null,
    val selectedEmployee: EmployeeDetailDto? = null,
    val errorMessage: String? = null,
)

@Composable
fun HiltechShell(
    state: HiltechShellState = HiltechShellState.SignedOut,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRefreshSecurity: () -> Unit = {},
    onReauthenticate: () -> Unit = {},
    onRevokeSession: (String) -> Unit = {},
    onRevokeDevice: (String) -> Unit = {},
    onRefreshPeople: () -> Unit = {},
    onSelectEmployee: (String) -> Unit = {},
    onCreateEmployee:
        (
            displayName: String,
            employeeCode: String,
            startDate: String,
            employmentTypeCode: String?,
        ) -> Unit = { _, _, _, _ -> },
) {
    MaterialTheme {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(
                        rememberScrollState(),
                    )
                    .padding(24.dp),
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

                    Text(
                        "Me / Sessions",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    val security = state.security
                    if (security == null) {
                        Text(
                            "Session and device security context is loading.",
                        )
                    } else {
                        val activeSessions =
                            security.sessions.count {
                                it.revokedAt == null
                            }
                        val activeDevices =
                            security.devices.count {
                                it.revokedAt == null
                            }
                        val currentSession =
                            security.sessions.firstOrNull {
                                it.current
                            }
                        val currentDevice =
                            security.devices.firstOrNull {
                                it.current
                            }
                        val remoteSession =
                            security.sessions.firstOrNull {
                                !it.current &&
                                    it.revokedAt == null
                            }
                        val remoteDevice =
                            security.devices.firstOrNull {
                                !it.current &&
                                    it.revokedAt == null
                            }

                        Text(
                            "Active sessions: $activeSessions · " +
                                "devices: $activeDevices",
                        )
                        currentSession?.let {
                            Text(
                                "Current session: " +
                                    it.sessionId.take(8),
                            )
                        }
                        currentDevice?.let {
                            Text(
                                "Current device: " +
                                    (it.deviceName
                                        ?: it.platform),
                            )
                        }

                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onRefreshSecurity,
                            ) {
                                Text("Refresh")
                            }
                            OutlinedButton(
                                onClick = onReauthenticate,
                            ) {
                                Text("Re-authenticate")
                            }
                        }

                        remoteSession?.let {
                            OutlinedButton(
                                onClick = {
                                    onRevokeSession(
                                        it.sessionId,
                                    )
                                },
                            ) {
                                Text(
                                    "Revoke other session " +
                                        it.sessionId.take(8),
                                )
                            }
                        }

                        remoteDevice?.let {
                            OutlinedButton(
                                onClick = {
                                    onRevokeDevice(
                                        it.deviceId,
                                    )
                                },
                            ) {
                                Text(
                                    "Revoke other device " +
                                        (it.deviceName
                                            ?: it.platform),
                                )
                            }
                        }
                    }

                    state.people?.let { people ->
                        PeopleSection(
                            state = people,
                            onRefresh =
                                onRefreshPeople,
                            onSelectEmployee =
                                onSelectEmployee,
                            onCreateEmployee =
                                onCreateEmployee,
                        )
                    }

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


@Composable
private fun PeopleSection(
    state: HiltechPeopleState,
    onRefresh: () -> Unit,
    onSelectEmployee: (String) -> Unit,
    onCreateEmployee:
        (
            displayName: String,
            employeeCode: String,
            startDate: String,
            employmentTypeCode: String?,
        ) -> Unit,
) {
    Text(
        "People",
        style = MaterialTheme.typography.titleMedium,
    )

    if (state.loading) {
        Text("Loading People context…")
    }

    state.errorMessage?.let {
        Text(it)
    }

    state.ownProfile?.let { own ->
        Text(
            "My employee profile",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            own.displayName +
                " · " +
                own.employeeCode +
                " · " +
                own.employeeState,
        )
        own.employmentTypeCode?.let {
            Text("Employment: $it")
        }
    }

    state.directory?.let { directory ->
        Text(
            "Employees: " +
                directory.items.size,
        )

        directory.items.forEach { employee ->
            if (directory.canManagePeople) {
                OutlinedButton(
                    onClick = {
                        onSelectEmployee(
                            employee.employeeId,
                        )
                    },
                ) {
                    Text(
                        employee.employeeCode +
                            " · " +
                            employee.displayName +
                            " · " +
                            employee.employeeState,
                    )
                }
            } else {
                Text(
                    employee.employeeCode +
                        " · " +
                        employee.displayName +
                        " · " +
                        employee.employeeState,
                )
            }
        }

        if (directory.canManagePeople) {
            PeopleCreateForm(
                onCreateEmployee =
                    onCreateEmployee,
            )
        }
    }

    state.selectedEmployee?.let { employee ->
        Text(
            "Employee detail",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            employee.displayName +
                " · " +
                employee.employeeCode,
        )
        Text(
            "State: " +
                employee.employeeState +
                " · version " +
                employee.version,
        )
        employee.employmentTypeCode
            ?.let {
                Text(
                    "Employment: $it",
                )
            }
        employee.legalName?.let {
            Text("Legal name: $it")
        }
        employee.mobile?.let {
            Text("Mobile: $it")
        }
        employee.email?.let {
            Text("Email: $it")
        }
    }

    OutlinedButton(
        onClick = onRefresh,
    ) {
        Text("Refresh People")
    }
}

@Composable
private fun PeopleCreateForm(
    onCreateEmployee:
        (
            displayName: String,
            employeeCode: String,
            startDate: String,
            employmentTypeCode: String?,
        ) -> Unit,
) {
    var displayName by
        remember {
            mutableStateOf("")
        }
    var employeeCode by
        remember {
            mutableStateOf("")
        }
    var startDate by
        remember {
            mutableStateOf("")
        }
    var employmentTypeCode by
        remember {
            mutableStateOf("")
        }

    Text(
        "Create employee",
        style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
        value = displayName,
        onValueChange = {
            displayName = it
        },
        label = {
            Text("Display name")
        },
        singleLine = true,
    )
    OutlinedTextField(
        value = employeeCode,
        onValueChange = {
            employeeCode = it
        },
        label = {
            Text("Employee code")
        },
        singleLine = true,
    )
    OutlinedTextField(
        value = startDate,
        onValueChange = {
            startDate = it
        },
        label = {
            Text("Start date (YYYY-MM-DD)")
        },
        singleLine = true,
    )
    OutlinedTextField(
        value = employmentTypeCode,
        onValueChange = {
            employmentTypeCode = it
        },
        label = {
            Text("Employment type code")
        },
        singleLine = true,
    )
    Button(
        onClick = {
            onCreateEmployee(
                displayName.trim(),
                employeeCode.trim(),
                startDate.trim(),
                employmentTypeCode
                    .trim()
                    .takeIf {
                        it.isNotEmpty()
                    },
            )
        },
        enabled =
            displayName.isNotBlank() &&
                employeeCode.isNotBlank() &&
                startDate.isNotBlank(),
    ) {
        Text("Create")
    }
}

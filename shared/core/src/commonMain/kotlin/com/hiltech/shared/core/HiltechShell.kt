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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentitySecuritySnapshot
import com.hiltech.shared.core.people.EmployeeDetailDto
import com.hiltech.shared.core.people.OnboardingRequirementDto
import com.hiltech.shared.core.people.OnboardingCaseDto
import com.hiltech.shared.core.people.EmployeeDocumentListDto
import com.hiltech.shared.core.people.CertificationListDto
import com.hiltech.shared.core.people.EmployeeDirectoryDto
import com.hiltech.shared.core.people.WorkforceAssignmentDto
import com.hiltech.shared.core.people.WorkforceStructureDto

sealed interface HiltechShellState {
    data object SignedOut : HiltechShellState
    data class Working(val message: String) : HiltechShellState
    data class SignedIn(
        val identity: IdentityBootstrapDto,
        val security: IdentitySecuritySnapshot? = null,
        val people: HiltechPeopleState? = null,
        val onboarding:
            HiltechOnboardingState? = null,
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

data class HiltechOnboardingState(
    val loading: Boolean = false,
    val ownCase: OnboardingCaseDto? = null,
    val ownDocuments:
        EmployeeDocumentListDto? = null,
    val ownCertifications:
        CertificationListDto? = null,
    val selectedEmployeeCase:
        OnboardingCaseDto? = null,
    val errorMessage: String? = null,
)

data class HiltechPeopleState(
    val loading: Boolean = false,
    val ownProfile: EmployeeDetailDto? = null,
    val ownWorkforceAssignment: WorkforceAssignmentDto? = null,
    val directory: EmployeeDirectoryDto? = null,
    val workforceStructure: WorkforceStructureDto? = null,
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
    onRefreshOnboarding: () -> Unit = {},
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

                    state.onboarding
                        ?.takeIf {
                            it.ownCase?.employeeState ==
                                "PREBOARDING"
                        }
                        ?.let {
                            EmployeeOnboardingSection(
                                state = it,
                                onRefresh =
                                    onRefreshOnboarding,
                            )
                        }

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

                    state.onboarding
                        ?.selectedEmployeeCase
                        ?.let {
                            AdminOnboardingSection(
                                onboarding = it,
                                onRefresh =
                                    onRefreshOnboarding,
                            )
                        }

                    state.onboarding
                        ?.takeIf {
                            it.ownCase?.employeeState !=
                                "PREBOARDING" &&
                                (
                                    it.loading ||
                                        it.ownCase != null ||
                                        it.errorMessage != null
                                )
                        }
                        ?.let {
                            EmployeeOnboardingSection(
                                state = it,
                                onRefresh =
                                    onRefreshOnboarding,
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
private fun EmployeeOnboardingSection(
    state: HiltechOnboardingState,
    onRefresh: () -> Unit,
) {
    CompositionLocalProvider(
        LocalLayoutDirection provides
            LayoutDirection.Rtl,
    ) {
        Column(
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "تجهيزك في HILTECH",
                style =
                    MaterialTheme.typography
                        .titleMedium,
            )

            if (state.loading) {
                Text("بنراجع خطوات التجهيز…")
            }

            state.errorMessage?.let {
                Text(it)
            }

            state.ownCase?.let {
                onboarding ->
                val complete =
                    onboarding.requirements
                        .count { item ->
                            item.status ==
                                "SATISFIED" ||
                                item.status ==
                                "WAIVED"
                        }
                Text(
                    "تم $complete من " +
                        onboarding.requirements.size +
                        " خطوات",
                )

                val needsEmployee =
                    onboarding.requirements
                        .filter {
                            it.status ==
                                "NEEDS_EMPLOYEE"
                        }
                val waitingHiltech =
                    onboarding.requirements
                        .filter {
                            it.status ==
                                "WAITING_HILTECH" ||
                                it.status ==
                                "BLOCKED"
                        }

                if (needsEmployee.isNotEmpty()) {
                    Text(
                        "مطلوب منك",
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                    needsEmployee.forEach {
                        OnboardingRequirementLine(
                            item = it,
                            employeeFacing = true,
                        )
                    }
                }

                if (waitingHiltech.isNotEmpty()) {
                    Text(
                        "في انتظار HILTECH",
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                    waitingHiltech.forEach {
                        OnboardingRequirementLine(
                            item = it,
                            employeeFacing = true,
                        )
                    }
                }

                if (
                    onboarding.blockingSatisfied
                ) {
                    Text(
                        "كل خطوات التجهيز الأساسية مكتملة.",
                    )
                }

                state.ownDocuments?.let {
                    Text(
                        "مستنداتك: " +
                            it.items.size,
                    )
                }
                state.ownCertifications?.let {
                    Text(
                        "شهاداتك: " +
                            it.items.size,
                    )
                }
            }

            OutlinedButton(
                onClick = onRefresh,
            ) {
                Text("تحديث")
            }
        }
    }
}

@Composable
private fun AdminOnboardingSection(
    onboarding: OnboardingCaseDto,
    onRefresh: () -> Unit,
) {
    Text(
        "Onboarding / تجهيز الموظف",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        onboarding.employeeDisplayName +
            " · " +
            onboarding.employeeCode +
            " · " +
            onboarding.state,
    )

    val waitingHiltech =
        onboarding.requirements
            .filter {
                it.status ==
                    "WAITING_HILTECH" ||
                    it.status ==
                    "BLOCKED"
            }
    val employeeActions =
        onboarding.requirements
            .filter {
                it.status ==
                    "NEEDS_EMPLOYEE"
            }

    if (employeeActions.isNotEmpty()) {
        Text(
            "Needs employee",
            style =
                MaterialTheme.typography.titleSmall,
        )
        employeeActions.forEach {
            OnboardingRequirementLine(
                item = it,
                employeeFacing = false,
            )
        }
    }

    if (waitingHiltech.isNotEmpty()) {
        Text(
            "Waiting on HILTECH",
            style =
                MaterialTheme.typography.titleSmall,
        )
        waitingHiltech.forEach {
            OnboardingRequirementLine(
                item = it,
                employeeFacing = false,
            )
        }
    }

    Text(
        if (onboarding.blockingSatisfied) {
            "Blocking requirements satisfied — activation can be reviewed."
        } else {
            "Activation remains blocked by current authoritative requirements."
        },
    )

    OutlinedButton(
        onClick = onRefresh,
    ) {
        Text("Refresh onboarding")
    }
}

@Composable
private fun OnboardingRequirementLine(
    item: OnboardingRequirementDto,
    employeeFacing: Boolean,
) {
    val status =
        if (employeeFacing) {
            when (item.status) {
                "NEEDS_EMPLOYEE" ->
                    "مطلوب منك"
                "WAITING_HILTECH" ->
                    "في انتظار HILTECH"
                "SATISFIED" ->
                    "مكتمل"
                "WAIVED" ->
                    "تم التجاوز باعتماد"
                else ->
                    "محتاج مراجعة"
            }
        } else {
            item.status
                .replace("_", " ")
        }

    Text(
        "• " +
            item.label +
            " — " +
            status,
    )
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

    state.ownWorkforceAssignment?.let { assignment ->
        Text(
            "My workforce context",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            assignment.roleLabel
                ?: assignment.roleCode,
        )
        assignment.teamName?.let {
            Text("Team: $it")
        }
        assignment.reportsToDisplayName
            ?.let {
                Text("Reports to: $it")
            }
        Text(
            "Effective from: " +
                assignment.effectiveFrom,
        )
    }

    state.workforceStructure?.let { structure ->
        Text(
            "Organization structure",
            style = MaterialTheme.typography.titleSmall,
        )
        structure.items.forEach { assignment ->
            Text(
                buildString {
                    append(
                        assignment.employeeCode,
                    )
                    append(" · ")
                    append(
                        assignment.employeeDisplayName,
                    )
                    append(" · ")
                    append(
                        assignment.roleLabel
                            ?: assignment.roleCode,
                    )
                    assignment.teamName?.let {
                        append(" · ")
                        append(it)
                    }
                    assignment.reportsToDisplayName
                        ?.let {
                            append(" · reports to ")
                            append(it)
                        }
                },
            )
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

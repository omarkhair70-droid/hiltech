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
import com.hiltech.shared.core.people.OffboardingCaseDto
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
        val projects:
            HiltechProjectsState? = null,
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
    val selectedWorkforceAssignment:
        WorkforceAssignmentDto? = null,
    val selectedWorkforceHistory:
        WorkforceStructureDto? = null,
    val selectedOffboarding:
        OffboardingCaseDto? = null,
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
    onChangeWorkforceAssignment:
        (
            employeeId: String,
            currentAssignmentId: String,
            baseAssignmentVersion: Long,
            teamId: String?,
            roleCode: String,
            roleLabel: String?,
            reportsToEmployeeId: String?,
        ) -> Unit = {
            _, _, _, _, _, _, _ -> Unit
        },
    onStartOffboarding:
        (
            employeeId: String,
            baseEmployeeVersion: Long,
            lastWorkingDate: String,
            reasonCategoryCode: String,
            note: String?,
        ) -> Unit = {
            _, _, _, _, _ -> Unit
        },
    onRevokeOffboardingAccess:
        (
            caseId: String,
            baseCaseVersion: Long,
        ) -> Unit = { _, _ -> },
    onResolveOffboardingClearance:
        (
            caseId: String,
            baseCaseVersion: Long,
            clearanceType: String,
            resolution: String,
            reason: String?,
        ) -> Unit = {
            _, _, _, _, _ -> Unit
        },
    onCompleteOffboarding:
        (
            caseId: String,
            baseCaseVersion: Long,
            baseEmployeeVersion: Long,
            baseEmploymentVersion: Long,
        ) -> Unit = {
            _, _, _, _ -> Unit
        },
    onCreateEmployee:
        (
            displayName: String,
            employeeCode: String,
            startDate: String,
            employmentTypeCode: String?,
        ) -> Unit = { _, _, _, _ -> },
    onRefreshProjects: () -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onCreateProject:
        (
            name: String,
            clientOrganizationId: String,
            sourceType: String,
            sourceExternalReference: String?,
            explicitProjectCode: String?,
            principalType: String?,
            principalId: String?,
            startDatePlanned: String?,
            endDatePlanned: String?,
        ) -> Unit = {
            _, _, _, _, _, _, _, _, _ -> Unit
        },
    onChangeProjectManager:
        (
            projectId: String,
            baseVersion: Long,
            principalType: String,
            principalId: String,
            reason: String?,
        ) -> Unit = {
            _, _, _, _, _ -> Unit
        },
    onStartProjectKickoff:
        (
            projectId: String,
            baseVersion: Long,
        ) -> Unit = { _, _ -> },
    onCompleteProjectKickoff:
        (
            projectId: String,
            baseVersion: Long,
        ) -> Unit = { _, _ -> },
    onCreateSite:
        (
            clientOrganizationId: String,
            siteCode: String,
            name: String,
            addressText: String?,
            timezone: String?,
        ) -> Unit = {
            _, _, _, _, _ -> Unit
        },
    onAttachProjectSite:
        (
            projectId: String,
            baseProjectVersion: Long,
            siteId: String,
            projectSiteCode: String?,
            accessInstructions: String?,
            projectSpecificNotes: String?,
        ) -> Unit = {
            _, _, _, _, _, _ -> Unit
        },
    onPlanningAction: (PlanningUiAction) -> Unit = {},
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
                            onChangeWorkforceAssignment =
                                onChangeWorkforceAssignment,
                            onStartOffboarding =
                                onStartOffboarding,
                            onRevokeOffboardingAccess =
                                onRevokeOffboardingAccess,
                            onResolveOffboardingClearance =
                                onResolveOffboardingClearance,
                            onCompleteOffboarding =
                                onCompleteOffboarding,
                            onCreateEmployee =
                                onCreateEmployee,
                        )
                    }

                    state.projects?.let {
                        ProjectControlSection(
                            state = it,
                            onRefresh =
                                onRefreshProjects,
                            onSelectProject =
                                onSelectProject,
                            onCreateProject =
                                onCreateProject,
                            onChangeManager =
                                onChangeProjectManager,
                            onStartKickoff =
                                onStartProjectKickoff,
                            onCompleteKickoff =
                                onCompleteProjectKickoff,
                            onCreateSite =
                                onCreateSite,
                            onAttachSite =
                                onAttachProjectSite,
                            onPlanningAction =
                                onPlanningAction,
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
    onChangeWorkforceAssignment:
        (
            employeeId: String,
            currentAssignmentId: String,
            baseAssignmentVersion: Long,
            teamId: String?,
            roleCode: String,
            roleLabel: String?,
            reportsToEmployeeId: String?,
        ) -> Unit,
    onStartOffboarding:
        (
            employeeId: String,
            baseEmployeeVersion: Long,
            lastWorkingDate: String,
            reasonCategoryCode: String,
            note: String?,
        ) -> Unit,
    onRevokeOffboardingAccess:
        (
            caseId: String,
            baseCaseVersion: Long,
        ) -> Unit,
    onResolveOffboardingClearance:
        (
            caseId: String,
            baseCaseVersion: Long,
            clearanceType: String,
            resolution: String,
            reason: String?,
        ) -> Unit,
    onCompleteOffboarding:
        (
            caseId: String,
            baseCaseVersion: Long,
            baseEmployeeVersion: Long,
            baseEmploymentVersion: Long,
        ) -> Unit,
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

        state.selectedWorkforceAssignment
            ?.let { assignment ->
                WorkforceAssignmentChangeSection(
                    employee = employee,
                    current = assignment,
                    structure =
                        state.workforceStructure,
                    history =
                        state.selectedWorkforceHistory,
                    onChange =
                        onChangeWorkforceAssignment,
                )
            }

        OffboardingAdminSection(
            employee = employee,
            offboarding =
                state.selectedOffboarding,
            onStart =
                onStartOffboarding,
            onRevokeAccess =
                onRevokeOffboardingAccess,
            onResolveClearance =
                onResolveOffboardingClearance,
            onComplete =
                onCompleteOffboarding,
        )
    }

    OutlinedButton(
        onClick = onRefresh,
    ) {
        Text("Refresh People")
    }
}


@Composable
private fun OffboardingAdminSection(
    employee: EmployeeDetailDto,
    offboarding: OffboardingCaseDto?,
    onStart:
        (
            employeeId: String,
            baseEmployeeVersion: Long,
            lastWorkingDate: String,
            reasonCategoryCode: String,
            note: String?,
        ) -> Unit,
    onRevokeAccess:
        (
            caseId: String,
            baseCaseVersion: Long,
        ) -> Unit,
    onResolveClearance:
        (
            caseId: String,
            baseCaseVersion: Long,
            clearanceType: String,
            resolution: String,
            reason: String?,
        ) -> Unit,
    onComplete:
        (
            caseId: String,
            baseCaseVersion: Long,
            baseEmployeeVersion: Long,
            baseEmploymentVersion: Long,
        ) -> Unit,
) {
    Text(
        "Offboarding",
        style =
            MaterialTheme.typography.titleSmall,
    )

    if (
        employee.employeeState ==
            "ACTIVE" &&
        offboarding == null
    ) {
        var lastWorkingDate by
            remember(employee.employeeId) {
                mutableStateOf("")
            }
        var reasonCode by
            remember(employee.employeeId) {
                mutableStateOf("")
            }
        var note by
            remember(employee.employeeId) {
                mutableStateOf("")
            }

        Text(
            "Start only when the employee is actually leaving HILTECH. " +
                "Starting offboarding does not erase employment history or automatically settle other modules.",
        )
        OutlinedTextField(
            value = lastWorkingDate,
            onValueChange = {
                lastWorkingDate = it
            },
            label = {
                Text(
                    "Last working date (YYYY-MM-DD)",
                )
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = reasonCode,
            onValueChange = {
                reasonCode = it
            },
            label = {
                Text(
                    "Reason category code",
                )
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = note,
            onValueChange = {
                note = it
            },
            label = {
                Text("Safe note (optional)")
            },
        )
        Button(
            onClick = {
                onStart(
                    employee.employeeId,
                    employee.version,
                    lastWorkingDate.trim(),
                    reasonCode.trim(),
                    note.trim()
                        .takeIf {
                            it.isNotEmpty()
                        },
                )
            },
            enabled =
                lastWorkingDate.isNotBlank() &&
                    reasonCode.isNotBlank(),
        ) {
            Text("Start offboarding")
        }
        return
    }

    if (offboarding == null) {
        if (
            employee.employeeState ==
            "FORMER"
        ) {
            Text(
                "Former employee. Historical People records remain preserved.",
            )
        } else {
            Text(
                "No offboarding case is available for this employee.",
            )
        }
        return
    }

    Text(
        offboarding.employeeDisplayName +
            " · " +
            offboarding.state +
            " · last working " +
            offboarding.lastWorkingDate,
    )
    Text(
        "Reason: " +
            offboarding.reasonCategoryCode,
    )
    offboarding.note?.let {
        Text("Note: $it")
    }

    Text(
        "Project / Asset / Finance / Payroll clearances are coordination records here; " +
            "their business transactions remain in their own HILTECH modules.",
    )

    Text(
        "Access facts: memberships " +
            offboarding.accessFacts
                .activeOrganizationMemberships +
            " · sessions " +
            offboarding.accessFacts
                .activeSessions +
            " · current assignment " +
            if (
                offboarding.accessFacts
                    .currentWorkforceAssignmentPresent
            ) {
                "yes"
            } else {
                "no"
            },
    )

    if (
        !offboarding.accessFacts.clear &&
        offboarding.state == "OPEN"
    ) {
        Button(
            onClick = {
                onRevokeAccess(
                    offboarding.caseId,
                    offboarding.caseVersion,
                )
            },
        ) {
            Text("Revoke HILTECH access")
        }
    } else if (
        offboarding.accessFacts.clear
    ) {
        Text("ACCESS · system verified CLEAR")
    }

    var exceptionReason by
        remember(offboarding.caseId) {
            mutableStateOf("")
        }

    offboarding.clearances
        .forEach {
            clearance ->
            Text(
                clearance.type +
                    " · " +
                    clearance.state +
                    " · source " +
                    clearance.source,
            )

            if (
                offboarding.state == "OPEN" &&
                clearance.type != "ACCESS" &&
                clearance.state == "PENDING"
            ) {
                Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onResolveClearance(
                                offboarding.caseId,
                                offboarding.caseVersion,
                                clearance.type,
                                "CLEAR",
                                null,
                            )
                        },
                    ) {
                        Text("Clear")
                    }
                    OutlinedButton(
                        onClick = {
                            onResolveClearance(
                                offboarding.caseId,
                                offboarding.caseVersion,
                                clearance.type,
                                "NOT_APPLICABLE",
                                null,
                            )
                        },
                    ) {
                        Text("Not applicable")
                    }
                }
            }
        }

    if (
        offboarding.state == "OPEN" &&
        offboarding.clearances.any {
            it.type != "ACCESS" &&
                it.state == "PENDING"
        }
    ) {
        OutlinedTextField(
            value = exceptionReason,
            onValueChange = {
                exceptionReason = it
            },
            label = {
                Text(
                    "Exception reason (for the selected pending clearance)",
                )
            },
        )
        val firstPending =
            offboarding.clearances
                .firstOrNull {
                    it.type != "ACCESS" &&
                        it.state == "PENDING"
                }
        if (firstPending != null) {
            OutlinedButton(
                onClick = {
                    onResolveClearance(
                        offboarding.caseId,
                        offboarding.caseVersion,
                        firstPending.type,
                        "EXCEPTION_ACCEPTED",
                        exceptionReason
                            .trim(),
                    )
                },
                enabled =
                    exceptionReason.isNotBlank(),
            ) {
                Text(
                    "Accept exception for " +
                        firstPending.type,
                )
            }
        }
    }

    if (offboarding.blockers.isNotEmpty()) {
        Text(
            "Completion blockers",
            style =
                MaterialTheme.typography.titleSmall,
        )
        offboarding.blockers.forEach {
            Text("• $it")
        }
    }

    val employmentVersion =
        offboarding.employmentVersion
    Button(
        onClick = {
            if (employmentVersion != null) {
                onComplete(
                    offboarding.caseId,
                    offboarding.caseVersion,
                    offboarding.employeeVersion,
                    employmentVersion,
                )
            }
        },
        enabled =
            offboarding.state == "OPEN" &&
                offboarding.canComplete &&
                employmentVersion != null,
    ) {
        Text(
            if (
                offboarding.state ==
                "COMPLETED"
            ) {
                "Offboarding completed"
            } else {
                "Complete offboarding"
            },
        )
    }

    if (
        offboarding.state == "COMPLETED"
    ) {
        Text(
            "Employee is FORMER. Employment and assignment history remain preserved.",
        )
    }
}


@Composable
private fun WorkforceAssignmentChangeSection(
    employee: EmployeeDetailDto,
    current: WorkforceAssignmentDto,
    structure: WorkforceStructureDto?,
    history: WorkforceStructureDto?,
    onChange:
        (
            employeeId: String,
            currentAssignmentId: String,
            baseAssignmentVersion: Long,
            teamId: String?,
            roleCode: String,
            roleLabel: String?,
            reportsToEmployeeId: String?,
        ) -> Unit,
) {
    var proposedTeamId by
        remember(current.assignmentId) {
            mutableStateOf(
                current.teamId,
            )
        }
    var proposedRoleCode by
        remember(current.assignmentId) {
            mutableStateOf(
                current.roleCode,
            )
        }
    var proposedRoleLabel by
        remember(current.assignmentId) {
            mutableStateOf(
                current.roleLabel.orEmpty(),
            )
        }
    var proposedManagerId by
        remember(current.assignmentId) {
            mutableStateOf(
                current.reportsToEmployeeId,
            )
        }
    var confirming by
        remember(current.assignmentId) {
            mutableStateOf(false)
        }

    val knownTeams =
        structure
            ?.items
            ?.mapNotNull {
                assignment ->
                assignment.teamId
                    ?.let {
                        id ->
                        id to
                            (
                                assignment.teamName
                                    ?: assignment.teamCode
                                    ?: id.take(8)
                            )
                    }
            }
            ?.distinctBy {
                it.first
            }
            .orEmpty()

    val managerCandidates =
        structure
            ?.items
            ?.filter {
                it.employeeId !=
                    employee.employeeId
            }
            ?.distinctBy {
                it.employeeId
            }
            .orEmpty()

    Text(
        "Current assignment",
        style =
            MaterialTheme.typography.titleSmall,
    )
    Text(
        buildString {
            append(
                current.roleLabel
                    ?: current.roleCode,
            )
            append(" · ")
            append(
                current.teamName
                    ?: "No team",
            )
            append(" · reports to ")
            append(
                current.reportsToDisplayName
                    ?: "Nobody",
            )
        },
    )

    Text(
        "Change assignment",
        style =
            MaterialTheme.typography.titleSmall,
    )
    Text(
        "This updates People and Team access only. " +
            "Project / Site / Work assignments are not changed here.",
    )

    Text("New team")
    if (knownTeams.isEmpty()) {
        Text(
            current.teamName
                ?: "No known Team choices are available in the current organization structure.",
        )
    } else {
        knownTeams.forEach {
            (teamId, teamName) ->
            OutlinedButton(
                onClick = {
                    proposedTeamId =
                        teamId
                    confirming = false
                },
            ) {
                Text(
                    if (
                        proposedTeamId ==
                        teamId
                    ) {
                        "✓ $teamName"
                    } else {
                        teamName
                    },
                )
            }
        }
        OutlinedButton(
            onClick = {
                proposedTeamId = null
                confirming = false
            },
        ) {
            Text(
                if (
                    proposedTeamId == null
                ) {
                    "✓ No team"
                } else {
                    "No team"
                },
            )
        }
    }

    OutlinedTextField(
        value = proposedRoleCode,
        onValueChange = {
            proposedRoleCode = it
            confirming = false
        },
        label = {
            Text("New role code")
        },
        singleLine = true,
    )
    OutlinedTextField(
        value = proposedRoleLabel,
        onValueChange = {
            proposedRoleLabel = it
            confirming = false
        },
        label = {
            Text("New role label")
        },
        singleLine = true,
    )

    Text("New reporting manager")
    managerCandidates.forEach {
        manager ->
        OutlinedButton(
            onClick = {
                proposedManagerId =
                    manager.employeeId
                confirming = false
            },
        ) {
            Text(
                if (
                    proposedManagerId ==
                    manager.employeeId
                ) {
                    "✓ " +
                        manager.employeeDisplayName
                } else {
                    manager.employeeDisplayName
                },
            )
        }
    }
    OutlinedButton(
        onClick = {
            proposedManagerId = null
            confirming = false
        },
    ) {
        Text(
            if (
                proposedManagerId == null
            ) {
                "✓ No reporting manager"
            } else {
                "No reporting manager"
            },
        )
    }

    if (!confirming) {
        Button(
            onClick = {
                confirming = true
            },
            enabled =
                proposedRoleCode
                    .isNotBlank(),
        ) {
            Text("Review assignment change")
        }
    } else {
        val proposedTeamName =
            knownTeams
                .firstOrNull {
                    it.first ==
                        proposedTeamId
                }
                ?.second
                ?: if (
                    proposedTeamId == null
                ) {
                    "No team"
                } else {
                    proposedTeamId
                        ?.take(8)
                        ?: "No team"
                }
        val proposedManagerName =
            managerCandidates
                .firstOrNull {
                    it.employeeId ==
                        proposedManagerId
                }
                ?.employeeDisplayName
                ?: if (
                    proposedManagerId == null
                ) {
                    "Nobody"
                } else {
                    proposedManagerId
                        ?.take(8)
                        ?: "Nobody"
                }

        Text(
            "Review before confirming",
            style =
                MaterialTheme.typography.titleSmall,
        )
        Text(
            "Current: " +
                (
                    current.teamName
                        ?: "No team"
                ) +
                " · " +
                (
                    current.roleLabel
                        ?: current.roleCode
                ) +
                " · " +
                (
                    current.reportsToDisplayName
                        ?: "Nobody"
                ),
        )
        Text(
            "Proposed: " +
                proposedTeamName +
                " · " +
                (
                    proposedRoleLabel
                        .trim()
                        .takeIf {
                            it.isNotEmpty()
                        }
                        ?: proposedRoleCode
                ) +
                " · " +
                proposedManagerName,
        )
        Text(
            "Previous assignment history will be preserved. " +
                "Project / Site / Work assignments will stay unchanged.",
        )
        Button(
            onClick = {
                onChange(
                    employee.employeeId,
                    current.assignmentId,
                    current.version,
                    proposedTeamId,
                    proposedRoleCode.trim(),
                    proposedRoleLabel
                        .trim()
                        .takeIf {
                            it.isNotEmpty()
                        },
                    proposedManagerId,
                )
                confirming = false
            },
        ) {
            Text("Confirm assignment change")
        }
        OutlinedButton(
            onClick = {
                confirming = false
            },
        ) {
            Text("Back")
        }
    }

    history?.let {
        assignmentHistory ->
        Text(
            "Assignment history",
            style =
                MaterialTheme.typography.titleSmall,
        )
        assignmentHistory.items
            .forEach {
                assignment ->
                Text(
                    buildString {
                        append(
                            assignment.roleLabel
                                ?: assignment.roleCode,
                        )
                        append(" · ")
                        append(
                            assignment.teamName
                                ?: "No team",
                        )
                        append(" · ")
                        append(
                            assignment.state,
                        )
                        append(" · ")
                        append(
                            assignment.effectiveFrom,
                        )
                        assignment.effectiveTo
                            ?.let {
                                append(" → ")
                                append(it)
                            }
                    },
                )
            }
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

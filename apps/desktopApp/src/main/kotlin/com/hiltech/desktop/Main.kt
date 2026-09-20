package com.hiltech.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hiltech.shared.core.HiltechOnboardingState
import com.hiltech.shared.core.HiltechPeopleState
import com.hiltech.shared.core.HiltechProjectsState
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.network.HiltechApiException
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
                people =
                    HiltechPeopleState(
                        loading = true,
                    ),
                onboarding =
                    HiltechOnboardingState(
                        loading = true,
                    ),
                projects =
                    HiltechProjectsState(
                        loading = true,
                    ),
            ),
        )
        refreshSecurity()
    }

    fun refreshPeople() {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val organizationId =
            signed.identity.organizations
                .firstOrNull {
                    it.primary
                }
                ?.organizationId
                ?: signed.identity
                    .organizations
                    .firstOrNull()
                    ?.organizationId
                ?: return

        setState(
            signed.copy(
                people =
                    (signed.people
                        ?: HiltechPeopleState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            val ownEmployeeResult =
                runCatching {
                    runtime.ownEmployee(
                        organizationId,
                    )
                }
            val ownAssignmentResult =
                runCatching {
                    runtime
                        .ownWorkforceAssignment(
                            organizationId,
                        )
                }
            val directoryResult =
                runCatching {
                    runtime.peopleDirectory(
                        organizationId,
                    )
                }
            val structureResult =
                runCatching {
                    runtime.workforceStructure(
                        organizationId,
                    )
                }

            val current =
                shellState.value as?
                    HiltechShellState.SignedIn
            if (
                current != null &&
                current.identity.identityId ==
                signed.identity.identityId
            ) {
                val messages =
                    mutableListOf<String>()

                ownEmployeeResult
                    .exceptionOrNull()
                    ?.let { failure ->
                        messages +=
                            if (
                                failure is HiltechApiException &&
                                failure.code ==
                                "EMPLOYEE_PROFILE_NOT_FOUND"
                            ) {
                                "No employee profile is linked to this identity yet."
                            } else {
                                failure.message
                                    ?: "Own employee profile could not be loaded."
                            }
                    }

                ownAssignmentResult
                    .exceptionOrNull()
                    ?.let { failure ->
                        if (
                            ownEmployeeResult.isSuccess
                        ) {
                            messages +=
                                if (
                                    failure is HiltechApiException &&
                                    failure.code ==
                                    "WORKFORCE_ASSIGNMENT_NOT_FOUND"
                                ) {
                                    "No current workforce assignment yet."
                                } else {
                                    failure.message
                                        ?: "Own workforce assignment could not be loaded."
                                }
                        }
                    }

                directoryResult
                    .exceptionOrNull()
                    ?.let { failure ->
                        messages +=
                            failure.message
                                ?: "People directory could not be loaded."
                    }

                structureResult
                    .exceptionOrNull()
                    ?.let { failure ->
                        messages +=
                            failure.message
                                ?: "Organization structure could not be loaded."
                    }

                setState(
                    current.copy(
                        people =
                            HiltechPeopleState(
                                ownProfile =
                                    ownEmployeeResult
                                        .getOrNull(),
                                ownWorkforceAssignment =
                                    ownAssignmentResult
                                        .getOrNull(),
                                directory =
                                    directoryResult
                                        .getOrNull(),
                                workforceStructure =
                                    structureResult
                                        .getOrNull(),
                                selectedEmployee =
                                    current.people
                                        ?.selectedEmployee,
                                selectedWorkforceAssignment =
                                    current.people
                                        ?.selectedWorkforceAssignment,
                                selectedWorkforceHistory =
                                    current.people
                                        ?.selectedWorkforceHistory,
                                errorMessage =
                                    messages
                                        .takeIf {
                                            it.isNotEmpty()
                                        }
                                        ?.joinToString(" "),
                            ),
                    ),
                )
            }
        }
    }

    fun refreshOnboarding() {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val organizationId =
            signed.identity.organizations
                .firstOrNull {
                    it.primary
                }
                ?.organizationId
                ?: signed.identity
                    .organizations
                    .firstOrNull()
                    ?.organizationId
                ?: return
        val selectedEmployeeId =
            signed.people
                ?.selectedEmployee
                ?.employeeId

        setState(
            signed.copy(
                onboarding =
                    (signed.onboarding
                        ?: HiltechOnboardingState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            val ownCaseResult =
                runCatching {
                    runtime.ownOnboarding(
                        organizationId,
                    )
                }
            val ownDocumentsResult =
                runCatching {
                    runtime.ownDocuments(
                        organizationId,
                    )
                }
            val ownCertificationsResult =
                runCatching {
                    runtime.ownCertifications(
                        organizationId,
                    )
                }
            val selectedResult =
                selectedEmployeeId?.let {
                    runCatching {
                        runtime
                            .employeeOnboarding(it)
                    }
                }

            val current =
                shellState.value as?
                    HiltechShellState.SignedIn
            if (
                current != null &&
                current.identity.identityId ==
                    signed.identity.identityId
            ) {
                fun absentCase(
                    failure: Throwable?,
                ): Boolean =
                    failure is HiltechApiException &&
                        failure.code ==
                        "ONBOARDING_CASE_NOT_FOUND"

                val ownFailure =
                    ownCaseResult
                        .exceptionOrNull()
                val selectedFailure =
                    selectedResult
                        ?.exceptionOrNull()

                val error =
                    listOfNotNull(
                        ownFailure
                            ?.takeUnless {
                                absentCase(it) ||
                                    (
                                        it is
                                            HiltechApiException &&
                                            it.code ==
                                            "OWN_EMPLOYEE_NOT_FOUND"
                                    )
                            }
                            ?.message,
                        selectedFailure
                            ?.takeUnless {
                                absentCase(it)
                            }
                            ?.message,
                    ).takeIf {
                        it.isNotEmpty()
                    }?.joinToString(" ")

                setState(
                    current.copy(
                        onboarding =
                            HiltechOnboardingState(
                                ownCase =
                                    ownCaseResult
                                        .getOrNull(),
                                ownDocuments =
                                    ownDocumentsResult
                                        .getOrNull(),
                                ownCertifications =
                                    ownCertificationsResult
                                        .getOrNull(),
                                selectedEmployeeCase =
                                    selectedResult
                                        ?.getOrNull(),
                                errorMessage =
                                    error,
                            ),
                    ),
                )
            }
        }
    }

    fun selectEmployee(
        employeeId: String,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return

        setState(
            signed.copy(
                people =
                    (signed.people
                        ?: HiltechPeopleState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            val detailResult =
                runCatching {
                    runtime.employeeDetail(
                        employeeId,
                    )
                }
            val assignmentResult =
                runCatching {
                    runtime
                        .employeeWorkforceAssignment(
                            employeeId,
                        )
                }
            val historyResult =
                runCatching {
                    runtime
                        .employeeWorkforceHistory(
                            employeeId,
                        )
                }
            val offboardingResult =
                runCatching {
                    runtime
                        .employeeOffboarding(
                            employeeId,
                        )
                }

            val current =
                shellState.value as?
                    HiltechShellState.SignedIn
            if (current == null) {
                return@launch
            }

            val detail =
                detailResult.getOrElse {
                    failure ->
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "Employee detail could not be loaded.",
                                    ),
                        ),
                    )
                    return@launch
                }

            val assignmentFailure =
                assignmentResult
                    .exceptionOrNull()
            val assignmentMessage =
                if (
                    assignmentFailure is
                    HiltechApiException &&
                    assignmentFailure.code ==
                    "WORKFORCE_ASSIGNMENT_NOT_FOUND"
                ) {
                    null
                } else {
                    assignmentFailure
                        ?.message
                }

            val historyMessage =
                historyResult
                    .exceptionOrNull()
                    ?.message

            val offboardingFailure =
                offboardingResult
                    .exceptionOrNull()
            val offboardingMessage =
                if (
                    offboardingFailure is
                    HiltechApiException &&
                    offboardingFailure.code ==
                    "OFFBOARDING_CASE_NOT_FOUND"
                ) {
                    null
                } else {
                    offboardingFailure
                        ?.message
                }

            setState(
                current.copy(
                    people =
                        (current.people
                            ?: HiltechPeopleState())
                            .copy(
                                loading = false,
                                selectedEmployee =
                                    detail,
                                selectedWorkforceAssignment =
                                    assignmentResult
                                        .getOrNull(),
                                selectedWorkforceHistory =
                                    historyResult
                                        .getOrNull(),
                                selectedOffboarding =
                                    offboardingResult
                                        .getOrNull(),
                                errorMessage =
                                    listOfNotNull(
                                        assignmentMessage,
                                        historyMessage,
                                        offboardingMessage,
                                    ).takeIf {
                                        it.isNotEmpty()
                                    }?.joinToString(" "),
                            ),
                ),
            )
            refreshOnboarding()
        }
    }

    fun changeWorkforceAssignment(
        employeeId: String,
        currentAssignmentId: String,
        baseAssignmentVersion: Long,
        teamId: String?,
        roleCode: String,
        roleLabel: String?,
        reportsToEmployeeId: String?,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return

        setState(
            signed.copy(
                people =
                    (signed.people
                        ?: HiltechPeopleState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            runCatching {
                runtime.changeWorkforceAssignment(
                    employeeId =
                        employeeId,
                    currentAssignmentId =
                        currentAssignmentId,
                    baseAssignmentVersion =
                        baseAssignmentVersion,
                    teamId =
                        teamId,
                    roleCode =
                        roleCode,
                    roleLabel =
                        roleLabel,
                    reportsToEmployeeId =
                        reportsToEmployeeId,
                )
            }.onSuccess { changed ->
                val history =
                    runCatching {
                        runtime
                            .employeeWorkforceHistory(
                                employeeId,
                            )
                    }.getOrNull()

                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        selectedWorkforceAssignment =
                                            changed,
                                        selectedWorkforceHistory =
                                            history,
                                        errorMessage =
                                            null,
                                    ),
                        ),
                    )
                    refreshPeople()
                    refreshOnboarding()
                }
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "Workforce assignment could not be changed.",
                                    ),
                        ),
                    )
                }
            }
        }
    }

    fun startOffboarding(
        employeeId: String,
        baseEmployeeVersion: Long,
        lastWorkingDate: String,
        reasonCategoryCode: String,
        note: String?,
    ) {
        scope.launch {
            runCatching {
                runtime.startEmployeeOffboarding(
                    employeeId =
                        employeeId,
                    baseEmployeeVersion =
                        baseEmployeeVersion,
                    lastWorkingDate =
                        lastWorkingDate,
                    reasonCategoryCode =
                        reasonCategoryCode,
                    note = note,
                )
            }.onSuccess {
                selectEmployee(employeeId)
                refreshPeople()
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "Offboarding could not be started.",
                                    ),
                        ),
                    )
                }
            }
        }
    }

    fun revokeOffboardingAccess(
        caseId: String,
        baseCaseVersion: Long,
    ) {
        val employeeId =
            (
                shellState.value as?
                    HiltechShellState.SignedIn
            )?.people
                ?.selectedEmployee
                ?.employeeId
                ?: return

        scope.launch {
            runCatching {
                runtime
                    .revokeEmployeeOffboardingAccess(
                        caseId = caseId,
                        baseCaseVersion =
                            baseCaseVersion,
                    )
            }.onSuccess {
                selectEmployee(employeeId)
                refreshPeople()
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "HILTECH access could not be revoked.",
                                    ),
                        ),
                    )
                }
            }
        }
    }

    fun resolveOffboardingClearance(
        caseId: String,
        baseCaseVersion: Long,
        clearanceType: String,
        resolution: String,
        reason: String?,
    ) {
        val employeeId =
            (
                shellState.value as?
                    HiltechShellState.SignedIn
            )?.people
                ?.selectedEmployee
                ?.employeeId
                ?: return

        scope.launch {
            runCatching {
                if (
                    clearanceType == "HR"
                ) {
                    runtime
                        .resolveEmployeeOffboardingHr(
                            caseId = caseId,
                            baseCaseVersion =
                                baseCaseVersion,
                            resolution =
                                resolution,
                            reason = reason,
                        )
                } else {
                    runtime
                        .resolveEmployeeOffboardingExternal(
                            caseId = caseId,
                            baseCaseVersion =
                                baseCaseVersion,
                            clearanceType =
                                clearanceType,
                            resolution =
                                resolution,
                            reason = reason,
                        )
                }
            }.onSuccess {
                selectEmployee(employeeId)
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "Offboarding clearance could not be resolved.",
                                    ),
                        ),
                    )
                }
            }
        }
    }

    fun completeOffboarding(
        caseId: String,
        baseCaseVersion: Long,
        baseEmployeeVersion: Long,
        baseEmploymentVersion: Long,
    ) {
        val employeeId =
            (
                shellState.value as?
                    HiltechShellState.SignedIn
            )?.people
                ?.selectedEmployee
                ?.employeeId
                ?: return

        scope.launch {
            runCatching {
                runtime
                    .completeEmployeeOffboarding(
                        caseId = caseId,
                        baseCaseVersion =
                            baseCaseVersion,
                        baseEmployeeVersion =
                            baseEmployeeVersion,
                        baseEmploymentVersion =
                            baseEmploymentVersion,
                    )
            }.onSuccess {
                selectEmployee(employeeId)
                refreshPeople()
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "Offboarding could not be completed.",
                                    ),
                        ),
                    )
                }
            }
        }
    }

    fun createEmployee(
        displayName: String,
        employeeCode: String,
        startDate: String,
        employmentTypeCode: String?,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val organizationId =
            signed.identity.organizations
                .firstOrNull {
                    it.primary
                }
                ?.organizationId
                ?: signed.identity
                    .organizations
                    .firstOrNull()
                    ?.organizationId
                ?: return

        setState(
            signed.copy(
                people =
                    (signed.people
                        ?: HiltechPeopleState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            runCatching {
                runtime.createEmployee(
                    organizationId =
                        organizationId,
                    displayName =
                        displayName,
                    employeeCode =
                        employeeCode,
                    startDate =
                        startDate,
                    employmentTypeCode =
                        employmentTypeCode,
                )
            }.onSuccess { created ->
                runCatching {
                    runtime.peopleDirectory(
                        organizationId,
                    )
                }.onSuccess { directory ->
                    val current =
                        shellState.value as?
                            HiltechShellState.SignedIn
                    if (current != null) {
                        setState(
                            current.copy(
                                people =
                                    HiltechPeopleState(
                                        ownProfile =
                                            current.people
                                                ?.ownProfile,
                                        ownWorkforceAssignment =
                                            current.people
                                                ?.ownWorkforceAssignment,
                                        directory =
                                            directory,
                                        workforceStructure =
                                            current.people
                                                ?.workforceStructure,
                                        selectedEmployee =
                                            created.employee,
                                        selectedWorkforceAssignment =
                                            null,
                                        selectedWorkforceHistory =
                                            null,
                                    ),
                            ),
                        )
                    }
                }.onFailure(::showFailure)
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                if (current != null) {
                    setState(
                        current.copy(
                            people =
                                (current.people
                                    ?: HiltechPeopleState())
                                    .copy(
                                        loading = false,
                                        errorMessage =
                                            failure.message
                                                ?: "Employee could not be created.",
                                    ),
                        ),
                    )
                }
            }
        }
    }

    fun refreshProjects() {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val organizationId =
            signed.identity.organizations
                .firstOrNull {
                    it.primary
                }
                ?.organizationId
                ?: signed.identity
                    .organizations
                    .firstOrNull()
                    ?.organizationId
                ?: return
        val selectedId =
            signed.projects
                ?.selectedProject
                ?.projectId

        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            val listResult =
                runCatching {
                    runtime.projects(
                        organizationId,
                    )
                }
            val detailResult =
                selectedId?.let {
                    runCatching {
                        runtime.projectDetail(it)
                    }
                }
            val sitesResult =
                selectedId?.let {
                    runCatching {
                        runtime.projectSites(it)
                    }
                }

            val current =
                shellState.value as?
                    HiltechShellState.SignedIn
                ?: return@launch
            val failure =
                listResult.exceptionOrNull()
                    ?: detailResult
                        ?.exceptionOrNull()
                    ?: sitesResult
                        ?.exceptionOrNull()

            setState(
                current.copy(
                    projects =
                        HiltechProjectsState(
                            loading = false,
                            list =
                                listResult.getOrNull()
                                    ?: current.projects
                                        ?.list,
                            selectedProject =
                                detailResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.selectedProject,
                            selectedSites =
                                sitesResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.selectedSites,
                            lastCreatedSite =
                                current.projects
                                    ?.lastCreatedSite,
                            errorCode =
                                (failure as?
                                    HiltechApiException)
                                    ?.code,
                            errorMessage =
                                failure?.message,
                        ),
                ),
            )
        }
    }

    fun selectProject(
        projectId: String,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return

        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            val detailResult =
                runCatching {
                    runtime.projectDetail(
                        projectId,
                    )
                }
            val sitesResult =
                runCatching {
                    runtime.projectSites(
                        projectId,
                    )
                }
            val current =
                shellState.value as?
                    HiltechShellState.SignedIn
                ?: return@launch
            val failure =
                detailResult.exceptionOrNull()
                    ?: sitesResult.exceptionOrNull()

            setState(
                current.copy(
                    projects =
                        (current.projects
                            ?: HiltechProjectsState())
                            .copy(
                                loading = false,
                                selectedProject =
                                    detailResult
                                        .getOrNull(),
                                selectedSites =
                                    sitesResult
                                        .getOrNull(),
                                errorCode =
                                    (failure as?
                                        HiltechApiException)
                                        ?.code,
                                errorMessage =
                                    failure?.message,
                            ),
                ),
            )
        }
    }

    fun createProject(
        name: String,
        clientOrganizationId: String,
        sourceType: String,
        sourceExternalReference: String?,
        explicitProjectCode: String?,
        principalType: String?,
        principalId: String?,
        startDatePlanned: String?,
        endDatePlanned: String?,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val organizationId =
            signed.identity.organizations
                .firstOrNull {
                    it.primary
                }
                ?.organizationId
                ?: signed.identity
                    .organizations
                    .firstOrNull()
                    ?.organizationId
                ?: return

        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )

        scope.launch {
            runCatching {
                runtime.createProject(
                    organizationId =
                        organizationId,
                    clientOrganizationId =
                        clientOrganizationId,
                    name = name,
                    sourceType =
                        sourceType,
                    sourceExternalReference =
                        sourceExternalReference,
                    explicitProjectCode =
                        explicitProjectCode,
                    principalType =
                        principalType,
                    principalId =
                        principalId,
                    startDatePlanned =
                        startDatePlanned,
                    endDatePlanned =
                        endDatePlanned,
                )
            }.onSuccess { created ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onSuccess
                val previous =
                    current.projects
                        ?.list
                val nextList =
                    previous?.copy(
                        items =
                            (
                                previous.items
                                    .filterNot {
                                        it.projectId ==
                                            created.project
                                                .projectId
                                    } +
                                    created.project
                            ),
                    )
                setState(
                    current.copy(
                        projects =
                            HiltechProjectsState(
                                loading = false,
                                list = nextList,
                                selectedProject =
                                    created.project,
                                selectedSites =
                                    null,
                                lastCreatedSite =
                                    current.projects
                                        ?.lastCreatedSite,
                            ),
                    ),
                )
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onFailure
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    errorCode =
                                        (failure as?
                                            HiltechApiException)
                                            ?.code,
                                    errorMessage =
                                        failure.message,
                                ),
                    ),
                )
            }
        }
    }

    fun changeProjectManager(
        projectId: String,
        baseVersion: Long,
        principalType: String,
        principalId: String,
        reason: String?,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )
        scope.launch {
            runCatching {
                runtime.changeProjectManager(
                    projectId =
                        projectId,
                    baseVersion =
                        baseVersion,
                    principalType =
                        principalType,
                    principalId =
                        principalId,
                    reason = reason,
                )
            }.onSuccess { changed ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onSuccess
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    selectedProject =
                                        changed.project,
                                    errorCode = null,
                                    errorMessage = null,
                                ),
                    ),
                )
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onFailure
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    errorCode =
                                        (failure as?
                                            HiltechApiException)
                                            ?.code,
                                    errorMessage =
                                        failure.message,
                                ),
                    ),
                )
            }
        }
    }

    fun runProjectTransition(
        projectId: String,
        baseVersion: Long,
        completeKickoff: Boolean,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )
        scope.launch {
            runCatching {
                if (completeKickoff) {
                    runtime.completeProjectKickoff(
                        projectId,
                        baseVersion,
                    )
                } else {
                    runtime.startProjectKickoff(
                        projectId,
                        baseVersion,
                    )
                }
            }.onSuccess { result ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onSuccess
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    selectedProject =
                                        result.project,
                                    errorCode = null,
                                    errorMessage = null,
                                ),
                    ),
                )
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onFailure
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    errorCode =
                                        (failure as?
                                            HiltechApiException)
                                            ?.code,
                                    errorMessage =
                                        failure.message,
                                ),
                    ),
                )
            }
        }
    }

    fun createSite(
        clientOrganizationId: String,
        siteCode: String,
        name: String,
        addressText: String?,
        timezone: String?,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val organizationId =
            signed.identity.organizations
                .firstOrNull {
                    it.primary
                }
                ?.organizationId
                ?: signed.identity
                    .organizations
                    .firstOrNull()
                    ?.organizationId
                ?: return
        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )
        scope.launch {
            runCatching {
                runtime.createSite(
                    organizationId =
                        organizationId,
                    clientOrganizationId =
                        clientOrganizationId,
                    siteCode = siteCode,
                    name = name,
                    addressText =
                        addressText,
                    timezone =
                        timezone,
                )
            }.onSuccess { created ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onSuccess
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    lastCreatedSite =
                                        created.site,
                                    errorCode = null,
                                    errorMessage = null,
                                ),
                    ),
                )
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onFailure
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    errorCode =
                                        (failure as?
                                            HiltechApiException)
                                            ?.code,
                                    errorMessage =
                                        failure.message,
                                ),
                    ),
                )
            }
        }
    }

    fun attachProjectSite(
        projectId: String,
        baseProjectVersion: Long,
        siteId: String,
        projectSiteCode: String?,
        accessInstructions: String?,
        projectSpecificNotes: String?,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        setState(
            signed.copy(
                projects =
                    (signed.projects
                        ?: HiltechProjectsState())
                        .copy(
                            loading = true,
                            errorCode = null,
                            errorMessage = null,
                        ),
            ),
        )
        scope.launch {
            runCatching {
                runtime.attachProjectSite(
                    projectId =
                        projectId,
                    baseProjectVersion =
                        baseProjectVersion,
                    siteId = siteId,
                    projectSiteCode =
                        projectSiteCode,
                    accessInstructions =
                        accessInstructions,
                    projectSpecificNotes =
                        projectSpecificNotes,
                )
            }.onSuccess { attached ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onSuccess
                val previousItems =
                    current.projects
                        ?.selectedSites
                        ?.items
                        .orEmpty()
                val nextSites =
                    current.projects
                        ?.selectedSites
                        ?.copy(
                            items =
                                previousItems
                                    .filterNot {
                                        it.projectSiteId ==
                                            attached.projectSite
                                                .projectSiteId
                                    } +
                                    attached.projectSite,
                        )
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    selectedProject =
                                        attached.project,
                                    selectedSites =
                                        nextSites,
                                    errorCode = null,
                                    errorMessage = null,
                                ),
                    ),
                )
            }.onFailure { failure ->
                val current =
                    shellState.value as?
                        HiltechShellState.SignedIn
                    ?: return@onFailure
                setState(
                    current.copy(
                        projects =
                            (current.projects
                                ?: HiltechProjectsState())
                                .copy(
                                    loading = false,
                                    errorCode =
                                        (failure as?
                                            HiltechApiException)
                                            ?.code,
                                    errorMessage =
                                        failure.message,
                                ),
                    ),
                )
            }
        }
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
                refreshPeople()
                refreshOnboarding()
                refreshProjects()
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
                onRefreshPeople = ::refreshPeople,
                onRefreshOnboarding =
                    ::refreshOnboarding,
                onSelectEmployee = ::selectEmployee,
                onChangeWorkforceAssignment =
                    ::changeWorkforceAssignment,
                onStartOffboarding =
                    ::startOffboarding,
                onRevokeOffboardingAccess =
                    ::revokeOffboardingAccess,
                onResolveOffboardingClearance =
                    ::resolveOffboardingClearance,
                onCompleteOffboarding =
                    ::completeOffboarding,
                onCreateEmployee = ::createEmployee,
                onRefreshProjects =
                    ::refreshProjects,
                onSelectProject =
                    ::selectProject,
                onCreateProject =
                    ::createProject,
                onChangeProjectManager =
                    ::changeProjectManager,
                onStartProjectKickoff = {
                    projectId,
                    baseVersion,
                    ->
                    runProjectTransition(
                        projectId,
                        baseVersion,
                        false,
                    )
                },
                onCompleteProjectKickoff = {
                    projectId,
                    baseVersion,
                    ->
                    runProjectTransition(
                        projectId,
                        baseVersion,
                        true,
                    )
                },
                onCreateSite = ::createSite,
                onAttachProjectSite =
                    ::attachProjectSite,
            )
        }
    }
}

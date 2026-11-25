package com.hiltech.desktop

import com.hiltech.shared.core.AssetAction
import com.hiltech.shared.core.AssetInventoryState
import com.hiltech.shared.core.StockAction
import com.hiltech.shared.core.StockInventoryState

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hiltech.shared.core.HiltechOnboardingState
import com.hiltech.shared.core.HiltechPeopleState
import com.hiltech.shared.core.HiltechProjectsState
import com.hiltech.shared.core.HiltechWarehouseState
import com.hiltech.shared.core.WarehouseImportReviewDraft
import com.hiltech.shared.core.WarehousePhysicalOperatorSection
import com.hiltech.shared.core.procurement.PurchaseRequirementOperatorSection
import com.hiltech.shared.core.procurement.WarehouseReplenishmentSection
import com.hiltech.shared.core.procurement.OfficeStockNeedSection
import com.hiltech.shared.core.procurement.AssetPurchaseNeedSection
import com.hiltech.shared.core.procurement.SupplierSourcingBuyerSection
import com.hiltech.shared.core.procurement.PurchaseOrderIssuerSection
import com.hiltech.shared.core.procurement.SupplierInvoiceFinanceSection
import com.hiltech.shared.core.procurement.SupplierPoCommunicationSection
import com.hiltech.shared.core.procurement.PoWarehouseIntakeSection
import com.hiltech.shared.core.executive.executiveNativeWorkspace
import com.hiltech.shared.core.payroll.ownPayslipNativeWorkspace
import com.hiltech.shared.core.payroll.payrollAdminReadNativeWorkspace
import com.hiltech.shared.core.commercial.commercialReadNativeWorkspace
import com.hiltech.shared.core.service.handoverReadNativeWorkspace
import com.hiltech.shared.core.service.serviceOperationsReadNativeWorkspace
import com.hiltech.shared.core.finance.financeEvidenceReadNativeWorkspace
import com.hiltech.shared.core.finance.financeBankCsvIntakeNativeWorkspace
import com.hiltech.shared.core.finance.financeCompletionOperatorWorkspace
import com.hiltech.shared.core.finance.financeCashboxOpeningWorkspace
import com.hiltech.shared.core.finance.FinanceBankCsvSelectedFile
import com.hiltech.shared.core.procurement.supplierInvoiceFinanceReadWorkspace
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.NativeOperationArea
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.NativeOperationAreas
import com.hiltech.shared.core.procurement.servicePurchaseAnyCurrentSourceVisible
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.PlanningUiAction
import com.hiltech.shared.core.PlanningFormHost
import com.hiltech.shared.core.PlanningFormOperation
import com.hiltech.shared.core.PlanningFormRequest
import com.hiltech.shared.core.PlanningFormResult
import com.hiltech.shared.core.planningFormResponse
import com.hiltech.shared.core.planningFormFailure
import com.hiltech.shared.core.projects.projectId
import com.hiltech.shared.core.ProjectCommandCenterUiAction
import com.hiltech.shared.core.WorkPlanningUiAction
import com.hiltech.shared.core.WorkReadinessUiAction
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.inbox.InboxWorkspace
import com.hiltech.shared.core.people.EmployeeWarehouseResponsibilitiesWorkspace
import com.hiltech.shared.core.people.EmployeeAccountWorkspace
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

fun main() {
    val operationsProfile = (System.getenv("HILTECH_PRODUCT_PROFILE")
        ?: System.getProperty("hiltech.productProfile")) == "operations"
    val executiveHomeAvailable = System.getenv("HILTECH_EXECUTIVE_HOME_UI_ENABLED")
        ?.equals("true", ignoreCase = true) ?: operationsProfile
    val purchasingAvailable = listOf(
        "HILTECH_SERVICE_PURCHASES_UI_ENABLED",
        "HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED",
        "HILTECH_SUPPLIER_SOURCING_UI_ENABLED",
        "HILTECH_PO_ISSUER_UI_ENABLED",
        "HILTECH_ASSET_PURCHASE_NEEDS_ENABLED",
        "HILTECH_OFFICE_STOCK_NEEDS_ENABLED",
        "HILTECH_WAREHOUSE_REPLENISHMENT_ENABLED",
        "HILTECH_PO_WAREHOUSE_INTAKE_UI_ENABLED",
        "HILTECH_SUPPLIER_INVOICE_FINANCE_UI_ENABLED",
    ).any { System.getenv(it).equals("true", ignoreCase = true) }
    val runtime = DesktopIdentityRuntime(
        apiBaseUrl =
            System.getenv("HILTECH_API_BASE_URL")
                ?: System.getProperty("hiltech.apiBaseUrl")
                ?: "",
        oidcIssuer =
            System.getenv("HILTECH_OIDC_ISSUER_URI")
                ?: System.getProperty("hiltech.oidcIssuerUri")
                ?: "",
        oidcClientId =
            System.getenv("HILTECH_OIDC_CLIENT_ID")
                ?: System.getProperty("hiltech.oidcClientId")
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

                failure is LinkageError ->
                    HiltechShellState.Failure(
                        code = "IDENTITY_RUNTIME_COMPONENT_MISSING",
                        message = "This installation is missing a sign-in component. Install the latest HILTECH update.",
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

    var planningSessionEpoch by mutableIntStateOf(0)
    var employeeReadSequence = 0L
    var planningCommandsJob = SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    fun invalidatePlanningCommands() {
        planningSessionEpoch += 1
        planningCommandsJob.cancel()
        planningCommandsJob = SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    }
    fun planningSessionScope(signed: HiltechShellState.SignedIn): String {
        val org = signed.identity.organizations.firstOrNull { it.primary }
            ?: signed.identity.organizations.firstOrNull()
        return "${signed.identity.identityId}:${signed.identity.identityVersion}:" +
            "${org?.organizationId}:${org?.membershipVersion}:$planningSessionEpoch"
    }

    fun showSignedIn(
        identity: IdentityBootstrapDto,
    ) {
        invalidatePlanningCommands()
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
                warehouse =
                    HiltechWarehouseState(
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
        val expectedSessionScope = planningSessionScope(signed)
        val expectedOrganizationId = (signed.identity.organizations.firstOrNull { it.primary }
            ?: signed.identity.organizations.firstOrNull())?.organizationId ?: return
        val requestSequence = ++employeeReadSequence
        val existingPeople = signed.people ?: HiltechPeopleState()
        val sameEmployee = existingPeople.selectedEmployee?.employeeId == employeeId

        setState(
            signed.copy(
                people = existingPeople.copy(
                    loading = true,
                    errorMessage = null,
                    selectedEmployee = existingPeople.selectedEmployee.takeIf { sameEmployee },
                    selectedWorkforceAssignment = existingPeople.selectedWorkforceAssignment.takeIf { sameEmployee },
                    selectedWorkforceHistory = existingPeople.selectedWorkforceHistory.takeIf { sameEmployee },
                    selectedOffboarding = existingPeople.selectedOffboarding.takeIf { sameEmployee },
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
            if (current == null || planningSessionScope(current) != expectedSessionScope ||
                employeeReadSequence != requestSequence) {
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
                                        selectedEmployee = current.people?.selectedEmployee?.takeIf {
                                            sameEmployee && it.employeeId == employeeId &&
                                                com.hiltech.shared.core.people.canRetainEmployeeAfterRefreshFailure(failure)
                                        },
                                        errorMessage =
                                            failure.message
                                                ?: "Employee detail could not be loaded.",
                                    ),
                        ),
                    )
                    return@launch
                }

            if (detail.employeeId != employeeId || detail.organizationId != expectedOrganizationId) {
                setState(current.copy(people = (current.people ?: HiltechPeopleState()).copy(
                    loading = false,
                    selectedEmployee = null,
                    selectedWorkforceAssignment = null,
                    selectedWorkforceHistory = null,
                    selectedOffboarding = null,
                    errorMessage = "Employee details could not be verified. Refresh the record.",
                )))
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

    fun refreshWarehouses() {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val organizationId = signed.identity.organizations
            .firstOrNull { it.primary }?.organizationId
            ?: signed.identity.organizations.firstOrNull()?.organizationId
            ?: return
        val selectedId = signed.warehouse?.selectedWarehouseId
        setState(
            signed.copy(
                warehouse = (signed.warehouse ?: HiltechWarehouseState()).copy(
                    loading = true, errorCode = null, errorMessage = null,
                ),
            ),
        )
        scope.launch {
            val directory = runCatching { runtime.warehouses(organizationId) }
            val imports = runCatching { runtime.warehouseImportBatches(organizationId) }
            val actualSelected = directory.getOrNull()?.items?.let { items ->
                selectedId?.takeIf { id -> items.any { it.warehouseId == id } }
                    ?: items.firstOrNull()?.warehouseId
            }
            val locations = actualSelected?.let {
                runCatching { runtime.warehouseLocations(it) }
            }
            val authority = actualSelected?.let {
                runCatching { runtime.currentWarehouseAuthority(it) }
            }
            val current = shellState.value as? HiltechShellState.SignedIn
            if (current != null &&
                current.identity.identityId == signed.identity.identityId
            ) {
                setState(
                    current.copy(
                        warehouse = HiltechWarehouseState(
                            directory = directory.getOrNull(),
                            selectedWarehouseId = actualSelected,
                            locations = locations?.getOrNull(),
                            currentAuthority = authority?.getOrNull(),
                            importDirectory = imports.getOrNull(),
                            selectedImport = current.warehouse?.selectedImport,
                            stock = current.warehouse?.stock ?: StockInventoryState(),
                            assets = current.warehouse?.takeIf { it.selectedWarehouseId == actualSelected }?.assets
                                ?: AssetInventoryState(),
                            errorCode = (directory.exceptionOrNull() as? HiltechApiException)?.code,
                            errorMessage = directory.exceptionOrNull()?.message,
                        ),
                    ),
                )
            }
        }
    }

    fun selectWarehouse(warehouseId: String) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        setState(
            signed.copy(
                warehouse = (signed.warehouse ?: HiltechWarehouseState()).copy(
                    selectedWarehouseId = warehouseId,
                    assets = AssetInventoryState(),
                    loading = true,
                    locations = null,
                    currentAuthority = null,
                    errorCode = null,
                    errorMessage = null,
                ),
            ),
        )
        scope.launch {
            val locations = runCatching { runtime.warehouseLocations(warehouseId) }
            val authority = runCatching { runtime.currentWarehouseAuthority(warehouseId) }
            val current = shellState.value as? HiltechShellState.SignedIn
            if (current != null &&
                current.identity.identityId == signed.identity.identityId &&
                current.warehouse?.selectedWarehouseId == warehouseId
            ) {
                setState(
                    current.copy(
                        warehouse = (current.warehouse ?: HiltechWarehouseState()).copy(
                            loading = false,
                            locations = locations.getOrNull(),
                            currentAuthority = authority.getOrNull(),
                            errorCode = (locations.exceptionOrNull() as? HiltechApiException)?.code,
                            errorMessage = locations.exceptionOrNull()?.message,
                        ),
                    ),
                )
            }
        }
    }

    data class PendingAssetCommand(val actor: String, val warehouse: String, val action: AssetAction.Command, val operationId: String)
    var pendingAssetCommand: PendingAssetCommand? = null
    var assetRequestSequence = 0

    fun applyAssetAction(action: AssetAction) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val setup = signed.warehouse ?: return
        val facility = setup.selectedWarehouseId ?: return
        val assets = setup.assets ?: return
        if (assets.loading) return
        val organization = signed.identity.organizations.firstOrNull { it.primary }
            ?: signed.identity.organizations.firstOrNull() ?: return
        val actor = signed.identity.identityId
        val command = (action as? AssetAction.Command)?.let {
            pendingAssetCommand?.takeIf { prior -> prior.actor == actor && prior.warehouse == facility && prior.action == it }
                ?: PendingAssetCommand(actor, facility, it, java.util.UUID.randomUUID().toString())
        }
        if (command != null) pendingAssetCommand = command
        val request = ++assetRequestSequence
        setState(signed.copy(warehouse = setup.copy(assets = assets.copy(loading = true, message = null,
            dueReturns = if (action == AssetAction.RefreshDueReturns || command != null) null else assets.dueReturns))))
        scope.launch {
            val result = runCatching {
                val refreshed = runtime.applyAssetAction(organization.organizationId, facility, assets, action, command?.operationId.orEmpty())
                val batch = (action as? AssetAction.Approve)?.let { runtime.warehouseImportBatch(it.batchId) }
                refreshed to batch
            }
            val current = shellState.value as? HiltechShellState.SignedIn ?: return@launch
            val currentSetup = current.warehouse ?: return@launch
            if (current.identity.identityId != actor || currentSetup.selectedWarehouseId != facility ||
                request != assetRequestSequence || currentSetup.assets?.loading != true) return@launch
            val updated = result.fold(
                onSuccess = { (refreshed, batch) ->
                    if (command != null && pendingAssetCommand == command) pendingAssetCommand = null
                    val selectedBatch = batch?.takeIf { it.batchId == currentSetup.selectedImport?.batchId }
                    currentSetup.copy(selectedImport = selectedBatch ?: currentSetup.selectedImport,
                        assets = refreshed.copy(loading = false, message = if (command == null) null else "Asset command recorded."))
                },
                onFailure = {
                    val message = (it as? HiltechApiException)?.message ?: "Asset request failed. Try again."
                    val fallback = if ((it as? HiltechApiException)?.code == "ASSET_UNAVAILABLE") AssetInventoryState() else assets
                    currentSetup.copy(assets = fallback.copy(loading = false, message = message,
                        dueReturns = if (action == AssetAction.RefreshDueReturns || command != null) null else fallback.dueReturns))
                },
            )
            setState(current.copy(warehouse = updated))
        }
    }

    data class PendingStockCommand(
        val actor: String, val warehouseId: String?, val action: StockAction, val operationId: String,
    )
    var pendingStockCommand: PendingStockCommand? = null

    fun applyStockAction(action: StockAction) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val setup = signed.warehouse ?: return
        val stock = setup.stock ?: return
        if (stock.loading) return
        val organization = signed.identity.organizations.firstOrNull { it.primary }
            ?: signed.identity.organizations.firstOrNull() ?: return
        val actor = signed.identity.identityId
        val isMutation = action !is StockAction.Refresh && action !is StockAction.Select && action !is StockAction.More && action !is StockAction.LoadIssued && action !is StockAction.LoadFulfillmentHistory
        val operation = pendingStockCommand?.takeIf {
            it.actor == actor && it.action == action && it.warehouseId == setup.selectedWarehouseId
        } ?: PendingStockCommand(actor, setup.selectedWarehouseId, action, java.util.UUID.randomUUID().toString())
        if (isMutation) pendingStockCommand = operation
        setState(signed.copy(warehouse = setup.copy(stock =
            if (action is StockAction.LoadFulfillmentHistory) stock.copy(loading = true, message = null, fulfillmentHistory = null)
            else stock.copy(loading = true, message = null))))
        scope.launch {
            val result = runCatching {
                val refreshed = runtime.applyStockAction(
                    organization.organizationId, setup.selectedWarehouseId, stock, action, operation.operationId,
                )
                val batch = (action as? StockAction.Approve)?.let { runtime.warehouseImportBatch(it.batchId) }
                refreshed to batch
            }
            val current = shellState.value as? HiltechShellState.SignedIn ?: return@launch
            val currentSetup = current.warehouse ?: return@launch
            if (current.identity.identityId != actor || currentSetup.selectedWarehouseId != setup.selectedWarehouseId) return@launch
            val updated = result.fold(
                onSuccess = { (refreshed, batch) ->
                    if (pendingStockCommand == operation) pendingStockCommand = null
                    val selectedBatch = batch?.takeIf { it.batchId == currentSetup.selectedImport?.batchId }
                    currentSetup.copy(selectedImport = selectedBatch ?: currentSetup.selectedImport,
                        stock = refreshed.copy(loading = false, message = if (isMutation) "Stock command recorded" else null))
                },
                onFailure = { failure ->
                    currentSetup.copy(stock = stock.copy(loading = false, message = failure.message ?: "Stock request failed.",
                        fulfillmentHistory = if (action is StockAction.LoadFulfillmentHistory) null else stock.fulfillmentHistory))
                },
            )
            setState(current.copy(warehouse = updated))
        }
    }

    fun mutateWarehouse(action: suspend () -> Unit) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        setState(
            signed.copy(
                warehouse = (signed.warehouse ?: HiltechWarehouseState()).copy(
                    loading = true, errorCode = null, errorMessage = null,
                ),
            ),
        )
        scope.launch {
            runCatching { action() }.onSuccess {
                refreshWarehouses()
            }.onFailure { failure ->
                val current = shellState.value as? HiltechShellState.SignedIn
                if (current != null &&
                    current.identity.identityId == signed.identity.identityId
                ) {
                    setState(
                        current.copy(
                            warehouse = (current.warehouse ?: HiltechWarehouseState()).copy(
                                loading = false,
                                errorCode = (failure as? HiltechApiException)?.code,
                                errorMessage = failure.message ?: "Warehouse command failed.",
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun createWarehouse(code: String, name: String) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val organizationId = signed.identity.organizations
            .firstOrNull { it.primary }?.organizationId
            ?: signed.identity.organizations.firstOrNull()?.organizationId
            ?: return
        mutateWarehouse { runtime.createWarehouse(organizationId, code, name) }
    }

    fun createWarehouseLocation(code: String, name: String) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val organizationId = signed.identity.organizations
            .firstOrNull { it.primary }?.organizationId
            ?: signed.identity.organizations.firstOrNull()?.organizationId
            ?: return
        val warehouseId = signed.warehouse?.selectedWarehouseId ?: return
        mutateWarehouse {
            runtime.createWarehouseLocation(organizationId, warehouseId, code, name)
        }
    }

    fun openWarehouseImport(batchId: String) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        setState(
            signed.copy(
                warehouse = (signed.warehouse ?: HiltechWarehouseState()).copy(
                    loading = true, errorCode = null, errorMessage = null,
                ),
            ),
        )
        scope.launch {
            val result = runCatching { runtime.warehouseImportBatch(batchId) }
            val current = shellState.value as? HiltechShellState.SignedIn
            if (current != null &&
                current.identity.identityId == signed.identity.identityId
            ) {
                setState(
                    current.copy(
                        warehouse = (current.warehouse ?: HiltechWarehouseState()).copy(
                            loading = false,
                            selectedImport = result.getOrNull(),
                            errorCode = (result.exceptionOrNull() as? HiltechApiException)?.code,
                            errorMessage = result.exceptionOrNull()?.message,
                        ),
                    ),
                )
            }
        }
    }

    fun reviewWarehouseImportRow(draft: WarehouseImportReviewDraft) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        if (signed.warehouse?.selectedImport?.batchId != draft.batchId) return
        setState(
            signed.copy(
                warehouse = (signed.warehouse ?: HiltechWarehouseState()).copy(
                    loading = true,
                    errorCode = null,
                    errorMessage = null,
                ),
            ),
        )
        scope.launch {
            val result = runCatching {
                runtime.reviewWarehouseImportRow(draft)
                runtime.warehouseImportBatch(draft.batchId)
            }
            val current = shellState.value as? HiltechShellState.SignedIn
            if (current != null &&
                current.identity.identityId == signed.identity.identityId &&
                current.warehouse?.selectedImport?.batchId == draft.batchId
            ) {
                setState(
                    current.copy(
                        warehouse = (current.warehouse ?: HiltechWarehouseState()).copy(
                            loading = false,
                            selectedImport = result.getOrNull()
                                ?: current.warehouse?.selectedImport,
                            errorCode = (result.exceptionOrNull() as? HiltechApiException)?.code,
                            errorMessage = result.exceptionOrNull()?.message,
                        ),
                    ),
                )
            }
        }
    }

    fun grantWarehouseAuthority(key: String, principalId: String) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val warehouseId = signed.warehouse?.selectedWarehouseId ?: return
        mutateWarehouse {
            runtime.grantWarehouseAuthority(warehouseId, key, principalId)
        }
    }

    fun endWarehouseAuthority(bindingId: String, baseVersion: Long) {
        val signed = shellState.value as? HiltechShellState.SignedIn ?: return
        val warehouseId = signed.warehouse?.selectedWarehouseId ?: return
        mutateWarehouse {
            runtime.endWarehouseAuthority(warehouseId, bindingId, baseVersion)
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
            val planResult =
                selectedId?.let {
                    runCatching {
                        runtime.projectPlan(it)
                    }
                }
            val workOrdersResult =
                selectedId?.let {
                    runCatching {
                        runtime.workOrders(it)
                    }
                }
            val workTypesResult =
                selectedId?.let {
                    runCatching {
                        runtime.workTypes(it)
                    }
                }
            val workReadinessResult =
                workOrdersResult
                    ?.getOrNull()
                    ?.let { orders ->
                        runCatching {
                            orders.associate { order ->
                                order.workOrderId to
                                    runtime.workReadiness(
                                        order.workOrderId,
                                    )
                            }
                        }
                    }
            val workQueueResult =
                selectedId?.let {
                    runCatching {
                        runtime.workQueueContext(it)
                    }
                }
            val commandCenterResult =
                selectedId?.let {
                    runCatching {
                        runtime.projectCommandCenter(it)
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
                    ?: planResult
                        ?.exceptionOrNull()
                    ?: workOrdersResult
                        ?.exceptionOrNull()
                    ?: workTypesResult
                        ?.exceptionOrNull()
                    ?: workReadinessResult
                        ?.exceptionOrNull()
                    ?: workQueueResult
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
                            plan =
                                planResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.plan,
                            workOrders =
                                workOrdersResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.workOrders
                                    ?: emptyList(),
                            workTypes =
                                workTypesResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.workTypes
                                    ?: emptyList(),
                            workReadinessByOrder =
                                workReadinessResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.workReadinessByOrder
                                    ?: emptyMap(),
                            workQueueContext =
                                workQueueResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.workQueueContext
                                    ?: emptyList(),
                            commandCenter =
                                commandCenterResult
                                    ?.getOrNull()
                                    ?: current.projects
                                        ?.commandCenter,
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
            val planResult =
                runCatching {
                    runtime.projectPlan(
                        projectId,
                    )
                }
            val workOrdersResult =
                runCatching {
                    runtime.workOrders(
                        projectId,
                    )
                }
            val workTypesResult =
                runCatching {
                    runtime.workTypes(
                        projectId,
                    )
                }
            val selectedWorkReadinessResult =
                workOrdersResult
                    .getOrNull()
                    ?.let { orders ->
                        runCatching {
                            orders.associate { order ->
                                order.workOrderId to
                                    runtime.workReadiness(
                                        order.workOrderId,
                                    )
                            }
                        }
                    }
            val selectedWorkQueueResult =
                runCatching {
                    runtime.workQueueContext(
                        projectId,
                    )
                }
            val selectedCommandCenterResult =
                runCatching {
                    runtime.projectCommandCenter(
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
                    ?: planResult.exceptionOrNull()
                    ?: workOrdersResult.exceptionOrNull()
                    ?: workTypesResult.exceptionOrNull()
                    ?: selectedWorkReadinessResult
                        ?.exceptionOrNull()
                    ?: selectedWorkQueueResult
                        .exceptionOrNull()

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
                                plan =
                                    planResult
                                        .getOrNull(),
                                workOrders =
                                    workOrdersResult
                                        .getOrNull()
                                        ?: emptyList(),
                                workTypes =
                                    workTypesResult
                                        .getOrNull()
                                        ?: emptyList(),
                                workReadinessByOrder =
                                    selectedWorkReadinessResult
                                        ?.getOrNull()
                                        ?: emptyMap(),
                                workQueueContext =
                                    selectedWorkQueueResult
                                        .getOrNull()
                                        ?: emptyList(),
                                commandCenter =
                                    selectedCommandCenterResult
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

    fun newPlanningOperation() = PlanningFormOperation(
        java.util.UUID.randomUUID().toString(), java.time.Instant.now().toString(),
    )

    fun runPlanningRequest(
        expectedScope: String,
        request: PlanningFormRequest,
        onResult: (PlanningFormResult) -> Unit,
    ) {
        val signed = shellState.value as? HiltechShellState.SignedIn
        val projectId = request.action.projectId()
        if (signed == null || planningSessionScope(signed) != expectedScope ||
            signed.projects?.selectedProject?.projectId != projectId
        ) {
            onResult(PlanningFormResult.Rejected("PLANNING_SESSION_CHANGED", null, null))
            return
        }
        fun sessionCurrent(): Boolean {
            val active = shellState.value as? HiltechShellState.SignedIn ?: return false
            return planningSessionScope(active) == expectedScope && active.projects?.selectedProject?.projectId == projectId
        }
        CoroutineScope(scope.coroutineContext + planningCommandsJob).launch {
            if (!sessionCurrent()) return@launch
            val result = try {
                Result.success(runtime.submitPlanningForm(request, ::sessionCurrent))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            // Refresh a conflicting version without replacing any form draft or rebasing it implicitly.
            val freshPlan = if ((result.exceptionOrNull() as? HiltechApiException)?.httpStatus == 409) {
                try { runtime.projectPlan(projectId) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } else null
            SwingUtilities.invokeLater {
                val current = shellState.value as? HiltechShellState.SignedIn ?: return@invokeLater
                if (planningSessionScope(current) != expectedScope ||
                    current.projects?.selectedProject?.projectId != projectId
                ) return@invokeLater
                val response = result.getOrNull()
                val confirmed = response?.let { planningFormResponse(request, it) }
                val validResponse = (confirmed as? PlanningFormResult.Succeeded)?.response
                val incomingPlan = validResponse?.plan ?: freshPlan
                if (incomingPlan != null && incomingPlan.project.projectId == projectId &&
                    incomingPlan.project.version >= (current.projects?.plan?.project?.version ?: 0)
                ) {
                    val projects = requireNotNull(current.projects)
                    val selected = projects.selectedProject?.copy(
                        lifecycleState = incomingPlan.project.lifecycleState,
                        baselineVersion = incomingPlan.project.baselineVersion,
                        version = incomingPlan.project.version,
                    )
                    shellState.value = current.copy(projects = projects.copy(
                        plan = incomingPlan, selectedProject = selected,
                        list = projects.list?.let { currentList -> currentList.copy(items = currentList.items.map {
                            if (it.projectId == projectId && selected != null) selected else it
                        }) }, errorCode = null, errorMessage = null,
                    ))
                }
                onResult(when {
                    confirmed != null -> confirmed
                    else -> planningFormFailure(requireNotNull(result.exceptionOrNull()))
                })
            }
        }
    }

    fun runWorkPlanningAction(
        action: WorkPlanningUiAction,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val projectId =
            when (action) {
                is WorkPlanningUiAction.Create ->
                    action.projectId
                is WorkPlanningUiAction.Activate ->
                    action.projectId
                else ->
                    signed.projects
                        ?.selectedProject
                        ?.projectId
                        ?: return
            }

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
                when (action) {
                    is WorkPlanningUiAction.Create ->
                        runtime.createWorkOrder(
                            action.projectId,
                            action.siteId,
                            action.projectSiteId,
                            action.areaId,
                            action.workPackageId,
                            action.workTypeCode,
                            action.workTypeRevision,
                            action.title,
                            action.description,
                            action.plannedStart,
                            action.plannedEnd,
                            action.priorityCode,
                            action.baseProjectVersion,
                            action.baselineVersion,
                        )

                    is WorkPlanningUiAction.Update ->
                        runtime.updateWorkOrder(
                            action.workOrderId,
                            action.areaId,
                            action.workPackageId,
                            action.title,
                            action.description,
                            action.plannedStart,
                            action.plannedEnd,
                            action.priorityCode,
                            action.baseVersion,
                        )

                    is WorkPlanningUiAction.Plan ->
                        runtime.planWork(
                            action.workOrderId,
                            action.workTypeCode,
                            action.workTypeRevision,
                            action.instructionJson,
                            action.instructionSummary,
                            action.baseVersion,
                            action.baselineVersion,
                        )

                    is WorkPlanningUiAction.ReviseInstruction ->
                        runtime.reviseWorkInstruction(
                            action.workOrderId,
                            action.instructionJson,
                            action.summary,
                            action.changeReason,
                            action.baseVersion,
                        )

                    is WorkPlanningUiAction.CreateTask ->
                        runtime.createWorkTask(
                            action.workOrderId,
                            action.taskCode,
                            action.title,
                            action.sortOrder,
                            action.mandatory,
                            action.baseVersion,
                        )

                    is WorkPlanningUiAction.UpdateTask ->
                        runtime.updateWorkTask(
                            action.taskId,
                            action.taskCode,
                            action.title,
                            action.description,
                            action.sortOrder,
                            action.mandatory,
                            action.estimatedDurationMinutes,
                            action.evidenceRequirementKey,
                            action.state,
                            action.baseTaskVersion,
                            action.baseWorkOrderVersion,
                        )

                    is WorkPlanningUiAction.AddDependency ->
                        runtime.addWorkDependency(
                            action.workOrderId,
                            action.predecessorWorkOrderId,
                            action.lagMinutes,
                            action.baseVersion,
                        )

                    is WorkPlanningUiAction.RemoveDependency ->
                        runtime.removeWorkDependency(
                            action.workOrderId,
                            action.dependencyId,
                            action.baseVersion,
                            action.baseDependencyVersion,
                        )

                    is WorkPlanningUiAction.Activate ->
                        runtime.activateProject(
                            action.projectId,
                            action.baseVersion,
                            action.baselineVersion,
                        )
                }
            }.onSuccess {
                selectProject(projectId)
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

    fun runWorkReadinessAction(
        action: WorkReadinessUiAction,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val projectId =
            signed.projects
                ?.selectedProject
                ?.projectId
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
                when (action) {
                    is WorkReadinessUiAction.Evaluate ->
                        runtime.evaluateWorkReadiness(
                            action.workOrderId,
                            action.baseVersion,
                        )

                    is WorkReadinessUiAction.Assign ->
                        runtime.assignWork(
                            action.workOrderId,
                            action.targetType,
                            action.targetId,
                            action.baseVersion,
                        )

                    is WorkReadinessUiAction.Reassign ->
                        runtime.reassignWork(
                            action.workOrderId,
                            action.targetType,
                            action.targetId,
                            action.baseVersion,
                            action.currentAssignmentId,
                            action.baseAssignmentVersion,
                            action.reason,
                        )

                    is WorkReadinessUiAction.Waive ->
                        runtime.waiveWorkReadiness(
                            action.workOrderId,
                            action.requirementId,
                            action.baseWorkOrderVersion,
                            action.baseRequirementVersion,
                            action.reason,
                        )
                }
            }.onSuccess {
                selectProject(projectId)
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

    fun runProjectCommandCenterAction(
        action: ProjectCommandCenterUiAction,
    ) {
        val signed =
            shellState.value as?
                HiltechShellState.SignedIn
                ?: return
        val projectId =
            signed.projects
                ?.selectedProject
                ?.projectId
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
                when (action) {
                    is ProjectCommandCenterUiAction.Accept ->
                        runtime.acceptWork(
                            action.workOrderId,
                            action.baseVersion,
                            action.reason,
                        )

                    is ProjectCommandCenterUiAction.RequestRework ->
                        runtime.requestWorkRework(
                            action.workOrderId,
                            action.baseVersion,
                            action.reason,
                        )

                    is ProjectCommandCenterUiAction.PutOnHold ->
                        runtime.putProjectOnHold(
                            action.projectId,
                            action.baseVersion,
                            action.reason,
                        )

                    is ProjectCommandCenterUiAction.Resume ->
                        runtime.resumeProject(
                            action.projectId,
                            action.baseVersion,
                            action.resolution,
                        )
                }
            }.onSuccess {
                selectProject(projectId)
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
        invalidatePlanningCommands()
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
                // setState posts the signed-in state to Swing EDT. Queue the
                // Warehouse refresh after that state update, not before it.
                SwingUtilities.invokeLater { refreshWarehouses() }
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
        invalidatePlanningCommands()
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
        var desktopFindRequest by remember { mutableIntStateOf(0) }
        Window(
            onCloseRequest = {
                scope.cancel()
                runtime.close()
                exitApplication()
            },
            title = "HILTECH",
            state = rememberWindowState(width = 1120.dp, height = 760.dp),
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K &&
                    shellState.value is HiltechShellState.SignedIn
                ) {
                    desktopFindRequest += 1
                    true
                } else false
            },
        ) {
            val navigationIdentity = (shellState.value as? HiltechShellState.SignedIn)?.identity
            val navigationOrganization = navigationIdentity?.organizations?.firstOrNull { it.primary }
                ?: navigationIdentity?.organizations?.firstOrNull()
            val nativeProjectScope = navigationIdentity?.identityId.orEmpty() + ":" +
                navigationOrganization?.organizationId.orEmpty()
            var executiveProjectFocus by remember(nativeProjectScope) { mutableIntStateOf(0) }
            val employeeSessionScope = (shellState.value as? HiltechShellState.SignedIn)?.let(::planningSessionScope).orEmpty()
            val employeeDrafts = androidx.compose.runtime.key(employeeSessionScope) {
                androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            }
            val languagePreferences = remember { java.util.prefs.Preferences.userRoot().node("com/hiltech/desktop/preferences") }
            var appLanguage by remember { mutableStateOf(HiltechLanguage.fromCode(languagePreferences.get("appLanguage", null))) }
            HiltechShell(
                state = shellState.value,
                language = appLanguage,
                onLanguageChange = { choice ->
                    appLanguage = choice
                    languagePreferences.put("appLanguage", choice.code)
                },
                projectFocusRequest = executiveProjectFocus,
                desktopNavigation = true,
                desktopFindRequest = desktopFindRequest,
                desktopPurchasingAvailable = purchasingAvailable,
                employeeAccountWorkspace = { employee ->
                    navigationIdentity?.let { identity ->
                        employeeDrafts.SaveableStateProvider("$employeeSessionScope:${employee.employeeId}") {
                        EmployeeAccountWorkspace(
                            employee = employee,
                            sessionScope = employeeSessionScope,
                            client = runtime.onboardingApi,
                            installationId = runtime.physicalWarehouseInstallationId,
                            newOperationId = { java.util.UUID.randomUUID().toString() },
                            onReauthenticate = { signIn(true) },
                            onAccountReady = { selectEmployee(employee.employeeId) },
                        )
                        EmployeeWarehouseResponsibilitiesWorkspace(
                            employee = employee,
                            sessionScope = employeeSessionScope,
                            client = runtime.physicalWarehouseApi,
                            installationId = runtime.physicalWarehouseInstallationId,
                            newOperationId = { java.util.UUID.randomUUID().toString() },
                            nowUtc = { java.time.Instant.now().toString() },
                        )
                        }
                    }
                },
                employeeOnboardingWorkspace = { employee ->
                    val expectedScope = employeeSessionScope
                    val expectedEmployeeId = employee.employeeId
                    val sessionCurrent = {
                        val current = shellState.value as? HiltechShellState.SignedIn
                        current != null && planningSessionScope(current) == expectedScope &&
                            current.people?.selectedEmployee?.employeeId == expectedEmployeeId
                    }
                    employeeDrafts.SaveableStateProvider("onboarding:$expectedScope:$expectedEmployeeId") {
                        com.hiltech.shared.core.people.EmployeeOnboardingWorkspace(
                            employee = employee,
                            sessionScope = expectedScope,
                            client = runtime.onboardingApi,
                            installationId = runtime.physicalWarehouseInstallationId,
                            newOperationId = { java.util.UUID.randomUUID().toString() },
                            isSessionCurrent = sessionCurrent,
                            submitCommand = { intent, unknown, sending ->
                                runtime.submitEmployeeOnboarding(intent, unknown, sessionCurrent, sending)
                            },
                            onEmployeeChanged = {
                                if (sessionCurrent()) selectEmployee(expectedEmployeeId)
                            },
                        )
                    }
                },
                inboxWorkspace = {
                    navigationIdentity?.let { identity ->
                        InboxWorkspace(
                            sessionScope = "${identity.identityId}:${identity.identityVersion}",
                            inbox = runtime.inboxApi,
                            approvals = runtime.approvalApi,
                            installationId = runtime.physicalWarehouseInstallationId,
                            newOperationId = { java.util.UUID.randomUUID().toString() },
                        )
                    }
                },
                operationalWorkspaces = (shellState.value as? HiltechShellState.SignedIn)?.let { signed ->
                    val org = signed.identity.organizations.firstOrNull { it.primary }
                        ?: signed.identity.organizations.firstOrNull()
                    if (org == null) emptyList() else buildList {
                        if (signed.identity.productCapabilities?.executiveHome ?: executiveHomeAvailable) {
                            add(executiveNativeWorkspace(
                                client = runtime.executiveApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                authorityRevisionKey =
                                    signed.identity.identityVersion.toString() + ":" +
                                        org.membershipVersion.toString(),
                                onOpenProject = { projectId ->
                                    // Original Projects API performs its own authorization.
                                    selectProject(projectId)
                                    executiveProjectFocus += 1
                                },
                            ))
                        }
                        run {
                            add(ownPayslipNativeWorkspace(
                                client = runtime.payrollOwnApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                            ))
                        }
                        if (System.getenv("HILTECH_PAYROLL_ADMIN_READ_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(payrollAdminReadNativeWorkspace(
                                client = runtime.payrollAdminApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                            ))
                        }
                        run {
                            add(commercialReadNativeWorkspace(
                                client = runtime.commercialApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                pickLogo = {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val chosen = java.util.concurrent.atomic.AtomicReference<java.io.File?>()
                                        SwingUtilities.invokeAndWait {
                                            val picker = javax.swing.JFileChooser()
                                            picker.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PNG / JPEG", "png", "jpg", "jpeg")
                                            if (picker.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chosen.set(picker.selectedFile)
                                        }
                                        chosen.get()?.let { file -> require(file.length() in 1L..1048576L); file.readBytes().also { require(it.size <= 1048576) } }
                                    }
                                },
                                exportPdf = { filename, bytes ->
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val chosen = java.util.concurrent.atomic.AtomicReference<java.io.File?>()
                                        SwingUtilities.invokeAndWait {
                                            val picker = javax.swing.JFileChooser()
                                            picker.selectedFile = java.io.File(filename.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                                            if (picker.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                                val selected = picker.selectedFile
                                                val target = if (selected.name.endsWith(".pdf", true)) selected else java.io.File(selected.parentFile, selected.name + ".pdf")
                                                if (!target.exists() || javax.swing.JOptionPane.showConfirmDialog(null, "Replace existing PDF?", "HILTECH", javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION) chosen.set(target)
                                            }
                                        }
                                        chosen.get()?.let { it.writeBytes(bytes); true } ?: false
                                    }
                                },
                            ))
                        }
                        if (System.getenv("HILTECH_HANDOVER_READ_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(handoverReadNativeWorkspace(
                                projects = runtime.readProjectsApi,
                                handover = runtime.handoverApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                            ))
                        }
                        if (System.getenv("HILTECH_SERVICE_OPERATIONS_READ_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(serviceOperationsReadNativeWorkspace(
                                projects = runtime.readProjectsApi,
                                ticketsApi = runtime.supportServiceApi,
                                warrantyApi = runtime.warrantyReadinessApi,
                                timeApi = runtime.supportTimeApi,
                                agendaApi = runtime.maintenanceAgendaApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                            ))
                        }
                        if (System.getenv("HILTECH_FINANCE_REVIEW_PREPARE_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(financeCashboxOpeningWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                api = runtime.financeEvidenceReadApis.cash,
                                newOperationId = { java.util.UUID.randomUUID().toString() },
                            ))
                            add(financeCompletionOperatorWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                apis = runtime.financeEvidenceReadApis,
                                newOperationId = { java.util.UUID.randomUUID().toString() },
                            ))
                        }
                        if (System.getenv("HILTECH_FINANCE_BANK_CSV_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(financeBankCsvIntakeNativeWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                api = runtime.financeBankIntakeApi,
                                uploader = runtime.financeBankCsvUploader,
                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                deriveProposalId = { bankOperation ->
                                    java.util.UUID.nameUUIDFromBytes(
                                        "hiltech:bank-statement-intake:$bankOperation"
                                            .toByteArray(Charsets.UTF_8),
                                    ).toString()
                                },
                                pickCsvFile = {
                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        val selected =
                                            java.util.concurrent.atomic.AtomicReference<java.io.File?>()
                                        javax.swing.SwingUtilities.invokeAndWait {
                                            val picker = javax.swing.JFileChooser()
                                            picker.dialogTitle = "HILTECH · Private bank CSV"
                                            picker.fileFilter =
                                                javax.swing.filechooser.FileNameExtensionFilter(
                                                    "Canonical UTF-8 bank CSV (2 MiB)", "csv",
                                                )
                                            picker.isAcceptAllFileFilterUsed = false
                                            if (picker.showOpenDialog(null) ==
                                                javax.swing.JFileChooser.APPROVE_OPTION
                                            ) selected.set(picker.selectedFile)
                                        }
                                        selected.get()?.let { file ->
                                            val path = file.toPath()
                                            require(java.nio.file.Files.isRegularFile(
                                                path, java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                            )) { "Bank statement source must be a regular file." }
                                            val size = java.nio.file.Files.size(path)
                                            require(size in 1L..(2L * 1024L * 1024L)) {
                                                "Bank statement must be 1 byte to 2 MiB."
                                            }
                                            val bytes = java.nio.file.Files.readAllBytes(path)
                                            require(bytes.size.toLong() == size &&
                                                file.name.endsWith(".csv", ignoreCase = true)
                                            ) { "The canonical bank CSV source changed." }
                                            val sha = java.security.MessageDigest
                                                .getInstance("SHA-256").digest(bytes)
                                                .joinToString("") { byte ->
                                                    "%02x".format(byte.toInt() and 0xff)
                                                }
                                            FinanceBankCsvSelectedFile(
                                                bytes = bytes, sha256 = sha,
                                                originalFileName = file.name,
                                                capturedAt = java.time.Instant.now().toString(),
                                            )
                                        }
                                    }
                                },
                            ))
                        }
                        if (System.getenv("HILTECH_FINANCE_EVIDENCE_READ_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true ||
                            System.getenv("HILTECH_FINANCE_REVIEW_PREPARE_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(financeEvidenceReadNativeWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                apis = runtime.financeEvidenceReadApis,
                                reviewPreparationEnabled = System.getenv(
                                    "HILTECH_FINANCE_REVIEW_PREPARE_UI_ENABLED"
                                )?.equals("true", ignoreCase = true) == true,
                                newOperationId = { java.util.UUID.randomUUID().toString() },
                            ))
                        }
                        if (System.getenv("HILTECH_FINANCE_INVOICE_READ_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(supplierInvoiceFinanceReadWorkspace(
                                client = runtime.supplierInvoiceFinanceApi,
                                organizationId = org.organizationId,
                                installationId = runtime.physicalWarehouseInstallationId,
                                newOperationId = { java.util.UUID.randomUUID().toString() },
                            ))
                        }
                        if (System.getenv("HILTECH_SERVICE_PURCHASES_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true &&
                            System.getenv("HILTECH_SERVICE_PURCHASES_WORKSPACE_UI_ENABLED")
                                ?.equals("true", ignoreCase = true) == true
                        ) {
                            add(NativeOperationalWorkspace(
                                id = "service-purchasing",
                                label = "مشتريات الخدمات",
                                canReadSource = {
                                    servicePurchaseAnyCurrentSourceVisible(
                                        needs = runtime.servicePurchaseNeedApi,
                                        sourcing = runtime.serviceSupplierSourcingApi,
                                        orders = runtime.serviceOrderAndInvoiceApi,
                                        organizationId = org.organizationId,
                                        installationId = runtime.physicalWarehouseInstallationId,
                                    )
                                },
                                hostOwnsScroll = true,
                                content = { _ ->
                                    androidx.compose.runtime.key(
                                        signed.identity.identityId, org.organizationId, "service-purchases",
                                    ) {
                                        DesktopServicePurchaseSections(
                                            runtime = runtime,
                                            organizationId = org.organizationId,
                                        )
                                    }
                                },
                            ))
                        }
                    }
                } ?: emptyList(),
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
                onRefreshWarehouse =
                    ::refreshWarehouses,
                onStockAction = ::applyStockAction,
                reservationPort = runtime.reservationPort,
                onAssetAction = ::applyAssetAction,
                onSelectWarehouse =
                    ::selectWarehouse,
                onCreateWarehouse =
                    ::createWarehouse,
                onCreateWarehouseLocation =
                    ::createWarehouseLocation,
                onOpenImportBatch =
                    ::openWarehouseImport,
                onReviewImportRow =
                    ::reviewWarehouseImportRow,
                onGrantWarehouseAuthority =
                    ::grantWarehouseAuthority,
                onEndWarehouseAuthority =
                    ::endWarehouseAuthority,
                warehouseOperator = {
                    val signed = shellState.value as? HiltechShellState.SignedIn
                    val identity = signed?.identity
                    val organization = identity?.organizations?.firstOrNull { it.primary }
                        ?: identity?.organizations?.firstOrNull()
                    if (identity != null && organization != null) {
                        WarehousePhysicalOperatorSection(
                            api = runtime.physicalWarehouseApi,
                            organizationId = organization.organizationId,
                            installationId = runtime.physicalWarehouseInstallationId,
                            operatorScopeKey = identity.identityId,
                            operationId = { fields ->
                                val payload =
                                    (listOf(
                                        "desktop-warehouse",
                                        identity.identityId,
                                        runtime.physicalWarehouseInstallationId,
                                    ) + fields).joinToString("") { value ->
                                        value?.toString()?.let { "${it.length}:$it" } ?: "-1:"
                                    }
                                java.util.UUID.nameUUIDFromBytes(
                                    payload.toByteArray(Charsets.UTF_8),
                                ).toString()
                            },
                        )
                    }
                },
                procurementOperator = {
                    NativeOperationAreas(
                        title = "المشتريات",
                        description = "اختر العملية المطلوبة وتابعها في مساحتها المستقلة.",
                        areas = buildList<NativeOperationArea> {
                            if (System.getenv("HILTECH_SERVICE_PURCHASES_UI_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true &&
                                System.getenv("HILTECH_SERVICE_PURCHASES_WORKSPACE_UI_ENABLED")
                                    ?.equals("true", ignoreCase = true) != true
                            ) {
                                add(NativeOperationArea("services", "شراء الخدمات", "طلبات الخدمات وعروض الأسعار وأوامر الخدمة") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, organization.organizationId, "service-purchases",
                                        ) {
                                            DesktopServicePurchaseSections(
                                                runtime = runtime,
                                                organizationId = organization.organizationId,
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("requirements", "احتياجات المشاريع", "طلبات الخامات المرتبطة بالأعمال والمشاريع") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val works = signed?.projects?.workOrders.orEmpty()
                                    if (identity != null && works.isNotEmpty()) {
                                        androidx.compose.runtime.key(identity.identityId) {
                                            PurchaseRequirementOperatorSection(
                                                api = runtime.procurementApi,
                                                warehouseApi = runtime.physicalWarehouseApi,
                                                workApi = runtime.procurementWorkApi,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                workChoices = works,
                                                projectIntakeId = signed.projects?.selectedProject?.projectId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_SUPPLIER_SOURCING_UI_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("sourcing", "الموردون وعروض الأسعار", "مقارنة عروض الموردين وإعداد طلب الشراء") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(identity.identityId, organization.organizationId) {
                                            SupplierSourcingBuyerSection(
                                                api = runtime.supplierSourcingApi,
                                                organizationId = organization.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                                purchaseCandidateApi = runtime.purchaseOrderCandidateApi.takeIf {
                                                    System.getenv("HILTECH_PO_CANDIDATES_UI_ENABLED")
                                                        ?.equals("true", ignoreCase = true) == true
                                                },
                                                purchaseApprovalRequestEnabled =
                                                    System.getenv("HILTECH_PO_APPROVAL_REQUEST_UI_ENABLED")
                                                        ?.equals("true", ignoreCase = true) == true,
                                                quoteDocumentUploader = runtime.supplierQuoteDocumentUploader,
                                                pickQuoteFile = {
                                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                                        val chosen = java.util.concurrent.atomic.AtomicReference<java.io.File?>()
                                                        javax.swing.SwingUtilities.invokeAndWait {
                                                            val picker = javax.swing.JFileChooser()
                                                            picker.dialogTitle = "Private supplier quotation document"
                                                            picker.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                                                "Quotation PDF or image (max 16 MiB)", "pdf", "jpg", "jpeg", "png", "webp",
                                                            )
                                                            picker.isAcceptAllFileFilterUsed = false
                                                            if (picker.showOpenDialog(null) ==
                                                                javax.swing.JFileChooser.APPROVE_OPTION
                                                            ) chosen.set(picker.selectedFile)
                                                        }
                                                        val file = chosen.get()
                                                        if (file == null) null else {
                                                            val path = file.toPath()
                                                            require(java.nio.file.Files.isRegularFile(
                                                                path, java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                                            )) { "Quotation source must be a regular file." }
                                                            val size = java.nio.file.Files.size(path)
                                                            require(size in 1..(16L * 1024L * 1024L)) {
                                                                "Quotation file must be between 1 byte and 16 MiB."
                                                            }
                                                            val ext = file.extension.lowercase()
                                                            val mime = when (ext) {
                                                                "pdf" -> "application/pdf"
                                                                "jpg", "jpeg" -> "image/jpeg"
                                                                "png" -> "image/png"
                                                                "webp" -> "image/webp"
                                                                else -> error("Unsupported quotation document format.")
                                                            }
                                                            val bytes = java.nio.file.Files.readAllBytes(path)
                                                            require(bytes.size.toLong() == size) {
                                                                "Quotation source changed during file selection."
                                                            }
                                                            val digest = java.security.MessageDigest
                                                                .getInstance("SHA-256").digest(bytes)
                                                                .joinToString("") { byte ->
                                                                    "%02x".format(byte.toInt() and 0xff)
                                                                }
                                                            com.hiltech.shared.core.procurement.SupplierQuotePickedFile(
                                                                fileName = file.name,
                                                                contentType = mime,
                                                                sha256 = digest,
                                                                bytes = bytes,
                                                                capturedAt = java.time.Instant.now().toString(),
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_PO_ISSUER_UI_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("orders", "أوامر الشراء", "الإصدار والتعديلات والإلغاء والتواصل مع المورد") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, organization.organizationId, "po-issuer",
                                        ) {
                                            PurchaseOrderIssuerSection(
                                                api = runtime.purchaseOrderIssueApi,
                                                amendmentApi = runtime.purchaseOrderDeliveryAmendmentApi.takeIf {
                                                    System.getenv("HILTECH_PO_AMENDMENT_UI_ENABLED")
                                                        ?.equals("true", ignoreCase = true) == true
                                                },
                                                cancellationEnabled =
                                                    System.getenv("HILTECH_PO_CANCELLATION_UI_ENABLED")
                                                        ?.equals("true", ignoreCase = true) == true,
                                                organizationId = organization.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                            )
                                        }
                                        if (System.getenv("HILTECH_PO_SUPPLIER_COMMUNICATION_UI_ENABLED")
                                                ?.equals("true", ignoreCase = true) == true
                                        ) {
                                            androidx.compose.runtime.key(
                                                identity.identityId, organization.organizationId, "po-dispatch-evidence",
                                            ) {
                                                SupplierPoCommunicationSection(
                                                    issued = runtime.purchaseOrderIssueApi,
                                                    communications = runtime.supplierPoCommunicationApi,
                                                    organizationId = organization.organizationId,
                                                    installationId = runtime.physicalWarehouseInstallationId,
                                                    newOperationId = { java.util.UUID.randomUUID().toString() },
                                                    currentInstant = { java.time.Instant.now().toString() },
                                                )
                                            }
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_ASSET_PURCHASE_NEEDS_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("asset-parts", "قطع غيار المعدات", "احتياجات الأصول والصيانة") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, organization.organizationId, "asset-parts-needs",
                                        ) {
                                            AssetPurchaseNeedSection(
                                                api = runtime.assetPurchaseNeedApi,
                                                warehouseApi = runtime.physicalWarehouseApi,
                                                organizationId = organization.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_OFFICE_STOCK_NEEDS_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("office-stock", "احتياجات المكتب", "طلبات المستلزمات الداخلية") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val org = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && org != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, org.organizationId, "office-needs",
                                        ) {
                                            OfficeStockNeedSection(
                                                api = runtime.officeStockNeedApi,
                                                organizationId = org.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_WAREHOUSE_REPLENISHMENT_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("replenishment", "إعادة التوريد", "احتياجات إعادة تعبئة المخزون") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, organization.organizationId, "warehouse-needs",
                                        ) {
                                            WarehouseReplenishmentSection(
                                                api = runtime.warehouseReplenishmentApi,
                                                warehouseApi = runtime.physicalWarehouseApi,
                                                organizationId = organization.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_PO_WAREHOUSE_INTAKE_UI_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("receiving", "استلام المشتريات", "استلام التوريدات ومستندات التسليم") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, organization.organizationId, "po-warehouse",
                                        ) {
                                            PoWarehouseIntakeSection(
                                                api = runtime.poWarehouseIntakeApi,
                                                warehouseApi = runtime.physicalWarehouseApi,
                                                organizationId = organization.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                                deliveryNoteUploader = runtime.poReceivingDeliveryNoteUploader,
                                                pickDeliveryNote = {
                                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                                        val chosen = java.util.concurrent.atomic.AtomicReference<java.io.File?>()
                                                        javax.swing.SwingUtilities.invokeAndWait {
                                                            val picker = javax.swing.JFileChooser()
                                                            picker.dialogTitle = "Private supplier delivery note document"
                                                            picker.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                                                "Delivery note PDF/image (max 16 MiB)", "pdf", "jpg", "jpeg", "png", "webp",
                                                            )
                                                            picker.isAcceptAllFileFilterUsed = false
                                                            if (picker.showOpenDialog(null) ==
                                                                javax.swing.JFileChooser.APPROVE_OPTION
                                                            ) chosen.set(picker.selectedFile)
                                                        }
                                                        val file = chosen.get()
                                                        if (file == null) null else {
                                                            val path = file.toPath()
                                                            require(java.nio.file.Files.isRegularFile(
                                                                path, java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                                            )) { "Delivery note source must be a regular file." }
                                                            val size = java.nio.file.Files.size(path)
                                                            require(size in 1..(16L * 1024L * 1024L)) {
                                                                "Delivery note must be between 1 byte and 16 MiB."
                                                            }
                                                            val mime = when (file.extension.lowercase()) {
                                                                "pdf" -> "application/pdf"
                                                                "jpg", "jpeg" -> "image/jpeg"
                                                                "png" -> "image/png"
                                                                "webp" -> "image/webp"
                                                                else -> error("Unsupported delivery note file type.")
                                                            }
                                                            val bytes = java.nio.file.Files.readAllBytes(path)
                                                            require(bytes.size.toLong() == size) {
                                                                "Delivery note document changed during file selection."
                                                            }
                                                            val digest = java.security.MessageDigest
                                                                .getInstance("SHA-256").digest(bytes)
                                                                .joinToString("") { byte ->
                                                                    "%02x".format(byte.toInt() and 0xff)
                                                                }
                                                            com.hiltech.shared.core.procurement.PoDeliveryNoteFile(
                                                                contentType = mime, originalFileName = file.name,
                                                                bytes = bytes, sha256 = digest,
                                                                capturedAt = java.time.Instant.now().toString(),
                                                            )
                                                        }
                                                    }
                                                },
              
                                            )
                                        }
                                    }
                                })
                            }
                            if (System.getenv("HILTECH_SUPPLIER_INVOICE_FINANCE_UI_ENABLED")
                                    ?.equals("true", ignoreCase = true) == true
                            ) {
                                add(NativeOperationArea("invoices", "فواتير الموردين", "تسجيل الفواتير ومراجعتها") {
                                    val signed = shellState.value as? HiltechShellState.SignedIn
                                    val identity = signed?.identity
                                    val organization = identity?.organizations?.firstOrNull { it.primary }
                                        ?: identity?.organizations?.firstOrNull()
                                    if (identity != null && organization != null) {
                                        androidx.compose.runtime.key(
                                            identity.identityId, organization.organizationId, "invoice-finance",
                                        ) {
                                            SupplierInvoiceFinanceSection(
                                                api = runtime.supplierInvoiceFinanceApi,
                                                organizationId = organization.organizationId,
                                                installationId = runtime.physicalWarehouseInstallationId,
                                                newOperationId = { java.util.UUID.randomUUID().toString() },
                                                documentUploader = runtime.supplierInvoiceDocumentUploader,
                                                pickInvoiceFile = {
                                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                                        val chosen = java.util.concurrent.atomic.AtomicReference<java.io.File?>()
                                                        javax.swing.SwingUtilities.invokeAndWait {
                                                            val picker = javax.swing.JFileChooser()
                                                            picker.dialogTitle = "Private supplier invoice document"
                                                            picker.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                                                                "Invoice PDF/image (max 16 MiB)", "pdf", "jpg", "jpeg", "png", "webp",
                                                            )
                                                            picker.isAcceptAllFileFilterUsed = false
                                                            if (picker.showOpenDialog(null) ==
                                                                javax.swing.JFileChooser.APPROVE_OPTION
                                                            ) chosen.set(picker.selectedFile)
                                                        }
                                                        val file = chosen.get()
                                                        if (file == null) null else {
                                                            val path = file.toPath()
                                                            require(java.nio.file.Files.isRegularFile(
                                                                path, java.nio.file.LinkOption.NOFOLLOW_LINKS,
                                                            )) { "Invoice source must be a regular file." }
                                                            val size = java.nio.file.Files.size(path)
                                                            require(size in 1..(16L * 1024L * 1024L)) {
                                                                "Invoice must be between 1 byte and 16 MiB."
                                                            }
                                                            val mime = when (file.extension.lowercase()) {
                                                                "pdf" -> "application/pdf"
                                                                "jpg", "jpeg" -> "image/jpeg"
                                                                "png" -> "image/png"
                                                                "webp" -> "image/webp"
                                                                else -> error("Unsupported invoice file type.")
                                                            }
                                                            val bytes = java.nio.file.Files.readAllBytes(path)
                                                            require(bytes.size.toLong() == size) {
                                                                "Invoice document changed during file selection."
                                                            }
                                                            val digest = java.security.MessageDigest
                                                                .getInstance("SHA-256").digest(bytes)
                                                                .joinToString("") { byte ->
                                                                    "%02x".format(byte.toInt() and 0xff)
                                                                }
                                                            com.hiltech.shared.core.procurement.SupplierInvoiceSelectedFile(
                                                                contentType = mime, originalFileName = file.name,
                                                                bytes = bytes, sha256 = digest,
                                                                capturedAt = java.time.Instant.now().toString(),
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                })
                            }
                        },
                    )
                },
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
                planningFormHost = (shellState.value as? HiltechShellState.SignedIn)?.let { signed ->
                    val expectedScope = planningSessionScope(signed)
                    PlanningFormHost(
                        scopeKey = expectedScope,
                        newOperation = ::newPlanningOperation,
                        submit = { request, completed -> runPlanningRequest(expectedScope, request, completed) },
                    )
                },
                onWorkPlanningAction =
                    ::runWorkPlanningAction,
                onWorkReadinessAction =
                    ::runWorkReadinessAction,
                onProjectCommandCenterAction =
                    ::runProjectCommandCenterAction,
            )
        }
    }
}

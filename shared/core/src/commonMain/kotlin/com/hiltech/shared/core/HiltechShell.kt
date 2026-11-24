package com.hiltech.shared.core

import androidx.compose.foundation.ScrollState

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import kotlinx.coroutines.launch
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
    data class OfflineFieldReadOnly(
        val identityId: String,
        val field: HiltechFieldState,
    ) : HiltechShellState

    data class SignedIn(
        val identity: IdentityBootstrapDto,
        val security: IdentitySecuritySnapshot? = null,
        val people: HiltechPeopleState? = null,
        val onboarding:
            HiltechOnboardingState? = null,
        val projects:
            HiltechProjectsState? = null,
        val warehouse:
            HiltechWarehouseState? = null,
        val field:
            HiltechFieldState? = null,
        val assetLookup: AssetLookupState? = null,
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
    onRefreshWarehouse: () -> Unit = {},
    onStockAction: (StockAction) -> Unit = {},
    reservationPort: ReservationUiPort? = null,
    onAssetAction: (AssetAction) -> Unit = {},
    onLookupAssetTag: (String) -> Unit = {},
    onSelectWarehouse: (String) -> Unit = {},
    onCreateWarehouse: (String, String) -> Unit = { _, _ -> },
    onCreateWarehouseLocation: (String, String) -> Unit = { _, _ -> },
    onOpenImportBatch: (String) -> Unit = {},
    onReviewImportRow: (WarehouseImportReviewDraft) -> Unit = {},
    onGrantWarehouseAuthority: (String, String) -> Unit = { _, _ -> },
    onEndWarehouseAuthority: (String, Long) -> Unit = { _, _ -> },
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
    onWorkPlanningAction: (WorkPlanningUiAction) -> Unit = {},
    onWorkReadinessAction: (WorkReadinessUiAction) -> Unit = {},
    onProjectCommandCenterAction: (ProjectCommandCenterUiAction) -> Unit = {},
    onRefreshFieldWork: () -> Unit = {},
    onSelectFieldWork: (String) -> Unit = {},
    onFieldFocusChanged: (Boolean) -> Unit = {},
    fieldBackRequest: Int = 0,
    onRefreshPersonalIssuedStock: (String) -> Unit = {},
    showPersonalIssuedStock: Boolean = false,
    onQueueMaterialUsage: (com.hiltech.shared.core.work.FieldPersonalIssuedStockLineDto, String) -> Unit = { _, _ -> },
    showMaterialUsageReporting: Boolean = false,
    onLookupAssignedAssetTag: (String, String) -> Unit = { _, _ -> },
    showAssignedAssetLookup: Boolean = false,
    onSubmitEngineeringTest: (String, FieldEngineeringTestDraft) -> Unit = { _, _ -> },
    showEngineeringTests: Boolean = false,
    captureFieldIntent: Boolean = false,
    onQueueFieldIntent:
        (com.hiltech.shared.core.field.FieldExecutionIntent, String?) -> Unit = { _, _ -> },
    onQueueCompletion: () -> Unit = {},
    captureEvidence: Boolean = false,
    onSelectEvidenceFile: (String) -> Unit = {},
    onTakeEvidencePhoto: (String) -> Unit = {},
    warehouseOperator: @Composable () -> Unit = {},
    procurementOperator: @Composable () -> Unit = {},
    // Only source-authorized, default-OFF modules are supplied by native hosts.
    operationalWorkspaces: List<NativeOperationalWorkspace> = emptyList(),
    // Host requests a source-owned Projects workspace focus after selecting
    // a canonical Executive project, never a new permission or API route.
    projectFocusRequest: Int = 0,
    desktopNavigation: Boolean = false,
    desktopPurchasingAvailable: Boolean = false,
    compactNavigation: Boolean = false,
    compactBackRequest: Int = 0,
    onCompactNavigationChanged: (canGoBack: Boolean, inWork: Boolean) -> Unit = { _, _ -> },
    peopleOffboardingAvailable: Boolean = true,
    inboxWorkspace: (@Composable () -> Unit)? = null,
    employeeAccountWorkspace: (@Composable (EmployeeDetailDto) -> Unit)? = null,
    desktopFindRequest: Int = 0,
) {
    HiltechOperationalTheme {
        // Never inherit a different actor's previous desktop/mobile scroll
        // position when signing out, switching org or restoring offline read.
        val shellScrollScope = when (state) {
            is HiltechShellState.SignedIn -> {
                val orgId = state.identity.organizations.firstOrNull { it.primary }
                    ?.organizationId ?: state.identity.organizations.firstOrNull()?.organizationId
                "signed:${state.identity.identityId}:${orgId.orEmpty()}"
            }
            is HiltechShellState.OfflineFieldReadOnly -> "offline:${state.identityId}"
            else -> "public"
        }
        val signedIn = state as? HiltechShellState.SignedIn
        val useWorkspaceNavigation = desktopNavigation || compactNavigation
        val destinations = if (useWorkspaceNavigation && signedIn != null) buildList {
            add(ShellWorkspace.Home)
            if (signedIn.field != null) add(ShellWorkspace.Work)
            if (inboxWorkspace != null) add(ShellWorkspace.Inbox)
            if (compactNavigation || operationalWorkspaces.isNotEmpty()) add(ShellWorkspace.Explore)
            if (signedIn.projects != null) add(ShellWorkspace.Projects)
            if (signedIn.people != null) add(ShellWorkspace.People)
            if (signedIn.warehouse != null || signedIn.assetLookup != null) add(ShellWorkspace.Warehouse)
            if (desktopPurchasingAvailable) add(ShellWorkspace.Purchasing)
            add(ShellWorkspace.Account)
        } else emptyList()
        var requestedWorkspace by remember(shellScrollScope) { mutableStateOf(ShellWorkspace.Home) }
        var finding by remember(shellScrollScope) { mutableStateOf(false) }
        LaunchedEffect(desktopFindRequest) {
            if (desktopNavigation && signedIn != null && desktopFindRequest > 0) finding = true
        }
        val selectedWorkspace = requestedWorkspace.takeIf(destinations::contains) ?: destinations.firstOrNull()
        LaunchedEffect(selectedWorkspace, compactNavigation) {
            if (compactNavigation) onCompactNavigationChanged(
                selectedWorkspace != null && selectedWorkspace != ShellWorkspace.Home,
                selectedWorkspace == ShellWorkspace.Work || selectedWorkspace == null,
            )
        }
        LaunchedEffect(compactBackRequest) {
            if (compactNavigation && compactBackRequest > 0) {
                requestedWorkspace = when (selectedWorkspace) {
                    ShellWorkspace.People, ShellWorkspace.Projects, ShellWorkspace.Warehouse,
                    ShellWorkspace.Purchasing, ShellWorkspace.Account ->
                        ShellWorkspace.Explore
                    else -> ShellWorkspace.Home
                }
            }
        }
        val workspaceScroll = remember(shellScrollScope) { mutableMapOf<ShellWorkspace?, ScrollState>() }
        val scopedShellScroll = workspaceScroll.getOrPut(selectedWorkspace) { ScrollState(0) }
        fun show(workspace: ShellWorkspace) = selectedWorkspace == null || selectedWorkspace == workspace
        val fieldWorkspaceRequester = remember(shellScrollScope) { BringIntoViewRequester() }
        val peopleWorkspaceRequester = remember(shellScrollScope) { BringIntoViewRequester() }
        val warehouseWorkspaceRequester = remember(shellScrollScope) { BringIntoViewRequester() }
        val projectWorkspaceRequester = remember(shellScrollScope) { BringIntoViewRequester() }
        val shellNavigationScope = rememberCoroutineScope()
        val canFocusProjects = state is HiltechShellState.SignedIn && state.projects != null
        LaunchedEffect(shellScrollScope, projectFocusRequest, canFocusProjects) {
            if (projectFocusRequest > 0 && canFocusProjects) {
                if (useWorkspaceNavigation) {
                    requestedWorkspace = ShellWorkspace.Projects
                } else {
                    projectWorkspaceRequester.bringIntoView()
                }
            }
        }
        NativeWorkspaceNavigation(
            selected = selectedWorkspace,
            destinations = destinations,
            scroll = scopedShellScroll,
            compact = compactNavigation,
            onSelect = { requestedWorkspace = it },
            onFind = if (desktopNavigation && signedIn != null) {
                { finding = true }
            } else null,
        ) {
            Text(
                selectedWorkspace?.label ?: "HILTECH OS",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (selectedWorkspace == null) {
                Text(
                    "Identity / Organization / Permissions",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

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

                is HiltechShellState.OfflineFieldReadOnly -> {
                    Text(
                        "وضع القراءة بدون إنترنت",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("نسخة محفوظة للجهاز فقط. لا نقدر نؤكد صلاحية التكليف الحالية أو أي تغيير حصل على السيرفر.")
                    Text("لا يوجد بدء/إنهاء عمل، تسجيل خامات، رفع صور، أو صلاحيات إدارة في الوضع ده.")
                    AssignedWorkSection(
                        state = state.field,
                        onRefresh = onRetry,
                        onSelectWork = onSelectFieldWork,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("إعادة الاتصال والتحقق") }
                        OutlinedButton(onClick = onSignOut) { Text("تسجيل الخروج") }
                    }
                }

                is HiltechShellState.SignedIn -> {
                    val primary =
                        state.identity.organizations.firstOrNull { it.primary }
                            ?: state.identity.organizations.firstOrNull()

                    if (show(ShellWorkspace.Home)) {
                        Text(
                            primary?.displayName ?: "HILTECH",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (selectedWorkspace == null) {
                            Text("Identity ${state.identity.identityId.take(8)} · ${state.identity.identityStatus}")
                        }
                        if (state.identity.teams.isNotEmpty()) {
                            Text("Teams: " + state.identity.teams.joinToString { it.name })
                        }
                        if (selectedWorkspace != null && inboxWorkspace != null) {
                            OutlinedButton(onClick = { requestedWorkspace = ShellWorkspace.Inbox }) {
                                Text("Open inbox")
                            }
                        }
                    }

                    if (selectedWorkspace == ShellWorkspace.Inbox) inboxWorkspace?.invoke()

                    // Navigate to the real already-mounted Compose workspaces.
                    // This is a scroll shortcut, not a permission grant, route
                    // impersonation or an additional backend/source-of-truth.
                    if (selectedWorkspace == null && (state.field != null || state.people != null ||
                        state.warehouse != null || state.projects != null
                    )) {
                        Text("مساحات العمل / Workspaces", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.field != null) {
                                OutlinedButton(onClick = {
                                    shellNavigationScope.launch { fieldWorkspaceRequester.bringIntoView() }
                                }) { Text("شغلي اليوم") }
                            }
                            if (state.warehouse != null) {
                                OutlinedButton(onClick = {
                                    shellNavigationScope.launch { warehouseWorkspaceRequester.bringIntoView() }
                                }) { Text("المخزن") }
                            }
                            if (state.projects != null) {
                                OutlinedButton(onClick = {
                                    shellNavigationScope.launch { projectWorkspaceRequester.bringIntoView() }
                                }) { Text("المشاريع") }
                            }
                            if (state.people != null) {
                                OutlinedButton(onClick = {
                                    shellNavigationScope.launch { peopleWorkspaceRequester.bringIntoView() }
                                }) { Text("الأفراد") }
                            }
                        }
                    }

                    // Phase 8–13 panels live in independent bounded native
                    // roots: never nest their root vertical scrollers here.
                    // Sign-in and current source-granted eligibility are both
                    // required; a tab never grants server-side authority.
                    if (show(ShellWorkspace.Explore) && primary != null && operationalWorkspaces.isNotEmpty()) {
                        NativeOperationalWorkspaceHub(
                            scopeKey = state.identity.identityId + ":" + primary.organizationId,
                            authorityRevisionKey =
                                state.identity.identityVersion.toString() + ":" +
                                    primary.membershipVersion.toString(),
                            workspaces = operationalWorkspaces,
                        )
                    }

                    if (show(ShellWorkspace.Home)) state.onboarding
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

                    if (show(ShellWorkspace.Account)) {
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
                    }

                    if (show(ShellWorkspace.People)) state.people?.let { people ->
                        Column {
                        // Tiny source-present anchor: scroll to section start,
                        // never try to bring a multi-screen Column in view.
                        Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(peopleWorkspaceRequester))
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
                            offboardingAvailable = peopleOffboardingAvailable,
                            accountWorkspace = employeeAccountWorkspace,
                        )
                        }
                    }

                    if (show(ShellWorkspace.Warehouse)) {
                        state.assetLookup?.let { AssetTagLookupSection(it, onLookupAssetTag) }
                    }

                    if (show(ShellWorkspace.Work)) state.field?.let {
                        Column {
                        // Tiny source-present anchor: scroll to section start,
                        // never try to bring a multi-screen Column in view.
                        Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(fieldWorkspaceRequester))
                        AssignedWorkSection(
                            state = it,
                            onRefresh =
                                onRefreshFieldWork,
                            onSelectWork =
                                onSelectFieldWork,
                            captureFieldIntent = captureFieldIntent,
                            onQueueFieldIntent = onQueueFieldIntent,
                            onQueueCompletion = onQueueCompletion,
                            captureEvidence = captureEvidence,
                            onSelectEvidenceFile = onSelectEvidenceFile,
                            onTakeEvidencePhoto = onTakeEvidencePhoto,
                            onRefreshPersonalIssuedStock = onRefreshPersonalIssuedStock,
                            showPersonalIssuedStock = showPersonalIssuedStock,
                            onQueueMaterialUsage = onQueueMaterialUsage,
                            showMaterialUsageReporting = showMaterialUsageReporting,
                            onLookupAssignedAssetTag = onLookupAssignedAssetTag,
                            showAssignedAssetLookup = showAssignedAssetLookup,
                            onSubmitEngineeringTest = onSubmitEngineeringTest,
                            showEngineeringTests = showEngineeringTests,
                            // Only verified sign-in gets focused compact navigation.
                            // The offline read-only branch uses the baseline.
                            focusScopeKey = primary?.organizationId?.let { organizationId ->
                                "${state.identity.identityId}:$organizationId"
                            }.orEmpty(),
                            onCompactFocusChanged = onFieldFocusChanged,
                            compactBackRequest = fieldBackRequest,
                        )
                        }
                    }

                    if (show(ShellWorkspace.Warehouse) ||
                        (compactNavigation && selectedWorkspace == ShellWorkspace.Explore && ShellWorkspace.Warehouse !in destinations)
                    ) Column {
                        // Tiny source-present anchor: scroll to section start,
                        // never try to bring a multi-screen Column in view.
                        Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(warehouseWorkspaceRequester))
                        warehouseOperator()
                    }
                    // Host applications mount procurement only with an explicit,
                    // default-OFF feature flag. Never show it during offline mode.
                    if (show(ShellWorkspace.Purchasing) || (compactNavigation && selectedWorkspace == ShellWorkspace.Explore)) {
                        procurementOperator()
                    }

                    if (show(ShellWorkspace.Warehouse)) state.warehouse?.let { warehouse ->
                        WarehouseSetupSection(
                            state = warehouse,
                            onRefresh = onRefreshWarehouse,
                            onStockAction = onStockAction,
                            reservationPort = reservationPort,
                            onAssetAction = onAssetAction,
                            onSelectWarehouse = onSelectWarehouse,
                            onCreateWarehouse = onCreateWarehouse,
                            onCreateWarehouseLocation = onCreateWarehouseLocation,
                            onOpenImportBatch = onOpenImportBatch,
                            onReviewImportRow = onReviewImportRow,
                            onGrantWarehouseAuthority = onGrantWarehouseAuthority,
                            onEndWarehouseAuthority = onEndWarehouseAuthority,
                        )
                    }

                    if (show(ShellWorkspace.Projects)) state.projects?.let {
                        Column {
                        // Tiny source-present anchor: scroll to section start,
                        // never try to bring a multi-screen Column in view.
                        Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(projectWorkspaceRequester))
                        ProjectControlSection(
                            reservationPort = reservationPort,
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
                            onWorkPlanningAction =
                                onWorkPlanningAction,
                            onWorkReadinessAction =
                                onWorkReadinessAction,
                            onProjectCommandCenterAction =
                                onProjectCommandCenterAction,
                        )
                        }
                    }

                    if (show(ShellWorkspace.People)) state.onboarding
                        ?.selectedEmployeeCase
                        ?.let {
                            AdminOnboardingSection(
                                onboarding = it,
                                onRefresh =
                                    onRefreshOnboarding,
                            )
                        }

                    if (show(ShellWorkspace.Account)) state.onboarding
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

                    if (show(ShellWorkspace.Account)) OutlinedButton(onClick = onSignOut) {
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

        if (finding && desktopNavigation && signedIn != null) {
            val commands = buildList {
                destinations.forEach { workspace ->
                    add(WorkspaceSearchResult("workspace:${workspace.name}", workspace.label, "Workspace") {
                        requestedWorkspace = workspace
                    })
                }
                signedIn.projects?.list?.items.orEmpty().forEach { project ->
                    add(WorkspaceSearchResult(
                        "project:${project.projectId}", project.name, "Project · ${project.projectCode}",
                    ) {
                        requestedWorkspace = ShellWorkspace.Projects
                        onSelectProject(project.projectId)
                    })
                }
                signedIn.people?.directory?.takeIf { it.canManagePeople }?.items.orEmpty().forEach { employee ->
                    add(WorkspaceSearchResult(
                        "employee:${employee.employeeId}", employee.displayName, "People · ${employee.employeeCode}",
                    ) {
                        requestedWorkspace = ShellWorkspace.People
                        onSelectEmployee(employee.employeeId)
                    })
                }
            }
            WorkspaceSearch(commands, onDismiss = { finding = false })
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
    offboardingAvailable: Boolean,
    accountWorkspace: (@Composable (EmployeeDetailDto) -> Unit)?,
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
        var query by remember { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        val term = query.trim()
        val matches = directory.items.filter { employee ->
            term.isEmpty() || employee.displayName.contains(term, ignoreCase = true) ||
                employee.employeeCode.contains(term, ignoreCase = true)
        }
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text("Employee directory", style = MaterialTheme.typography.titleMedium)
            if (directory.canManagePeople) {
                OutlinedButton(enabled = !state.loading, onClick = { creating = !creating }) {
                    Text(if (creating) "Close new employee form" else "New employee")
                }
                if (creating) {
                    PeopleCreateForm(
                        onCreateEmployee = onCreateEmployee,
                        canSubmit = !state.loading,
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search by name or employee code") },
                singleLine = true,
            )
            if (matches.isEmpty()) {
                Text(if (term.isEmpty()) "No employees available" else "No matching employees")
            }
        }
        matches.forEach { employee ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(employee.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(employee.employeeCode, style = MaterialTheme.typography.bodySmall)
                    }
                    if (directory.canManagePeople) {
                        OutlinedButton(
                            enabled = !state.loading,
                            onClick = { onSelectEmployee(employee.employeeId) },
                        ) { Text("Open") }
                    }
                }
                HiltechStateTag(
                    label = employee.employeeState.lowercase().replaceFirstChar { it.uppercaseChar() },
                    tone = if (employee.employeeState == "ACTIVE") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL,
                )
            }
        }
    }

    state.selectedEmployee?.let { employee ->
        val detailAnchor = remember(employee.employeeId) { BringIntoViewRequester() }
        LaunchedEffect(employee.employeeId) { detailAnchor.bringIntoView() }
        HiltechPanel(Modifier.fillMaxWidth().bringIntoViewRequester(detailAnchor)) {
            Text(employee.displayName, style = MaterialTheme.typography.titleLarge)
            Text(employee.employeeCode, style = MaterialTheme.typography.bodySmall)
            HiltechStateTag(
                label = employee.employeeState.lowercase().replaceFirstChar { it.uppercaseChar() },
                tone = if (employee.employeeState == "ACTIVE") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL,
            )
            employee.employmentTypeCode?.let { Text("Employment: $it") }
            employee.legalName?.takeUnless { it == employee.displayName }?.let { Text("Legal name: $it") }
            employee.mobile?.let { Text("Mobile: $it") }
            employee.email?.let { Text("Email: $it") }
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

        if (!state.loading && state.directory?.canManagePeople == true) {
            accountWorkspace?.invoke(employee)
        }

        if (offboardingAvailable) OffboardingAdminSection(
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
        enabled = !state.loading,
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
    var rolesExpanded by remember(current.assignmentId) { mutableStateOf(false) }
    var customRole by remember(current.assignmentId) { mutableStateOf(false) }
    val knownRoles = (listOf(current) + structure?.items.orEmpty()).distinctBy { it.roleCode }

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

    if (customRole) {
        OutlinedTextField(
            value = proposedRoleCode,
            onValueChange = {
                proposedRoleCode = it
                confirming = false
            },
            label = { Text("Role code") },
            singleLine = true,
        )
        OutlinedButton(onClick = { customRole = false }) { Text("Choose an existing role") }
    } else {
        Box {
            OutlinedButton(onClick = { rolesExpanded = true }) {
                Text(proposedRoleLabel.ifBlank { proposedRoleCode })
            }
            DropdownMenu(expanded = rolesExpanded, onDismissRequest = { rolesExpanded = false }) {
                knownRoles.forEach { role ->
                    DropdownMenuItem(text = { Text(role.roleLabel ?: role.roleCode) }, onClick = {
                        proposedRoleCode = role.roleCode
                        proposedRoleLabel = role.roleLabel.orEmpty()
                        confirming = false
                        rolesExpanded = false
                    })
                }
                DropdownMenuItem(text = { Text("Enter a new role") }, onClick = {
                    rolesExpanded = false
                    customRole = true
                    confirming = false
                })
            }
        }
    }
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
    canSubmit: Boolean,
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
            canSubmit && displayName.isNotBlank() &&
                employeeCode.isNotBlank() &&
                startDate.isNotBlank(),
    ) {
        Text("Create")
    }
}

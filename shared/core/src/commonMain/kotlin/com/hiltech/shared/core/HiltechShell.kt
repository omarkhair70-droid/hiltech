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
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.design.HiltechLanguageProvider
import com.hiltech.shared.core.design.HiltechLanguagePreference
import com.hiltech.shared.core.design.LocalHiltechLanguage
import com.hiltech.shared.core.design.hiltechText
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
    warehouseOperatorAvailable: Boolean = false,
    compactNavigation: Boolean = false,
    compactBackRequest: Int = 0,
    onCompactNavigationChanged: (canGoBack: Boolean, inWork: Boolean) -> Unit = { _, _ -> },
    peopleOffboardingAvailable: Boolean = true,
    inboxWorkspace: (@Composable () -> Unit)? = null,
    employeeAccountWorkspace: (@Composable (EmployeeDetailDto) -> Unit)? = null,
    desktopFindRequest: Int = 0,
    language: HiltechLanguage = HiltechLanguage.Arabic,
    onLanguageChange: (HiltechLanguage) -> Unit = {},
) {
    HiltechLanguageProvider(language) {
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
            if (signedIn.warehouse != null || signedIn.assetLookup != null || warehouseOperatorAvailable) add(ShellWorkspace.Warehouse)
            if (desktopPurchasingAvailable) add(ShellWorkspace.Purchasing)
            add(ShellWorkspace.Account)
        } else emptyList()
        val nativeScope = if (useWorkspaceNavigation && signedIn != null) shellScrollScope else ""
        val nativeAuthority = signedIn?.identity?.let { identity ->
            val org = identity.organizations.firstOrNull { it.primary } ?: identity.organizations.firstOrNull()
            "${identity.identityVersion}:${org?.membershipVersion}"
        }.orEmpty()
        val nativeWorkspaces = if (nativeScope.isNotBlank()) uniqueNativeWorkspaces(operationalWorkspaces) else emptyList()
        val nativeSession = rememberNativeWorkspaceSession(nativeScope, nativeAuthority, nativeWorkspaces)
        val activeNative = nativeWorkspaces.firstOrNull { it.id == nativeSession.selectedId && nativeSession.availability[it.id] == WorkspaceAvailability.AVAILABLE }
        var requestedWorkspace by remember(shellScrollScope) { mutableStateOf(ShellWorkspace.Home) }
        var finding by remember(shellScrollScope) { mutableStateOf(false) }
        var requestedFieldFocus by remember(shellScrollScope) { mutableStateOf<String?>(null) }
        fun openAssignedWork(workId: String) {
            if (ShellWorkspace.Work !in destinations || signedIn?.field?.today?.none { it.workOrderId == workId } != false) return
            nativeSession.close()
            requestedWorkspace = ShellWorkspace.Work
            requestedFieldFocus = workId
            onSelectFieldWork(workId)
        }
        LaunchedEffect(desktopFindRequest) {
            if (desktopNavigation && signedIn != null && desktopFindRequest > 0) finding = true
        }
        val selectedWorkspace = requestedWorkspace.takeIf(destinations::contains) ?: destinations.firstOrNull()
        LaunchedEffect(selectedWorkspace, compactNavigation, activeNative?.id) {
            if (compactNavigation) onCompactNavigationChanged(
                activeNative != null || (selectedWorkspace != null && selectedWorkspace != ShellWorkspace.Home),
                activeNative == null && (selectedWorkspace == ShellWorkspace.Work || selectedWorkspace == null),
            )
        }
        LaunchedEffect(compactBackRequest) {
            if (compactNavigation && compactBackRequest > 0) {
                if (nativeSession.selectedId != null || nativeSession.pendingId != null) {
                    nativeSession.close()
                    return@LaunchedEffect
                }
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
                    nativeSession.close()
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
            onSelect = { nativeSession.close(); requestedWorkspace = it },
            onFind = if (useWorkspaceNavigation && signedIn != null) {
                { finding = true }
            } else null,
            boundedContent = if (activeNative != null) {
                { NativeWorkspacePane(activeNative, nativeScope, nativeSession::close) }
            } else null,
        ) {
            Text(
                selectedWorkspace?.let { language.text(it.displayLabel, it.label) } ?: "HILTECH OS",
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
                    Text(hiltechText("سجّل الدخول بحساب HILTECH للمتابعة.", "Sign in with your HILTECH identity to continue."))
                    Button(onClick = onSignIn) { Text(hiltechText("تسجيل الدخول", "Sign in")) }
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
                        hiltechText("وضع القراءة بدون إنترنت", "Offline read-only mode"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(hiltechText("نسخة محفوظة للجهاز فقط. لا نقدر نؤكد صلاحية التكليف الحالية أو أي تغيير حصل على السيرفر.", "Local cached data only. Current assignment authority and server changes cannot be confirmed."))
                    Text(hiltechText("لا يوجد بدء/إنهاء عمل، تسجيل خامات، رفع صور، أو صلاحيات إدارة في الوضع ده.", "Starting or completing work, material logging, photo uploads and administration are unavailable."))
                    AssignedWorkSection(
                        state = state.field,
                        onRefresh = onRetry,
                        onSelectWork = onSelectFieldWork,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text(hiltechText("إعادة الاتصال والتحقق", "Reconnect and verify")) }
                        OutlinedButton(onClick = onSignOut) { Text(hiltechText("تسجيل الخروج", "Sign out")) }
                    }
                }

                is HiltechShellState.SignedIn -> {
                    val primary =
                        state.identity.organizations.firstOrNull { it.primary }
                            ?: state.identity.organizations.firstOrNull()

                    if (show(ShellWorkspace.Home)) {
                        if (selectedWorkspace == null) {
                            Text(primary?.displayName ?: "HILTECH", style = MaterialTheme.typography.titleLarge)
                            Text("Identity " + state.identity.identityId.take(8) + " · " + state.identity.identityStatus)
                        } else {
                            val colors = com.hiltech.shared.core.design.LocalHiltechOperationalColors.current
                            HiltechPanel(Modifier.fillMaxWidth()) {
                                Text("HILTECH / " + (primary?.organizationCode ?: "WORKSPACE"),
                                    color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                                Text(state.people?.ownProfile?.displayName?.let { hiltechText("أهلًا، ", "Welcome, ") + it }
                                    ?: (primary?.displayName ?: "HILTECH"), style = MaterialTheme.typography.headlineMedium)
                                Text(hiltechText("مساحة عملك", "Your workspace"), style = MaterialTheme.typography.titleMedium)
                                state.people?.ownWorkforceAssignment?.let { assignment ->
                                    Text(assignment.roleLabel ?: assignment.roleCode, color = colors.secondaryText)
                                }
                                if (state.identity.teams.isNotEmpty()) Text(state.identity.teams.joinToString { it.name },
                                    color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                            }
                            if (nativeSession.availability["executive-home"] == WorkspaceAvailability.AVAILABLE) {
                                HomeWorkspaceLink(
                                    title = hiltechText("الشركة اليوم", "Company today"),
                                    detail = hiltechText("القرارات والمشروعات وإشارات التشغيل", "Decisions, projects and operational signals"),
                                    workspace = ShellWorkspace.Home,
                                    onOpen = { requestedWorkspace = ShellWorkspace.Explore; nativeSession.open("executive-home") },
                                )
                            }
                            if (ShellWorkspace.Work in destinations) {
                                HomeWorkspaceLink(
                                    title = hiltechText("شغلي اليوم", "My work today"),
                                    detail = hiltechText("التكليفات المعروضة: ", "Visible assignments: ") + (state.field?.today?.size ?: 0),
                                    workspace = ShellWorkspace.Work,
                                    onOpen = { requestedWorkspace = ShellWorkspace.Work },
                                )
                            }
                            if (ShellWorkspace.Inbox in destinations) {
                                HomeWorkspaceLink(hiltechText("الوارد والقرارات", "Inbox & decisions"),
                                    hiltechText("المراجعات والطلبات الموجهة إليك", "Reviews and requests assigned to you"),
                                    ShellWorkspace.Inbox) { requestedWorkspace = ShellWorkspace.Inbox }
                            }
                            val areas = destinations.filter { it in setOf(ShellWorkspace.Projects, ShellWorkspace.People, ShellWorkspace.Warehouse, ShellWorkspace.Purchasing) }
                            if (areas.isNotEmpty()) {
                                Text(hiltechText("مساحات عملك", "Your workspaces"), style = MaterialTheme.typography.titleLarge)
                                areas.forEach { area ->
                                    val detail = when (area) {
                                        ShellWorkspace.Projects -> hiltechText("المشاريع والتنفيذ والتخطيط", "Projects, execution and planning")
                                        ShellWorkspace.People -> hiltechText("الأفراد والمسؤوليات", "People and responsibilities")
                                        ShellWorkspace.Warehouse -> hiltechText("الأصناف والمعدات والحركات", "Stock, equipment and movements")
                                        else -> hiltechText("طلبات التوريد والموردون", "Supply requests and suppliers")
                                    }
                                    HomeWorkspaceLink(language.text(area.displayLabel, area.label), detail, area) { requestedWorkspace = area }
                                }
                            }
                            if (ShellWorkspace.Explore in destinations) HomeWorkspaceLink(
                                hiltechText("استكشف الشركة", "Explore the company"),
                                hiltechText("باقي المساحات المتاحة لحسابك", "Other workspaces available to your account"),
                                ShellWorkspace.Explore) { requestedWorkspace = ShellWorkspace.Explore }
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
                    if (selectedWorkspace == ShellWorkspace.Explore && nativeWorkspaces.isNotEmpty()) {
                        NativeWorkspaceDirectory(nativeWorkspaces, nativeSession, nativeSession::open)
                    } else if (selectedWorkspace == null && primary != null && operationalWorkspaces.isNotEmpty()) {
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
                        HiltechLanguagePreference(language, onLanguageChange)
                        Text(
                            hiltechText("حسابي والأجهزة", "Me / Sessions"),
                            style = MaterialTheme.typography.titleMedium,
                        )

                        val security = state.security
                        if (security == null) {
                            Text(
                                hiltechText("جارٍ تحميل معلومات الجلسات والأجهزة.", "Session and device security context is loading."),
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
                            relatedProjects = state.projects?.list?.items.orEmpty().filter { it.organizationId == primary?.organizationId },
                            openRelatedProject = if (useWorkspaceNavigation && ShellWorkspace.Projects in destinations) { projectId ->
                                nativeSession.close()
                                requestedWorkspace = ShellWorkspace.Projects
                                onSelectProject(projectId)
                            } else null,
                        )
                        }
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
                            externalFocusId = requestedFieldFocus,
                            onExternalFocusConsumed = { requestedFieldFocus = null },
                        )
                        }
                    }

                    if (show(ShellWorkspace.Warehouse)) {
                        Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(warehouseWorkspaceRequester))
                        if (selectedWorkspace == null) {
                            state.assetLookup?.let { AssetTagLookupSection(it, onLookupAssetTag) }
                            warehouseOperator()
                            state.warehouse?.let { warehouse ->
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
                        } else {
                            NativeOperationAreas(
                                title = "المخازن",
                                description = "اختر ما تحتاج إليه: تنفيذ حركة أو مراجعة المخزون أو البحث عن معدة.",
                                areas = buildList<NativeOperationArea> {
                                    if (warehouseOperatorAvailable || desktopNavigation) {
                                        add(NativeOperationArea("physical", "العمليات اليومية", "التسليم والاستلام والحركات ومتابعة جاهزية الموارد.") {
                                            warehouseOperator()
                                        })
                                    }
                                    if (state.assetLookup != null) {
                                        add(NativeOperationArea("scan", "البحث عن معدة", "افتح سجل المعدة باستخدام AssetTag.") {
                                            state.assetLookup?.let { AssetTagLookupSection(it, onLookupAssetTag) }
                                        })
                                    }
                                    if (state.warehouse != null) {
                                        add(NativeOperationArea("inventory", "إدارة المخزون", "الأصناف والمعدات والحجوزات والمواقع والصلاحيات.") {
                                            state.warehouse?.let { warehouse ->
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
                                        })
                                    }
                                },
                            )
                        }
                    }
                    if (show(ShellWorkspace.Purchasing)) procurementOperator()

                    if (show(ShellWorkspace.Projects)) state.projects?.let {
                        Column {
                        // Tiny source-present anchor: scroll to section start,
                        // never try to bring a multi-screen Column in view.
                        Spacer(Modifier.fillMaxWidth().height(1.dp).bringIntoViewRequester(projectWorkspaceRequester))
                        ProjectControlSection(
                            employeeChoices = state.people?.directory?.items.orEmpty().filter { it.employeeState == "ACTIVE" }.map {
                                com.hiltech.shared.core.design.HiltechRecordChoice(it.employeeId, it.displayName, it.employeeCode)
                            },
                            teamChoices = state.identity.teams.filter { it.organizationId == primary?.organizationId }.map {
                                com.hiltech.shared.core.design.HiltechRecordChoice(it.teamId, it.name, it.code)
                            },
                            reservationPort = reservationPort,
                            assignedWork = state.field?.today.orEmpty(),
                            openAssignedWork = if (ShellWorkspace.Work in destinations) ({ id: String -> openAssignedWork(id) }) else null,
                            readableEmployeeIds = state.people?.directory?.takeIf { it.canManagePeople }?.items.orEmpty().map { it.employeeId }.toSet(),
                            openResponsibleEmployee = if (useWorkspaceNavigation && ShellWorkspace.People in destinations) { employeeId ->
                                nativeSession.close()
                                requestedWorkspace = ShellWorkspace.People
                                onSelectEmployee(employeeId)
                            } else null,
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
                        Text(hiltechText("تسجيل الخروج", "Sign out"))
                    }
                }

                is HiltechShellState.AccessDenied -> {
                    Text(
                        hiltechText("الوصول غير متاح", "Access unavailable"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(state.message)
                    Text(hiltechText("المرجع: ", "Reference: ") + state.code)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onRetry) { Text(hiltechText("حاول مرة أخرى", "Try again")) }
                        OutlinedButton(onClick = onSignOut) {
                            Text(hiltechText("تسجيل الخروج", "Sign out"))
                        }
                    }
                }

                is HiltechShellState.ConfigurationRequired -> {
                    Text(
                        hiltechText("لم يتم إعداد HILTECH", "HILTECH is not configured"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(state.message)
                }

                is HiltechShellState.Failure -> {
                    Text(
                        hiltechText("حدث خطأ", "Something went wrong"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(state.message)
                    Text(hiltechText("المرجع: ", "Reference: ") + state.code)
                    Button(onClick = onRetry) { Text(hiltechText("إعادة المحاولة", "Retry")) }
                }
            }
        }

        if (finding && useWorkspaceNavigation && signedIn != null) {
            val commands = buildList {
                destinations.forEach { workspace ->
                    add(WorkspaceSearchResult("workspace:${workspace.name}", language.text(workspace.displayLabel, workspace.label), language.text("مساحة عمل", "Workspace")) {
                        nativeSession.close()
                        requestedWorkspace = workspace
                    })
                }
                nativeWorkspaces.filter { nativeSession.availability[it.id] == WorkspaceAvailability.AVAILABLE }.forEach { workspace ->
                    add(WorkspaceSearchResult("specialist:${workspace.id}", nativeWorkspaceTitle(workspace, language), language.text("العمليات", "Operations")) {
                        requestedWorkspace = ShellWorkspace.Explore
                        nativeSession.open(workspace.id)
                    })
                }
                signedIn.projects?.list?.items.orEmpty().forEach { project ->
                    add(WorkspaceSearchResult(
                        "project:${project.projectId}", project.name, language.text("مشروع", "Project") + " · ${project.projectCode}",
                    ) {
                        nativeSession.close()
                        requestedWorkspace = ShellWorkspace.Projects
                        onSelectProject(project.projectId)
                    })
                }
                signedIn.field?.today.orEmpty().forEach { work ->
                    add(WorkspaceSearchResult(
                        "work:${work.workOrderId}", work.title,
                        language.text("تكليف", "Assignment") + " · ${work.workOrderCode} · ${work.project.name} · ${work.site.name}",
                    ) { openAssignedWork(work.workOrderId) })
                }
                signedIn.people?.directory?.takeIf { it.canManagePeople }?.items.orEmpty().forEach { employee ->
                    add(WorkspaceSearchResult(
                        "employee:${employee.employeeId}", employee.displayName, language.text("موظف", "Employee") + " · ${employee.employeeCode}",
                    ) {
                        nativeSession.close()
                        requestedWorkspace = ShellWorkspace.People
                        onSelectEmployee(employee.employeeId)
                    })
                }
            }
            WorkspaceSearch(commands, onDismiss = { finding = false }, compact = compactNavigation)
        }
    }
    }
}


@Composable
private fun HomeWorkspaceLink(title: String, detail: String, workspace: ShellWorkspace, onOpen: () -> Unit) {
    val colors = com.hiltech.shared.core.design.LocalHiltechOperationalColors.current
    androidx.compose.material3.Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth(),
        shape = com.hiltech.shared.core.design.HiltechShape.medium, color = colors.surface,
        contentColor = colors.primaryText) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            WorkspaceGlyph(workspace, colors.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            }
            Text(hiltechText("فتح", "Open"), color = colors.accent)
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
            LocalHiltechLanguage.current.direction,
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
    relatedProjects: List<com.hiltech.shared.core.projects.ProjectSummaryDto> = emptyList(),
    openRelatedProject: ((String) -> Unit)? = null,
) {
    val colors = com.hiltech.shared.core.design.LocalHiltechOperationalColors.current
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var directoryMode by remember { mutableStateOf(true) }
    val employee = state.selectedEmployee
    var showDetail by remember(employee?.employeeId) { mutableStateOf(employee != null) }
    var tab by remember(employee?.employeeId) { mutableStateOf(PeopleWorkspaceTab.Profile) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                com.hiltech.shared.core.design.HiltechSectionHeading("PEOPLE / ORGANIZATION", hiltechText("الأفراد", "People"),
                    hiltechText("دليل الفريق، المسؤوليات، وحسابات العمل.", "Team directory, responsibilities and work accounts."))
            }
            androidx.compose.material3.TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
        }
        if (state.loading) Text(hiltechText("جارٍ تحديث بيانات الفريق…", "Refreshing team data…"), color = colors.secondaryText)
        state.errorMessage?.let {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechStateTag(hiltechText("تعذر تحديث البيانات", "Unable to refresh data"), HiltechStateTone.ATTENTION)
                Text(it)
            }
        }
        val directory = state.directory
        if (directory != null) {
            val browser: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(selected = directoryMode, onClick = { directoryMode = true },
                            label = { Text(hiltechText("الموظفون", "Employees")) })
                        if (state.workforceStructure != null) {
                            androidx.compose.material3.FilterChip(selected = !directoryMode, onClick = { directoryMode = false },
                                label = { Text(hiltechText("هيكل الفريق", "Team structure")) })
                        }
                    }
                    if (directoryMode || state.workforceStructure == null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(hiltechText("دليل الموظفين", "Employee directory"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (directory.canManagePeople) Button(enabled = !state.loading, onClick = { creating = !creating }) {
                                Text(if (creating) hiltechText("إغلاق", "Close") else hiltechText("+ موظف", "+ Employee"))
                            }
                        }
                        if (creating && directory.canManagePeople) HiltechPanel(Modifier.fillMaxWidth()) {
                            PeopleCreateForm(onCreateEmployee = onCreateEmployee, canSubmit = !state.loading)
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(hiltechText("الاسم أو كود الموظف", "Employee name or code")) }, singleLine = true)
                        val matches = directory.items.filter { item ->
                            query.isBlank() || item.displayName.contains(query.trim(), ignoreCase = true) ||
                                item.employeeCode.contains(query.trim(), ignoreCase = true)
                        }
                        Text(hiltechText("المعروض: ", "Visible: ") + matches.size, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                        if (matches.isEmpty()) Text(if (query.isBlank()) hiltechText("لا توجد سجلات متاحة.", "No available records.") else hiltechText("لا توجد نتائج مطابقة.", "No matching results."))
                        matches.forEach { item ->
                            androidx.compose.material3.Surface(
                                onClick = { creating = false; showDetail = true; onSelectEmployee(item.employeeId) },
                                enabled = directory.canManagePeople && !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (employee?.employeeId == item.employeeId) colors.selected else colors.surface,
                                contentColor = colors.primaryText,
                                shape = com.hiltech.shared.core.design.HiltechShape.medium,
                            ) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    androidx.compose.material3.Surface(color = colors.surfaceElevated,
                                        shape = com.hiltech.shared.core.design.HiltechShape.small) {
                                        Text(item.displayName.trim().take(2), Modifier.padding(12.dp),
                                            style = MaterialTheme.typography.titleMedium, color = colors.accent)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(item.employeeCode, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                                        Text(if (item.linkedIdentity) hiltechText("حساب مرتبط", "Account linked") else hiltechText("حساب غير مرتبط", "Account not linked"),
                                            color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                                    }
                                    HiltechStateTag(employeeStateLabel(item.employeeState),
                                        if (item.employeeState == "ACTIVE") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                                }
                            }
                        }
                    } else {
                        val groups = state.workforceStructure?.items.orEmpty().groupBy { it.teamName ?: "بدون فريق" }
                        if (groups.isEmpty()) Text(hiltechText("لا توجد تعيينات متاحة.", "No assignments available."))
                        groups.forEach { (team, assignments) ->
                            HiltechPanel(Modifier.fillMaxWidth()) {
                                Text(team, style = MaterialTheme.typography.titleMedium)
                                assignments.forEach { assignment ->
                                    androidx.compose.material3.HorizontalDivider(color = colors.divider)
                                    Text(assignment.employeeDisplayName, style = MaterialTheme.typography.titleSmall)
                                    Text(assignment.roleLabel ?: assignment.roleCode, color = colors.secondaryText)
                                    assignment.reportsToDisplayName?.let { Text(hiltechText("المسؤول المباشر: ", "Reports to: ") + it,
                                        style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }

            val detail: @Composable () -> Unit = {
                if (employee == null || !directory.canManagePeople) {
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(hiltechText("اختر موظفًا", "Select an employee"), style = MaterialTheme.typography.titleLarge)
                        Text(hiltechText("افتح سجل الموظف للاطلاع على ملفه، مسؤولياته، وحساب العمل.", "Open an employee to view their profile, responsibilities and account."))
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(employee.employeeCode, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                            Text(employee.displayName, style = MaterialTheme.typography.headlineSmall)
                            HiltechStateTag(employeeStateLabel(employee.employeeState),
                                if (employee.employeeState == "ACTIVE") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                            state.selectedWorkforceAssignment?.let {
                                Text(it.roleLabel ?: it.roleCode, style = MaterialTheme.typography.titleMedium)
                                it.teamName?.let { name -> Text(name, color = colors.secondaryText) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PeopleWorkspaceTab.entries.filter {
                                (it != PeopleWorkspaceTab.Account || accountWorkspace != null) &&
                                    (it != PeopleWorkspaceTab.Offboarding || offboardingAvailable)
                            }.forEach { item ->
                                androidx.compose.material3.FilterChip(selected = tab == item, onClick = { tab = item },
                                    label = { Text(hiltechText(item.title, item.english)) })
                            }
                        }
                        when (tab) {
                            PeopleWorkspaceTab.Profile -> HiltechPanel(Modifier.fillMaxWidth()) {
                                Text(hiltechText("بيانات الموظف", "Employee information"), style = MaterialTheme.typography.titleMedium)
                                employee.legalName?.takeUnless { it == employee.displayName }?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("الاسم الرسمي", "Legal name"), it) }
                                employee.employmentTypeCode?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("نوع التوظيف", "Employment type"), it) }
                                employee.mobile?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("الهاتف", "Phone"), it) }
                                employee.email?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("البريد الإلكتروني", "Email"), it) }
                                employee.hireDate?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("تاريخ التعيين", "Hire date"), it) }
                                com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("حساب العمل", "Work account"),
                                    if (employee.linkedIdentityId != null) hiltechText("مرتبط", "Linked") else hiltechText("لم يتم ربطه", "Not linked"))
                            }
                            PeopleWorkspaceTab.Assignment -> {
                                val ownedProjects = relatedProjects.filter { project ->
                                    project.currentResponsibility.principalType == "EMPLOYEE" &&
                                        project.currentResponsibility.principalId == employee.employeeId
                                }
                                if (openRelatedProject != null && ownedProjects.isNotEmpty()) {
                                    HiltechPanel(Modifier.fillMaxWidth()) {
                                        Text(hiltechText("المشاريع المرتبطة بمسؤولية الموظف", "Projects owned by this employee"),
                                            style = MaterialTheme.typography.titleMedium)
                                        ownedProjects.forEach { project ->
                                            androidx.compose.material3.Surface(onClick = { openRelatedProject(project.projectId) },
                                                color = colors.surfaceElevated, contentColor = colors.primaryText,
                                                shape = com.hiltech.shared.core.design.HiltechShape.small,
                                                modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text(project.name, style = MaterialTheme.typography.titleSmall)
                                                    Text(project.projectCode, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                }
                                val assignment = state.selectedWorkforceAssignment
                                if (assignment != null) WorkforceAssignmentChangeSection(employee = employee, current = assignment,
                                    structure = state.workforceStructure, history = state.selectedWorkforceHistory,
                                    onChange = onChangeWorkforceAssignment)
                                else Text(hiltechText("لم تتوفر بيانات التعيين الحالي.", "Current assignment data is unavailable."))
                            }
                            PeopleWorkspaceTab.Account -> if (!state.loading) accountWorkspace?.invoke(employee)
                            PeopleWorkspaceTab.Offboarding -> if (offboardingAvailable) OffboardingAdminSection(
                                employee = employee, offboarding = state.selectedOffboarding,
                                onStart = onStartOffboarding, onRevokeAccess = onRevokeOffboardingAccess,
                                onResolveClearance = onResolveOffboardingClearance, onComplete = onCompleteOffboarding)
                        }
                    }
                }
            }

            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 900.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(.9f)) { browser() }
                        Column(Modifier.weight(1.6f)) { detail() }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showDetail && employee != null && directory.canManagePeople) {
                            androidx.compose.material3.TextButton(onClick = { showDetail = false }) { Text(hiltechText("العودة إلى الدليل", "Back to directory")) }
                            detail()
                        } else browser()
                    }
                }
            }
        }

        state.ownProfile?.let { own ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("ملفي الشخصي", "My profile"), style = MaterialTheme.typography.titleMedium)
                Text(own.displayName, style = MaterialTheme.typography.titleLarge)
                Text(own.employeeCode, color = colors.secondaryText)
                HiltechStateTag(employeeStateLabel(own.employeeState), HiltechStateTone.NEUTRAL)
                own.employmentTypeCode?.let { Text(it) }
                state.ownWorkforceAssignment?.let { assignment ->
                    com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("المسؤولية", "Responsibility"), assignment.roleLabel ?: assignment.roleCode)
                    assignment.teamName?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("الفريق", "Team"), it) }
                    assignment.reportsToDisplayName?.let { com.hiltech.shared.core.design.HiltechKeyValue(hiltechText("المسؤول المباشر", "Reports to"), it) }
                    Text(assignment.effectiveFrom, style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                }
            }
        }
    }
}

private enum class PeopleWorkspaceTab(val title: String, val english: String) {
    Profile("الملف", "Profile"), Assignment("المسؤوليات", "Responsibilities"), Account("حساب العمل", "Work account"), Offboarding("إنهاء الخدمة", "Offboarding"),
}

@Composable
private fun employeeStateLabel(state: String): String = when (state) {
    "ACTIVE" -> hiltechText("نشط", "Active")
    "PREBOARDING" -> hiltechText("قيد التجهيز", "Preboarding")
    "OFFBOARDING" -> hiltechText("إنهاء الخدمة", "Offboarding")
    "TERMINATED" -> hiltechText("منتهي", "Terminated")
    "INACTIVE" -> hiltechText("غير نشط", "Inactive")
    else -> state
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

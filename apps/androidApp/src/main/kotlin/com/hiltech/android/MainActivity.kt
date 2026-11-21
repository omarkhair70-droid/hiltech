package com.hiltech.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.android.identity.AndroidIdentityRuntime
import com.hiltech.shared.core.AssetLookupState
import com.hiltech.shared.core.HiltechFieldState
import com.hiltech.shared.core.HiltechOnboardingState
import com.hiltech.shared.core.HiltechPeopleState
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.WarehousePhysicalOperatorSection
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.network.HiltechApiException
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

    private val hiltechApplication:
        HiltechApplication
        get() =
            application as HiltechApplication

    private val identityRuntime:
        AndroidIdentityRuntime
        get() =
            hiltechApplication.identityRuntime

    private var assetLookupSequence = 0

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
                onRefreshPeople = ::refreshPeople,
                onRefreshOnboarding =
                    ::refreshOnboarding,
                onLookupAssetTag = ::lookupAssetTag,
                onRefreshFieldWork =
                    ::refreshFieldWork,
                onSelectFieldWork =
                    ::selectFieldWork,
                warehouseOperator = {
                    val signed = shellState as? HiltechShellState.SignedIn
                    val identity = signed?.identity
                    val organization = identity?.organizations?.firstOrNull { it.primary }
                        ?: identity?.organizations?.firstOrNull()
                    if (identity != null && organization != null) {
                        key(identity.identityId, organization.organizationId) {
                            WarehousePhysicalOperatorSection(
                                api = hiltechApplication.warehouseApi,
                                organizationId = organization.organizationId,
                                installationId = hiltechApplication.installationId,
                                operationId = { fields ->
                                    val payload = (listOf(identity.identityId, hiltechApplication.installationId) + fields)
                                        .joinToString("") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-1:" }
                                    java.util.UUID.nameUUIDFromBytes(payload.toByteArray(Charsets.UTF_8)).toString()
                                },
                            )
                        }
                    }
                },
            )
        }

        // Activity recreation must restore identity too; mutable in-memory state does not survive process death.
        if (!handleCallbackIntent(intent)) {
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
            shellState = HiltechShellState.ConfigurationRequired(
                "Set HILTECH API and OIDC issuer configuration before sign-in.",
            )
            return
        }

        shellState = HiltechShellState.Working("Checking your HILTECH session…")
        scope.launch {
            runCatching {
                identityRuntime.restoreIdentity()
            }.onSuccess { identity ->
                if (identity == null) {
                    shellState = HiltechShellState.SignedOut
                } else {
                    showSignedIn(identity)
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                // An explicit denial makes all cached read leases unusable until next verified bootstrap.
                if (AndroidIdentityRuntime.isAccessDenied(failure) ||
                    (failure is HiltechApiException && failure.httpStatus in setOf(401, 403, 404))) {
                    identityRuntime.invalidateOfflineReadIdentity()
                }
                if (failure is HiltechApiException && failure.code == "NETWORK_UNAVAILABLE") {
                    val local = runCatching {
                        identityRuntime.restoreOfflineReadIdentity()?.let { identityId ->
                            hiltechApplication.fieldOfflineReadCache
                                .cachedToday(identityId)?.let { snapshot ->
                                    HiltechShellState.OfflineFieldReadOnly(
                                        identityId = identityId,
                                        field = HiltechFieldState(
                                            today = snapshot.value,
                                            cachedTodayAt = snapshot.fetchedAtEpochMs,
                                        ),
                                    )
                                }
                        }
                    }.getOrNull()
                    if (local != null) {
                        shellState = local
                    } else {
                        showFailure(failure)
                    }
                } else {
                    showFailure(failure)
                }
            }
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
        assetLookupSequence++
        shellState =
            HiltechShellState.SignedIn(
                identity = identity,
                assetLookup = AssetLookupState(),
                people =
                    HiltechPeopleState(
                        loading = true,
                    ),
                onboarding =
                    HiltechOnboardingState(
                        loading = true,
                    ),
                field =
                    HiltechFieldState(
                        loading = true,
                    ),
            )
        refreshSecurity()
        refreshPeople()
        refreshOnboarding()
        refreshFieldWork()
    }

    private fun lookupAssetTag(code: String) {
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val request = ++assetLookupSequence
        shellState = signed.copy(assetLookup = AssetLookupState(loading = true))
        scope.launch {
            val response = runCatching {
                hiltechApplication.warehouseApi.lookupAssetTag(code, hiltechApplication.installationId)
            }
            val current = shellState as? HiltechShellState.SignedIn ?: return@launch
            if (request != assetLookupSequence || current.identity.identityId != signed.identity.identityId) return@launch
            val lookup = response.fold(
                onSuccess = { AssetLookupState(result = it) },
                onFailure = {
                    val unavailable = (it as? HiltechApiException)?.code == "ASSET_UNAVAILABLE"
                    AssetLookupState(message = if (unavailable) "Asset tag unavailable." else "Lookup failed. Try again.")
                },
            )
            shellState = current.copy(assetLookup = lookup)
        }
    }

    private fun refreshFieldWork() {
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val identityId = signed.identity.identityId
        val previous = signed.field ?: HiltechFieldState()
        shellState = signed.copy(field = previous.copy(loading = true, errorMessage = null))

        scope.launch {
            val result = runCatching {
                hiltechApplication.fieldAssignedWorkOfflineRepository.today(
                    identityId, hiltechApplication.installationId,
                )
            }
            val active = shellState as? HiltechShellState.SignedIn ?: return@launch
            if (active.identity.identityId != identityId) return@launch

            result.onSuccess { snapshot ->
                val today = snapshot.value
                val selectedId = active.field?.selectedWorkOrderId?.takeIf { id ->
                    today.any { it.workOrderId == id }
                }
                val selectedResult = selectedId?.let { id ->
                    runCatching {
                        hiltechApplication.fieldAssignedWorkOfflineRepository.jobBundle(
                            identityId, id, hiltechApplication.installationId,
                        )
                    }
                }
                val current = shellState as? HiltechShellState.SignedIn ?: return@onSuccess
                if (current.identity.identityId != identityId) return@onSuccess
                val bundleError = selectedResult?.exceptionOrNull() as? HiltechApiException
                if (bundleError?.httpStatus in setOf(401, 403, 404)) {
                    identityRuntime.invalidateOfflineReadIdentity()
                    shellState = current.copy(field = HiltechFieldState(
                        errorMessage = "تعذر تأكيد صلاحيتك الحالية. لا يمكن عرض تكليف محفوظ.",
                    ))
                    return@onSuccess
                }
                val notVisible = bundleError?.code == "OBJECT_NOT_VISIBLE"
                shellState = current.copy(
                    field = HiltechFieldState(
                        loading = false,
                        today = today,
                        selectedWorkOrderId = if (notVisible) null else selectedId,
                        selectedBundle = selectedResult?.getOrNull()?.value,
                        cachedTodayAt = snapshot.fetchedAtEpochMs,
                        cachedBundleAt = selectedResult?.getOrNull()?.fetchedAtEpochMs,
                        errorMessage = selectedResult?.exceptionOrNull()
                            ?.takeUnless { notVisible }?.message,
                    ),
                )
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                if (current.identity.identityId != identityId) return@onFailure
                val denied = failure is HiltechApiException &&
                    (failure.httpStatus == 401 || failure.httpStatus == 403 ||
                        failure.httpStatus == 404 || failure.code == "REAUTH_REQUIRED")
                if (denied) identityRuntime.invalidateOfflineReadIdentity()
                shellState = current.copy(
                    field = if (denied) {
                        HiltechFieldState(
                            loading = false,
                            errorMessage = "تعذر تأكيد صلاحيتك الحالية. لا يمكن عرض تكليف محفوظ.",
                        )
                    } else {
                        (current.field ?: previous).copy(
                            loading = false,
                            errorMessage = failure.message ?: "Assigned Work could not be loaded.",
                        )
                    },
                )
            }
        }
    }

    private fun selectCachedFieldWork(workOrderId: String, offline: HiltechShellState.OfflineFieldReadOnly) {
        if (offline.field.today.none { it.workOrderId == workOrderId }) return
        shellState = offline.copy(
            field = offline.field.copy(
                loading = true,
                selectedWorkOrderId = workOrderId,
                selectedBundle = null,
                cachedBundleAt = null,
                errorMessage = null,
            ),
        )
        scope.launch {
            val snapshot = runCatching {
                hiltechApplication.fieldOfflineReadCache
                    .cachedBundle(offline.identityId, workOrderId)
            }.getOrNull()
            val current = shellState as? HiltechShellState.OfflineFieldReadOnly ?: return@launch
            if (current.identityId != offline.identityId ||
                current.field.selectedWorkOrderId != workOrderId) return@launch
            shellState = current.copy(
                field = current.field.copy(
                    loading = false,
                    selectedBundle = snapshot?.value,
                    cachedBundleAt = snapshot?.fetchedAtEpochMs,
                    errorMessage = if (snapshot == null) {
                        "تفاصيل العمل دي مش متخزنة على الجهاز. يلزم اتصال بالإنترنت."
                    } else null,
                ),
            )
        }
    }

    private fun selectFieldWork(workOrderId: String) {
        val offline = shellState as? HiltechShellState.OfflineFieldReadOnly
        if (offline != null) {
            selectCachedFieldWork(workOrderId, offline)
            return
        }
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val identityId = signed.identity.identityId
        val field = signed.field ?: HiltechFieldState()
        if (field.today.none { it.workOrderId == workOrderId }) return
        shellState = signed.copy(
            field = field.copy(
                loading = true,
                selectedWorkOrderId = workOrderId,
                selectedBundle = null,
                cachedBundleAt = null,
                errorMessage = null,
            ),
        )
        scope.launch {
            runCatching {
                hiltechApplication.fieldAssignedWorkOfflineRepository.jobBundle(
                    identityId, workOrderId, hiltechApplication.installationId,
                )
            }.onSuccess { snapshot ->
                val current = shellState as? HiltechShellState.SignedIn ?: return@onSuccess
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != workOrderId) return@onSuccess
                shellState = current.copy(
                    field = (current.field ?: HiltechFieldState()).copy(
                        loading = false,
                        selectedBundle = snapshot.value,
                        cachedBundleAt = snapshot.fetchedAtEpochMs,
                        errorMessage = null,
                    ),
                )
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != workOrderId) return@onFailure
                val denied = failure is HiltechApiException &&
                    (failure.httpStatus == 401 || failure.httpStatus == 403 ||
                        failure.httpStatus == 404 || failure.code == "REAUTH_REQUIRED")
                if (denied) identityRuntime.invalidateOfflineReadIdentity()
                if (denied) {
                    shellState = current.copy(
                        field = HiltechFieldState(
                            loading = false,
                            errorMessage = "تعذر تأكيد صلاحيتك الحالية. لا يمكن عرض تكليف محفوظ.",
                        ),
                    )
                } else if (failure is HiltechApiException && failure.code == "OBJECT_NOT_VISIBLE") {
                    shellState = current.copy(
                        field = (current.field ?: HiltechFieldState()).copy(
                            loading = false,
                            selectedWorkOrderId = null,
                            selectedBundle = null,
                            cachedBundleAt = null,
                            errorMessage = "التكليف لم يعد متاحًا لك.",
                        ),
                    )
                    refreshFieldWork()
                } else {
                    shellState = current.copy(
                        field = (current.field ?: HiltechFieldState()).copy(
                            loading = false,
                            errorMessage = failure.message ?: "Job bundle could not be loaded.",
                        ),
                    )
                }
            }
        }
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

    private fun refreshPeople() {
        val signed =
            shellState as?
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

        shellState =
            signed.copy(
                people =
                    (signed.people
                        ?: HiltechPeopleState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            )

        scope.launch {
            val ownProfileResult =
                runCatching {
                    hiltechApplication.peopleApi
                        .own(
                            organizationId =
                                organizationId,
                            installationId =
                                hiltechApplication
                                    .installationId,
                        )
                }
            val ownAssignmentResult =
                runCatching {
                    hiltechApplication
                        .workforceAssignmentApi
                        .own(
                            organizationId =
                                organizationId,
                            installationId =
                                hiltechApplication
                                    .installationId,
                        )
                }

            val current =
                shellState as?
                    HiltechShellState.SignedIn
            if (
                current != null &&
                current.identity.identityId ==
                signed.identity.identityId
            ) {
                val profileFailure =
                    ownProfileResult
                        .exceptionOrNull()
                val assignmentFailure =
                    ownAssignmentResult
                        .exceptionOrNull()

                val profileMessage =
                    when {
                        profileFailure == null ->
                            null

                        profileFailure is HiltechApiException &&
                            profileFailure.code ==
                            "EMPLOYEE_PROFILE_NOT_FOUND" ->
                            "No employee profile is linked to this identity yet."

                        else ->
                            profileFailure.message
                                ?: "People profile could not be loaded."
                    }

                val assignmentMessage =
                    when {
                        assignmentFailure == null ->
                            null

                        ownProfileResult.isFailure ->
                            null

                        assignmentFailure is HiltechApiException &&
                            assignmentFailure.code ==
                            "WORKFORCE_ASSIGNMENT_NOT_FOUND" ->
                            "No current workforce assignment yet."

                        else ->
                            assignmentFailure.message
                                ?: "Workforce assignment could not be loaded."
                    }

                shellState =
                    current.copy(
                        people =
                            HiltechPeopleState(
                                ownProfile =
                                    ownProfileResult
                                        .getOrNull(),
                                ownWorkforceAssignment =
                                    ownAssignmentResult
                                        .getOrNull(),
                                errorMessage =
                                    listOfNotNull(
                                        profileMessage,
                                        assignmentMessage,
                                    ).takeIf {
                                        it.isNotEmpty()
                                    }?.joinToString(" "),
                            ),
                    )
            }
        }
    }

    private fun refreshOnboarding() {
        val signed =
            shellState as?
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

        shellState =
            signed.copy(
                onboarding =
                    (signed.onboarding
                        ?: HiltechOnboardingState())
                        .copy(
                            loading = true,
                            errorMessage = null,
                        ),
            )

        scope.launch {
            val onboardingResult =
                runCatching {
                    hiltechApplication
                        .onboardingApi
                        .own(
                            organizationId =
                                organizationId,
                            installationId =
                                hiltechApplication
                                    .installationId,
                        )
                }
            val documentsResult =
                runCatching {
                    hiltechApplication
                        .hrDocumentsApi
                        .ownDocuments(
                            organizationId =
                                organizationId,
                            installationId =
                                hiltechApplication
                                    .installationId,
                        )
                }
            val certificationsResult =
                runCatching {
                    hiltechApplication
                        .hrDocumentsApi
                        .ownCertifications(
                            organizationId =
                                organizationId,
                            installationId =
                                hiltechApplication
                                    .installationId,
                        )
                }

            val current =
                shellState as?
                    HiltechShellState.SignedIn
            if (
                current != null &&
                current.identity.identityId ==
                    signed.identity.identityId
            ) {
                val onboardingFailure =
                    onboardingResult
                        .exceptionOrNull()
                val onboardingMessage =
                    when {
                        onboardingFailure ==
                            null -> null
                        onboardingFailure is
                            HiltechApiException &&
                            onboardingFailure.code ==
                            "ONBOARDING_CASE_NOT_FOUND" ->
                            null
                        onboardingFailure is
                            HiltechApiException &&
                            onboardingFailure.code ==
                            "OWN_EMPLOYEE_NOT_FOUND" ->
                            null
                        else ->
                            onboardingFailure
                                ?.message
                                ?: "Onboarding could not be loaded."
                    }

                shellState =
                    current.copy(
                        onboarding =
                            HiltechOnboardingState(
                                ownCase =
                                    onboardingResult
                                        .getOrNull(),
                                ownDocuments =
                                    documentsResult
                                        .getOrNull(),
                                ownCertifications =
                                    certificationsResult
                                        .getOrNull(),
                                selectedEmployeeCase =
                                    current.onboarding
                                        ?.selectedEmployeeCase,
                                errorMessage =
                                    onboardingMessage,
                            ),
                    )
            }
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

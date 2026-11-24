package com.hiltech.android

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hiltech.android.sync.HiltechSyncWorker
import com.hiltech.shared.core.field.FieldExecutionIntent
import com.hiltech.shared.core.field.FieldQueuedIntent
import java.util.UUID
import android.os.Bundle
import com.hiltech.android.field.PendingEvidenceCapture
import com.hiltech.android.field.PendingEvidenceCaptureState
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.android.identity.AndroidIdentityRuntime
import com.hiltech.shared.core.AssetLookupState
import com.hiltech.shared.core.FieldEngineeringTestDraft
import com.hiltech.shared.core.HiltechFieldState
import com.hiltech.shared.core.work.FieldEngineeringTestCaptureDto
import com.hiltech.shared.core.work.FieldPersonalIssuedStockLineDto
import com.hiltech.shared.core.HiltechOnboardingState
import com.hiltech.shared.core.HiltechPeopleState
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
import com.hiltech.shared.core.procurement.servicePurchaseAnyCurrentSourceVisible
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.WarehousePhysicalOperatorSection
import com.hiltech.shared.core.procurement.PurchaseRequirementOperatorSection
import com.hiltech.shared.core.procurement.WarehouseReplenishmentSection
import com.hiltech.shared.core.procurement.OfficeStockNeedSection
import com.hiltech.shared.core.procurement.AssetPurchaseNeedSection
import com.hiltech.shared.core.procurement.PoWarehouseIntakeSection
import com.hiltech.shared.core.procurement.SupplierInvoiceFinanceSection
import com.hiltech.shared.core.identity.IdentityApiException
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.NativeOidcException
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CompletableDeferred
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

    private var pendingEvidenceCapture: PendingEvidenceCapture? = null

    private val chooseEvidenceDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val request = pendingEvidenceCapture
            pendingEvidenceCapture = null
            if (uri != null && request != null) captureEvidenceUri(request, uri)
        }

    // Separate bounded bank CSV picker, never routed through field-work Evidence.
    private var pendingBankCsvPicker: CompletableDeferred<FinanceBankCsvSelectedFile?>? = null
    private val chooseBankCsvFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val awaiting = pendingBankCsvPicker
            pendingBankCsvPicker = null
            if (awaiting != null) {
                if (uri == null) awaiting.complete(null) else scope.launch(Dispatchers.IO) {
                    try {
                        var fileName: String? = null
                        contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (column >= 0) fileName = cursor.getString(column)
                            }
                        }
                        val name = requireNotNull(fileName) { "Original bank CSV filename missing." }
                        require(name.endsWith(".csv", ignoreCase = true))
                        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                require(out.size() <= 2 * 1024 * 1024) {
                                    "Bank CSV cannot exceed 2 MiB."
                                }
                            }
                            out.toByteArray()
                        } ?: throw IllegalArgumentException("Bank CSV URI is unavailable.")
                        require(bytes.isNotEmpty())
                        val sha = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(bytes).joinToString("") { byte ->
                                "%02x".format(byte.toInt() and 0xff)
                            }
                        awaiting.complete(FinanceBankCsvSelectedFile(
                            bytes = bytes,
                            sha256 = sha,
                            originalFileName = name,
                            capturedAt = Instant.now().toString(),
                        ))
                    } catch (failure: Exception) {
                        awaiting.completeExceptionally(failure)
                    }
                }
            }
        }

    private suspend fun pickBankCsvFile(): FinanceBankCsvSelectedFile? {
        check(pendingBankCsvPicker == null) { "Bank file picker already open." }
        val awaiting = CompletableDeferred<FinanceBankCsvSelectedFile?>()
        pendingBankCsvPicker = awaiting
        try {
            chooseBankCsvFile.launch(arrayOf("text/*", "application/octet-stream"))
            return awaiting.await()
        } finally {
            if (pendingBankCsvPicker === awaiting) pendingBankCsvPicker = null
        }
    }

    private val takeEvidencePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val request = pendingEvidenceCapture
            pendingEvidenceCapture = null
            val photo = request?.cameraFile
            if (success && request != null && photo != null) {
                captureEvidenceCamera(request, photo)
            } else {
                photo?.delete()
            }
        }

    private var assetLookupSequence = 0
    private var personalIssuedStockSequence = 0
    private var assignedAssetLookupSequence = 0
    private var engineeringTestSequence = 0

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
        // Android may destroy this Activity while the system picker or camera
        // is open. Recover its scoped request before ActivityResult delivery.
        pendingEvidenceCapture = PendingEvidenceCaptureState.restore(
            savedInstanceState,
            File(filesDir, "field-camera"),
        )

        setContent {
            var fieldFocused by remember { mutableStateOf(false) }
            var fieldBackRequest by remember { mutableStateOf(0) }
            BackHandler(enabled = fieldFocused) {
                fieldBackRequest += 1
            }
            HiltechShell(
                state = shellState,
                operationalWorkspaces = (shellState as? HiltechShellState.SignedIn)?.let { signed ->
                    val org = signed.identity.organizations.firstOrNull { it.primary }
                        ?: signed.identity.organizations.firstOrNull()
                    if (org == null) emptyList() else buildList {
                        if (BuildConfig.HILTECH_EXECUTIVE_HOME_ENABLED) {
                            add(executiveNativeWorkspace(
                                client = hiltechApplication.executiveApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                                authorityRevisionKey =
                                    signed.identity.identityVersion.toString() + ":" +
                                        org.membershipVersion.toString(),
                                // Android original Projects detail route not mounted yet.
                            ))
                        }
                        if (BuildConfig.HILTECH_OWN_PAYSLIPS_ENABLED) {
                            add(ownPayslipNativeWorkspace(
                                client = hiltechApplication.payrollOwnApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                            ))
                        }
                        if (BuildConfig.HILTECH_PAYROLL_ADMIN_READ_ENABLED) {
                            add(payrollAdminReadNativeWorkspace(
                                client = hiltechApplication.payrollAdminApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                            ))
                        }
                        if (BuildConfig.HILTECH_COMMERCIAL_READ_ENABLED) {
                            add(commercialReadNativeWorkspace(
                                client = hiltechApplication.commercialApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                            ))
                        }
                        if (BuildConfig.HILTECH_HANDOVER_READ_ENABLED) {
                            add(handoverReadNativeWorkspace(
                                projects = hiltechApplication.readProjectsApi,
                                handover = hiltechApplication.handoverApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                            ))
                        }
                        if (BuildConfig.HILTECH_SERVICE_OPERATIONS_READ_ENABLED) {
                            add(serviceOperationsReadNativeWorkspace(
                                projects = hiltechApplication.readProjectsApi,
                                ticketsApi = hiltechApplication.supportServiceApi,
                                warrantyApi = hiltechApplication.warrantyReadinessApi,
                                timeApi = hiltechApplication.supportTimeApi,
                                agendaApi = hiltechApplication.maintenanceAgendaApi,
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                            ))
                        }
                        if (BuildConfig.HILTECH_FINANCE_REVIEW_PREPARE_ENABLED) {
                            add(financeCashboxOpeningWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                                api = hiltechApplication.financeEvidenceReadApis.cash,
                                newOperationId = { UUID.randomUUID().toString() },
                            ))
                            add(financeCompletionOperatorWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                                apis = hiltechApplication.financeEvidenceReadApis,
                                newOperationId = { UUID.randomUUID().toString() },
                            ))
                        }
                        if (BuildConfig.HILTECH_FINANCE_BANK_CSV_ENABLED) {
                            add(financeBankCsvIntakeNativeWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                                api = hiltechApplication.financeBankIntakeApi,
                                uploader = hiltechApplication.financeBankCsvUploader,
                                pickCsvFile = { pickBankCsvFile() },
                                newOperationId = { UUID.randomUUID().toString() },
                                deriveProposalId = { operation ->
                                    UUID.nameUUIDFromBytes(
                                        "hiltech:bank-statement-intake:$operation"
                                            .toByteArray(Charsets.UTF_8),
                                    ).toString()
                                },
                            ))
                        }
                        if (BuildConfig.HILTECH_FINANCE_EVIDENCE_READ_ENABLED ||
                            BuildConfig.HILTECH_FINANCE_REVIEW_PREPARE_ENABLED) {
                            add(financeEvidenceReadNativeWorkspace(
                                identityId = signed.identity.identityId,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                                apis = hiltechApplication.financeEvidenceReadApis,
                                reviewPreparationEnabled = BuildConfig.HILTECH_FINANCE_REVIEW_PREPARE_ENABLED,
                                newOperationId = { UUID.randomUUID().toString() },
                            ))
                        }
                        if (BuildConfig.HILTECH_FINANCE_INVOICE_READ_ENABLED) {
                            add(supplierInvoiceFinanceReadWorkspace(
                                client = hiltechApplication.supplierInvoiceFinanceApi,
                                organizationId = org.organizationId,
                                installationId = hiltechApplication.installationId,
                                newOperationId = { UUID.randomUUID().toString() },
                            ))
                        }
                        if (BuildConfig.HILTECH_SERVICE_PURCHASES_ENABLED &&
                            BuildConfig.HILTECH_SERVICE_PURCHASES_WORKSPACE_ENABLED
                        ) {
                            add(NativeOperationalWorkspace(
                                id = "service-purchasing",
                                label = "مشتريات الخدمات",
                                canReadSource = {
                                    servicePurchaseAnyCurrentSourceVisible(
                                        needs = hiltechApplication.servicePurchaseNeedApi,
                                        sourcing = hiltechApplication.serviceSupplierSourcingApi,
                                        orders = hiltechApplication.serviceOrderAndInvoiceApi,
                                        organizationId = org.organizationId,
                                        installationId = hiltechApplication.installationId,
                                    )
                                },
                                hostOwnsScroll = true,
                                content = { _ ->
                                    key(signed.identity.identityId, org.organizationId, "service-purchases") {
                                        AndroidServicePurchaseSections(
                                            application = hiltechApplication,
                                            organizationId = org.organizationId,
                                        )
                                    }
                                },
                            ))
                        }
                    }
                } ?: emptyList(),
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
                onFieldFocusChanged = { focused -> fieldFocused = focused },
                fieldBackRequest = fieldBackRequest,
                onRefreshPersonalIssuedStock =
                    ::refreshPersonalIssuedStockSelected,
                showPersonalIssuedStock = BuildConfig.HILTECH_PERSONAL_ISSUED_STOCK_ENABLED,
                onQueueMaterialUsage = ::queueMaterialUsage,
                showMaterialUsageReporting = BuildConfig.HILTECH_MATERIAL_USAGE_REPORTING_ENABLED,
                onLookupAssignedAssetTag = ::lookupAssignedAssetTag,
                showAssignedAssetLookup = BuildConfig.HILTECH_ASSIGNED_ASSET_LOOKUP_ENABLED,
                onSubmitEngineeringTest = ::submitEngineeringTest,
                showEngineeringTests = BuildConfig.HILTECH_ENGINEERING_TESTS_ENABLED,
                captureFieldIntent = true,
                onQueueFieldIntent = ::queueFieldExecution,
                onQueueCompletion = ::queueFieldCompletion,
                captureEvidence = true,
                onSelectEvidenceFile = ::selectEvidenceFile,
                onTakeEvidencePhoto = ::startEvidencePhoto,
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
                                operatorScopeKey = identity.identityId,
                                operationId = { fields ->
                                    val payload = (listOf(identity.identityId, hiltechApplication.installationId) + fields)
                                        .joinToString("") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-1:" }
                                    java.util.UUID.nameUUIDFromBytes(payload.toByteArray(Charsets.UTF_8)).toString()
                                },
                            )
                            if (BuildConfig.HILTECH_ASSET_PURCHASE_NEEDS_ENABLED) {
                                key(identity.identityId, organization.organizationId, "asset-parts-needs") {
                                    AssetPurchaseNeedSection(
                                        api = hiltechApplication.assetPurchaseNeedApi,
                                        warehouseApi = hiltechApplication.warehouseApi,
                                        organizationId = organization.organizationId,
                                        installationId = hiltechApplication.installationId,
                                        newOperationId = { UUID.randomUUID().toString() },
                                    )
                                }
                            }
                            if (BuildConfig.HILTECH_OFFICE_STOCK_NEEDS_ENABLED) {
                                key(identity.identityId, organization.organizationId, "office-needs") {
                                    OfficeStockNeedSection(
                                        api = hiltechApplication.officeStockNeedApi,
                                        organizationId = organization.organizationId,
                                        installationId = hiltechApplication.installationId,
                                        newOperationId = { UUID.randomUUID().toString() },
                                    )
                                }
                            }
                            if (BuildConfig.HILTECH_WAREHOUSE_REPLENISHMENT_ENABLED) {
                                key(identity.identityId, organization.organizationId, "warehouse-needs") {
                                    WarehouseReplenishmentSection(
                                        api = hiltechApplication.warehouseReplenishmentApi,
                                        warehouseApi = hiltechApplication.warehouseApi,
                                        organizationId = organization.organizationId,
                                        installationId = hiltechApplication.installationId,
                                        newOperationId = { UUID.randomUUID().toString() },
                                    )
                                }
                            }
                            if (BuildConfig.HILTECH_PO_WAREHOUSE_INTAKE_ENABLED) {
                                AndroidPoWarehouseIntakeSection(
                                    api = hiltechApplication.poWarehouseIntakeApi,
                                    warehouseApi = hiltechApplication.warehouseApi,
                                    deliveryNoteUploader = hiltechApplication.poDeliveryNoteUploader,
                                    organizationId = organization.organizationId,
                                    installationId = hiltechApplication.installationId,
                                )
                            }
                        }
                    }
                },
                procurementOperator = {
                    if (BuildConfig.HILTECH_SERVICE_PURCHASES_ENABLED &&
                        !BuildConfig.HILTECH_SERVICE_PURCHASES_WORKSPACE_ENABLED
                    ) {
                        val signed = shellState as? HiltechShellState.SignedIn
                        val identity = signed?.identity
                        val organization = identity?.organizations?.firstOrNull { it.primary }
                            ?: identity?.organizations?.firstOrNull()
                        if (identity != null && organization != null) {
                            key(identity.identityId, organization.organizationId, "service-purchases") {
                                AndroidServicePurchaseSections(
                                    application = hiltechApplication,
                                    organizationId = organization.organizationId,
                                )
                            }
                        }
                    }
                    if (BuildConfig.HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED) {
                        val signed = shellState as? HiltechShellState.SignedIn
                        val identity = signed?.identity
                        val selectedWork = signed?.field?.selectedWorkOrderId
                        if (identity != null && !selectedWork.isNullOrBlank()) {
                            key(identity.identityId, selectedWork) {
                                PurchaseRequirementOperatorSection(
                                    api = hiltechApplication.procurementApi,
                                    warehouseApi = hiltechApplication.warehouseApi,
                                    workApi = hiltechApplication.procurementWorkApi,
                                    installationId = hiltechApplication.installationId,
                                    selectedWorkOrderId = selectedWork,
                                    newOperationId = { UUID.randomUUID().toString() },
                                )
                            }
                        }
                    }
                    if (BuildConfig.HILTECH_SUPPLIER_INVOICE_FINANCE_ENABLED) {
                        val signed = shellState as? HiltechShellState.SignedIn
                        val identity = signed?.identity
                        val organization = identity?.organizations?.firstOrNull { it.primary }
                            ?: identity?.organizations?.firstOrNull()
                        if (identity != null && organization != null) {
                            key(identity.identityId, organization.organizationId, "finance-invoice") {
                                SupplierInvoiceFinanceSection(
                                    api = hiltechApplication.supplierInvoiceFinanceApi,
                                    organizationId = organization.organizationId,
                                    installationId = hiltechApplication.installationId,
                                    newOperationId = { UUID.randomUUID().toString() },
                                )
                            }
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

    override fun onSaveInstanceState(outState: Bundle) {
        PendingEvidenceCaptureState.save(outState, pendingEvidenceCapture)
        super.onSaveInstanceState(outState)
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
                    val priorActor = runCatching {
                        identityRuntime.restoreOfflineReadIdentity()
                    }.getOrNull()
                    identityRuntime.invalidateOfflineReadIdentity()
                    if (priorActor != null) runCatching {
                        hiltechApplication.fieldOfflineReadCache.clearIdentity(priorActor)
                    }
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
        // Drop any previous-session response even if the same actor signs in again.
        personalIssuedStockSequence++
        assignedAssetLookupSequence++
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
        scheduleFieldCommandReplay(identity.identityId)
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
                    runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(identityId) }
                    shellState = current.copy(field = HiltechFieldState(
                        errorMessage = "تعذر تأكيد صلاحيتك الحالية. لا يمكن عرض تكليف محفوظ.",
                    ))
                    return@onSuccess
                }
                val notVisible = bundleError?.code == "OBJECT_NOT_VISIBLE"
                val pending = selectedId?.let { id ->
                    runCatching { hiltechApplication.fieldExecutionQueue.outstanding(identityId, id) }.getOrNull()
                }
                val localEvidence = selectedId?.let { id ->
                    runCatching {
                        hiltechApplication.fieldEvidenceQueue.list(
                            identityId, hiltechApplication.installationId, id,
                        )
                    }.getOrDefault(emptyList())
                } ?: emptyList()
                shellState = current.copy(
                    field = HiltechFieldState(
                        loading = false,
                        today = today,
                        selectedWorkOrderId = if (notVisible) null else selectedId,
                        selectedBundle = selectedResult?.getOrNull()?.value,
                        cachedTodayAt = snapshot.fetchedAtEpochMs,
                        cachedBundleAt = selectedResult?.getOrNull()?.fetchedAtEpochMs,
                        personalIssuedStock = null,
                        personalIssuedStockLoading = false,
                        personalIssuedStockError = null,
                        pendingFieldIntent = pending,
                        localEvidence = localEvidence,
                        errorMessage = selectedResult?.exceptionOrNull()
                            ?.takeUnless { notVisible }?.message,
                    ),
                )
                if (!notVisible && selectedId != null &&
                    selectedResult?.getOrNull()?.value != null &&
                    selectedResult?.getOrNull()?.fetchedAtEpochMs == null) {
                    refreshPersonalIssuedStock(selectedId, identityId)
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                if (current.identity.identityId != identityId) return@onFailure
                val denied = failure is HiltechApiException &&
                    (failure.httpStatus == 401 || failure.httpStatus == 403 ||
                        failure.httpStatus == 404 || failure.code == "REAUTH_REQUIRED")
                if (denied) {
                    identityRuntime.invalidateOfflineReadIdentity()
                    runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(identityId) }
                }
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
                personalIssuedStock = null,
                personalIssuedStockLoading = false,
                personalIssuedStockError = null,
                materialUsageIntents = emptyList(),
                materialUsageMessage = null,
            ),
        )
        scope.launch {
            val snapshot = runCatching {
                hiltechApplication.fieldOfflineReadCache
                    .cachedBundle(offline.identityId, workOrderId)
            }.getOrNull()
            val materialIntents = runCatching {
                hiltechApplication.fieldMaterialUsageQueue.list(
                    offline.identityId,
                    hiltechApplication.installationId,
                    workOrderId,
                )
            }.getOrDefault(emptyList())
            val current = shellState as? HiltechShellState.OfflineFieldReadOnly ?: return@launch
            if (current.identityId != offline.identityId ||
                current.field.selectedWorkOrderId != workOrderId) return@launch
            shellState = current.copy(
                field = current.field.copy(
                    loading = false,
                    selectedBundle = snapshot?.value,
                    cachedBundleAt = snapshot?.fetchedAtEpochMs,
                    materialUsageIntents = materialIntents,
                    materialUsageMessage = null,
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
                personalIssuedStock = null,
                personalIssuedStockLoading = false,
                personalIssuedStockError = null,
                materialUsageIntents = emptyList(),
                materialUsageMessage = null,
                assignedAssetLookup = AssetLookupState(),
                engineeringTestSubmitting = false,
                engineeringTestMessage = null,
                errorMessage = null,
            ),
        )
        scope.launch {
            runCatching {
                hiltechApplication.fieldAssignedWorkOfflineRepository.jobBundle(
                    identityId, workOrderId, hiltechApplication.installationId,
                )
            }.onSuccess { snapshot ->
                val pending = runCatching {
                    hiltechApplication.fieldExecutionQueue.outstanding(identityId, workOrderId)
                }.getOrNull()
                val localEvidence = runCatching {
                    hiltechApplication.fieldEvidenceQueue.list(
                        identityId, hiltechApplication.installationId, workOrderId,
                    )
                }.getOrDefault(emptyList())
                val materialIntents = runCatching {
                    hiltechApplication.fieldMaterialUsageQueue.list(
                        identityId, hiltechApplication.installationId, workOrderId,
                    )
                }.getOrDefault(emptyList())
                val current = shellState as? HiltechShellState.SignedIn ?: return@onSuccess
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != workOrderId) return@onSuccess
                shellState = current.copy(
                    field = (current.field ?: HiltechFieldState()).copy(
                        loading = false,
                        selectedBundle = snapshot.value,
                        cachedBundleAt = snapshot.fetchedAtEpochMs,
                        pendingFieldIntent = pending,
                        fieldIntentMessage = null,
                        localEvidence = localEvidence,
                        materialUsageIntents = materialIntents,
                        materialUsageMessage = null,
                        personalIssuedStock = null,
                        personalIssuedStockLoading = false,
                        personalIssuedStockError = null,
                        assignedAssetLookup = AssetLookupState(),
                        engineeringTestSubmitting = false,
                        engineeringTestMessage = null,
                        errorMessage = null,
                    ),
                )
                if (snapshot.fetchedAtEpochMs == null) {
                    refreshPersonalIssuedStock(workOrderId, identityId)
                }
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != workOrderId) return@onFailure
                val denied = failure is HiltechApiException &&
                    (failure.httpStatus == 401 || failure.httpStatus == 403 ||
                        failure.httpStatus == 404 || failure.code == "REAUTH_REQUIRED")
                if (denied) {
                    identityRuntime.invalidateOfflineReadIdentity()
                    runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(identityId) }
                }
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


    private fun lookupAssignedAssetTag(workOrderId: String, code: String) {
        if (!BuildConfig.HILTECH_ASSIGNED_ASSET_LOOKUP_ENABLED) return
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val field = signed.field ?: return
        val actorId = signed.identity.identityId
        if (field.selectedWorkOrderId != workOrderId ||
            field.selectedBundle?.workOrderId != workOrderId ||
            field.cachedBundleAt != null) return

        val request = ++assignedAssetLookupSequence
        shellState = signed.copy(
            field = field.copy(assignedAssetLookup = AssetLookupState(loading = true)),
        )
        scope.launch {
            val result = runCatching {
                hiltechApplication.fieldAssignedWorkApi.assignedAssetTag(
                    workOrderId, code, hiltechApplication.installationId,
                )
            }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            val current = shellState as? HiltechShellState.SignedIn ?: return@launch
            val selected = current.field ?: return@launch
            if (request != assignedAssetLookupSequence ||
                current.identity.identityId != actorId ||
                selected.selectedWorkOrderId != workOrderId ||
                selected.selectedBundle?.workOrderId != workOrderId ||
                selected.cachedBundleAt != null) return@launch

            val failure = result.exceptionOrNull() as? HiltechApiException
            if (failure?.httpStatus in setOf(401, 403) || failure?.code == "REAUTH_REQUIRED") {
                identityRuntime.invalidateOfflineReadIdentity()
                runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(actorId) }
                shellState = current.copy(
                    field = HiltechFieldState(
                        today = selected.today,
                        errorMessage = "صلاحية عرض التكليف اتغيرت. يلزم إعادة التأكد من السيرفر.",
                    ),
                )
                return@launch
            }

            val lookup = result.fold(
                onSuccess = { AssetLookupState(result = it) },
                onFailure = {
                    val unavailable = (it as? HiltechApiException)?.code ==
                        "FIELD_ASSET_LOOKUP_UNAVAILABLE"
                    AssetLookupState(
                        message = if (unavailable) {
                            "الأصل غير متاح داخل التكليف الحالي."
                        } else {
                            "تعذر قراءة AssetTag. حاول مرة تانية."
                        },
                    )
                },
            )
            shellState = current.copy(field = selected.copy(assignedAssetLookup = lookup))
        }
    }


    private fun submitEngineeringTest(
        workOrderId: String,
        draft: FieldEngineeringTestDraft,
    ) {
        if (!BuildConfig.HILTECH_ENGINEERING_TESTS_ENABLED) return
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val field = signed.field ?: return
        val bundle = field.selectedBundle ?: return
        if (field.selectedWorkOrderId != workOrderId ||
            bundle.workOrderId != workOrderId ||
            field.cachedBundleAt != null ||
            field.engineeringTestSubmitting) return

        val requirement = bundle.evidenceRequirements
            .singleOrNull { it.requirementKey == draft.requirementKey }
            ?: return
        val test = requirement.structuredTest ?: return
        val binding = bundle.binding ?: return
        val rawEvidenceId =
            bundle.existingEvidence.firstOrNull {
                it.requirementKey == draft.requirementKey &&
                    it.storageState == "READY"
            }?.evidenceId
        val actorId = signed.identity.identityId
        val requestSequence = ++engineeringTestSequence
        val operationId = UUID.randomUUID().toString()
        val capturedAtEpochMs = System.currentTimeMillis()
        val capturedAt = Instant.ofEpochMilli(capturedAtEpochMs).toString()
        shellState = signed.copy(
            field = field.copy(
                engineeringTestSubmitting = true,
                engineeringTestMessage = null,
            ),
        )
        scope.launch {
            if (test.rawEvidenceRequired && rawEvidenceId == null) {
                val queued =
                    runCatching {
                        hiltechApplication.fieldEngineeringTestOfflineQueue.queue(
                            actorId = actorId,
                            installationId = hiltechApplication.installationId,
                            bundle = bundle,
                            draft = draft,
                            operationId = operationId,
                            capturedAtEpochMs = capturedAtEpochMs,
                            verifiedOnlineThisSession = true,
                            serverReadyEvidenceId = null,
                        )
                    }
                val current =
                    shellState as? HiltechShellState.SignedIn
                        ?: return@launch
                val selected =
                    current.field ?: return@launch
                if (
                    requestSequence != engineeringTestSequence ||
                    current.identity.identityId != actorId ||
                    selected.selectedWorkOrderId != workOrderId ||
                    selected.selectedBundle?.workOrderId != workOrderId ||
                    selected.cachedBundleAt != null
                ) {
                    return@launch
                }
                queued.fold(
                    onSuccess = {
                        shellState =
                            current.copy(
                                field =
                                    selected.copy(
                                        engineeringTestSubmitting = false,
                                        engineeringTestMessage =
                                            "تم حفظ نتيجة الاختبار محليًا. سيتم رفع الـEvidence ثم اعتماد النتيجة عند رجوع الاتصال.",
                                    ),
                            )
                        scheduleFieldCommandReplay(actorId)
                    },
                    onFailure = { error ->
                        shellState =
                            current.copy(
                                field =
                                    selected.copy(
                                        engineeringTestSubmitting = false,
                                        engineeringTestMessage =
                                            error.message
                                                ?: "لازم تسجل الـEvidence المطلوب قبل حفظ الاختبار أوفلاين.",
                                    ),
                            )
                    },
                )
                return@launch
            }

            val result = runCatching {
                val instrumentAssetId =
                    if (test.instrumentAssetRequired) {
                        val tag = draft.instrumentTag?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: error("Instrument AssetTag is required.")
                        hiltechApplication.fieldAssignedWorkApi.assignedAssetTag(
                            workOrderId,
                            tag,
                            hiltechApplication.installationId,
                        ).assetId
                    } else {
                        null
                    }
                hiltechApplication.fieldAssignedWorkApi.captureEngineeringTest(
                    workOrderId = workOrderId,
                    request = FieldEngineeringTestCaptureDto(
                        operationId = operationId,
                        assignmentId = bundle.assignment.assignmentId,
                        assignmentVersion = bundle.assignment.version,
                        workOrderVersion = bundle.workOrderVersion,
                        evidencePolicyId = binding.evidencePolicy.id,
                        evidencePolicyRevision = binding.evidencePolicy.revision,
                        requirementKey = draft.requirementKey,
                        schemaTemplateId = test.templateId,
                        schemaTemplateRevision = test.templateRevision,
                        schemaVersion = test.schemaVersion,
                        testSubjectRef = draft.testSubjectRef,
                        values = draft.values,
                        instrumentAssetId = instrumentAssetId,
                        rawEvidenceId = rawEvidenceId,
                        deviceReportedStatus = draft.deviceReportedStatus,
                        capturedAt = capturedAt,
                        clientOccurredAt = capturedAt,
                    ),
                    installationId = hiltechApplication.installationId,
                )
            }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            val current = shellState as? HiltechShellState.SignedIn ?: return@launch
            val selected = current.field ?: return@launch
            if (requestSequence != engineeringTestSequence ||
                current.identity.identityId != actorId ||
                selected.selectedWorkOrderId != workOrderId ||
                selected.selectedBundle?.workOrderId != workOrderId ||
                selected.cachedBundleAt != null) return@launch

            val failure = result.exceptionOrNull() as? HiltechApiException
            if (failure?.httpStatus in setOf(401, 403) ||
                failure?.code in setOf("REAUTH_REQUIRED", "OBJECT_NOT_VISIBLE", "ENGINEERING_TEST_UNAVAILABLE")) {
                if (failure?.httpStatus in setOf(401, 403) || failure?.code == "REAUTH_REQUIRED") {
                    identityRuntime.invalidateOfflineReadIdentity()
                    runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(actorId) }
                }
                shellState = current.copy(
                    field = selected.copy(
                        engineeringTestSubmitting = false,
                        engineeringTestMessage =
                            "الاختبار لم يعد متاحًا بالصلاحية/الـconfig الحالي. أعد تحميل التكليف.",
                    ),
                )
                return@launch
            }

            val retryableNetworkFailure =
                failure != null &&
                    (
                        failure.retryable ||
                            failure.code == "NETWORK_UNAVAILABLE" ||
                            failure.httpStatus == 429 ||
                            (failure.httpStatus ?: 0) >= 500
                    )
            if (retryableNetworkFailure) {
                val queued =
                    runCatching {
                        hiltechApplication.fieldEngineeringTestOfflineQueue.queue(
                            actorId = actorId,
                            installationId = hiltechApplication.installationId,
                            bundle = bundle,
                            draft = draft,
                            operationId = operationId,
                            capturedAtEpochMs = capturedAtEpochMs,
                            verifiedOnlineThisSession = true,
                            serverReadyEvidenceId = rawEvidenceId,
                        )
                    }
                if (queued.isSuccess) {
                    shellState =
                        current.copy(
                            field =
                                selected.copy(
                                    engineeringTestSubmitting = false,
                                    engineeringTestMessage =
                                        "الاتصال انقطع؛ النتيجة محفوظة محليًا وستُراجع على السيرفر عند رجوع الشبكة.",
                                ),
                        )
                    scheduleFieldCommandReplay(actorId)
                    return@launch
                }
                shellState =
                    current.copy(
                        field =
                            selected.copy(
                                engineeringTestSubmitting = false,
                                engineeringTestMessage =
                                    queued.exceptionOrNull()?.message
                                        ?: "تعذر حفظ نتيجة الاختبار محليًا.",
                            ),
                    )
                return@launch
            }

            result.fold(
                onSuccess = { receipt ->
                    shellState = current.copy(
                        field = selected.copy(
                            engineeringTestSubmitting = false,
                            engineeringTestMessage =
                                "تم حفظ النتيجة: " + receipt.evaluatedStatus +
                                    " · " + receipt.testSubjectRef,
                        ),
                    )
                },
                onFailure = { error ->
                    val api = error as? HiltechApiException
                    val stale = api?.httpStatus == 409 ||
                        api?.code?.contains("CHANGED") == true ||
                        api?.code?.contains("CALIBRATION") == true
                    shellState = current.copy(
                        field = selected.copy(
                            engineeringTestSubmitting = false,
                            engineeringTestMessage =
                                if (stale) {
                                    "المصدر أو الـschema اتغير. أعد تحميل التكليف قبل المحاولة."
                                } else {
                                    api?.envelope?.message
                                        ?: error.message
                                        ?: "تعذر إرسال نتيجة الاختبار."
                                },
                        ),
                    )
                },
            )
        }
    }


    private fun refreshPersonalIssuedStockSelected(workOrderId: String) {
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        if (signed.field?.selectedWorkOrderId != workOrderId) return
        refreshPersonalIssuedStock(workOrderId, signed.identity.identityId)
    }

    /** S04A: read-only, never cached, never shared across actor or WorkOrder. */
    private fun refreshPersonalIssuedStock(workOrderId: String, actorId: String) {
        if (!BuildConfig.HILTECH_PERSONAL_ISSUED_STOCK_ENABLED) return
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val field = signed.field ?: return
        if (signed.identity.identityId != actorId ||
            field.selectedWorkOrderId != workOrderId ||
            field.cachedBundleAt != null ||
            field.selectedBundle?.workOrderId != workOrderId) return
        val request = ++personalIssuedStockSequence
        shellState = signed.copy(field = field.copy(
            personalIssuedStock = null,
            personalIssuedStockLoading = true,
            personalIssuedStockError = null,
        ))
        scope.launch {
            val result = runCatching {
                hiltechApplication.fieldAssignedWorkApi.personalIssuedStock(
                    workOrderId, hiltechApplication.installationId,
                )
            }
            val materialIntents = runCatching {
                hiltechApplication.fieldMaterialUsageQueue.list(
                    actorId,
                    hiltechApplication.installationId,
                    workOrderId,
                )
            }.getOrDefault(emptyList())
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            val current = shellState as? HiltechShellState.SignedIn ?: return@launch
            val selected = current.field ?: return@launch
            if (request != personalIssuedStockSequence ||
                current.identity.identityId != actorId ||
                selected.selectedWorkOrderId != workOrderId ||
                selected.cachedBundleAt != null ||
                selected.selectedBundle?.workOrderId != workOrderId) return@launch

            val denied = result.exceptionOrNull() as? HiltechApiException
            if (denied?.httpStatus in setOf(401, 403) ||
                denied?.code in setOf("REAUTH_REQUIRED", "OBJECT_NOT_VISIBLE")) {
                identityRuntime.invalidateOfflineReadIdentity()
                runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(actorId) }
                shellState = current.copy(field = HiltechFieldState(
                    today = selected.today,
                    errorMessage = "صلاحية عرض التكليف اتغيرت. يلزم إعادة التأكد من السيرفر.",
                ))
                return@launch
            }
            result.onSuccess { projection ->
                if (projection.workOrderId != workOrderId ||
                    projection.workOrderVersion != selected.selectedBundle?.workOrderVersion) {
                    shellState = current.copy(field = selected.copy(
                        personalIssuedStock = null,
                        personalIssuedStockLoading = false,
                        personalIssuedStockError = "مصدر الخامات اتغير؛ حدّث التكليف قبل القراءة.",
                        materialUsageIntents = materialIntents,
                    ))
                } else {
                    shellState = current.copy(field = selected.copy(
                        personalIssuedStock = projection,
                        personalIssuedStockLoading = false,
                        personalIssuedStockError = null,
                        materialUsageIntents = materialIntents,
                    ))
                }
            }.onFailure { failure ->
                shellState = current.copy(field = selected.copy(
                    personalIssuedStock = null,
                    personalIssuedStockLoading = false,
                    personalIssuedStockError = failure.message ?: "تعذر قراءة الرصيد الحالي.",
                    materialUsageIntents = materialIntents,
                ))
            }
        }
    }

    private fun scheduleFieldCommandReplay(identityId: String) {
        WorkManager.getInstance(this).enqueueUniqueWork(
            "hiltech-field-execution-sync-" + identityId,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HiltechSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    private fun queueFieldCompletion() {
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val actorId = signed.identity.identityId
        val field = signed.field ?: return
        val bundle = field.selectedBundle ?: return
        if (
            field.cachedBundleAt != null ||
            field.selectedWorkOrderId != bundle.workOrderId ||
            field.pendingFieldIntent != null
        ) {
            return
        }

        val operationId = UUID.randomUUID().toString()
        scope.launch {
            runCatching {
                hiltechApplication.fieldCompletionQueue.queue(
                    identityId = actorId,
                    installationId = hiltechApplication.installationId,
                    bundle = bundle,
                    operationId = operationId,
                    nowEpochMs = System.currentTimeMillis(),
                    verifiedOnlineThisSession = true,
                )
            }.onSuccess { queued ->
                val current = shellState as? HiltechShellState.SignedIn ?: return@onSuccess
                val selected = current.field ?: return@onSuccess
                if (
                    current.identity.identityId != actorId ||
                    selected.selectedWorkOrderId != bundle.workOrderId ||
                    selected.selectedBundle?.workOrderVersion != bundle.workOrderVersion ||
                    selected.cachedBundleAt != null
                ) {
                    return@onSuccess
                }
                shellState = current.copy(
                    field = selected.copy(
                        pendingFieldIntent = FieldQueuedIntent(
                            operationId = queued.operationId,
                            commandType = "SubmitWorkCompletion",
                            state = queued.state,
                            targetId = queued.workOrderId,
                        ),
                        fieldIntentMessage =
                            "طلب التسليم محفوظ محليًا، وليس موافقة نهائية. المزامنة ستراجع الشروط على السيرفر.",
                    ),
                )
                scheduleFieldCommandReplay(actorId)
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                val selected = current.field ?: return@onFailure
                if (
                    current.identity.identityId != actorId ||
                    selected.selectedWorkOrderId != bundle.workOrderId
                ) {
                    return@onFailure
                }
                shellState = current.copy(
                    field = selected.copy(
                        fieldIntentMessage =
                            failure.message ?: "تعذر حفظ طلب تسليم العمل للمراجعة.",
                    ),
                )
            }
        }
    }

    private fun queueMaterialUsage(
        item: FieldPersonalIssuedStockLineDto,
        quantity: String,
    ) {
        if (!BuildConfig.HILTECH_MATERIAL_USAGE_REPORTING_ENABLED) return
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val identityId = signed.identity.identityId
        val field = signed.field ?: return
        val bundle = field.selectedBundle ?: return
        val issued = field.personalIssuedStock ?: return
        if (field.cachedBundleAt != null ||
            field.selectedWorkOrderId != bundle.workOrderId ||
            issued.workOrderId != bundle.workOrderId ||
            issued.workOrderVersion != bundle.workOrderVersion ||
            issued.status != "SOURCE_CURRENT") return

        scope.launch {
            runCatching {
                hiltechApplication.fieldMaterialUsageQueue.queue(
                    identityId = identityId,
                    installationId = hiltechApplication.installationId,
                    bundle = bundle,
                    issued = issued,
                    item = item,
                    quantity = quantity,
                    operationId = UUID.randomUUID().toString(),
                    nowEpochMs = System.currentTimeMillis(),
                    verifiedOnlineThisSession = true,
                )
            }.onSuccess {
                val intents = runCatching {
                    hiltechApplication.fieldMaterialUsageQueue.list(
                        identityId,
                        hiltechApplication.installationId,
                        bundle.workOrderId,
                    )
                }.getOrDefault(emptyList())
                val current = shellState as? HiltechShellState.SignedIn ?: return@onSuccess
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != bundle.workOrderId) return@onSuccess
                val latest = current.field ?: return@onSuccess
                shellState = current.copy(
                    field = latest.copy(
                        materialUsageIntents = intents,
                        materialUsageMessage =
                            "اتحفظ بلاغ الاستخدام محليًا. الرصيد مش هيتغير إلا بعد قبول السيرفر ومراجعة المخزن.",
                    ),
                )
                scheduleFieldCommandReplay(identityId)
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != bundle.workOrderId) return@onFailure
                shellState = current.copy(
                    field = (current.field ?: HiltechFieldState()).copy(
                        materialUsageMessage =
                            failure.message ?: "تعذر حفظ بلاغ استخدام الخامة.",
                    ),
                )
            }
        }
    }

    private fun queueFieldExecution(action: FieldExecutionIntent, reason: String?) {
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val identityId = signed.identity.identityId
        val field = signed.field ?: return
        val bundle = field.selectedBundle ?: return
        if (field.cachedBundleAt != null || field.selectedWorkOrderId != bundle.workOrderId) return
        if (field.pendingFieldIntent != null) return

        scope.launch {
            runCatching {
                hiltechApplication.fieldExecutionQueue.queue(
                    identityId = identityId,
                    installationId = hiltechApplication.installationId,
                    bundle = bundle,
                    action = action,
                    operationId = UUID.randomUUID().toString(),
                    nowEpochMs = System.currentTimeMillis(),
                    verifiedOnlineThisSession = true,
                    explanation = reason,
                )
            }.onSuccess { pending ->
                val current = shellState as? HiltechShellState.SignedIn ?: return@onSuccess
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != bundle.workOrderId) return@onSuccess
                val latestField = current.field ?: return@onSuccess
                shellState = current.copy(
                    field = latestField.copy(
                        pendingFieldIntent = pending,
                        fieldIntentMessage = "الأمر محفوظ على الجهاز. لم يتم تأكيد تطبيقه من السيرفر.",
                    ),
                )
                scheduleFieldCommandReplay(identityId)
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                val current = shellState as? HiltechShellState.SignedIn ?: return@onFailure
                if (current.identity.identityId != identityId ||
                    current.field?.selectedWorkOrderId != bundle.workOrderId) return@onFailure
                shellState = current.copy(field = (current.field ?: HiltechFieldState()).copy(
                    fieldIntentMessage = failure.message ?: "تعذر حفظ أمر العمل محليًا.",
                ))
            }
        }
    }

    private fun eligibleEvidenceRequirement(key: String): PendingEvidenceCapture? {
        val signed = shellState as? HiltechShellState.SignedIn ?: return null
        val field = signed.field ?: return null
        val bundle = field.selectedBundle ?: return null
        if (field.cachedBundleAt != null || field.selectedWorkOrderId != bundle.workOrderId ||
            !bundle.executionCapabilities.evidenceCaptureOffline) return null
        val requirement = bundle.evidenceRequirements.singleOrNull { it.requirementKey == key }
            ?: return null
        if (!requirement.offlineCaptureAllowedByPolicy ||
            requirement.allowedContentTypes.isEmpty()) return null
        val binding = bundle.binding ?: return null
        return PendingEvidenceCapture(
            actorId = signed.identity.identityId,
            workOrderId = bundle.workOrderId,
            requirementKey = key,
            assignmentId = bundle.assignment.assignmentId,
            assignmentVersion = bundle.assignment.version,
            workOrderVersion = bundle.workOrderVersion,
            bindingRevision = binding.bindingRevision,
            evidencePolicyId = binding.evidencePolicy.id,
            evidencePolicyRevision = binding.evidencePolicy.revision,
        )
    }

    private fun selectEvidenceFile(key: String) {
        val request = eligibleEvidenceRequirement(key) ?: return
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val bundle = signed.field?.selectedBundle ?: return
        val types = bundle.evidenceRequirements.singleOrNull { it.requirementKey == key }
            ?.allowedContentTypes?.toTypedArray() ?: return
        pendingEvidenceCapture = request
        chooseEvidenceDocument.launch(types)
    }

    private fun startEvidencePhoto(key: String) {
        val request = eligibleEvidenceRequirement(key) ?: return
        val signed = shellState as? HiltechShellState.SignedIn ?: return
        val bundle = signed.field?.selectedBundle ?: return
        if (bundle.evidenceRequirements.none {
                it.requirementKey == key && "image/jpeg" in it.allowedContentTypes
            }) return
        val dir = File(filesDir, "field-camera")
        if (!dir.exists() && !dir.mkdirs()) return
        val photo = File(dir, UUID.randomUUID().toString() + ".jpg")
        val uri = FileProvider.getUriForFile(
            this, packageName + ".field-evidence", photo,
        )
        pendingEvidenceCapture = request.copy(cameraFile = photo)
        takeEvidencePhoto.launch(uri)
    }

    private fun captureEvidenceUri(request: PendingEvidenceCapture, uri: Uri) {
        saveFieldEvidence(request) { evidenceId ->
            hiltechApplication.fieldEvidenceFiles.importFromUri(evidenceId, uri)
        }
    }

    private fun captureEvidenceCamera(request: PendingEvidenceCapture, photo: File) {
        saveFieldEvidence(request) { evidenceId ->
            hiltechApplication.fieldEvidenceFiles.saveFromFile(evidenceId, "image/jpeg", photo)
        }
    }

    private fun saveFieldEvidence(
        request: PendingEvidenceCapture,
        read: suspend (String) -> com.hiltech.android.field.PrivateFieldEvidenceFile,
    ) {
        val evidenceId = UUID.randomUUID().toString()
        scope.launch {
            var staged = false
            runCatching {
                val file = read(evidenceId)
                val signed = shellState as? HiltechShellState.SignedIn
                if (signed != null && signed.identity.identityId != request.actorId) {
                    throw IllegalStateException("Evidence belongs to a different technician.")
                }
                val selected = signed?.field
                // Normal path: the originally verified, non-cached JobBundle
                // still exists even when the network drops during camera capture.
                val inSessionBundle = selected?.takeIf {
                    it.selectedWorkOrderId == request.workOrderId &&
                        it.cachedBundleAt == null
                }?.selectedBundle
                val bundle = inSessionBundle ?: run {
                    if (selected?.selectedWorkOrderId != null &&
                        selected.selectedWorkOrderId != request.workOrderId) {
                        throw IllegalStateException("Selected WorkOrder changed during capture.")
                    }
                    // Activity recreation can deliver camera/picker results before
                    // MainActivity's asynchronous login and Today refresh completes.
                    // Restore current online identity and request a fresh server
                    // JobBundle; a stale offline cache is never capture authority.
                    val verified = identityRuntime.restoreIdentity()
                        ?: throw IllegalStateException("Technician must sign in again before Evidence capture.")
                    if (verified.identityId != request.actorId) {
                        throw IllegalStateException("Technician identity changed during Evidence capture.")
                    }
                    val source = hiltechApplication.fieldAssignedWorkOfflineRepository.jobBundle(
                        request.actorId, request.workOrderId,
                        hiltechApplication.installationId,
                    )
                    if (source.cached || source.fetchedAtEpochMs != null) {
                        throw IllegalStateException("Current online JobBundle is required to recover camera capture.")
                    }
                    source.value
                }
                if (!request.matches(bundle)) {
                    throw IllegalStateException("WorkOrder, assignment or EvidencePolicy changed during capture.")
                }
                val row = hiltechApplication.fieldEvidenceQueue.capture(
                    actorId = request.actorId,
                    installationId = hiltechApplication.installationId,
                    bundle = bundle,
                    requirementKey = request.requirementKey,
                    contentType = file.contentType,
                    sizeBytes = file.sizeBytes,
                    sha256 = file.sha256,
                    localFileRef = file.fileRef,
                    evidenceId = evidenceId,
                    capturedAtEpochMs = System.currentTimeMillis(),
                    verifiedOnlineThisSession = true,
                )
                staged = true
                val records = hiltechApplication.fieldEvidenceQueue.list(
                    request.actorId, hiltechApplication.installationId, request.workOrderId,
                )
                val active = shellState as? HiltechShellState.SignedIn
                val visibleField = active?.field
                if (active?.identity?.identityId == request.actorId &&
                    visibleField != null &&
                    visibleField.selectedWorkOrderId == request.workOrderId) {
                    shellState = active.copy(
                        field = visibleField.copy(
                            localEvidence = records,
                            evidenceMessage = "الدليل محفوظ محليًا (LOCAL_READY)، ولم يُعتمد على السيرفر.",
                        ),
                    )
                }
                scheduleFieldCommandReplay(request.actorId)
                row
            }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) return@onFailure
                if (!staged) {
                    hiltechApplication.fieldEvidenceFiles.removeAfterFailedCapture(evidenceId)
                }
                val active = shellState as? HiltechShellState.SignedIn
                val visibleField = active?.field
                if (active?.identity?.identityId == request.actorId &&
                    visibleField != null &&
                    visibleField.selectedWorkOrderId == request.workOrderId) {
                    shellState = active.copy(field = visibleField.copy(
                        evidenceMessage = failure.message ?: "تعذر حفظ الدليل، ولم يتم رفعه.",
                    ))
                }
            }
            if (staged) request.cameraFile?.delete()
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
        personalIssuedStockSequence++
        val actor = when (val state = shellState) {
            is HiltechShellState.SignedIn -> state.identity.identityId
            is HiltechShellState.OfflineFieldReadOnly -> state.identityId
            else -> null
        }
        shellState = HiltechShellState.Working("Signing out…")
        scope.launch {
            runCatching { identityRuntime.logout() }
            if (actor != null) {
                runCatching { hiltechApplication.fieldOfflineReadCache.clearIdentity(actor) }
            }
            shellState = HiltechShellState.SignedOut
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

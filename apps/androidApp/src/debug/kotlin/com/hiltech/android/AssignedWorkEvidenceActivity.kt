package com.hiltech.android

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import java.io.File
import java.io.FileOutputStream
import com.hiltech.shared.core.local.LocalEvidenceStates
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.hiltech.shared.core.field.FieldExecutionIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.widget.TextView
import com.hiltech.android.identity.AndroidEncryptedOidcStore
import com.hiltech.shared.core.identity.auth.NativeOidcTokenSet
import kotlinx.coroutines.runBlocking
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.HiltechFieldState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentityOrganizationDto
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldBlockerDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldEvidenceRequirementDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldReadinessRequirementDto
import com.hiltech.shared.core.work.FieldResourceRequirementDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldTodayItemDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.FieldInstructionDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto

class AssignedWorkEvidenceActivity :
    ComponentActivity() {
    // Debug-only, high-contrast visual markers make emulator screenshots
    // independently reviewable instead of showing an empty startup surface.
    private fun proofMarkerView(marker: String, explanation: String) =
        TextView(this).apply {
            text = "$marker\n\n$explanation"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)
        val mode =
            intent.getStringExtra("mode")
                ?: "today"

        if (mode in setOf("evidence-seed", "evidence-persisted", "evidence-corrupt")) {
            // Synthetic debug Evidence proof only, isolated from production APIs and credentials.
            val app = application as HiltechApplication
            val identity = fixtureIdentity()
            val evidenceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa12"
            val syntheticBundle = jobBundle().copy(
                lifecycleState = "IN_PROGRESS",
                readinessState = "READY",
                executionCapabilities = FieldExecutionCapabilitiesDto(
                    startOffline = false, blockOffline = true, resumeOffline = false,
                    evidenceCaptureOffline = true, submitOffline = false,
                    authoritativeOfflineQueue = false,
                ),
            )
            if (mode == "evidence-seed") {
                val photo = File(filesDir, "field-evidence-native-fixture.jpg")
                FileOutputStream(photo).use { output ->
                    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.BLUE)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                    bitmap.recycle()
                }
                runBlocking {
                    val local = app.fieldEvidenceFiles.saveFromFile(evidenceId, "image/jpeg", photo)
                    app.fieldEvidenceQueue.capture(
                        actorId = identity.identityId,
                        installationId = app.installationId,
                        bundle = syntheticBundle,
                        requirementKey = "INSTALLATION-PHOTO",
                        contentType = local.contentType,
                        sizeBytes = local.sizeBytes,
                        sha256 = local.sha256,
                        localFileRef = local.fileRef,
                        evidenceId = local.evidenceId,
                        capturedAtEpochMs = System.currentTimeMillis(),
                        verifiedOnlineThisSession = true,
                    )
                }
                photo.delete()
                setContentView(proofMarkerView(
                    "PHASE6_S03_EVIDENCE_SAVED_LOCAL_READY",
                    "Local private Evidence bytes and Room metadata saved.",
                ))
                return
            }

            val evidence = runBlocking {
                app.fieldEvidenceQueue.list(identity.identityId, app.installationId, WORK_ORDER_ID)
            }
            if (mode == "evidence-corrupt") {
                val row = evidence.singleOrNull { it.evidenceId == evidenceId }
                    ?: error("Missing synthetic Evidence row")
                val corrupted = File(noBackupFilesDir, "field-evidence/" + row.localFileRef)
                // Flip an in-place byte without changing file length: this specifically
                // proves SHA-256 revalidation, not merely an expected-size guard.
                java.io.RandomAccessFile(corrupted, "rw").use { random ->
                    require(random.length() > 3)
                    random.seek(2)
                    val original = random.readUnsignedByte()
                    random.seek(2)
                    random.writeByte(original xor 0x01)
                    random.fd.sync()
                }
                val rejected = runBlocking {
                    runCatching { app.fieldEvidenceFiles.readAndVerify(row) }.isFailure
                }
                setContentView(proofMarkerView(
                    if (rejected && row.localState == LocalEvidenceStates.LOCAL_READY) {
                        "PHASE6_S03_CORRUPTION_REJECTED_BYTES_PRESERVED"
                    } else {
                        "PHASE6_S03_CORRUPTION_PROOF_FAILED"
                    },
                    "Same-size file tampering rejected; Evidence is not READY.",
                ))
                return
            }
            setContent {
                HiltechShell(
                    state = HiltechShellState.SignedIn(
                        identity = identity,
                        field = fixtureState("cached").copy(
                            selectedBundle = syntheticBundle,
                            localEvidence = evidence,
                        ),
                    ),
                    captureEvidence = true,
                )
            }
            return
        }

        if (mode == "cold-seed") {
            // Debug-only evidence: persist a token-bound verified identity and Room bundle,
            // then force-stop the process and launch the real production MainActivity offline.
            val app = application as HiltechApplication
            val identity = fixtureIdentity()
            runBlocking {
                val now = System.currentTimeMillis()
                val store = AndroidEncryptedOidcStore(this@AssignedWorkEvidenceActivity)
                store.save(
                    NativeOidcTokenSet(
                        accessToken = "phase6-debug-offline-token",
                        refreshToken = null,
                        tokenType = "Bearer",
                        scope = "openid",
                        obtainedAtEpochMs = now - 1_000,
                        expiresAtEpochMs = now + 600_000,
                    ),
                )
                store.saveVerifiedFieldIdentity(identity, app.installationId)
                app.fieldOfflineReadCache.saveToday(identity.identityId, listOf(todayItem(actionable = true)))
                app.fieldOfflineReadCache.saveBundle(identity.identityId, jobBundle())
            }
            setContentView(TextView(this).apply { text = "PHASE6_COLD_OFFLINE_SEEDED" })
            return
        }

        if (mode == "field-command-start" || mode == "field-command-persisted") {
            // Debug-only native proof: actual shared execution controls write to
            // production Room queue; no synthetic identity is authorized for any
            // production endpoint or WorkOrder mutation.
            val app = application as HiltechApplication
            val sourceBundle = jobBundle().copy(
                lifecycleState = "ASSIGNED",
                readinessState = "READY",
                blockers = emptyList(),
                waitingReasonCodes = emptyList(),
                executionCapabilities = FieldExecutionCapabilitiesDto(
                    startOffline = true,
                    blockOffline = false,
                    resumeOffline = false,
                    evidenceCaptureOffline = false,
                    submitOffline = false,
                    authoritativeOfflineQueue = false,
                ),
            )
            val existing = if (mode == "field-command-persisted") {
                runBlocking {
                    app.fieldExecutionQueue.outstanding(fixtureIdentity().identityId, WORK_ORDER_ID)
                }
            } else null
            val initial = fixtureState("detail").copy(
                today = listOf(todayItem(actionable = true)),
                selectedBundle = sourceBundle,
                pendingFieldIntent = existing,
                fieldIntentMessage = null,
            )
            setContent {
                var field by remember { mutableStateOf(initial) }
                HiltechShell(
                    state = HiltechShellState.SignedIn(
                        identity = fixtureIdentity(),
                        field = field,
                    ),
                    captureFieldIntent = true,
                    onQueueFieldIntent = { action: FieldExecutionIntent, explanation: String? ->
                        lifecycleScope.launch {
                            runCatching {
                                app.fieldExecutionQueue.queue(
                                    identityId = fixtureIdentity().identityId,
                                    installationId = app.installationId,
                                    bundle = sourceBundle,
                                    action = action,
                                    operationId = java.util.UUID.randomUUID().toString(),
                                    nowEpochMs = System.currentTimeMillis(),
                                    verifiedOnlineThisSession = true,
                                    explanation = explanation,
                                )
                            }.onSuccess {
                                field = field.copy(pendingFieldIntent = it)
                            }.onFailure {
                                field = field.copy(fieldIntentMessage = it.message)
                            }
                        }
                    },
                )
            }
            return
        }

        setContent {
            HiltechShell(
                state = if (mode == "cold-offline") {
                    HiltechShellState.OfflineFieldReadOnly(
                        identityId = fixtureIdentity().identityId,
                        field = fixtureState("cached"),
                    )
                } else {
                    HiltechShellState.SignedIn(
                        identity = fixtureIdentity(),
                        field = fixtureState(mode),
                    )
                },
            )
        }
    }
}

private fun fixtureIdentity() =
    IdentityBootstrapDto(
        identityId =
            "99999999-9999-4999-8999-999999999999",
        identityStatus = "ACTIVE",
        identityVersion = 1,
        primaryOrganizationId =
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        organizations =
            listOf(
                IdentityOrganizationDto(
                    membershipId =
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    organizationId =
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    organizationCode =
                        "HILTECH",
                    displayName =
                        "HILTECH",
                    organizationType =
                        "HILTECH",
                    membershipType =
                        "EMPLOYEE",
                    roleLabel =
                        "Field technician",
                    primary = true,
                    membershipVersion = 1,
                ),
            ),
        teams = emptyList(),
    )

private fun fixtureState(
    mode: String,
): HiltechFieldState =
    when (mode) {
        "revoked" ->
            HiltechFieldState(
                loading = false,
                today = emptyList(),
                selectedWorkOrderId = null,
                selectedBundle = null,
            )

        "cached" ->
            HiltechFieldState(
                loading = false,
                today = listOf(todayItem(actionable = false)),
                selectedWorkOrderId = WORK_ORDER_ID,
                selectedBundle = jobBundle(),
                cachedTodayAt = 1_700_000_000_000L,
                cachedBundleAt = 1_700_000_000_100L,
            )

        "detail" ->
            HiltechFieldState(
                loading = false,
                today =
                    listOf(
                        todayItem(
                            actionable = false,
                        ),
                    ),
                selectedWorkOrderId =
                    WORK_ORDER_ID,
                selectedBundle =
                    jobBundle(),
            )

        else ->
            HiltechFieldState(
                loading = false,
                today =
                    listOf(
                        todayItem(
                            actionable = true,
                        ),
                    ),
                selectedWorkOrderId = null,
                selectedBundle = null,
            )
    }

private fun todayItem(
    actionable: Boolean,
) =
    FieldTodayItemDto(
        workOrderId = WORK_ORDER_ID,
        workOrderCode = "WO-2026-010",
        title = "تركيب راك الموقع",
        lifecycleState = "ASSIGNED",
        readinessState =
            if (actionable) {
                "READY"
            } else {
                "BLOCKED"
            },
        plannedStart =
            "2026-09-21T07:00:00Z",
        plannedEnd =
            "2026-09-21T10:00:00Z",
        priorityCode = "HIGH",
        project = project(),
        site = site(),
        area = null,
        assignment = assignment(),
        waitingReasonCodes =
            if (actionable) {
                emptyList()
            } else {
                listOf(
                    "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                )
            },
        importantBlocker =
            if (actionable) {
                null
            } else {
                "في انتظار مصدر مواد موثوق من Phase 5"
            },
        instructionRevision = 2,
        workOrderVersion = 8,
        bundleAsOf =
            "2026-09-21T06:30:00Z",
        currentlyActionable =
            actionable,
    )

private fun jobBundle() =
    TechnicianJobBundleDto(
        workOrderId = WORK_ORDER_ID,
        workOrderCode = "WO-2026-010",
        title = "تركيب راك الموقع",
        description =
            "نفّذ حسب تعليمات الموقع الحالية. الشاشة دي للقراءة فقط.",
        lifecycleState = "ASSIGNED",
        readinessState = "BLOCKED",
        workOrderVersion = 8,
        project = project(),
        site = site(),
        area = null,
        assignment = assignment(),
        binding =
            FieldWorkBindingDto(
                bindingRevision = 3,
                workType =
                    config(
                        "RACK-INSTALL",
                        "Rack installation",
                        4,
                    ),
                assignmentPolicy =
                    config(
                        "FIELD-ASSIGN",
                        "Field assignment",
                        2,
                    ),
                readinessPolicy =
                    config(
                        "FIELD-READY",
                        "Field readiness",
                        3,
                    ),
                evidencePolicy =
                    config(
                        "FIELD-EVIDENCE",
                        "Field evidence",
                        2,
                    ),
                reviewPolicy =
                    config(
                        "TECH-REVIEW",
                        "Technical review",
                        1,
                    ),
                trackingPolicy = null,
                checklistTemplate = null,
                instructionTemplate =
                    config(
                        "RACK-INSTRUCTION",
                        "Rack instruction",
                        2,
                    ),
            ),
        instruction =
            FieldInstructionDto(
                revision = 2,
                payloadSchemaVersion = 1,
                structuredPayloadJson =
                    """{"steps":["Verify rack position","Confirm grounding"]}""",
                summary =
                    "راجع مكان الراك والتأريض قبل أي تنفيذ.",
                sourceTemplate =
                    config(
                        "RACK-INSTRUCTION",
                        "Rack instruction",
                        2,
                    ),
            ),
        tasks = emptyList(),
        checklist = emptyList(),
        readinessRequirements =
            listOf(
                FieldReadinessRequirementDto(
                    requirementId =
                        REQUIREMENT_ID,
                    family = "MATERIAL",
                    key = "FIBER-PATCH-CORD",
                    typeCode = "MATERIAL",
                    label =
                        "Fiber patch cord",
                    required = true,
                    satisfactionState =
                        "BLOCKED",
                    evaluationReasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    waived = false,
                    version = 2,
                ),
            ),
        evidenceRequirements =
            listOf(
                FieldEvidenceRequirementDto(
                    requirementKey =
                        "INSTALLATION-PHOTO",
                    evidenceTypeCode = "PHOTO",
                    stage = "BEFORE_SUBMIT",
                    minCount = 1,
                    maxCount = 4,
                    offlineCaptureAllowedByPolicy =
                        true,
                    allowedContentTypes =
                        listOf(
                            "image/jpeg",
                        ),
                ),
            ),
        existingEvidence = emptyList(),
        resourceRequirements =
            listOf(
                FieldResourceRequirementDto(
                    requirementId =
                        REQUIREMENT_ID,
                    family = "MATERIAL",
                    key = "FIBER-PATCH-CORD",
                    typeCode = "MATERIAL",
                    required = true,
                    state = "BLOCKED",
                    integrationState =
                        "SOURCE_PENDING_PHASE5",
                    reasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    version = 2,
                ),
            ),
        safeDocumentRefs = emptyList(),
        blockers =
            listOf(
                FieldBlockerDto(
                    blockerId =
                        BLOCKER_ID,
                    requirementId =
                        REQUIREMENT_ID,
                    blockerTypeCode =
                        "READINESS_REQUIREMENT",
                    explanationCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    description =
                        "في انتظار مصدر مواد موثوق من Phase 5",
                    state = "OPEN",
                    version = 1,
                ),
            ),
        waitingReasonCodes =
            listOf(
                "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
            ),
        executionCapabilities =
            FieldExecutionCapabilitiesDto(
                startOffline = false,
                blockOffline = false,
                resumeOffline = false,
                evidenceCaptureOffline = false,
                submitOffline = false,
                authoritativeOfflineQueue =
                    false,
            ),
        asOf = "2026-09-21T06:30:00Z",
    )

private fun project() =
    FieldProjectDto(
        projectId = PROJECT_ID,
        projectCode = "P-010",
        name = "Beni Suef rollout",
        lifecycleState = "ACTIVE",
        baselineVersion = 2,
    )

private fun site() =
    FieldSiteDto(
        siteId = SITE_ID,
        siteCode = "BS-01",
        name = "موقع بني سويف 01",
        projectSiteId =
            PROJECT_SITE_ID,
        projectSiteCode = "PS-01",
        lifecycleState = "ACTIVE",
        accessInstructions =
            "اتصل بمسؤول الموقع قبل الدخول.",
        timezone = "Africa/Cairo",
    )

private fun assignment() =
    FieldAssignmentDto(
        assignmentId =
            ASSIGNMENT_ID,
        targetType = "USER",
        targetId = USER_ID,
        targetLabel = "الفني الحالي",
        validFrom =
            "2026-09-21T06:00:00Z",
        validUntil = null,
        version = 1,
    )

private fun config(
    code: String,
    name: String,
    revision: Int,
) =
    FieldConfigRefDto(
        id =
            java.util.UUID
                .nameUUIDFromBytes(
                    code.toByteArray(),
                )
                .toString(),
        code = code,
        name = name,
        revision = revision,
    )

private const val WORK_ORDER_ID =
    "11111111-1111-4111-8111-111111111111"
private const val PROJECT_ID =
    "22222222-2222-4222-8222-222222222222"
private const val SITE_ID =
    "33333333-3333-4333-8333-333333333333"
private const val PROJECT_SITE_ID =
    "44444444-4444-4444-8444-444444444444"
private const val ASSIGNMENT_ID =
    "55555555-5555-4555-8555-555555555555"
private const val USER_ID =
    "66666666-6666-4666-8666-666666666666"
private const val REQUIREMENT_ID =
    "77777777-7777-4777-8777-777777777777"
private const val BLOCKER_ID =
    "88888888-8888-4888-8888-888888888888"

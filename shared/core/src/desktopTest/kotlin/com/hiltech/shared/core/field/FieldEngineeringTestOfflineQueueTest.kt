package com.hiltech.shared.core.field

import com.hiltech.shared.core.FieldEngineeringTestDraft
import com.hiltech.shared.core.local.EngineeringTestIntentStates
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldEngineeringMetricDefinitionDto
import com.hiltech.shared.core.work.FieldEngineeringTestCaptureDto
import com.hiltech.shared.core.work.FieldEngineeringTestDefinitionDto
import com.hiltech.shared.core.work.FieldEngineeringTestReceiptDto
import com.hiltech.shared.core.work.FieldEvidenceRequirementDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldEngineeringTestOfflineQueueTest {
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val actorId = "22222222-2222-4222-8222-222222222222"
    private val assignmentId = "33333333-3333-4333-8333-333333333333"
    private val installationId = "44444444-4444-4444-8444-444444444444"
    private val evidencePolicyId = "55555555-5555-4555-8555-555555555555"
    private val schemaTemplateId = "66666666-6666-4666-8666-666666666666"

    @Test
    fun queuePersistsExactSourceAndRejectsStaleCaptureOrDuplicateSubject() =
        runBlocking {
            val folder =
                Files.createTempDirectory(
                    "hiltech-engineering-offline-queue",
                ).toFile()
            val path = folder.resolve("field.db").absolutePath
            try {
                val db =
                    buildHiltechLocalDatabase(
                        getDesktopDatabaseBuilder(path),
                    )
                try {
                    val queue =
                        FieldEngineeringTestOfflineQueue(db)
                    val operationId =
                        "77777777-7777-4777-8777-777777777777"
                    val pending =
                        queue.queue(
                            actorId = actorId,
                            installationId = installationId,
                            bundle = bundle(),
                            draft = draft(),
                            operationId = operationId,
                            capturedAtEpochMs = 1_700_000_000_000L,
                            verifiedOnlineThisSession = true,
                        )
                    assertEquals(
                        EngineeringTestIntentStates.PENDING,
                        pending.state,
                    )
                    val row =
                        assertNotNull(
                            db.engineeringTestIntentDao()
                                .get(operationId),
                        )
                    assertEquals(workId, row.workOrderId)
                    assertEquals(assignmentId, row.assignmentId)
                    assertEquals(3L, row.assignmentVersion)
                    assertEquals(11L, row.workOrderVersion)
                    assertEquals(2, row.bindingRevision)
                    assertEquals(evidencePolicyId, row.evidencePolicyId)
                    assertEquals(4, row.evidencePolicyRevision)
                    assertEquals(schemaTemplateId, row.schemaTemplateId)
                    assertEquals(7, row.schemaTemplateRevision)
                    assertEquals(3, row.schemaVersion)
                    assertEquals("LINK-A01", row.testSubjectRef)
                    assertEquals(null, row.rawEvidenceSource)

                    assertFailsWith<IllegalArgumentException> {
                        queue.queue(
                            actorId = actorId,
                            installationId = installationId,
                            bundle = bundle(),
                            draft = draft(),
                            operationId =
                                "88888888-8888-4888-8888-888888888888",
                            capturedAtEpochMs =
                                1_700_000_000_010L,
                            verifiedOnlineThisSession = true,
                        )
                    }
                    assertFailsWith<IllegalArgumentException> {
                        queue.queue(
                            actorId = actorId,
                            installationId = installationId,
                            bundle = bundle(),
                            draft =
                                draft(
                                    subject =
                                        "LINK-A02",
                                ),
                            operationId =
                                "99999999-9999-4999-8999-999999999999",
                            capturedAtEpochMs =
                                1_700_000_000_020L,
                            verifiedOnlineThisSession = false,
                        )
                    }
                } finally {
                    db.close()
                }

                val reopened =
                    buildHiltechLocalDatabase(
                        getDesktopDatabaseBuilder(path),
                    )
                try {
                    val row =
                        assertNotNull(
                            reopened.engineeringTestIntentDao()
                                .get(
                                    "77777777-7777-4777-8777-777777777777",
                                ),
                        )
                    assertEquals(
                        EngineeringTestIntentStates.PENDING,
                        row.state,
                    )
                    assertEquals(
                        1,
                        reopened.engineeringTestIntentDao()
                            .outstandingForWorkOrder(
                                actorId,
                                installationId,
                                workId,
                            ),
                    )
                } finally {
                    reopened.close()
                }
            } finally {
                folder.deleteRecursively()
            }
        }

    @Test
    fun exactReplayResolvesInstrumentOnlineAndAppliesOriginalOperation() =
        runBlocking {
            withDatabase("hiltech-engineering-offline-replay") { db ->
                val operationId =
                    "77777777-7777-4777-8777-777777777777"
                FieldEngineeringTestOfflineQueue(db).queue(
                    actorId = actorId,
                    installationId = installationId,
                    bundle = bundle(instrumentRequired = true),
                    draft =
                        draft(
                            instrumentTag =
                                "TAG_001",
                        ),
                    operationId = operationId,
                    capturedAtEpochMs =
                        1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )

                var resolvedTag: String? = null
                var captured: FieldEngineeringTestCaptureDto? =
                    null
                val remote =
                    object : FieldEngineeringTestReplayRemote {
                        override suspend fun currentBundle(
                            workOrderId: String,
                            installationId: String,
                        ) = bundle(instrumentRequired = true)

                        override suspend fun resolveInstrumentAssetId(
                            workOrderId: String,
                            opaqueTag: String,
                            installationId: String,
                        ): String {
                            resolvedTag = opaqueTag
                            return "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                        }

                        override suspend fun capture(
                            workOrderId: String,
                            request: FieldEngineeringTestCaptureDto,
                            installationId: String,
                        ): FieldEngineeringTestReceiptDto {
                            captured = request
                            return receipt(
                                operationId =
                                    request.operationId,
                                subject =
                                    request.testSubjectRef,
                            )
                        }
                    }

                val summary =
                    coordinator(db, remote)
                        .replayOnce(
                            actorId,
                            installationId,
                        )

                assertEquals(1, summary.applied)
                assertEquals(0, summary.retryable)
                assertEquals(0, summary.reviewRequired)
                assertEquals("TAG_001", resolvedTag)
                val request = assertNotNull(captured)
                assertEquals(operationId, request.operationId)
                assertEquals(assignmentId, request.assignmentId)
                assertEquals(3L, request.assignmentVersion)
                assertEquals(11L, request.workOrderVersion)
                assertEquals(
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    request.instrumentAssetId,
                )
                assertEquals("0.18", request.values["loss"])

                val row =
                    assertNotNull(
                        db.engineeringTestIntentDao()
                            .get(operationId),
                    )
                assertEquals(
                    EngineeringTestIntentStates.APPLIED,
                    row.state,
                )
                assertEquals(operationId, row.serverResultId)
                assertEquals("PASS", row.evaluatedStatus)
                assertNull(row.nextRetryAtEpochMs)
            }
        }

    @Test
    fun sourceChangeMovesIntentToReviewWithoutSubmitting() =
        runBlocking {
            withDatabase("hiltech-engineering-offline-source-change") { db ->
                val operationId =
                    "77777777-7777-4777-8777-777777777777"
                FieldEngineeringTestOfflineQueue(db).queue(
                    actorId,
                    installationId,
                    bundle(),
                    draft(),
                    operationId,
                    1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                var submitted = false
                val remote =
                    object : FieldEngineeringTestReplayRemote {
                        override suspend fun currentBundle(
                            workOrderId: String,
                            installationId: String,
                        ) =
                            bundle(
                                workVersion = 12,
                            )

                        override suspend fun resolveInstrumentAssetId(
                            workOrderId: String,
                            opaqueTag: String,
                            installationId: String,
                        ) = error("must not resolve")

                        override suspend fun capture(
                            workOrderId: String,
                            request: FieldEngineeringTestCaptureDto,
                            installationId: String,
                        ): FieldEngineeringTestReceiptDto {
                            submitted = true
                            return receipt(
                                request.operationId,
                                request.testSubjectRef,
                            )
                        }
                    }

                val summary =
                    coordinator(db, remote)
                        .replayOnce(
                            actorId,
                            installationId,
                        )

                assertEquals(1, summary.reviewRequired)
                assertEquals(false, submitted)
                val row =
                    assertNotNull(
                        db.engineeringTestIntentDao()
                            .get(operationId),
                    )
                assertEquals(
                    EngineeringTestIntentStates.REVIEW_REQUIRED,
                    row.state,
                )
                assertEquals(
                    "ENGINEERING_TEST_SOURCE_CHANGED",
                    row.lastErrorCode,
                )
            }
        }

    @Test
    fun localRequiredEvidenceRetriesUntilFinalizedThenUsesServerEvidenceId() =
        runBlocking {
            withDatabase("hiltech-engineering-offline-evidence") { db ->
                val localEvidenceId =
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                db.evidenceQueueDao().insert(
                    localEvidence(
                        evidenceId = localEvidenceId,
                        state = LocalEvidenceStates.LOCAL_READY,
                        finalizedServerId = null,
                    ),
                )
                val operationId =
                    "77777777-7777-4777-8777-777777777777"
                FieldEngineeringTestOfflineQueue(db).queue(
                    actorId,
                    installationId,
                    bundle(rawEvidenceRequired = true),
                    draft(),
                    operationId,
                    1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                var submitted: FieldEngineeringTestCaptureDto? =
                    null
                val remote =
                    object : FieldEngineeringTestReplayRemote {
                        override suspend fun currentBundle(
                            workOrderId: String,
                            installationId: String,
                        ) =
                            bundle(rawEvidenceRequired = true)

                        override suspend fun resolveInstrumentAssetId(
                            workOrderId: String,
                            opaqueTag: String,
                            installationId: String,
                        ) = error("no instrument")

                        override suspend fun capture(
                            workOrderId: String,
                            request: FieldEngineeringTestCaptureDto,
                            installationId: String,
                        ): FieldEngineeringTestReceiptDto {
                            submitted = request
                            return receipt(
                                request.operationId,
                                request.testSubjectRef,
                            )
                        }
                    }

                val first =
                    coordinator(db, remote)
                        .replayOnce(
                            actorId,
                            installationId,
                        )
                assertEquals(1, first.retryable)
                assertNull(submitted)
                assertEquals(
                    EngineeringTestIntentStates.RETRYABLE,
                    db.engineeringTestIntentDao()
                        .get(operationId)?.state,
                )

                val serverEvidenceId =
                    "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
                db.evidenceQueueDao().updateUploadState(
                    evidenceId = localEvidenceId,
                    state = LocalEvidenceStates.READY,
                    uploadSessionId =
                        "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    remoteObjectRef = "private/evidence",
                    finalizedServerEvidenceId =
                        serverEvidenceId,
                    uploadAttemptCount = 1,
                    lastUploadErrorCode = null,
                    updatedAtEpochMs =
                        1_700_000_030_000L,
                )
                // The row is retryable for +30s; advance replay time.
                val second =
                    coordinator(
                        db,
                        remote,
                        now = 1_700_000_060_000L,
                    ).replayOnce(
                        actorId,
                        installationId,
                    )

                assertEquals(1, second.applied)
                assertEquals(
                    serverEvidenceId,
                    assertNotNull(submitted).rawEvidenceId,
                )
                assertEquals(
                    EngineeringTestIntentStates.APPLIED,
                    db.engineeringTestIntentDao()
                        .get(operationId)?.state,
                )
            }
        }

    @Test
    fun staleLocalEvidenceCannotBackAnewEngineeringIntent() =
        runBlocking {
            withDatabase("hiltech-engineering-offline-stale-evidence") { db ->
                db.evidenceQueueDao().insert(
                    localEvidence(
                        evidenceId =
                            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        state =
                            LocalEvidenceStates.LOCAL_READY,
                        finalizedServerId = null,
                    ).copy(
                        workOrderVersion = 10,
                    ),
                )
                assertFailsWith<IllegalArgumentException> {
                    FieldEngineeringTestOfflineQueue(db).queue(
                        actorId,
                        installationId,
                        bundle(rawEvidenceRequired = true),
                        draft(),
                        "77777777-7777-4777-8777-777777777777",
                        1_700_000_000_000L,
                        verifiedOnlineThisSession = true,
                    )
                }
            }
        }

    @Test
    fun unresolvedInstrumentMovesIntentToReviewInsteadOfRetryingForever() =
        runBlocking {
            withDatabase("hiltech-engineering-offline-instrument-review") { db ->
                val operationId =
                    "77777777-7777-4777-8777-777777777777"
                FieldEngineeringTestOfflineQueue(db).queue(
                    actorId,
                    installationId,
                    bundle(instrumentRequired = true),
                    draft(instrumentTag = "TAG_404"),
                    operationId,
                    1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                var submitted = false
                val remote =
                    object : FieldEngineeringTestReplayRemote {
                        override suspend fun currentBundle(
                            workOrderId: String,
                            installationId: String,
                        ) = bundle(instrumentRequired = true)

                        override suspend fun resolveInstrumentAssetId(
                            workOrderId: String,
                            opaqueTag: String,
                            installationId: String,
                        ): String? = null

                        override suspend fun capture(
                            workOrderId: String,
                            request: FieldEngineeringTestCaptureDto,
                            installationId: String,
                        ): FieldEngineeringTestReceiptDto {
                            submitted = true
                            return receipt(
                                request.operationId,
                                request.testSubjectRef,
                            )
                        }
                    }

                val summary =
                    coordinator(db, remote)
                        .replayOnce(actorId, installationId)

                assertEquals(1, summary.reviewRequired)
                assertEquals(false, submitted)
                val row =
                    assertNotNull(
                        db.engineeringTestIntentDao()
                            .get(operationId),
                    )
                assertEquals(
                    EngineeringTestIntentStates.REVIEW_REQUIRED,
                    row.state,
                )
                assertEquals(
                    "INSTRUMENT_ASSET_NOT_RESOLVED",
                    row.lastErrorCode,
                )
            }
        }

    @Test
    fun pendingFieldTransitionBlocksEngineeringIntentAndViceVersa() =
        runBlocking {
            withDatabase("hiltech-engineering-offline-mutual-exclusion") { db ->
                val fieldQueue = FieldExecutionQueue(db)
                fieldQueue.queue(
                    actorId,
                    installationId,
                    bundle(),
                    FieldExecutionIntent.START,
                    "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                    1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                assertFailsWith<IllegalArgumentException> {
                    FieldEngineeringTestOfflineQueue(db).queue(
                        actorId,
                        installationId,
                        bundle(),
                        draft(subject = "LINK-B01"),
                        "ffffffff-ffff-4fff-8fff-ffffffffffff",
                        1_700_000_000_100L,
                        verifiedOnlineThisSession = true,
                    )
                }
            }

            withDatabase("hiltech-engineering-offline-reverse-exclusion") { db ->
                FieldEngineeringTestOfflineQueue(db).queue(
                    actorId,
                    installationId,
                    bundle(),
                    draft(),
                    "77777777-7777-4777-8777-777777777777",
                    1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                assertFailsWith<IllegalArgumentException> {
                    FieldExecutionQueue(db).queue(
                        actorId,
                        installationId,
                        bundle(),
                        FieldExecutionIntent.START,
                        "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                        1_700_000_000_100L,
                        verifiedOnlineThisSession = true,
                    )
                }
            }
        }

    private suspend fun <T> withDatabase(
        prefix: String,
        block: suspend (com.hiltech.shared.core.local.HiltechLocalDatabase) -> T,
    ): T {
        val folder =
            Files.createTempDirectory(prefix).toFile()
        val db =
            buildHiltechLocalDatabase(
                getDesktopDatabaseBuilder(
                    folder.resolve("field.db")
                        .absolutePath,
                ),
            )
        return try {
            block(db)
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }

    private fun coordinator(
        db: com.hiltech.shared.core.local.HiltechLocalDatabase,
        remote: FieldEngineeringTestReplayRemote,
        now: Long = 1_700_000_000_000L,
    ) =
        FieldEngineeringTestReplayCoordinator(
            db = db,
            remote = remote,
            nowEpochMs = { now },
            formatEpochUtc = {
                "2023-11-14T22:13:20.000Z"
            },
        )

    private fun draft(
        subject: String = "LINK-A01",
        instrumentTag: String? = null,
    ) =
        FieldEngineeringTestDraft(
            requirementKey = "fiber_acceptance",
            testSubjectRef = subject,
            values = mapOf("loss" to "0.18"),
            instrumentTag = instrumentTag,
            deviceReportedStatus = "PASS",
        )

    private fun bundle(
        workVersion: Long = 11,
        rawEvidenceRequired: Boolean = false,
        instrumentRequired: Boolean = false,
    ): TechnicianJobBundleDto {
        val genericRef =
            FieldConfigRefDto(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab",
                "CONFIG",
                "Config",
                1,
            )
        val evidenceRef =
            FieldConfigRefDto(
                evidencePolicyId,
                "FIELD-EVIDENCE",
                "Field evidence",
                4,
            )
        val test =
            FieldEngineeringTestDefinitionDto(
                templateId = schemaTemplateId,
                templateCode = "FIBER_ACCEPTANCE",
                templateName = "Fiber acceptance",
                templateRevision = 7,
                schemaVersion = 3,
                label = "Acceptance test",
                subjectLabel = "Link",
                rawEvidenceRequired =
                    rawEvidenceRequired,
                instrumentAssetRequired =
                    instrumentRequired,
                calibrationRequired =
                    instrumentRequired,
                deviceReportedStatusAllowed = true,
                reviewerRelationshipCode =
                    "FIELD_ENGINEER",
                metrics =
                    listOf(
                        FieldEngineeringMetricDefinitionDto(
                            key = "loss",
                            label = "Loss",
                            valueType = "DECIMAL",
                            required = true,
                            dimension = "attenuation",
                            unitCode = "dB",
                            precision = 2,
                            minValue = "0.00",
                            maxValue = "0.30",
                        ),
                    ),
            )
        return TechnicianJobBundleDto(
            workOrderId = workId,
            workOrderCode = "WO-11",
            title = "Fiber installation",
            lifecycleState = "ASSIGNED",
            readinessState = "READY",
            workOrderVersion = workVersion,
            project =
                FieldProjectDto(
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbc",
                    "PRJ",
                    "Project",
                    "ACTIVE",
                    1,
                ),
            site =
                FieldSiteDto(
                    "cccccccc-cccc-4ccc-8ccc-cccccccccccd",
                    "SITE",
                    "Site",
                    "dddddddd-dddd-4ddd-8ddd-ddddddddddde",
                    "PS",
                    "ACTIVE",
                ),
            assignment =
                FieldAssignmentDto(
                    assignmentId,
                    "USER",
                    actorId,
                    "Tech",
                    "2026-11-21T01:00:00Z",
                    version = 3,
                ),
            binding =
                FieldWorkBindingDto(
                    bindingRevision = 2,
                    workType = genericRef,
                    assignmentPolicy = genericRef,
                    readinessPolicy = genericRef,
                    evidencePolicy = evidenceRef,
                    reviewPolicy = genericRef,
                ),
            evidenceRequirements =
                listOf(
                    FieldEvidenceRequirementDto(
                        requirementKey =
                            "fiber_acceptance",
                        evidenceTypeCode =
                            "ENGINEERING_TEST",
                        stage = "BEFORE_SUBMIT",
                        minCount = 1,
                        maxCount = 1,
                        offlineCaptureAllowedByPolicy =
                            true,
                        allowedContentTypes =
                            listOf("application/pdf"),
                        structuredTest = test,
                    ),
                ),
            executionCapabilities =
                FieldExecutionCapabilitiesDto(
                    startOffline = true,
                    blockOffline = true,
                    resumeOffline = true,
                    evidenceCaptureOffline = true,
                    engineeringTestCaptureOffline = true,
                    submitOffline = false,
                    authoritativeOfflineQueue = false,
                ),
            asOf = "2026-11-21T06:00:00Z",
        )
    }

    private fun receipt(
        operationId: String,
        subject: String,
    ) =
        FieldEngineeringTestReceiptDto(
            resultId = operationId,
            workOrderId = workId,
            requirementKey = "fiber_acceptance",
            testSubjectRef = subject,
            evaluatedStatus = "PASS",
            deviceReportedStatus = "PASS",
            reviewerRelationshipCode =
                "FIELD_ENGINEER",
            replayed = false,
        )

    private fun localEvidence(
        evidenceId: String,
        state: String,
        finalizedServerId: String?,
    ) =
        LocalEvidenceEntity(
            evidenceId = evidenceId,
            workOrderId = workId,
            targetType = "WORK_ORDER",
            targetId = workId,
            requirementKey = "fiber_acceptance",
            evidencePolicyId = evidencePolicyId,
            evidencePolicyRevision = 4,
            evidenceTypeCode = "ENGINEERING_TEST",
            localFileRef = "private/$evidenceId",
            sha256 =
                "0".repeat(64),
            contentType = "application/pdf",
            sizeBytes = 12,
            capturedAtEpochMs =
                1_700_000_000_000L,
            capturedByUserId = actorId,
            sourceDeviceId = installationId,
            localState = state,
            uploadSessionId = null,
            remoteObjectRef = null,
            finalizedServerEvidenceId =
                finalizedServerId,
            uploadAttemptCount = 0,
            lastUploadErrorCode = null,
            updatedAtEpochMs =
                1_700_000_000_000L,
            assignmentId = assignmentId,
            assignmentVersion = 3,
            workOrderVersion = 11,
            bindingRevision = 2,
            serverEvidenceId =
                finalizedServerId,
        )
}

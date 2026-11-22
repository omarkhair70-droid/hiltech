package com.hiltech.shared.core.field

import androidx.room3.withWriteTransaction
import com.hiltech.shared.core.FieldEngineeringTestDraft
import com.hiltech.shared.core.local.EngineeringTestIntentStates
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.LocalEngineeringTestIntentEntity
import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.work.FieldEngineeringTestCaptureDto
import com.hiltech.shared.core.work.FieldEngineeringTestReceiptDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class FieldQueuedEngineeringTest(
    val operationId: String,
    val workOrderId: String,
    val requirementKey: String,
    val testSubjectRef: String,
    val state: String,
)

data class FieldEngineeringReplaySummary(
    val applied: Int = 0,
    val retryable: Int = 0,
    val reviewRequired: Int = 0,
    val reauthenticationRequired: Boolean = false,
)

interface FieldEngineeringTestReplayRemote {
    suspend fun currentBundle(
        workOrderId: String,
        installationId: String,
    ): TechnicianJobBundleDto

    suspend fun resolveInstrumentAssetId(
        workOrderId: String,
        opaqueTag: String,
        installationId: String,
    ): String?

    suspend fun capture(
        workOrderId: String,
        request: FieldEngineeringTestCaptureDto,
        installationId: String,
    ): FieldEngineeringTestReceiptDto
}

/**
 * Durable technician-entered test intent.
 *
 * The local row is never authoritative. Capture is allowed only from a bundle that
 * was verified online in the current session. Replay requires an exact current
 * assignment/policy/schema match and the server re-authorizes the actual write.
 */
class FieldEngineeringTestOfflineQueue(
    private val db: HiltechLocalDatabase,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun queue(
        actorId: String,
        installationId: String,
        bundle: TechnicianJobBundleDto,
        draft: FieldEngineeringTestDraft,
        operationId: String,
        capturedAtEpochMs: Long,
        verifiedOnlineThisSession: Boolean,
        serverReadyEvidenceId: String? = null,
        supersedesResultId: String? = null,
    ): FieldQueuedEngineeringTest {
        require(verifiedOnlineThisSession) {
            "A cached bundle is not authority to capture an engineering test intent."
        }
        require(actorId.isNotBlank() && installationId.isNotBlank())
        require(UUID_REGEX.matches(operationId))
        require(UUID_REGEX.matches(bundle.workOrderId))
        require(bundle.workOrderVersion >= 1 && bundle.assignment.version >= 1)
        require(bundle.executionCapabilities.engineeringTestCaptureOffline) {
            "Offline engineering-test capture is not allowed by the current source."
        }
        val binding = requireNotNull(bundle.binding) {
            "The WorkOrder policy binding is unavailable."
        }
        val requirement = bundle.evidenceRequirements.singleOrNull {
            it.requirementKey == draft.requirementKey
        } ?: throw IllegalArgumentException("The engineering-test requirement is unavailable.")
        require(requirement.offlineCaptureAllowedByPolicy) {
            "This engineering-test requirement is not allowed for local capture."
        }
        val test = requireNotNull(requirement.structuredTest) {
            "The engineering-test schema is unavailable."
        }
        val subject = draft.testSubjectRef.trim()
        require(subject.isNotEmpty() && subject.length <= 160)
        require(draft.values.isNotEmpty() && draft.values.size <= 100)
        val allowedMetricKeys = test.metrics.map { it.key }.toSet()
        require(draft.values.keys.all { it in allowedMetricKeys }) {
            "The draft contains a field outside the bound schema."
        }
        test.metrics.filter { it.required }.forEach { metric ->
            require(!draft.values[metric.key].isNullOrBlank()) {
                "A required engineering-test value is missing."
            }
        }
        val instrumentTag = draft.instrumentTag?.trim()?.takeIf { it.isNotEmpty() }
        if (test.instrumentAssetRequired) {
            require(instrumentTag != null && OPAQUE_TAG_REGEX.matches(instrumentTag)) {
                "A valid instrument AssetTag is required."
            }
        }
        val status = draft.deviceReportedStatus?.trim()?.uppercase()
        require(status == null || status in setOf("PASS", "FAIL", "UNKNOWN")) {
            "Device-reported status is invalid."
        }
        val raw = resolveRawEvidence(
            actorId = actorId,
            installationId = installationId,
            bundle = bundle,
            requirementKey = draft.requirementKey,
            rawEvidenceRequired = test.rawEvidenceRequired,
            serverReadyEvidenceId = serverReadyEvidenceId,
        )
        val valuesJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            draft.values.toSortedMap(),
        )

        return db.withWriteTransaction {
            require(
                db.pendingCommandDao()
                    .outstandingCompletion(actorId, bundle.workOrderId)
                    .isEmpty(),
            ) {
                "Resolve the pending completion submission before another engineering test."
            }
            require(db.pendingCommandDao()
                .outstandingFieldCommand(actorId, bundle.workOrderId)
                .isEmpty()) {
                "Sync or resolve the pending field state change before capturing a test."
            }
            require(db.engineeringTestIntentDao().get(operationId) == null) {
                "A local operationId cannot be reused."
            }
            require(
                db.engineeringTestIntentDao().outstandingForSubject(
                    actorId,
                    installationId,
                    bundle.workOrderId,
                    draft.requirementKey,
                    subject,
                ) == 0,
            ) {
                "An engineering-test intent for this subject is already pending."
            }

            val row = LocalEngineeringTestIntentEntity(
                operationId = operationId,
                actorUserIdentityId = actorId,
                deviceId = installationId,
                workOrderId = bundle.workOrderId,
                assignmentId = bundle.assignment.assignmentId,
                assignmentVersion = bundle.assignment.version,
                workOrderVersion = bundle.workOrderVersion,
                bindingRevision = binding.bindingRevision,
                evidencePolicyId = binding.evidencePolicy.id,
                evidencePolicyRevision = binding.evidencePolicy.revision,
                requirementKey = draft.requirementKey,
                schemaTemplateId = test.templateId,
                schemaTemplateRevision = test.templateRevision,
                schemaVersion = test.schemaVersion,
                testSubjectRef = subject,
                valuesJson = valuesJson,
                instrumentTag = instrumentTag,
                rawEvidenceSource = raw?.first,
                rawEvidenceRef = raw?.second,
                deviceReportedStatus = status,
                capturedAtEpochMs = capturedAtEpochMs,
                clientOccurredAtEpochMs = capturedAtEpochMs,
                supersedesResultId = supersedesResultId,
                state = EngineeringTestIntentStates.PENDING,
                retryCount = 0,
                nextRetryAtEpochMs = null,
                lastErrorCode = null,
                lastCorrelationId = null,
                serverResultId = null,
                evaluatedStatus = null,
                updatedAtEpochMs = capturedAtEpochMs,
            )
            db.engineeringTestIntentDao().insert(row)
            row.view()
        }
    }

    private suspend fun resolveRawEvidence(
        actorId: String,
        installationId: String,
        bundle: TechnicianJobBundleDto,
        requirementKey: String,
        rawEvidenceRequired: Boolean,
        serverReadyEvidenceId: String?,
    ): Pair<String, String>? {
        serverReadyEvidenceId?.let {
            require(UUID_REGEX.matches(it))
            require(
                bundle.existingEvidence.any { evidence ->
                    evidence.evidenceId == it &&
                        evidence.requirementKey == requirementKey &&
                        evidence.storageState == "READY"
                },
            ) {
                "The selected server Evidence is not READY for this requirement."
            }
            return "SERVER" to it
        }
        if (!rawEvidenceRequired) return null

        val binding =
            requireNotNull(bundle.binding) {
                "The WorkOrder policy binding is unavailable."
            }
        val local = db.evidenceQueueDao()
            .forWorkOrder(actorId, installationId, bundle.workOrderId)
            .asReversed()
            .firstOrNull {
                it.requirementKey == requirementKey &&
                    it.assignmentId == bundle.assignment.assignmentId &&
                    it.assignmentVersion == bundle.assignment.version &&
                    it.workOrderVersion == bundle.workOrderVersion &&
                    it.bindingRevision == binding.bindingRevision &&
                    it.evidencePolicyId == binding.evidencePolicy.id &&
                    it.evidencePolicyRevision == binding.evidencePolicy.revision &&
                    it.localState !in setOf(
                        LocalEvidenceStates.REJECTED,
                        LocalEvidenceStates.DISCARDED_EXPLICITLY,
                    )
            } ?: throw IllegalArgumentException(
            "Capture the required Evidence before queuing this engineering test.",
        )
        return "LOCAL" to local.evidenceId
    }

    private fun LocalEngineeringTestIntentEntity.view() =
        FieldQueuedEngineeringTest(
            operationId,
            workOrderId,
            requirementKey,
            testSubjectRef,
            state,
        )

    private companion object {
        val UUID_REGEX =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        val OPAQUE_TAG_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

class FieldEngineeringTestReplayCoordinator(
    private val db: HiltechLocalDatabase,
    private val remote: FieldEngineeringTestReplayRemote,
    private val nowEpochMs: () -> Long,
    private val formatEpochUtc: (Long) -> String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun replayOnce(
        actorId: String,
        installationId: String,
    ): FieldEngineeringReplaySummary {
        var summary = FieldEngineeringReplaySummary()
        val rows = db.engineeringTestIntentDao()
            .syncable(actorId, installationId, nowEpochMs())

        for (row in rows) {
            val outcome = try {
                replay(row, actorId, installationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                classifyFailure(row, actorId, installationId, failure)
            }
            summary = when (outcome) {
                ReplayOutcome.APPLIED ->
                    summary.copy(applied = summary.applied + 1)
                ReplayOutcome.RETRY ->
                    summary.copy(retryable = summary.retryable + 1)
                ReplayOutcome.REVIEW ->
                    summary.copy(reviewRequired = summary.reviewRequired + 1)
                ReplayOutcome.REAUTH ->
                    summary.copy(reauthenticationRequired = true)
            }
            if (outcome == ReplayOutcome.REAUTH) return summary
        }
        return summary
    }

    private suspend fun replay(
        row: LocalEngineeringTestIntentEntity,
        actorId: String,
        installationId: String,
    ): ReplayOutcome {
        val bundle = remote.currentBundle(row.workOrderId, installationId)
        val binding = bundle.binding
        val requirement = bundle.evidenceRequirements.singleOrNull {
            it.requirementKey == row.requirementKey
        }
        val test = requirement?.structuredTest
        if (
            bundle.workOrderId != row.workOrderId ||
            bundle.workOrderVersion != row.workOrderVersion ||
            bundle.assignment.assignmentId != row.assignmentId ||
            bundle.assignment.version != row.assignmentVersion ||
            binding == null ||
            binding.bindingRevision != row.bindingRevision ||
            binding.evidencePolicy.id != row.evidencePolicyId ||
            binding.evidencePolicy.revision != row.evidencePolicyRevision ||
            !bundle.executionCapabilities.engineeringTestCaptureOffline ||
            requirement == null ||
            !requirement.offlineCaptureAllowedByPolicy ||
            test == null ||
            test.templateId != row.schemaTemplateId ||
            test.templateRevision != row.schemaTemplateRevision ||
            test.schemaVersion != row.schemaVersion
        ) {
            move(row, actorId, installationId, EngineeringTestIntentStates.REVIEW_REQUIRED,
                error = "ENGINEERING_TEST_SOURCE_CHANGED")
            return ReplayOutcome.REVIEW
        }

        // No same-Work field transition may race this exact captured Work version.
        if (db.pendingCommandDao()
                .outstandingFieldCommand(actorId, row.workOrderId)
                .isNotEmpty()) {
            move(row, actorId, installationId, EngineeringTestIntentStates.RETRYABLE,
                error = "FIELD_STATE_COMMAND_PENDING", retry = true)
            return ReplayOutcome.RETRY
        }

        val rawEvidenceId = when (row.rawEvidenceSource) {
            null -> null
            "SERVER" -> row.rawEvidenceRef
            "LOCAL" -> {
                val localId = row.rawEvidenceRef ?: return review(row, actorId, installationId, "RAW_EVIDENCE_REF_MISSING")
                val local = db.evidenceQueueDao().get(localId)
                    ?: return review(row, actorId, installationId, "RAW_EVIDENCE_LOCAL_MISSING")
                when (local.localState) {
                    LocalEvidenceStates.READY -> local.finalizedServerEvidenceId
                        ?: return review(row, actorId, installationId, "RAW_EVIDENCE_SERVER_ID_MISSING")
                    LocalEvidenceStates.REJECTED,
                    LocalEvidenceStates.DISCARDED_EXPLICITLY ->
                        return review(row, actorId, installationId, "RAW_EVIDENCE_REJECTED")
                    else -> {
                        move(row, actorId, installationId, EngineeringTestIntentStates.RETRYABLE,
                            error = "RAW_EVIDENCE_NOT_READY", retry = true)
                        return ReplayOutcome.RETRY
                    }
                }
            }
            else -> return review(row, actorId, installationId, "RAW_EVIDENCE_SOURCE_INVALID")
        }

        val instrumentAssetId =
            if (test.instrumentAssetRequired) {
                val tag = row.instrumentTag
                    ?: return review(row, actorId, installationId, "INSTRUMENT_TAG_MISSING")
                remote.resolveInstrumentAssetId(
                    row.workOrderId,
                    tag,
                    installationId,
                ) ?: return review(
                    row,
                    actorId,
                    installationId,
                    "INSTRUMENT_ASSET_NOT_RESOLVED",
                )
            } else null

        val values = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                row.valuesJson,
            )
        }.getOrElse {
            return review(row, actorId, installationId, "ENGINEERING_VALUES_INVALID")
        }

        val receipt = remote.capture(
            row.workOrderId,
            FieldEngineeringTestCaptureDto(
                operationId = row.operationId,
                assignmentId = row.assignmentId,
                assignmentVersion = row.assignmentVersion,
                workOrderVersion = row.workOrderVersion,
                evidencePolicyId = row.evidencePolicyId,
                evidencePolicyRevision = row.evidencePolicyRevision,
                requirementKey = row.requirementKey,
                schemaTemplateId = row.schemaTemplateId,
                schemaTemplateRevision = row.schemaTemplateRevision,
                schemaVersion = row.schemaVersion,
                testSubjectRef = row.testSubjectRef,
                values = values,
                instrumentAssetId = instrumentAssetId,
                rawEvidenceId = rawEvidenceId,
                deviceReportedStatus = row.deviceReportedStatus,
                capturedAt = formatEpochUtc(row.capturedAtEpochMs),
                clientOccurredAt = formatEpochUtc(row.clientOccurredAtEpochMs),
                supersedesResultId = row.supersedesResultId,
            ),
            installationId,
        )
        if (receipt.resultId != row.operationId ||
            receipt.workOrderId != row.workOrderId ||
            receipt.requirementKey != row.requirementKey ||
            receipt.testSubjectRef != row.testSubjectRef) {
            return review(row, actorId, installationId, "ENGINEERING_RECEIPT_MISMATCH")
        }
        move(
            row,
            actorId,
            installationId,
            EngineeringTestIntentStates.APPLIED,
            resultId = receipt.resultId,
            evaluatedStatus = receipt.evaluatedStatus,
        )
        return ReplayOutcome.APPLIED
    }

    private suspend fun classifyFailure(
        row: LocalEngineeringTestIntentEntity,
        actorId: String,
        installationId: String,
        failure: Throwable,
    ): ReplayOutcome {
        val api = failure as? HiltechApiException
        if (api == null) {
            move(
                row,
                actorId,
                installationId,
                EngineeringTestIntentStates.RETRYABLE,
                error = "ENGINEERING_REPLAY_EXCEPTION",
                retry = true,
            )
            return ReplayOutcome.RETRY
        }
        if (api.httpStatus == 401 || api.code == "REAUTH_REQUIRED") {
            move(
                row,
                actorId,
                installationId,
                EngineeringTestIntentStates.RETRYABLE,
                error = "REAUTH_REQUIRED",
                correlation = api.correlationId,
                retry = true,
                retryDelayMs = 900_000L,
            )
            return ReplayOutcome.REAUTH
        }
        if (
            api.httpStatus in setOf(403, 404, 409) ||
            api.code.contains("CHANGED") ||
            api.code.contains("UNAVAILABLE") ||
            api.code.contains("CALIBRATION")
        ) {
            move(
                row,
                actorId,
                installationId,
                EngineeringTestIntentStates.REVIEW_REQUIRED,
                error = api.code,
                correlation = api.correlationId,
            )
            return ReplayOutcome.REVIEW
        }
        if (
            api.retryable ||
            api.httpStatus == 429 ||
            (api.httpStatus ?: 0) >= 500 ||
            api.code == "NETWORK_UNAVAILABLE"
        ) {
            move(
                row,
                actorId,
                installationId,
                EngineeringTestIntentStates.RETRYABLE,
                error = api.code,
                correlation = api.correlationId,
                retry = true,
            )
            return ReplayOutcome.RETRY
        }
        move(
            row,
            actorId,
            installationId,
            EngineeringTestIntentStates.REVIEW_REQUIRED,
            error = api.code,
            correlation = api.correlationId,
        )
        return ReplayOutcome.REVIEW
    }

    private suspend fun review(
        row: LocalEngineeringTestIntentEntity,
        actorId: String,
        installationId: String,
        code: String,
    ): ReplayOutcome {
        move(row, actorId, installationId, EngineeringTestIntentStates.REVIEW_REQUIRED, error = code)
        return ReplayOutcome.REVIEW
    }

    private suspend fun move(
        row: LocalEngineeringTestIntentEntity,
        actorId: String,
        installationId: String,
        state: String,
        error: String? = null,
        correlation: String? = null,
        resultId: String? = row.serverResultId,
        evaluatedStatus: String? = row.evaluatedStatus,
        retry: Boolean = false,
        retryDelayMs: Long = 30_000L,
    ) {
        val nextRetryCount = if (retry) row.retryCount + 1 else row.retryCount
        db.engineeringTestIntentDao().updateState(
            operationId = row.operationId,
            actorId = actorId,
            installationId = installationId,
            state = state,
            retryCount = nextRetryCount,
            nextRetryAtEpochMs = if (retry) nowEpochMs() + retryDelayMs.coerceAtMost(900_000L) else null,
            lastErrorCode = error,
            lastCorrelationId = correlation,
            serverResultId = resultId,
            evaluatedStatus = evaluatedStatus,
            updatedAtEpochMs = nowEpochMs(),
        )
    }

    private enum class ReplayOutcome { APPLIED, RETRY, REVIEW, REAUTH }
}

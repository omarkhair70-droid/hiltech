package com.hiltech.server.documents

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.documents.storage.EvidenceFinalizeVerification
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.documents.storage.EvidenceStorageProperties
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ReserveEvidenceUploadRequest(
    val operationId: String,
    val targetType: String,
    val targetId: String,
    val workOrderId: String? = null,
    val evidenceRequirementKey: String,
    val evidenceTypeCode: String,
    val contentType: String,
    val originalFileName: String? = null,
    val sizeBytes: Long,
    val expectedSha256: String,
    val capturedAt: String,
    val clientOccurredAt: String? = null,
    val supersedesEvidenceId: String? = null,
)

data class FinalizeEvidenceRequest(
    val operationId: String,
    val uploadSessionId: String,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
)

data class EvidenceUploadTargetResponse(
    val uploadUrl: String,
    val requiredHeaders: Map<String, List<String>>,
    val expiresAt: String,
    val expectedSizeBytes: Long,
)

data class EvidenceMetadataResponse(
    val evidenceId: String,
    val uploadSessionId: String,
    val targetType: String,
    val targetId: String,
    val workOrderId: String,
    val evidenceRequirementKey: String,
    val evidencePolicyId: String,
    val evidencePolicyRevision: Int,
    val evidenceTypeCode: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageState: String,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val evidenceVersion: Long,
)

data class ReserveEvidenceUploadResponse(
    val evidence: EvidenceMetadataResponse,
    val upload: EvidenceUploadTargetResponse,
    val correlationId: String,
    val replayed: Boolean,
)

data class FinalizeEvidenceResponse(
    val evidence: EvidenceMetadataResponse,
    val correlationId: String,
    val replayed: Boolean,
)

data class EvidenceDownloadTargetResponse(
    val downloadUrl: String,
    val expiresAt: String,
    val correlationId: String,
)

fun interface EvidenceStorageAccessPort {
    fun current(): EvidenceObjectStoragePort?
}

@Component
class SpringEvidenceStorageAccess(
    private val provider: ObjectProvider<EvidenceObjectStoragePort>,
) : EvidenceStorageAccessPort {
    override fun current(): EvidenceObjectStoragePort? =
        provider.ifAvailable
}

@Component
class EvidenceLifecycleService(
    private val persistence: EvidencePersistencePort,
    private val targetAuthorization: EvidenceTargetAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val projectionWriter: AuthorizationProjectionIntentWriter,
    private val audit: AuditEventWriter,
    private val storageAccess: EvidenceStorageAccessPort,
    private val storageProperties: EvidenceStorageProperties,
    private val clock: Clock,
) {
    fun reserve(
        actorIdentityId: UUID,
        idempotencyHeader: String?,
        correlationId: String,
        request: ReserveEvidenceUploadRequest,
    ): ReserveEvidenceUploadResponse {
        val command = request.toReserveCommand()

        IdempotencyKeyContract.requireMatches(
            rawHeader = idempotencyHeader,
            operationId = command.operationId,
        )

        if (command.targetType != "WORK_ORDER") {
            throw ProductApiException(
                code = "EVIDENCE_TARGET_UNSUPPORTED",
                message = "This Evidence target type is not supported yet.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        if (
            command.workOrderId != null &&
            command.workOrderId != command.targetId
        ) {
            throw ProductApiException(
                code = "EVIDENCE_WORK_ORDER_TARGET_MISMATCH",
                message = "workOrderId must match the WorkOrder target.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val workOrderId = command.targetId

        if (
            !targetAuthorization.canReserveForWorkOrder(
                identityId = actorIdentityId,
                workOrderId = workOrderId,
            )
        ) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "The requested WorkOrder is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        val policy =
            persistence.loadWorkOrderPolicy(
                workOrderId = workOrderId,
                requirementKey = command.requirementKey,
            ) ?: throw ProductApiException(
                code = "EVIDENCE_REQUIREMENT_NOT_BOUND",
                message = "The WorkOrder does not contain this Evidence requirement.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )

        validateReserveAgainstPolicy(
            command = command,
            policy = policy,
        )

        command.supersedesEvidenceId?.let { superseded ->
            if (
                !persistence.isValidSupersededEvidence(
                    supersedesEvidenceId = superseded,
                    organizationId = policy.organizationId,
                    workOrderId = workOrderId,
                )
            ) {
                throw ProductApiException(
                    code = "EVIDENCE_SUPERSEDES_INVALID",
                    message = "Superseded Evidence must belong to the same WorkOrder.",
                    status = HttpStatus.UNPROCESSABLE_ENTITY,
                )
            }
        }

        val storage = requireStorage()
        val now = clock.instant()
        val evidenceId = UUID.randomUUID()
        val uploadSessionId = UUID.randomUUID()
        val objectVersionId = UUID.randomUUID()
        val spec =
            EvidenceUploadSpec(
                evidenceId = evidenceId,
                organizationId = policy.organizationId,
                objectVersionId = objectVersionId,
                contentType = command.contentType,
                expectedSizeBytes = command.sizeBytes,
                expectedSha256Hex = command.sha256,
            )
        val expiresAt =
            now.plusSeconds(storageProperties.uploadExpirySeconds)

        val execution =
            idempotency.execute(
                spec =
                    IdempotentCommandSpec(
                        operationId = command.operationId,
                        actorUserId = actorIdentityId,
                        commandType = "ReserveEvidenceUpload",
                        targetType = "WorkOrder",
                        targetId = workOrderId,
                        requestFingerprint = command.fingerprint(),
                        correlationId = correlationId,
                    ),
            ) {
                persistence.insertReservation(
                    EvidenceReservationInsert(
                        evidenceId = evidenceId,
                        uploadSessionId = uploadSessionId,
                        operationId = command.operationId,
                        objectKey = spec.objectKey,
                        policy = policy,
                        contentType = command.contentType,
                        originalFileName = command.originalFileName,
                        sizeBytes = command.sizeBytes,
                        sha256 = command.sha256,
                        capturedAt = command.capturedAt,
                        clientOccurredAt = command.clientOccurredAt,
                        capturedByUserId = actorIdentityId,
                        supersedesEvidenceId = command.supersedesEvidenceId,
                        uploadExpiresAt = expiresAt,
                        createdAt = now,
                    ),
                )

                projectionWriter.write(
                    EvidenceAuthorizationProjectionFactory.workOrder(
                        evidenceId = evidenceId,
                        workOrderId = workOrderId,
                        evidenceVersion = 1,
                        occurredAt = now,
                    ),
                )
                projectionWriter.write(
                    EvidenceAuthorizationProjectionFactory.creator(
                        evidenceId = evidenceId,
                        identityId = actorIdentityId,
                        evidenceVersion = 1,
                        occurredAt = now,
                    ),
                )

                audit.append(
                    AuditEventRecord(
                        actorUserId = actorIdentityId,
                        action = "EVIDENCE_UPLOAD_RESERVED",
                        targetType = "Evidence",
                        targetId = evidenceId,
                        newStateRef = "evidence:RESERVED",
                        occurredAt = now,
                        correlationId = correlationId,
                        reason = "WORK_ORDER_EVIDENCE_RESERVE",
                        configRevisionRefsJson =
                            """{"evidencePolicyId":"${policy.evidencePolicyId}","revision":${policy.evidencePolicyRevision}}""",
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode = "EVIDENCE_RESERVED",
                )
            }

        val reservation =
            persistence.findReservationByOperationId(
                command.operationId,
            ) ?: throw ProductApiException(
                code = "EVIDENCE_RESERVATION_UNAVAILABLE",
                message = "The Evidence reservation is unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )

        if (
            !clock.instant().isBefore(
                reservation.uploadExpiresAt,
            )
        ) {
            throw ProductApiException(
                code = "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                message = "The Evidence upload session has expired.",
                status = HttpStatus.CONFLICT,
            )
        }

        val signed =
            runCatching {
                storage.createUploadTarget(
                    reservation.toStorageSpec(),
                )
            }.getOrElse {
                throw ProductApiException(
                    code = "EVIDENCE_STORAGE_UNAVAILABLE",
                    message = "Evidence storage is temporarily unavailable.",
                    status = HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
            }

        val effectiveExpiry =
            if (
                signed.expiresAt.isBefore(
                    reservation.uploadExpiresAt,
                )
            ) {
                signed.expiresAt
            } else {
                reservation.uploadExpiresAt
            }

        return ReserveEvidenceUploadResponse(
            evidence = reservation.toMetadata(),
            upload =
                EvidenceUploadTargetResponse(
                    uploadUrl = signed.uploadUrl,
                    requiredHeaders = signed.requiredHeaders,
                    expiresAt = effectiveExpiry.toString(),
                    expectedSizeBytes = reservation.sizeBytes,
                ),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    fun finalize(
        actorIdentityId: UUID,
        evidenceId: UUID,
        idempotencyHeader: String?,
        correlationId: String,
        request: FinalizeEvidenceRequest,
    ): FinalizeEvidenceResponse {
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        val uploadSessionId =
            request.uploadSessionId.toUuid(
                "INVALID_UPLOAD_SESSION_ID",
            )
        val sha256 =
            normalizeSha256(
                request.expectedSha256,
            )

        IdempotencyKeyContract.requireMatches(
            rawHeader = idempotencyHeader,
            operationId = operationId,
        )

        val record =
            persistence.loadFinalizeRecord(
                evidenceId = evidenceId,
                uploadSessionId = uploadSessionId,
            ) ?: throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )

        if (
            request.expectedSizeBytes != record.expectedSizeBytes ||
            sha256 != record.expectedSha256
        ) {
            throw ProductApiException(
                code = "EVIDENCE_FINALIZE_EXPECTATION_MISMATCH",
                message = "Finalize expectations do not match the reservation.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (
            record.workOrderLifecycleState in TERMINAL_WORK_STATES
        ) {
            throw ProductApiException(
                code = "WORK_ORDER_TERMINAL",
                message = "Evidence cannot be finalized for a terminal WorkOrder.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (
            !targetAuthorization.canFinalizeEvidence(
                identityId = actorIdentityId,
                evidenceId = record.evidenceId,
                workOrderId = record.workOrderId,
                creatorIdentityId = record.capturedByUserId,
            )
        ) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message = "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        if (
            record.storageState in FINAL_EVIDENCE_STATES
        ) {
            val execution =
                idempotency.execute(
                    spec =
                        finalizeSpec(
                            operationId = operationId,
                            actorIdentityId = actorIdentityId,
                            record = record,
                            sha256 = sha256,
                            sizeBytes = request.expectedSizeBytes,
                            correlationId = correlationId,
                        ),
                ) {
                    IdempotentCommandOutcome(
                        resultCode =
                            "EVIDENCE_" + record.storageState,
                    )
                }

            return FinalizeEvidenceResponse(
                evidence = record.toMetadata(),
                correlationId = correlationId,
                replayed = execution.replayed,
            )
        }

        if (
            !clock.instant().isBefore(
                record.uploadExpiresAt,
            )
        ) {
            throw ProductApiException(
                code = "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                message = "The Evidence upload session has expired.",
                status = HttpStatus.CONFLICT,
            )
        }

        val storage = requireStorage()
        val verification =
            storage.verifyUploadedObject(
                record.toStorageSpec(),
            )

        val targetState =
            when (verification) {
                is EvidenceFinalizeVerification.Retryable ->
                    throw ProductApiException(
                        code = verification.code,
                        message = "Evidence storage verification should be retried.",
                        status = HttpStatus.SERVICE_UNAVAILABLE,
                        retryable = true,
                    )

                is EvidenceFinalizeVerification.Rejected ->
                    "REJECTED"

                is EvidenceFinalizeVerification.Verified ->
                    verifiedTargetState(
                        record = record,
                        verification = verification,
                    )
            }

        val now = clock.instant()
        val execution =
            idempotency.execute(
                spec =
                    finalizeSpec(
                        operationId = operationId,
                        actorIdentityId = actorIdentityId,
                        record = record,
                        sha256 = sha256,
                        sizeBytes = request.expectedSizeBytes,
                        correlationId = correlationId,
                    ),
            ) {
                val updated =
                    persistence.markFinalized(
                        evidenceId = record.evidenceId,
                        uploadSessionId = record.uploadSessionId,
                        expectedEvidenceVersion = record.evidenceVersion,
                        expectedUploadSessionVersion = record.uploadSessionVersion,
                        state = targetState,
                        finalizedAt = now,
                    )

                if (!updated) {
                    throw ProductApiException(
                        code = "VERSION_CONFLICT",
                        message = "Evidence changed before finalize completed.",
                        status = HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId = actorIdentityId,
                        action = "EVIDENCE_UPLOAD_FINALIZED",
                        targetType = "Evidence",
                        targetId = record.evidenceId,
                        previousStateRef =
                            "evidence:" + record.storageState,
                        newStateRef =
                            "evidence:$targetState",
                        occurredAt = now,
                        correlationId = correlationId,
                        reason =
                            "BINARY_VERIFICATION_" + targetState,
                        configRevisionRefsJson =
                            """{"evidencePolicyId":"${record.evidencePolicyId}","revision":${record.evidencePolicyRevision}}""",
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EVIDENCE_$targetState",
                )
            }

        val updated =
            persistence.loadFinalizeRecord(
                evidenceId = record.evidenceId,
                uploadSessionId = record.uploadSessionId,
            ) ?: throw ProductApiException(
                code = "EVIDENCE_FINALIZE_RESULT_UNAVAILABLE",
                message = "The finalized Evidence is unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )

        return FinalizeEvidenceResponse(
            evidence = updated.toMetadata(),
            correlationId = correlationId,
            replayed = execution.replayed,
        )
    }

    fun metadata(
        actorIdentityId: UUID,
        evidenceId: UUID,
    ): EvidenceMetadataResponse {
        val record =
            loadVisibleEvidence(
                actorIdentityId =
                    actorIdentityId,
                evidenceId =
                    evidenceId,
                download = false,
            )

        return record.toMetadata()
    }

    fun downloadTarget(
        actorIdentityId: UUID,
        evidenceId: UUID,
        correlationId: String,
    ): EvidenceDownloadTargetResponse {
        val record =
            loadVisibleEvidence(
                actorIdentityId =
                    actorIdentityId,
                evidenceId =
                    evidenceId,
                download = true,
            )

        if (
            record.storageState != "READY"
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_NOT_DOWNLOADABLE",
                message =
                    "Evidence is not ready for download.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        if (
            record.classificationCode
                .equals(
                    "HIGHLY_RESTRICTED",
                    ignoreCase = true,
                )
        ) {
            throw ProductApiException(
                code =
                    "EVIDENCE_DIRECT_DOWNLOAD_FORBIDDEN",
                message =
                    "This Evidence classification requires protected delivery.",
                status =
                    HttpStatus.valueOf(422),
            )
        }

        val storage =
            requireStorage()
        val signed =
            runCatching {
                storage.createDownloadTarget(
                    record.objectKey,
                )
            }.getOrElse {
                throw ProductApiException(
                    code =
                        "EVIDENCE_STORAGE_UNAVAILABLE",
                    message =
                        "Evidence storage is temporarily unavailable.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
            }

        audit.append(
            AuditEventRecord(
                actorUserId =
                    actorIdentityId,
                action =
                    "EVIDENCE_DOWNLOAD_TARGET_ISSUED",
                targetType =
                    "Evidence",
                targetId =
                    record.evidenceId,
                newStateRef =
                    "evidence:" +
                        record.storageState,
                occurredAt =
                    clock.instant(),
                correlationId =
                    correlationId,
                reason =
                    "PRIVATE_SIGNED_DOWNLOAD",
                configRevisionRefsJson =
                    """{"evidencePolicyId":"${record.evidencePolicyId}","revision":${record.evidencePolicyRevision}}""",
            ),
        )

        return EvidenceDownloadTargetResponse(
            downloadUrl =
                signed.downloadUrl,
            expiresAt =
                signed.expiresAt.toString(),
            correlationId =
                correlationId,
        )
    }

    private fun loadVisibleEvidence(
        actorIdentityId: UUID,
        evidenceId: UUID,
        download: Boolean,
    ): EvidenceFinalizeRecord {
        val record =
            persistence.loadEvidenceRecord(
                evidenceId,
            ) ?: throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message =
                    "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )

        val allowed =
            if (download) {
                targetAuthorization
                    .canDownloadEvidence(
                        identityId =
                            actorIdentityId,
                        evidenceId =
                            record.evidenceId,
                        workOrderId =
                            record.workOrderId,
                        creatorIdentityId =
                            record.capturedByUserId,
                    )
            } else {
                targetAuthorization
                    .canViewEvidence(
                        identityId =
                            actorIdentityId,
                        evidenceId =
                            record.evidenceId,
                        workOrderId =
                            record.workOrderId,
                        creatorIdentityId =
                            record.capturedByUserId,
                    )
            }

        if (!allowed) {
            throw ProductApiException(
                code = "OBJECT_NOT_VISIBLE",
                message =
                    "The requested Evidence is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        return record
    }

    private fun validateReserveAgainstPolicy(
        command: ReserveCommand,
        policy: WorkOrderEvidencePolicySnapshot,
    ) {
        if (
            policy.lifecycleState in TERMINAL_WORK_STATES
        ) {
            throw ProductApiException(
                code = "WORK_ORDER_TERMINAL",
                message = "Evidence cannot be reserved for a terminal WorkOrder.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (
            command.assertedEvidenceTypeCode !=
                policy.evidenceTypeCode
        ) {
            throw ProductApiException(
                code = "EVIDENCE_TYPE_POLICY_MISMATCH",
                message = "Evidence type does not match the bound WorkOrder policy.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        if (
            command.contentType !in
                policy.allowedContentTypes
        ) {
            throw ProductApiException(
                code = "EVIDENCE_CONTENT_TYPE_NOT_ALLOWED",
                message = "This content type is not allowed by the bound Evidence policy.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }

        val maxBytes =
            minOf(
                policy.systemMaxSizeBytes,
                EvidenceUploadSpec.MAX_EVIDENCE_OBJECT_BYTES,
            )
        if (
            command.sizeBytes !in 1..maxBytes
        ) {
            throw ProductApiException(
                code = "EVIDENCE_SIZE_NOT_ALLOWED",
                message = "Evidence size exceeds the bound policy limit.",
                status = HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
    }

    private fun verifiedTargetState(
        record: EvidenceFinalizeRecord,
        verification: EvidenceFinalizeVerification.Verified,
    ): String =
        when (record.securityScanClass) {
            "ARBITRARY_FILE" ->
                "QUARANTINED"

            "GENERATED_TRUSTED_FORMAT" ->
                "READY"

            "NATIVE_MEDIA" -> {
                val detected =
                    verification.detectedContentType
                        ?.lowercase()

                if (
                    detected !=
                    record.contentType.lowercase()
                ) {
                    "REJECTED"
                } else {
                    "READY"
                }
            }

            else ->
                "REJECTED"
        }

    private fun finalizeSpec(
        operationId: UUID,
        actorIdentityId: UUID,
        record: EvidenceFinalizeRecord,
        sha256: String,
        sizeBytes: Long,
        correlationId: String,
    ): IdempotentCommandSpec =
        IdempotentCommandSpec(
            operationId = operationId,
            actorUserId = actorIdentityId,
            commandType = "FinalizeEvidence",
            targetType = "Evidence",
            targetId = record.evidenceId,
            requestFingerprint =
                IdempotencyKeyContract.fingerprint(
                    listOf(
                        record.evidenceId,
                        record.uploadSessionId,
                        sha256,
                        sizeBytes,
                    ).joinToString("|"),
                ),
            correlationId = correlationId,
        )

    private fun requireStorage(): EvidenceObjectStoragePort =
        storageAccess.current()
            ?: throw ProductApiException(
                code = "EVIDENCE_STORAGE_UNAVAILABLE",
                message = "Evidence storage is not configured.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
                retryable = true,
            )

    private fun ReserveEvidenceUploadRequest.toReserveCommand(): ReserveCommand {
        val normalizedTarget =
            targetType.trim().uppercase()

        return ReserveCommand(
            operationId =
                operationId.toUuid(
                    "INVALID_OPERATION_ID",
                ),
            targetType =
                normalizedTarget,
            targetId =
                targetId.toUuid(
                    "INVALID_TARGET_ID",
                ),
            workOrderId =
                workOrderId
                    ?.takeIf { it.isNotBlank() }
                    ?.toUuid(
                        "INVALID_WORK_ORDER_ID",
                    ),
            requirementKey =
                evidenceRequirementKey
                    .trim()
                    .takeIf {
                        it.isNotEmpty() &&
                            it.length <= 64
                    }
                    ?: malformed(
                        "INVALID_EVIDENCE_REQUIREMENT_KEY",
                    ),
            assertedEvidenceTypeCode =
                evidenceTypeCode
                    .trim()
                    .takeIf {
                        it.isNotEmpty() &&
                            it.length <= 64
                    }
                    ?: malformed(
                        "INVALID_EVIDENCE_TYPE",
                    ),
            contentType =
                normalizeContentType(
                    contentType,
                ),
            originalFileName =
                sanitizeFileName(
                    originalFileName,
                ),
            sizeBytes = sizeBytes,
            sha256 =
                normalizeSha256(
                    expectedSha256,
                ),
            capturedAt =
                capturedAt.toInstant(
                    "INVALID_CAPTURED_AT",
                ),
            clientOccurredAt =
                clientOccurredAt
                    ?.takeIf { it.isNotBlank() }
                    ?.toInstant(
                        "INVALID_CLIENT_OCCURRED_AT",
                    ),
            supersedesEvidenceId =
                supersedesEvidenceId
                    ?.takeIf { it.isNotBlank() }
                    ?.toUuid(
                        "INVALID_SUPERSEDES_EVIDENCE_ID",
                    ),
        )
    }

    private fun ReserveCommand.fingerprint(): String =
        IdempotencyKeyContract.fingerprint(
            listOf(
                targetType,
                targetId,
                workOrderId,
                requirementKey,
                assertedEvidenceTypeCode,
                contentType,
                originalFileName,
                sizeBytes,
                sha256,
                capturedAt,
                clientOccurredAt,
                supersedesEvidenceId,
            ).joinToString("|") {
                it?.toString() ?: ""
            },
        )

    private fun EvidenceReservationRecord.toStorageSpec(): EvidenceUploadSpec =
        EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = organizationId,
            objectVersionId =
                objectKey
                    .substringAfterLast("/")
                    .toUuid(
                        "EVIDENCE_OBJECT_KEY_INVALID",
                    ),
            contentType = contentType,
            expectedSizeBytes = sizeBytes,
            expectedSha256Hex = sha256,
        ).also {
            if (
                it.objectKey != objectKey
            ) {
                throw ProductApiException(
                    code = "EVIDENCE_OBJECT_KEY_INVALID",
                    message = "Evidence storage identity is invalid.",
                    status = HttpStatus.INTERNAL_SERVER_ERROR,
                )
            }
        }

    private fun EvidenceFinalizeRecord.toStorageSpec(): EvidenceUploadSpec =
        EvidenceUploadSpec(
            evidenceId = evidenceId,
            organizationId = organizationId,
            objectVersionId =
                objectKey
                    .substringAfterLast("/")
                    .toUuid(
                        "EVIDENCE_OBJECT_KEY_INVALID",
                    ),
            contentType = contentType,
            expectedSizeBytes = expectedSizeBytes,
            expectedSha256Hex = expectedSha256,
        ).also {
            if (
                it.objectKey != objectKey
            ) {
                throw ProductApiException(
                    code = "EVIDENCE_OBJECT_KEY_INVALID",
                    message = "Evidence storage identity is invalid.",
                    status = HttpStatus.INTERNAL_SERVER_ERROR,
                )
            }
        }

    private fun EvidenceReservationRecord.toMetadata(): EvidenceMetadataResponse =
        EvidenceMetadataResponse(
            evidenceId = evidenceId.toString(),
            uploadSessionId = uploadSessionId.toString(),
            targetType = "WORK_ORDER",
            targetId = workOrderId.toString(),
            workOrderId = workOrderId.toString(),
            evidenceRequirementKey = evidenceRequirementKey,
            evidencePolicyId = evidencePolicyId.toString(),
            evidencePolicyRevision = evidencePolicyRevision,
            evidenceTypeCode = evidenceTypeCode,
            contentType = contentType,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            storageState = storageState,
            classificationCode = classificationCode,
            clientVisibilityMode = clientVisibilityMode,
            evidenceVersion = evidenceVersion,
        )

    private fun EvidenceFinalizeRecord.toMetadata(): EvidenceMetadataResponse =
        EvidenceMetadataResponse(
            evidenceId = evidenceId.toString(),
            uploadSessionId = uploadSessionId.toString(),
            targetType = "WORK_ORDER",
            targetId = workOrderId.toString(),
            workOrderId = workOrderId.toString(),
            evidenceRequirementKey = evidenceRequirementKey,
            evidencePolicyId = evidencePolicyId.toString(),
            evidencePolicyRevision = evidencePolicyRevision,
            evidenceTypeCode = evidenceTypeCode,
            contentType = contentType,
            sizeBytes = expectedSizeBytes,
            sha256 = expectedSha256,
            storageState = storageState,
            classificationCode = classificationCode,
            clientVisibilityMode = clientVisibilityMode,
            evidenceVersion = evidenceVersion,
        )

    private fun normalizeContentType(
        value: String,
    ): String =
        value
            .substringBefore(";")
            .trim()
            .lowercase()
            .takeIf {
                CONTENT_TYPE_PATTERN.matches(it)
            }
            ?: malformed(
                "INVALID_EVIDENCE_CONTENT_TYPE",
            )

    private fun normalizeSha256(
        value: String,
    ): String =
        value
            .trim()
            .lowercase()
            .takeIf {
                SHA256_PATTERN.matches(it)
            }
            ?: malformed(
                "INVALID_EVIDENCE_SHA256",
            )

    private fun sanitizeFileName(
        value: String?,
    ): String? =
        value
            ?.replace("\\", "/")
            ?.substringAfterLast("/")
            ?.filter {
                it.code >= 0x20 &&
                    it != 0x7f.toChar()
            }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(255)

    private fun String.toUuid(
        code: String,
    ): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            malformed(code)
        }

    private fun String.toInstant(
        code: String,
    ): Instant =
        runCatching {
            Instant.parse(this)
        }.getOrElse {
            malformed(code)
        }

    private fun <T> malformed(
        code: String,
    ): T =
        throw ProductApiException(
            code = code,
            message = "Evidence request is invalid.",
            status = HttpStatus.BAD_REQUEST,
        )

    private data class ReserveCommand(
        val operationId: UUID,
        val targetType: String,
        val targetId: UUID,
        val workOrderId: UUID?,
        val requirementKey: String,
        val assertedEvidenceTypeCode: String,
        val contentType: String,
        val originalFileName: String?,
        val sizeBytes: Long,
        val sha256: String,
        val capturedAt: Instant,
        val clientOccurredAt: Instant?,
        val supersedesEvidenceId: UUID?,
    )

    companion object {
        private val TERMINAL_WORK_STATES =
            setOf(
                "ACCEPTED",
                "CLOSED",
                "CANCELLED",
            )

        private val FINAL_EVIDENCE_STATES =
            setOf(
                "READY",
                "QUARANTINED",
                "REJECTED",
            )

        private val SHA256_PATTERN =
            Regex("^[0-9a-f]{64}$")

        private val CONTENT_TYPE_PATTERN =
            Regex(
                "^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$",
            )
    }
}

@RestController
@RequestMapping("/v1/evidence")
class EvidenceLifecycleController(
    private val service: EvidenceLifecycleService,
) {
    @PostMapping("/reservations")
    fun reserve(
        requestContext: HttpServletRequest,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request: ReserveEvidenceUploadRequest,
    ): ReserveEvidenceUploadResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.reserve(
            actorIdentityId =
                context.requireIdentityId(),
            idempotencyHeader =
                idempotencyKey,
            correlationId =
                context.correlationId,
            request = request,
        )
    }

    @GetMapping("/{evidenceId}")
    fun evidenceMetadata(
        requestContext: HttpServletRequest,
        @PathVariable
        evidenceId: String,
    ): EvidenceMetadataResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.metadata(
            actorIdentityId =
                context.requireIdentityId(),
            evidenceId =
                evidenceId.toUuidOrBadRequest(
                    "INVALID_EVIDENCE_ID",
                ),
        )
    }

    @PostMapping("/{evidenceId}/download-target")
    fun downloadTarget(
        requestContext: HttpServletRequest,
        @PathVariable
        evidenceId: String,
    ): EvidenceDownloadTargetResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.downloadTarget(
            actorIdentityId =
                context.requireIdentityId(),
            evidenceId =
                evidenceId.toUuidOrBadRequest(
                    "INVALID_EVIDENCE_ID",
                ),
            correlationId =
                context.correlationId,
        )
    }

    @PostMapping("/{evidenceId}/finalize")
    fun finalizeEvidence(
        requestContext: HttpServletRequest,
        @PathVariable
        evidenceId: String,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request: FinalizeEvidenceRequest,
    ): FinalizeEvidenceResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.finalize(
            actorIdentityId =
                context.requireIdentityId(),
            evidenceId =
                evidenceId.toUuidOrBadRequest(
                    "INVALID_EVIDENCE_ID",
                ),
            idempotencyHeader =
                idempotencyKey,
            correlationId =
                context.correlationId,
            request = request,
        )
    }

    private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
        .requireIdentityId(): UUID =
        identityId
            ?.let {
                runCatching {
                    UUID.fromString(it)
                }.getOrNull()
            }
            ?: throw ProductApiException(
                code = "UNAUTHENTICATED",
                message = "Authentication is required.",
                status = HttpStatus.UNAUTHORIZED,
            )

    private fun String.toUuidOrBadRequest(
        code: String,
    ): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            throw ProductApiException(
                code = code,
                message = "Evidence request is invalid.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
}

package com.hiltech.shared.core.evidence

import kotlinx.serialization.Serializable

@Serializable
data class ReserveEvidenceUploadRequestDto(
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

@Serializable
data class FinalizeEvidenceRequestDto(
    val operationId: String,
    val uploadSessionId: String,
    val expectedSha256: String,
    val expectedSizeBytes: Long,
)

@Serializable
data class EvidenceUploadTargetDto(
    val uploadUrl: String,
    val requiredHeaders: Map<String, List<String>>,
    val expiresAt: String,
    val expectedSizeBytes: Long,
)

@Serializable
data class EvidenceMetadataDto(
    val evidenceId: String,
    val uploadSessionId: String,
    val targetType: String,
    val targetId: String,
    val workOrderId: String? = null,
    val evidenceRequirementKey: String? = null,
    val evidencePolicyId: String? = null,
    val evidencePolicyRevision: Int? = null,
    val evidenceTypeCode: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageState: String,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val evidenceVersion: Long,
)

@Serializable
data class ReserveEvidenceUploadResponseDto(
    val evidence: EvidenceMetadataDto,
    val upload: EvidenceUploadTargetDto,
    val correlationId: String,
    val replayed: Boolean,
)

@Serializable
data class FinalizeEvidenceResponseDto(
    val evidence: EvidenceMetadataDto,
    val correlationId: String,
    val replayed: Boolean,
)

@Serializable
data class EvidenceDownloadTargetDto(
    val downloadUrl: String,
    val expiresAt: String,
    val correlationId: String,
)

class EvidenceBinaryUploadException(
    val httpStatus: Int?,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

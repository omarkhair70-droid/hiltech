package com.hiltech.shared.core.commercial

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadResponseDto

/**
 * Commercial-specific orchestration on top of the SINGLE canonical Evidence API.
 * Operation IDs are stable UUIDs supplied/persisted by the caller for retry.
 * Company authorization, target identity, content scan and exact signature/PO
 * attestation are independently checked again by the server.
 */
enum class CommercialAwardDocumentType(
    val targetType: String,
    val evidenceType: String,
) {
    SIGNED_CONTRACT("COMMERCIAL_CONTRACT", "COMMERCIAL_SIGNED_CONTRACT"),
    CLIENT_PO("COMMERCIAL_CLIENT_PO", "COMMERCIAL_CLIENT_PO"),
}

data class CommercialAwardDocumentUpload(
    val document: CommercialAwardDocumentType,
    val targetId: String,
    val originalFileName: String,
    val contentType: String,
    val bytes: ByteArray,
    val sha256: String,
    val capturedAtUtc: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val confirmOperationId: String,
)

fun commercialEvidenceRequest(input: CommercialAwardDocumentUpload): ReserveEvidenceUploadRequestDto {
    require(input.bytes.isNotEmpty() && input.bytes.size <= 16_777_216) {
        "Commercial award files must respect the canonical Evidence size limit."
    }
    require(input.contentType in setOf("application/pdf", "image/png", "image/jpeg")) {
        "A signed PDF or supported document image is required."
    }
    require(Regex("^[0-9a-f]{64}$").matches(input.sha256)) {
        "SHA-256 must be the exact lowercase digest of the upload bytes."
    }
    require(input.originalFileName.isNotBlank())
    return ReserveEvidenceUploadRequestDto(
        operationId = input.reserveOperationId,
        targetType = input.document.targetType,
        targetId = input.targetId,
        evidenceRequirementKey = input.document.evidenceType,
        evidenceTypeCode = input.document.evidenceType,
        contentType = input.contentType,
        originalFileName = input.originalFileName,
        sizeBytes = input.bytes.size.toLong(),
        expectedSha256 = input.sha256,
        capturedAt = input.capturedAtUtc,
    )
}

class CommercialAwardDocumentCoordinator(
    private val evidence: EvidenceApiClient,
    private val commercial: CommercialApiClient,
    private val installationId: String,
) {
    suspend fun reserve(input: CommercialAwardDocumentUpload): ReserveEvidenceUploadResponseDto {
        val response = evidence.reserve(commercialEvidenceRequest(input), installationId)
        validateMetadata(input, response.evidence)
        require(response.evidence.storageState == "RESERVED") {
            "Existing Evidence is already finalized or invalid; inspect metadata before retry."
        }
        return response
    }

    suspend fun uploadAndFinalize(
        input: CommercialAwardDocumentUpload,
        reservation: ReserveEvidenceUploadResponseDto,
    ): EvidenceMetadataDto {
        validateMetadata(input, reservation.evidence)
        evidence.uploadBytes(reservation.upload, input.contentType, input.bytes)
        val finalized = evidence.finalize(
            reservation.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId = input.finalizeOperationId,
                uploadSessionId = reservation.evidence.uploadSessionId,
                expectedSha256 = input.sha256,
                expectedSizeBytes = input.bytes.size.toLong(),
            ),
            installationId,
        )
        validateMetadata(input, finalized.evidence)
        require(finalized.evidence.storageState == "READY") {
            "Document is not a verified READY Evidence; no contract/PO confirmation is permitted."
        }
        return finalized.evidence
    }

    suspend fun confirm(
        input: CommercialAwardDocumentUpload,
        ready: EvidenceMetadataDto,
    ): CommercialCommandResultDto {
        validateMetadata(input, ready)
        require(ready.storageState == "READY") {
            "Cannot attest a signed contract or PO without canonical READY Evidence."
        }
        return when (input.document) {
            CommercialAwardDocumentType.SIGNED_CONTRACT -> commercial.confirmSigned(
                input.targetId, ready.evidenceId, input.confirmOperationId, installationId,
            )
            CommercialAwardDocumentType.CLIENT_PO -> commercial.verifyClientPo(
                input.targetId, ready.evidenceId, input.confirmOperationId, installationId,
            )
        }
    }

    private fun validateMetadata(input: CommercialAwardDocumentUpload, record: EvidenceMetadataDto) {
        require(
            record.targetType == input.document.targetType &&
            record.targetId == input.targetId &&
            record.evidenceTypeCode == input.document.evidenceType &&
            record.contentType == input.contentType &&
            record.sha256 == input.sha256 &&
            record.sizeBytes == input.bytes.size.toLong() &&
            record.classificationCode == "HIGHLY_RESTRICTED" &&
            record.clientVisibilityMode == "INTERNAL_ONLY"
        ) { "Evidence metadata does not belong to this exact restricted commercial document." }
    }
}

package com.hiltech.shared.core.service

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import kotlinx.serialization.Serializable

/** Only immutable request metadata is saveable. File bytes and signed upload URLs stay in session memory. */
@Serializable
data class ServiceDeliveryUploadIntent(
    val slotOperationId: String, val reserveOperationId: String, val finalizeOperationId: String,
    val organizationId: String, val projectId: String, val purpose: String, val title: String,
    val filename: String, val contentType: String, val sizeBytes: Long, val sha256: String, val capturedAt: String,
    val slotId: String? = null, val evidenceId: String? = null, val uploadSessionId: String? = null,
)

fun serviceDeliverySelectedFileValid(file: ServiceSelectedFile): Boolean =
    file.contentType in setOf("application/pdf", "image/png", "image/jpeg") && file.bytes.size in 1..16_777_216 &&
        Regex("^[a-f0-9]{64}$").matches(file.sha256) && file.originalFileName.isNotBlank() && file.capturedAt.isNotBlank()

fun serviceDeliveryFileMatches(intent: ServiceDeliveryUploadIntent, file: ServiceSelectedFile): Boolean =
    serviceDeliverySelectedFileValid(file) && file.bytes.size.toLong() == intent.sizeBytes && file.sha256 == intent.sha256 && file.contentType == intent.contentType

class ServiceDeliveryUploadCoordinator(private val delivery: ServiceDeliveryApiClient, private val evidence: EvidenceApiClient) {
    suspend fun upload(initial: ServiceDeliveryUploadIntent, file: ServiceSelectedFile?, installation: String,
        save: (ServiceDeliveryUploadIntent) -> Unit): EvidenceMetadataDto {
        var intent = initial
        if (intent.slotId == null) {
            val slot = delivery.createDocument(intent.organizationId, intent.projectId,
                ServiceDeliveryDocumentRequest(intent.slotOperationId, intent.purpose, intent.title), installation)
            intent = intent.copy(slotId = slot.slotId); save(intent)
        }
        if (intent.evidenceId != null) {
            val current = evidence.metadata(requireNotNull(intent.evidenceId), installation)
            verify(current, intent)
            if (current.storageState == "READY") return current
        }
        require(file != null && serviceDeliveryFileMatches(intent, file)) { "SERVICE_DELIVERY_RESELECT_SAME_FILE" }
        val reservation = evidence.reserve(ReserveEvidenceUploadRequestDto(
            operationId = intent.reserveOperationId, targetType = "SERVICE_DELIVERY_DOCUMENT", targetId = requireNotNull(intent.slotId),
            evidenceRequirementKey = "SERVICE_DELIVERY_" + intent.purpose, evidenceTypeCode = "SERVICE_DELIVERY_" + intent.purpose,
            contentType = intent.contentType, originalFileName = intent.filename, sizeBytes = intent.sizeBytes,
            expectedSha256 = intent.sha256, capturedAt = intent.capturedAt,
        ), installation)
        verify(reservation.evidence, intent)
        if (intent.evidenceId != null) check(reservation.evidence.evidenceId == intent.evidenceId && reservation.evidence.uploadSessionId == intent.uploadSessionId)
        intent = intent.copy(evidenceId = reservation.evidence.evidenceId, uploadSessionId = reservation.evidence.uploadSessionId); save(intent)
        if (reservation.evidence.storageState == "READY") return reservation.evidence
        check(reservation.upload.expectedSizeBytes == intent.sizeBytes)
        evidence.uploadBytes(reservation.upload, intent.contentType, file.bytes)
        val finalized = evidence.finalize(requireNotNull(intent.evidenceId), FinalizeEvidenceRequestDto(
            intent.finalizeOperationId, requireNotNull(intent.uploadSessionId), intent.sha256, intent.sizeBytes), installation)
        verify(finalized.evidence, intent)
        check(finalized.evidence.storageState == "READY")
        return finalized.evidence
    }

    private fun verify(value: EvidenceMetadataDto, intent: ServiceDeliveryUploadIntent) {
        check(value.targetType == "SERVICE_DELIVERY_DOCUMENT" && value.targetId == intent.slotId && value.workOrderId == null && value.evidencePolicyId == null)
        check(value.evidenceTypeCode == "SERVICE_DELIVERY_" + intent.purpose && value.evidenceRequirementKey == value.evidenceTypeCode)
        check(value.contentType == intent.contentType && value.sizeBytes == intent.sizeBytes && value.sha256 == intent.sha256)
        check(value.classificationCode == "HIGHLY_RESTRICTED" && value.clientVisibilityMode == "INTERNAL_ONLY")
        if (intent.evidenceId != null) check(value.evidenceId == intent.evidenceId && value.uploadSessionId == intent.uploadSessionId)
    }
}

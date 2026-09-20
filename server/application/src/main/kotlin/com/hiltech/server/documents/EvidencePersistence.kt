package com.hiltech.server.documents

import java.time.Instant
import java.util.UUID

data class WorkOrderEvidencePolicySnapshot(
    val workOrderId: UUID,
    val organizationId: UUID,
    val workOrderVersion: Long,
    val lifecycleState: String,
    val instructionRevision: Long?,
    val evidencePolicyId: UUID,
    val evidencePolicyRevision: Int,
    val requirementKey: String,
    val evidenceTypeCode: String,
    val allowedContentTypes: Set<String>,
    val systemMaxSizeBytes: Long,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val securityScanClass: String,
)

data class EvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val policy: WorkOrderEvidencePolicySnapshot,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val supersedesEvidenceId: UUID?,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class EmployeeDocumentEvidenceReservationInsert(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val objectKey: String,
    val organizationId: UUID,
    val employeeDocumentId: UUID,
    val evidenceTypeCode: String,
    val contentType: String,
    val originalFileName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Instant,
    val clientOccurredAt: Instant?,
    val capturedByUserId: UUID,
    val securityScanClass: String,
    val uploadExpiresAt: Instant,
    val createdAt: Instant,
)

data class EvidenceReservationRecord(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val targetType: String,
    val targetId: UUID,
    val workOrderId: UUID?,
    val evidenceRequirementKey: String?,
    val evidencePolicyId: UUID?,
    val evidencePolicyRevision: Int?,
    val evidenceTypeCode: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val capturedByUserId: UUID,
    val storageState: String,
    val objectKey: String,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val securityScanClass: String?,
    val uploadState: String,
    val uploadExpiresAt: Instant,
    val evidenceVersion: Long,
    val uploadSessionVersion: Long,
)

data class EvidenceFinalizeRecord(
    val evidenceId: UUID,
    val uploadSessionId: UUID,
    val organizationId: UUID,
    val targetType: String,
    val targetId: UUID,
    val workOrderId: UUID?,
    val workOrderLifecycleState: String?,
    val capturedByUserId: UUID,
    val evidenceRequirementKey: String?,
    val evidencePolicyId: UUID?,
    val evidencePolicyRevision: Int?,
    val evidenceTypeCode: String,
    val contentType: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val storageState: String,
    val uploadState: String,
    val objectKey: String,
    val uploadExpiresAt: Instant,
    val classificationCode: String,
    val clientVisibilityMode: String,
    val securityScanClass: String,
    val evidenceVersion: Long,
    val uploadSessionVersion: Long,
)

interface EvidencePersistencePort {
    fun loadWorkOrderPolicy(
        workOrderId: UUID,
        requirementKey: String,
    ): WorkOrderEvidencePolicySnapshot?

    fun isValidSupersededEvidence(
        supersedesEvidenceId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
    ): Boolean

    fun insertReservation(
        value: EvidenceReservationInsert,
    )

    fun insertEmployeeDocumentReservation(
        value: EmployeeDocumentEvidenceReservationInsert,
    )

    fun findReservationByOperationId(
        operationId: UUID,
    ): EvidenceReservationRecord?

    fun loadEvidenceRecord(
        evidenceId: UUID,
    ): EvidenceFinalizeRecord?

    fun loadFinalizeRecord(
        evidenceId: UUID,
        uploadSessionId: UUID,
    ): EvidenceFinalizeRecord?

    fun markFinalized(
        evidenceId: UUID,
        uploadSessionId: UUID,
        expectedEvidenceVersion: Long,
        expectedUploadSessionVersion: Long,
        state: String,
        finalizedAt: Instant,
    ): Boolean
}

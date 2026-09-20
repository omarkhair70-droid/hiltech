package com.hiltech.server.documents

import java.time.Instant
import java.util.UUID

data class EmployeeDocumentEvidenceTargetSnapshot(
    val documentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val documentTypeCode: String,
    val verificationState: String,
    val evidenceId: UUID?,
    val version: Long,
)

interface EmployeeDocumentEvidenceTargetPort {
    fun loadTarget(
        documentId: UUID,
    ): EmployeeDocumentEvidenceTargetSnapshot?

    fun canReserve(
        identityId: UUID,
        target: EmployeeDocumentEvidenceTargetSnapshot,
    ): Boolean

    fun canAccess(
        identityId: UUID,
        target: EmployeeDocumentEvidenceTargetSnapshot,
    ): Boolean

    fun attachEvidence(
        documentId: UUID,
        evidenceId: UUID,
        at: Instant,
    ): Boolean
}

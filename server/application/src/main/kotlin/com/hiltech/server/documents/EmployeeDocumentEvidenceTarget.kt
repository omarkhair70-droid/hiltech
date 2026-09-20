package com.hiltech.server.documents

import java.time.Instant
import java.util.UUID

data class EmployeeDocumentEvidenceTargetSnapshot(
    val documentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val verificationState: String,
    val evidenceId: UUID?,
    val version: Long,
)

interface EmployeeDocumentEvidenceTargetPort {
    fun loadTarget(
        documentId: UUID,
    ): EmployeeDocumentEvidenceTargetSnapshot?

    fun canManage(
        identityId: UUID,
        organizationId: UUID,
    ): Boolean

    fun attachEvidence(
        documentId: UUID,
        evidenceId: UUID,
        at: Instant,
    ): Boolean
}

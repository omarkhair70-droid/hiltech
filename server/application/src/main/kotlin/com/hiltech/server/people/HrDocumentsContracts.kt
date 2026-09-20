package com.hiltech.server.people

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class HrVerificationState {
    UNVERIFIED,
    VERIFIED,
    REJECTED,
}

data class EmployeeDocumentSnapshot(
    val documentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employeeCode: String,
    val employeeDisplayName: String,
    val documentTypeCode: String,
    val documentLabel: String?,
    val issueDate: LocalDate?,
    val expiryDate: LocalDate?,
    val verificationState: HrVerificationState,
    val verifiedAt: Instant?,
    val verifiedByUserId: UUID?,
    val evidenceId: UUID?,
    val evidenceStorageState: String?,
    val retentionPolicyCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class CertificationSnapshot(
    val certificationId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employeeCode: String,
    val employeeDisplayName: String,
    val certificationTypeCode: String,
    val certificationLabel: String?,
    val issuer: String?,
    val issuedAt: Instant?,
    val validUntil: Instant?,
    val verificationState: HrVerificationState,
    val verifiedAt: Instant?,
    val verifiedByUserId: UUID?,
    val employeeDocumentId: UUID?,
    val documentVerificationState: HrVerificationState?,
    val documentEvidenceStorageState: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class CertificationEligibilitySnapshot(
    val employeeId: UUID,
    val employeeCode: String,
    val employeeDisplayName: String,
    val certificationId: UUID,
    val certificationTypeCode: String,
    val certificationLabel: String?,
    val verificationState: HrVerificationState,
    val validNow: Boolean,
    val validUntil: Instant?,
)

data class CreateEmployeeDocumentCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseEmployeeVersion: Long,
    val documentTypeCode: String,
    val documentLabel: String?,
    val issueDate: LocalDate?,
    val expiryDate: LocalDate?,
    val retentionPolicyCode: String?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class VerifyEmployeeDocumentCommand(
    val operationId: UUID,
    val documentId: UUID,
    val baseVersion: Long,
    val result: HrVerificationState,
    val actorUserId: UUID,
    val correlationId: String,
)

data class CreateCertificationCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseEmployeeVersion: Long,
    val certificationTypeCode: String,
    val certificationLabel: String?,
    val issuer: String?,
    val issuedAt: Instant?,
    val validUntil: Instant?,
    val employeeDocumentId: UUID?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class VerifyCertificationCommand(
    val operationId: UUID,
    val certificationId: UUID,
    val baseVersion: Long,
    val result: HrVerificationState,
    val actorUserId: UUID,
    val correlationId: String,
)

data class EmployeeDocumentCommandResult(
    val document: EmployeeDocumentSnapshot,
    val replayed: Boolean,
)

data class CertificationCommandResult(
    val certification: CertificationSnapshot,
    val replayed: Boolean,
)

data class EmployeeDocumentCreated(
    val eventId: UUID = UUID.randomUUID(),
    val documentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val documentTypeCode: String,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmployeeDocumentVerified(
    val eventId: UUID = UUID.randomUUID(),
    val documentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val documentTypeCode: String,
    val verificationState: HrVerificationState,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class CertificationCreated(
    val eventId: UUID = UUID.randomUUID(),
    val certificationId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val certificationTypeCode: String,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class CertificationVerified(
    val eventId: UUID = UUID.randomUUID(),
    val certificationId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val certificationTypeCode: String,
    val verificationState: HrVerificationState,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

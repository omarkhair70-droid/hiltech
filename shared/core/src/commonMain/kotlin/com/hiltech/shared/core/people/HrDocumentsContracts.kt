package com.hiltech.shared.core.people

import kotlinx.serialization.Serializable

@Serializable
data class CreateEmployeeDocumentRequestDto(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val documentTypeCode: String,
    val documentLabel: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val retentionPolicyCode: String? = null,
)

@Serializable
data class VerifyHrRecordRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val result: String,
)

@Serializable
data class CreateCertificationRequestDto(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val certificationTypeCode: String,
    val certificationLabel: String? = null,
    val issuer: String? = null,
    val issuedAt: String? = null,
    val validUntil: String? = null,
    val employeeDocumentId: String? = null,
)

@Serializable
data class EmployeeDocumentDto(
    val documentId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val documentTypeCode: String,
    val documentLabel: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val verificationState: String,
    val verifiedAt: String? = null,
    val verifiedByUserId: String? = null,
    val evidenceId: String? = null,
    val evidenceStorageState: String? = null,
    val retentionPolicyCode: String? = null,
    val version: Long,
)

@Serializable
data class EmployeeDocumentListDto(
    val items: List<EmployeeDocumentDto>,
    val correlationId: String,
)

@Serializable
data class EmployeeDocumentCommandResponseDto(
    val document: EmployeeDocumentDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class CertificationDto(
    val certificationId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val certificationTypeCode: String,
    val certificationLabel: String? = null,
    val issuer: String? = null,
    val issuedAt: String? = null,
    val validUntil: String? = null,
    val verificationState: String,
    val verifiedAt: String? = null,
    val verifiedByUserId: String? = null,
    val employeeDocumentId: String? = null,
    val documentVerificationState: String? = null,
    val documentEvidenceStorageState: String? = null,
    val version: Long,
)

@Serializable
data class CertificationListDto(
    val items: List<CertificationDto>,
    val correlationId: String,
)

@Serializable
data class CertificationCommandResponseDto(
    val certification: CertificationDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class CertificationEligibilityDto(
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val certificationId: String,
    val certificationTypeCode: String,
    val certificationLabel: String? = null,
    val verificationState: String,
    val validNow: Boolean,
    val validUntil: String? = null,
)

@Serializable
data class CertificationEligibilityListDto(
    val items: List<CertificationEligibilityDto>,
    val correlationId: String,
)

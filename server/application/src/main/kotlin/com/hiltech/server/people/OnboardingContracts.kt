package com.hiltech.server.people

import java.time.Instant
import java.util.UUID

enum class OnboardingCaseState {
    OPEN,
    ACTIVATED,
}

enum class OnboardingRequirementType {
    PROFILE_FIELD,
    IDENTITY_READY,
    WORKFORCE_ASSIGNMENT,
    EMPLOYEE_DOCUMENT,
    CERTIFICATION,
    MANUAL_CONFIRMATION,
}

enum class OnboardingResponsibility {
    EMPLOYEE,
    HILTECH,
    SHARED,
}

enum class OnboardingRequirementStatus {
    NEEDS_EMPLOYEE,
    WAITING_HILTECH,
    SATISFIED,
    WAIVED,
    BLOCKED,
}

enum class OnboardingManualResolutionType {
    SATISFIED,
    WAIVED,
}

data class OnboardingPolicyContext(
    val configRevisionId: UUID,
    val scopeType: String,
    val scopeOrganizationId: UUID?,
    val lifecycleState: String,
    val code: String,
    val name: String,
    val revisionNumber: Int,
)

data class OnboardingRequirementDefinition(
    val id: UUID,
    val configRevisionId: UUID,
    val requirementKey: String,
    val requirementType: OnboardingRequirementType,
    val label: String,
    val responsibility: OnboardingResponsibility,
    val blocking: Boolean,
    val waiverAllowed: Boolean,
    val selfServiceVisible: Boolean,
    val employeeMaySubmit: Boolean,
    val evidenceRequired: Boolean,
    val profileFieldCode: String?,
    val documentTypeCode: String?,
    val certificationTypeCode: String?,
    val manualConfirmationCode: String?,
    val sortOrder: Int,
)

data class OnboardingEmployeeContext(
    val employeeId: UUID,
    val organizationId: UUID,
    val personId: UUID,
    val employeeCode: String,
    val employeeState: EmployeeState,
    val employeeVersion: Long,
    val displayName: String,
    val mobile: String?,
    val email: String?,
    val personVersion: Long,
)

data class OnboardingCaseRecord(
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val policyConfigRevisionId: UUID,
    val state: OnboardingCaseState,
    val startedAt: Instant,
    val startedByUserId: UUID,
    val activatedAt: Instant?,
    val activatedByUserId: UUID?,
    val version: Long,
)

data class OnboardingManualResolution(
    val resolutionId: UUID,
    val onboardingCaseId: UUID,
    val requirementKey: String,
    val resolution: OnboardingManualResolutionType,
    val reason: String?,
    val resolvedAt: Instant,
    val resolvedByUserId: UUID,
    val version: Long,
)

data class OnboardingDocumentFact(
    val verificationState: HrVerificationState,
    val evidenceStorageState: String?,
)

data class OnboardingCertificationFact(
    val verificationState: HrVerificationState,
    val issuedAt: Instant?,
    val validUntil: Instant?,
)

data class OnboardingRequirementView(
    val requirementKey: String,
    val label: String,
    val responsibility: OnboardingResponsibility,
    val blocking: Boolean,
    val status: OnboardingRequirementStatus,
    val actionCode: String?,
    val sourceStateCode: String?,
    val sortOrder: Int,
)

data class OnboardingCaseView(
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employeeCode: String,
    val employeeDisplayName: String,
    val employeeState: EmployeeState,
    val employeeVersion: Long,
    val policyConfigRevisionId: UUID,
    val policyCode: String,
    val policyRevisionNumber: Int,
    val state: OnboardingCaseState,
    val requirements: List<OnboardingRequirementView>,
    val blockingSatisfied: Boolean,
    val caseVersion: Long,
    val startedAt: Instant,
    val activatedAt: Instant?,
)

data class StartOnboardingCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseEmployeeVersion: Long,
    val onboardingPolicyRevisionId: UUID,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ResolveOnboardingRequirementCommand(
    val operationId: UUID,
    val onboardingCaseId: UUID,
    val baseVersion: Long,
    val requirementKey: String,
    val resolution: OnboardingManualResolutionType,
    val reason: String?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ActivateEmployeeOnboardingCommand(
    val operationId: UUID,
    val onboardingCaseId: UUID,
    val caseBaseVersion: Long,
    val employeeBaseVersion: Long,
    val actorUserId: UUID,
    val correlationId: String,
)

data class OnboardingCommandResult(
    val onboarding: OnboardingCaseView,
    val replayed: Boolean,
)

data class OnboardingStarted(
    val eventId: UUID = UUID.randomUUID(),
    val onboardingCaseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val policyConfigRevisionId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class OnboardingRequirementResolved(
    val eventId: UUID = UUID.randomUUID(),
    val onboardingCaseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val requirementKey: String,
    val resolution: OnboardingManualResolutionType,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmployeeActivated(
    val eventId: UUID = UUID.randomUUID(),
    val onboardingCaseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class OnboardingActivated(
    val eventId: UUID = UUID.randomUUID(),
    val onboardingCaseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)


interface OnboardingSelfServicePolicyPort {
    fun ownEmployee(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): OnboardingEmployeeContext?

    fun canViewEmployeeDocument(
        identityId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        documentTypeCode: String,
        at: Instant,
    ): Boolean

    fun canSubmitEmployeeDocument(
        identityId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        documentTypeCode: String,
        at: Instant,
    ): Boolean
}

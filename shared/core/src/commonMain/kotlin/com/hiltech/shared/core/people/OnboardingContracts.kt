package com.hiltech.shared.core.people

import kotlinx.serialization.Serializable

@Serializable
data class StartOnboardingRequestDto(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val onboardingPolicyRevisionId: String,
)

@Serializable
data class ResolveOnboardingRequirementRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val resolution: String,
    val reason: String? = null,
)

@Serializable
data class ActivateEmployeeOnboardingRequestDto(
    val operationId: String,
    val caseBaseVersion: Long,
    val employeeBaseVersion: Long,
)

@Serializable
data class OnboardingRequirementDto(
    val requirementKey: String,
    val label: String,
    val responsibility: String,
    val blocking: Boolean,
    val status: String,
    val actionCode: String? = null,
    val sourceStateCode: String? = null,
    val sortOrder: Int,
)

@Serializable
data class OnboardingCaseDto(
    val caseId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val employeeState: String,
    val employeeVersion: Long,
    val policyConfigRevisionId: String,
    val policyCode: String,
    val policyRevisionNumber: Int,
    val state: String,
    val requirements:
        List<OnboardingRequirementDto>,
    val blockingSatisfied: Boolean,
    val caseVersion: Long,
    val startedAt: String,
    val activatedAt: String? = null,
)

@Serializable
data class OnboardingCommandResponseDto(
    val onboarding: OnboardingCaseDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class ProvisionEmployeeIdentityRequestDto(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val requestedLogin: String,
    val deliveryMode: String,
)

@Serializable
data class EmployeeIdentityInvitationDto(
    val invitationId: String,
    val organizationId: String,
    val employeeId: String,
    val state: String,
    val deliveryMode: String,
    val deliveryState: String,
    val userIdentityId: String? = null,
    val temporaryCredential: String? = null,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

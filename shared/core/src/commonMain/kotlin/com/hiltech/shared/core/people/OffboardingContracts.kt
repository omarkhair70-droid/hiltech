package com.hiltech.shared.core.people

import kotlinx.serialization.Serializable

@Serializable
data class StartEmployeeOffboardingRequestDto(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val lastWorkingDate: String,
    val reasonCategoryCode: String,
    val note: String? = null,
)

@Serializable
data class RevokeEmployeeOffboardingAccessRequestDto(
    val operationId: String,
    val baseCaseVersion: Long,
)

@Serializable
data class ResolveOffboardingClearanceRequestDto(
    val operationId: String,
    val baseCaseVersion: Long,
    val resolution: String,
    val reason: String? = null,
)

@Serializable
data class CompleteEmployeeOffboardingRequestDto(
    val operationId: String,
    val baseCaseVersion: Long,
    val baseEmployeeVersion: Long,
    val baseEmploymentVersion: Long,
)

@Serializable
data class OffboardingClearanceDto(
    val type: String,
    val state: String,
    val source: String,
    val reason: String? = null,
    val resolvedAt: String? = null,
    val version: Long,
)

@Serializable
data class OffboardingAccessFactsDto(
    val linkedIdentityPresent: Boolean,
    val activeOrganizationMemberships: Int,
    val activeSessions: Int,
    val currentWorkforceAssignmentPresent: Boolean,
    val clear: Boolean,
)

@Serializable
data class OffboardingCaseDto(
    val caseId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val employeeState: String,
    val employeeVersion: Long,
    val activeEmploymentId: String? = null,
    val employmentStartDate: String? = null,
    val employmentVersion: Long? = null,
    val state: String,
    val lastWorkingDate: String,
    val reasonCategoryCode: String,
    val note: String? = null,
    val clearances:
        List<OffboardingClearanceDto>,
    val accessFacts:
        OffboardingAccessFactsDto,
    val canComplete: Boolean,
    val blockers: List<String>,
    val caseVersion: Long,
    val startedAt: String,
    val completedAt: String? = null,
)

@Serializable
data class OffboardingCommandResponseDto(
    val offboarding: OffboardingCaseDto,
    val replayed: Boolean,
    val correlationId: String,
)

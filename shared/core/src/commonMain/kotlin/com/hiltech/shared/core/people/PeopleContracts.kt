package com.hiltech.shared.core.people

import kotlinx.serialization.Serializable

@Serializable
data class CreateEmployeeRequestDto(
    val operationId: String,
    val organizationId: String,
    val displayName: String,
    val employeeCode: String,
    val startDate: String,
    val employmentTypeCode: String? = null,
    val linkedUserIdentityId: String? = null,
    val hireDate: String? = null,
    val legalName: String? = null,
    val mobile: String? = null,
    val email: String? = null,
)

@Serializable
data class LinkEmployeeIdentityRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val userIdentityId: String,
)

@Serializable
data class UpdateEmployeeProfileRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val displayName: String,
    val legalName: String? = null,
    val mobile: String? = null,
    val email: String? = null,
)

@Serializable
data class EmployeeDirectoryItemDto(
    val employeeId: String,
    val employeeCode: String,
    val displayName: String,
    val employeeState: String,
    val linkedIdentity: Boolean,
    val currentEmploymentTypeCode: String? = null,
    val hireDate: String? = null,
    val version: Long,
)

@Serializable
data class EmployeeDirectoryDto(
    val items: List<EmployeeDirectoryItemDto>,
    val canManagePeople: Boolean,
    val correlationId: String,
)

@Serializable
data class EmployeeDetailDto(
    val employeeId: String,
    val organizationId: String,
    val employeeCode: String,
    val employeeState: String,
    val displayName: String,
    val legalName: String? = null,
    val mobile: String? = null,
    val email: String? = null,
    val hireDate: String? = null,
    val endDate: String? = null,
    val currentEmploymentId: String? = null,
    val employmentTypeCode: String? = null,
    val employmentStartDate: String? = null,
    val employmentEndDate: String? = null,
    val linkedIdentityId: String? = null,
    val version: Long,
)

@Serializable
data class EmployeeCommandResponseDto(
    val employee: EmployeeDetailDto,
    val replayed: Boolean,
    val correlationId: String,
)

package com.hiltech.shared.core.people

import kotlinx.serialization.Serializable

@Serializable
data class CreateWorkforceAssignmentRequestDto(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val teamId: String? = null,
    val roleCode: String,
    val roleLabel: String? = null,
    val reportsToEmployeeId: String? = null,
    val effectiveFrom: String,
)

@Serializable
data class ChangeWorkforceAssignmentRequestDto(
    val operationId: String,
    val currentAssignmentId: String,
    val baseAssignmentVersion: Long,
    val teamId: String? = null,
    val roleCode: String,
    val roleLabel: String? = null,
    val reportsToEmployeeId: String? = null,
)

@Serializable
data class WorkforceAssignmentDto(
    val assignmentId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val teamId: String? = null,
    val teamCode: String? = null,
    val teamName: String? = null,
    val roleCode: String,
    val roleLabel: String? = null,
    val reportsToEmployeeId: String? = null,
    val reportsToEmployeeCode: String? = null,
    val reportsToDisplayName: String? = null,
    val state: String,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
    val version: Long,
    val supersedesAssignmentId: String? = null,
)

@Serializable
data class WorkforceAssignmentCommandResponseDto(
    val assignment:
        WorkforceAssignmentDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class WorkforceStructureDto(
    val items:
        List<WorkforceAssignmentDto>,
    val correlationId: String,
)

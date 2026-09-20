package com.hiltech.server.people

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class EmployeeState {
    PREBOARDING,
    ACTIVE,
    OFFBOARDING,
    FORMER,
}

enum class EmploymentState {
    ACTIVE,
    ENDED,
}

data class EmployeeAggregateSnapshot(
    val employeeId: UUID,
    val organizationId: UUID,
    val personId: UUID,
    val employeeCode: String,
    val employeeState: EmployeeState,
    val hireDate: LocalDate?,
    val endDate: LocalDate?,
    val employeeVersion: Long,
    val displayName: String,
    val legalName: String?,
    val mobile: String?,
    val email: String?,
    val personVersion: Long,
    val activeEmploymentId: UUID?,
    val employmentTypeCode: String?,
    val employmentStartDate: LocalDate?,
    val employmentEndDate: LocalDate?,
    val employmentVersion: Long?,
    val linkedIdentityId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class EmployeeDirectoryRecord(
    val employeeId: UUID,
    val employeeCode: String,
    val displayName: String,
    val employeeState: EmployeeState,
    val linkedIdentity: Boolean,
    val currentEmploymentTypeCode: String?,
    val hireDate: LocalDate?,
    val version: Long,
)

data class CreateEmployeeCommand(
    val operationId: UUID,
    val organizationId: UUID,
    val displayName: String,
    val employeeCode: String,
    val startDate: LocalDate,
    val employmentTypeCode: String?,
    val linkedUserIdentityId: UUID?,
    val hireDate: LocalDate?,
    val legalName: String?,
    val mobile: String?,
    val email: String?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class LinkEmployeeIdentityCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseVersion: Long,
    val userIdentityId: UUID,
    val actorUserId: UUID,
    val correlationId: String,
)

data class UpdateEmployeeProfileCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseVersion: Long,
    val displayName: String,
    val legalName: String?,
    val mobile: String?,
    val email: String?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class PeopleCommandResult(
    val employee: EmployeeAggregateSnapshot,
    val replayed: Boolean,
)

data class EmployeeCreated(
    val eventId: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val organizationId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmployeeIdentityLinked(
    val eventId: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val organizationId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmployeeProfileUpdated(
    val eventId: UUID = UUID.randomUUID(),
    val employeeId: UUID,
    val organizationId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

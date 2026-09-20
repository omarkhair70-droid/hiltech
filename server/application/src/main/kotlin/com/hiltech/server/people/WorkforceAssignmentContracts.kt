package com.hiltech.server.people

import java.time.Instant
import java.util.UUID

enum class WorkforceAssignmentState {
    ACTIVE,
    ENDED,
}

data class WorkforceAssignmentSnapshot(
    val assignmentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employeeCode: String,
    val employeeDisplayName: String,
    val teamId: UUID?,
    val teamCode: String?,
    val teamName: String?,
    val roleCode: String,
    val roleLabel: String?,
    val reportsToEmployeeId: UUID?,
    val reportsToEmployeeCode: String?,
    val reportsToDisplayName: String?,
    val state: WorkforceAssignmentState,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val version: Long,
    val supersedesAssignmentId: UUID? = null,
)

data class CreateWorkforceAssignmentCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseEmployeeVersion: Long,
    val teamId: UUID?,
    val roleCode: String,
    val roleLabel: String?,
    val reportsToEmployeeId: UUID?,
    val effectiveFrom: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ChangeWorkforceAssignmentCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val currentAssignmentId: UUID,
    val baseAssignmentVersion: Long,
    val teamId: UUID?,
    val roleCode: String,
    val roleLabel: String?,
    val reportsToEmployeeId: UUID?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class WorkforceAssignmentCommandResult(
    val assignment:
        WorkforceAssignmentSnapshot,
    val replayed: Boolean,
)

data class WorkforceAssignmentCreated(
    val eventId: UUID = UUID.randomUUID(),
    val assignmentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val teamId: UUID?,
    val roleCode: String,
    val reportsToEmployeeId: UUID?,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class WorkforceAssignmentChanged(
    val eventId: UUID = UUID.randomUUID(),
    val previousAssignmentId: UUID,
    val newAssignmentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val previousTeamId: UUID?,
    val newTeamId: UUID?,
    val previousRoleCode: String,
    val newRoleCode: String,
    val previousReportsToEmployeeId: UUID?,
    val newReportsToEmployeeId: UUID?,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class WorkforceAssignmentSecuritySynchronized(
    val eventId: UUID = UUID.randomUUID(),
    val assignmentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val teamId: UUID,
    val userIdentityId: UUID,
    val teamMembershipId: UUID,
    val sourceVersion: Long,
    val occurredAt: Instant,
    val correlationId: String?,
)

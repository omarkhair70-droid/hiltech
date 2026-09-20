package com.hiltech.server.people

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class OffboardingCaseState {
    OPEN,
    COMPLETED,
}

enum class OffboardingClearanceType {
    HR,
    ACCESS,
    PROJECT,
    ASSET,
    FINANCE,
    PAYROLL,
}

enum class OffboardingClearanceState {
    PENDING,
    CLEAR,
    NOT_APPLICABLE,
    EXCEPTION_ACCEPTED,
}

enum class OffboardingClearanceSource {
    PEOPLE,
    IDENTITY,
    EXTERNAL_DOMAIN,
}

data class OffboardingCaseRecord(
    val caseId: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val state: OffboardingCaseState,
    val lastWorkingDate: LocalDate,
    val reasonCategoryCode: String,
    val note: String?,
    val startedAt: Instant,
    val startedByUserId: UUID,
    val completedAt: Instant?,
    val completedByUserId: UUID?,
    val version: Long,
)

data class OffboardingClearanceRecord(
    val clearanceId: UUID,
    val caseId: UUID,
    val type: OffboardingClearanceType,
    val state: OffboardingClearanceState,
    val reason: String?,
    val resolvedAt: Instant?,
    val resolvedByUserId: UUID?,
    val source: OffboardingClearanceSource,
    val version: Long,
)

data class OffboardingAccessFacts(
    val linkedIdentityPresent: Boolean,
    val activeOrganizationMemberships: Int,
    val activeSessions: Int,
    val currentWorkforceAssignmentPresent: Boolean,
) {
    val clear: Boolean
        get() =
            activeOrganizationMemberships == 0 &&
                activeSessions == 0 &&
                !currentWorkforceAssignmentPresent
}

data class OffboardingClearanceView(
    val type: OffboardingClearanceType,
    val state: OffboardingClearanceState,
    val source: OffboardingClearanceSource,
    val reason: String?,
    val resolvedAt: Instant?,
    val version: Long,
)

data class OffboardingCaseView(
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employeeCode: String,
    val employeeDisplayName: String,
    val employeeState: EmployeeState,
    val employeeVersion: Long,
    val activeEmploymentId: UUID?,
    val employmentStartDate: LocalDate?,
    val employmentVersion: Long?,
    val state: OffboardingCaseState,
    val lastWorkingDate: LocalDate,
    val reasonCategoryCode: String,
    val note: String?,
    val clearances: List<OffboardingClearanceView>,
    val accessFacts: OffboardingAccessFacts,
    val canComplete: Boolean,
    val blockers: List<String>,
    val caseVersion: Long,
    val startedAt: Instant,
    val completedAt: Instant?,
)

data class StartEmployeeOffboardingCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseEmployeeVersion: Long,
    val lastWorkingDate: LocalDate,
    val reasonCategoryCode: String,
    val note: String?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class RevokeEmployeeOffboardingAccessCommand(
    val operationId: UUID,
    val caseId: UUID,
    val baseCaseVersion: Long,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ResolveOffboardingClearanceCommand(
    val operationId: UUID,
    val caseId: UUID,
    val baseCaseVersion: Long,
    val clearanceType: OffboardingClearanceType,
    val resolution: OffboardingClearanceState,
    val reason: String?,
    val actorUserId: UUID,
    val correlationId: String,
)

data class CompleteEmployeeOffboardingCommand(
    val operationId: UUID,
    val caseId: UUID,
    val baseCaseVersion: Long,
    val baseEmployeeVersion: Long,
    val baseEmploymentVersion: Long,
    val actorUserId: UUID,
    val correlationId: String,
)

data class OffboardingCommandResult(
    val offboarding: OffboardingCaseView,
    val replayed: Boolean,
)

data class EmployeeOffboardingStarted(
    val eventId: UUID = UUID.randomUUID(),
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val lastWorkingDate: LocalDate,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmployeeOffboardingAccessRevoked(
    val eventId: UUID = UUID.randomUUID(),
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class OffboardingClearanceResolved(
    val eventId: UUID = UUID.randomUUID(),
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val clearanceType: OffboardingClearanceType,
    val resolution: OffboardingClearanceState,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmploymentEnded(
    val eventId: UUID = UUID.randomUUID(),
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employmentId: UUID,
    val endDate: LocalDate,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class EmployeeOffboarded(
    val eventId: UUID = UUID.randomUUID(),
    val caseId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val endDate: LocalDate,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

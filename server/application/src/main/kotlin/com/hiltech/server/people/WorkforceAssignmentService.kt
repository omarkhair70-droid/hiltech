package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Component
class WorkforceAssignmentService(
    private val persistence:
        WorkforceAssignmentPersistencePort,
    private val peopleAuthorization:
        PeopleAuthorizationPort,
    private val idempotency:
        IdempotentCommandExecutor,
    private val security:
        WorkforceAssignmentSecuritySynchronizer,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun create(
        command:
            CreateWorkforceAssignmentCommand,
    ): WorkforceAssignmentCommandResult {
        requirePositiveVersion(
            command.baseEmployeeVersion,
        )

        val initialEmployee =
            persistence.employee(
                command.employeeId,
            )
                ?: throw assignmentError(
                    code =
                        "EMPLOYEE_NOT_FOUND",
                    message =
                        "The employee does not exist.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        requireManage(
            actorUserId =
                command.actorUserId,
            organizationId =
                initialEmployee
                    .organizationId,
        )

        val normalizedRoleCode =
            normalizeRoleCode(
                command.roleCode,
            )
        val normalizedRoleLabel =
            optionalText(
                command.roleLabel,
                160,
            )
        val assignmentId =
            deterministicAssignmentId(
                command.operationId,
            )
        val fingerprint =
            IdempotencyKeyContract
                .fingerprint(
                    listOf(
                        command.employeeId,
                        command.baseEmployeeVersion,
                        command.teamId,
                        normalizedRoleCode,
                        normalizedRoleLabel,
                        command.reportsToEmployeeId,
                        command.effectiveFrom,
                    ).joinToString("|"),
                )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_CREATE_WORKFORCE_ASSIGNMENT",
                    targetType =
                        "WORKFORCE_ASSIGNMENT",
                    targetId =
                        assignmentId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val now =
                    clock.instant()

                if (
                    command.effectiveFrom >
                    now
                ) {
                    throw assignmentError(
                        code =
                            "FUTURE_ASSIGNMENT_NOT_SUPPORTED",
                        message =
                            "Future-dated workforce assignment activation is not supported in this slice.",
                        status =
                            HttpStatus.BAD_REQUEST,
                    )
                }

                val employee =
                    persistence.lockEmployee(
                        command.employeeId,
                    )
                        ?: throw assignmentError(
                            code =
                                "EMPLOYEE_NOT_FOUND",
                            message =
                                "The employee does not exist.",
                            status =
                                HttpStatus.NOT_FOUND,
                        )

                requireManage(
                    actorUserId =
                        command.actorUserId,
                    organizationId =
                        employee.organizationId,
                )

                requireEmployeeVersion(
                    expected =
                        command.baseEmployeeVersion,
                    current =
                        employee.employeeVersion,
                )

                if (
                    employee.employeeState !in
                    setOf(
                        EmployeeState.PREBOARDING,
                        EmployeeState.ACTIVE,
                    )
                ) {
                    throw assignmentError(
                        code =
                            "EMPLOYEE_NOT_ASSIGNABLE",
                        message =
                            "The employee is not in a state that can receive a workforce assignment.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.lockOrganization(
                        employee.organizationId,
                    )
                ) {
                    throw assignmentError(
                        code =
                            "ORGANIZATION_NOT_ACTIVE",
                        message =
                            "The employee organization is not active.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    persistence.hasActiveAssignment(
                        employee.employeeId,
                    )
                ) {
                    throw assignmentError(
                        code =
                            "ACTIVE_WORKFORCE_ASSIGNMENT_EXISTS",
                        message =
                            "The employee already has a current workforce assignment.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                command.teamId?.let {
                    teamId ->
                    val team =
                        persistence.team(teamId)
                            ?: throw assignmentError(
                                code =
                                    "TEAM_NOT_FOUND",
                                message =
                                    "The team does not exist.",
                                status =
                                    HttpStatus.NOT_FOUND,
                            )

                    if (
                        team.organizationId !=
                        employee.organizationId
                    ) {
                        throw assignmentError(
                            code =
                                "TEAM_ORGANIZATION_MISMATCH",
                            message =
                                "The team does not belong to the employee organization.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                    }

                    if (!team.active) {
                        throw assignmentError(
                            code =
                                "TEAM_NOT_ACTIVE",
                            message =
                                "The team is not active.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                    }
                }

                command
                    .reportsToEmployeeId
                    ?.let { managerId ->
                        if (
                            managerId ==
                            employee.employeeId
                        ) {
                            throw assignmentError(
                                code =
                                    "REPORTING_SELF_REFERENCE",
                                message =
                                    "An employee cannot report to themselves.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }

                        val manager =
                            persistence.employee(
                                managerId,
                            )
                                ?: throw assignmentError(
                                    code =
                                        "REPORTING_MANAGER_NOT_FOUND",
                                    message =
                                        "The reporting manager does not exist.",
                                    status =
                                        HttpStatus.NOT_FOUND,
                                )

                        if (
                            manager.organizationId !=
                            employee.organizationId
                        ) {
                            throw assignmentError(
                                code =
                                    "REPORTING_MANAGER_ORGANIZATION_MISMATCH",
                                message =
                                    "The reporting manager does not belong to the employee organization.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }

                        if (
                            manager.employeeState !in
                            setOf(
                                EmployeeState.PREBOARDING,
                                EmployeeState.ACTIVE,
                            )
                        ) {
                            throw assignmentError(
                                code =
                                    "REPORTING_MANAGER_NOT_CURRENT",
                                message =
                                    "The reporting manager is not current.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }

                        if (
                            persistence
                                .wouldCreateReportingCycle(
                                    employeeId =
                                        employee.employeeId,
                                    reportsToEmployeeId =
                                        managerId,
                                )
                        ) {
                            throw assignmentError(
                                code =
                                    "REPORTING_CYCLE",
                                message =
                                    "The reporting relationship would create a cycle.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }
                    }

                try {
                    persistence.insertAssignment(
                        assignmentId =
                            assignmentId,
                        organizationId =
                            employee.organizationId,
                        employeeId =
                            employee.employeeId,
                        teamId =
                            command.teamId,
                        roleCode =
                            normalizedRoleCode,
                        roleLabel =
                            normalizedRoleLabel,
                        reportsToEmployeeId =
                            command
                                .reportsToEmployeeId,
                        effectiveFrom =
                            command.effectiveFrom,
                        createdAt = now,
                    )
                } catch (
                    failure:
                        DataIntegrityViolationException,
                ) {
                    throw assignmentError(
                        code =
                            "WORKFORCE_ASSIGNMENT_CONFLICT",
                        message =
                            "The workforce assignment conflicts with current People state.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (command.teamId != null) {
                    security.syncAssignment(
                        assignmentId =
                            assignmentId,
                        occurredAt =
                            now,
                        correlationId =
                            command.correlationId,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "WORKFORCE_ASSIGNMENT_CREATED",
                        targetType =
                            "WORKFORCE_ASSIGNMENT",
                        targetId =
                            assignmentId,
                        newStateRef =
                            "ACTIVE",
                        safeDiffJson =
                            """{"fields":["employee","team","role","reportsTo","effectiveFrom"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                events.publishEvent(
                    WorkforceAssignmentCreated(
                        assignmentId =
                            assignmentId,
                        organizationId =
                            employee.organizationId,
                        employeeId =
                            employee.employeeId,
                        teamId =
                            command.teamId,
                        roleCode =
                            normalizedRoleCode,
                        reportsToEmployeeId =
                            command
                                .reportsToEmployeeId,
                        sourceVersion = 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "WORKFORCE_ASSIGNMENT_CREATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "assignmentId",
                                assignmentId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return WorkforceAssignmentCommandResult(
            assignment =
                requireNotNull(
                    persistence.loadAssignment(
                        assignmentId,
                    ),
                ),
            replayed =
                execution.replayed,
        )
    }

    fun currentForEmployee(
        actorUserId: UUID,
        employeeId: UUID,
    ): WorkforceAssignmentSnapshot {
        val employee =
            persistence.employee(
                employeeId,
            )
                ?: throw assignmentError(
                    code =
                        "EMPLOYEE_NOT_FOUND",
                    message =
                        "The employee does not exist.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        requireManage(
            actorUserId =
                actorUserId,
            organizationId =
                employee.organizationId,
        )

        return persistence
            .currentForEmployee(
                employeeId =
                    employeeId,
                at = clock.instant(),
            )
            ?: throw assignmentError(
                code =
                    "WORKFORCE_ASSIGNMENT_NOT_FOUND",
                message =
                    "The employee has no current workforce assignment.",
                status =
                    HttpStatus.NOT_FOUND,
            )
    }

    fun own(
        actorUserId: UUID,
        organizationId: UUID,
    ): WorkforceAssignmentSnapshot {
        requireView(
            actorUserId =
                actorUserId,
            organizationId =
                organizationId,
        )

        return persistence.ownAssignment(
            identityId =
                actorUserId,
            organizationId =
                organizationId,
            at = clock.instant(),
        )
            ?: throw assignmentError(
                code =
                    "WORKFORCE_ASSIGNMENT_NOT_FOUND",
                message =
                    "No current workforce assignment is linked to this identity.",
                status =
                    HttpStatus.NOT_FOUND,
            )
    }

    fun organizationStructure(
        actorUserId: UUID,
        organizationId: UUID,
        limit: Int,
    ): List<WorkforceAssignmentSnapshot> {
        requireView(
            actorUserId =
                actorUserId,
            organizationId =
                organizationId,
        )

        return persistence
            .organizationStructure(
                organizationId =
                    organizationId,
                at = clock.instant(),
                limit = limit,
            )
    }

    private fun requireManage(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !peopleAuthorization
                .canManagePeople(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw assignmentError(
                code =
                    "PEOPLE_ACCESS_DENIED",
                message =
                    "You are not authorized for this People action.",
                status =
                    HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun requireView(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !peopleAuthorization
                .canViewDirectory(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw assignmentError(
                code =
                    "PEOPLE_ACCESS_DENIED",
                message =
                    "You are not authorized for this People read.",
                status =
                    HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun requireEmployeeVersion(
        expected: Long,
        current: Long,
    ) {
        if (expected != current) {
            throw assignmentError(
                code =
                    "EMPLOYEE_VERSION_CONFLICT",
                message =
                    "The employee has changed. Refresh before retrying.",
                status =
                    HttpStatus.CONFLICT,
                currentVersion =
                    current,
            )
        }
    }

    private fun requirePositiveVersion(
        value: Long,
    ) {
        if (value < 1) {
            throw assignmentError(
                code =
                    "INVALID_BASE_VERSION",
                message =
                    "baseEmployeeVersion must be positive.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun normalizeRoleCode(
        value: String,
    ): String {
        val normalized =
            value.trim()
                .uppercase(Locale.ROOT)

        if (
            !Regex(
                "^[A-Z][A-Z0-9_-]{0,79}$",
            ).matches(normalized)
        ) {
            throw assignmentError(
                code =
                    "INVALID_ROLE_CODE",
                message =
                    "roleCode is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        return normalized
    }

    private fun optionalText(
        value: String?,
        maxLength: Int,
    ): String? {
        val normalized =
            value?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: return null

        if (
            normalized.length >
            maxLength
        ) {
            throw assignmentError(
                code =
                    "INVALID_ROLE_LABEL",
                message =
                    "roleLabel exceeds its allowed length.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        return normalized
    }

    private fun deterministicAssignmentId(
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            (
                "workforce-assignment:" +
                    operationId
            ).toByteArray(
                StandardCharsets.UTF_8,
            ),
        )

    private fun assignmentError(
        code: String,
        message: String,
        status: HttpStatus,
        currentVersion: Long? = null,
    ): ProductApiException =
        ProductApiException(
            code = code,
            message = message,
            status = status,
            currentVersion =
                currentVersion,
        )
}

package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.EmploymentAccessRevocationPort
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// Slice 06 closure trigger: runtime semantics unchanged; exact-head CI proves the closure lineage.
@Component
class OffboardingService(
    private val persistence:
        OffboardingPersistencePort,
    private val authorization:
        PeopleAuthorizationPort,
    private val identityAccess:
        EmploymentAccessRevocationPort,
    private val workforce:
        WorkforceAssignmentPersistencePort,
    private val workforceSecurity:
        WorkforceAssignmentSecuritySynchronizer,
    private val idempotency:
        IdempotentCommandExecutor,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun start(
        command:
            StartEmployeeOffboardingCommand,
    ): OffboardingCommandResult {
        requirePositiveVersion(
            command.baseEmployeeVersion,
            "baseEmployeeVersion",
        )

        val initial =
            requireEmployee(
                command.employeeId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )

        val reasonCode =
            normalizeCode(
                command.reasonCategoryCode,
                "INVALID_OFFBOARDING_REASON_CODE",
            )
        val note =
            optionalText(
                command.note,
                500,
            )
        val caseId =
            deterministicId(
                "offboarding-case",
                command.operationId,
            )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    initial.employeeId,
                    command.baseEmployeeVersion,
                    command.lastWorkingDate,
                    reasonCode,
                    note ?: "",
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
                        "PEOPLE_START_OFFBOARDING",
                    targetType =
                        "OFFBOARDING_CASE",
                    targetId = caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val employee =
                    persistence.employee(
                        employeeId =
                            command.employeeId,
                        forUpdate = true,
                    )
                        ?: throw offboardingError(
                            code =
                                "EMPLOYEE_NOT_FOUND",
                            message =
                                "The employee does not exist.",
                            status =
                                HttpStatus.NOT_FOUND,
                        )

                requireManage(
                    command.actorUserId,
                    employee.organizationId,
                )

                if (
                    employee.state !=
                    EmployeeState.ACTIVE
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_NOT_ACTIVE",
                        message =
                            "Only an active employee can start offboarding.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    employee.employeeVersion !=
                    command.baseEmployeeVersion
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_VERSION_CONFLICT",
                        message =
                            "The employee changed. Refresh before retrying.",
                        status =
                            HttpStatus.CONFLICT,
                        currentVersion =
                            employee.employeeVersion,
                    )
                }

                val employmentId =
                    employee.activeEmploymentId
                        ?: throw offboardingError(
                            code =
                                "ACTIVE_EMPLOYMENT_REQUIRED",
                            message =
                                "Offboarding requires an active employment record.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                val employmentStart =
                    requireNotNull(
                        employee.employmentStartDate,
                    )

                if (
                    command.lastWorkingDate <
                    employmentStart
                ) {
                    throw offboardingError(
                        code =
                            "OFFBOARDING_LAST_WORKING_DATE_INVALID",
                        message =
                            "Last working date cannot be before the active employment start date.",
                        status =
                            HttpStatus.BAD_REQUEST,
                    )
                }

                employee.hireDate?.let {
                    hireDate ->
                    if (
                        command.lastWorkingDate <
                        hireDate
                    ) {
                        throw offboardingError(
                            code =
                                "OFFBOARDING_LAST_WORKING_DATE_INVALID",
                            message =
                                "Last working date cannot be before hire date.",
                            status =
                                HttpStatus.BAD_REQUEST,
                        )
                    }
                }

                persistence
                    .openForEmployee(
                        employee.employeeId,
                    )
                    ?.let {
                        existing ->
                        throw offboardingError(
                            code =
                                "OPEN_OFFBOARDING_CASE_EXISTS",
                            message =
                                "The employee already has an open offboarding case.",
                            status =
                                HttpStatus.CONFLICT,
                            currentVersion =
                                existing.version,
                        )
                    }

                val now = clock.instant()
                val access =
                    identityAccess.snapshot(
                        personId =
                            employee.personId,
                        organizationId =
                            employee.organizationId,
                        at = now,
                    )
                val assignment =
                    workforce
                        .currentForEmployee(
                            employee.employeeId,
                            now,
                        )
                val accessInitiallyClear =
                    access.clear &&
                        assignment == null

                try {
                    persistence.insertCase(
                        caseId = caseId,
                        operationId =
                            command.operationId,
                        organizationId =
                            employee.organizationId,
                        employeeId =
                            employee.employeeId,
                        lastWorkingDate =
                            command.lastWorkingDate,
                        reasonCategoryCode =
                            reasonCode,
                        note = note,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                    persistence
                        .insertInitialClearances(
                            caseId = caseId,
                            accessInitiallyClear =
                                accessInitiallyClear,
                            actorUserId =
                                command.actorUserId,
                            at = now,
                        )
                } catch (
                    failure:
                        DataIntegrityViolationException,
                ) {
                    throw offboardingError(
                        code =
                            "OFFBOARDING_CASE_CONFLICT",
                        message =
                            "The offboarding case conflicts with current People state.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence
                        .transitionEmployeeToOffboarding(
                            employeeId =
                                employee.employeeId,
                            expectedVersion =
                                command.baseEmployeeVersion,
                            at = now,
                        )
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_VERSION_CONFLICT",
                        message =
                            "The employee changed while starting offboarding.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_OFFBOARDING_STARTED",
                        targetType =
                            "OFFBOARDING_CASE",
                        targetId = caseId,
                        previousStateRef =
                            "ACTIVE",
                        newStateRef =
                            "OFFBOARDING",
                        safeDiffJson =
                            """{"fields":["lastWorkingDate","reasonCategoryCode"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeOffboardingStarted(
                        caseId = caseId,
                        organizationId =
                            employee.organizationId,
                        employeeId =
                            employee.employeeId,
                        lastWorkingDate =
                            command.lastWorkingDate,
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
                        "EMPLOYEE_OFFBOARDING_STARTED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                caseId.toString(),
                            )
                            put(
                                "employmentId",
                                employmentId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return OffboardingCommandResult(
            offboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId = caseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun revokeAccess(
        command:
            RevokeEmployeeOffboardingAccessCommand,
    ): OffboardingCommandResult {
        requirePositiveVersion(
            command.baseCaseVersion,
            "baseCaseVersion",
        )
        val initial =
            requireCase(
                command.caseId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.caseId,
                    command.baseCaseVersion,
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
                        "PEOPLE_REVOKE_OFFBOARDING_ACCESS",
                    targetType =
                        "OFFBOARDING_CASE",
                    targetId =
                        command.caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val case =
                    requireOpenCaseLocked(
                        command.caseId,
                        command.baseCaseVersion,
                    )
                val employee =
                    requireEmployeeLocked(
                        case.employeeId,
                    )

                requireManage(
                    command.actorUserId,
                    case.organizationId,
                )

                if (
                    employee.state !=
                    EmployeeState.OFFBOARDING
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_NOT_OFFBOARDING",
                        message =
                            "Access revocation requires an offboarding employee.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                val now = clock.instant()

                identityAccess
                    .revokeForEmployment(
                        personId =
                            employee.personId,
                        organizationId =
                            employee.organizationId,
                        at = now,
                    )

                workforce
                    .currentForEmployee(
                        employee.employeeId,
                        now,
                    )
                    ?.let {
                        assignment ->
                        if (
                            !workforce.endAssignment(
                                assignmentId =
                                    assignment.assignmentId,
                                expectedVersion =
                                    assignment.version,
                                effectiveTo = now,
                            )
                        ) {
                            throw offboardingError(
                                code =
                                    "WORKFORCE_ASSIGNMENT_VERSION_CONFLICT",
                                message =
                                    "The workforce assignment changed while revoking access.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }

                        workforceSecurity
                            .endAssignmentAuthority(
                                assignmentId =
                                    assignment.assignmentId,
                                occurredAt = now,
                                correlationId =
                                    command.correlationId,
                            )
                    }

                val facts =
                    accessFacts(
                        employee,
                        now,
                    )

                if (facts.clear) {
                    if (
                        !persistence.resolveClearance(
                            caseId =
                                case.caseId,
                            type =
                                OffboardingClearanceType
                                    .ACCESS,
                            resolution =
                                OffboardingClearanceState
                                    .CLEAR,
                            source =
                                OffboardingClearanceSource
                                    .IDENTITY,
                            reason = null,
                            actorUserId =
                                command.actorUserId,
                            at = now,
                        )
                    ) {
                        throw offboardingError(
                            code =
                                "OFFBOARDING_ACCESS_CLEARANCE_MISSING",
                            message =
                                "The ACCESS clearance slot is unavailable.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                    }
                }

                if (
                    !persistence.bumpCaseVersion(
                        caseId =
                            case.caseId,
                        expectedVersion =
                            command.baseCaseVersion,
                        at = now,
                    )
                ) {
                    throw offboardingError(
                        code =
                            "OFFBOARDING_VERSION_CONFLICT",
                        message =
                            "The offboarding case changed while revoking access.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_OFFBOARDING_ACCESS_REVOKED",
                        targetType =
                            "OFFBOARDING_CASE",
                        targetId =
                            case.caseId,
                        safeDiffJson =
                            """{"fields":["organizationMembership","sessions","workforceAssignment","teamAuthority"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                if (facts.clear) {
                    events.publishEvent(
                        EmployeeOffboardingAccessRevoked(
                            caseId =
                                case.caseId,
                            organizationId =
                                case.organizationId,
                            employeeId =
                                case.employeeId,
                            sourceVersion =
                                case.version + 1,
                            actorUserId =
                                command.actorUserId,
                            occurredAt = now,
                            correlationId =
                                command.correlationId,
                        ),
                    )
                }

                IdempotentCommandOutcome(
                    resultCode =
                        if (facts.clear) {
                            "OFFBOARDING_ACCESS_CLEAR"
                        } else {
                            "OFFBOARDING_ACCESS_PARTIALLY_REVOKED"
                        },
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                case.caseId.toString(),
                            )
                            put(
                                "accessClear",
                                facts.clear,
                            )
                        }.toString(),
                )
            }

        return OffboardingCommandResult(
            offboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId =
                        command.caseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun resolveHr(
        command:
            ResolveOffboardingClearanceCommand,
    ): OffboardingCommandResult =
        resolve(
            command = command,
            allowedTypes =
                setOf(
                    OffboardingClearanceType.HR,
                ),
        )

    fun resolveExternal(
        command:
            ResolveOffboardingClearanceCommand,
    ): OffboardingCommandResult =
        resolve(
            command = command,
            allowedTypes =
                setOf(
                    OffboardingClearanceType.PROJECT,
                    OffboardingClearanceType.ASSET,
                    OffboardingClearanceType.FINANCE,
                    OffboardingClearanceType.PAYROLL,
                ),
        )

    fun complete(
        command:
            CompleteEmployeeOffboardingCommand,
    ): OffboardingCommandResult {
        requirePositiveVersion(
            command.baseCaseVersion,
            "baseCaseVersion",
        )
        requirePositiveVersion(
            command.baseEmployeeVersion,
            "baseEmployeeVersion",
        )
        requirePositiveVersion(
            command.baseEmploymentVersion,
            "baseEmploymentVersion",
        )

        val initial =
            requireCase(
                command.caseId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.caseId,
                    command.baseCaseVersion,
                    command.baseEmployeeVersion,
                    command.baseEmploymentVersion,
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
                        "PEOPLE_COMPLETE_OFFBOARDING",
                    targetType =
                        "OFFBOARDING_CASE",
                    targetId =
                        command.caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val case =
                    requireOpenCaseLocked(
                        command.caseId,
                        command.baseCaseVersion,
                    )
                val employee =
                    requireEmployeeLocked(
                        case.employeeId,
                    )
                requireManage(
                    command.actorUserId,
                    case.organizationId,
                )

                if (
                    employee.state !=
                    EmployeeState.OFFBOARDING
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_NOT_OFFBOARDING",
                        message =
                            "Only an offboarding employee can be completed.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    employee.employeeVersion !=
                    command.baseEmployeeVersion
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_VERSION_CONFLICT",
                        message =
                            "The employee changed. Refresh before retrying.",
                        status =
                            HttpStatus.CONFLICT,
                        currentVersion =
                            employee.employeeVersion,
                    )
                }

                val employmentId =
                    employee.activeEmploymentId
                        ?: throw offboardingError(
                            code =
                                "ACTIVE_EMPLOYMENT_REQUIRED",
                            message =
                                "Completion requires the active employment record.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                val employmentVersion =
                    requireNotNull(
                        employee.employmentVersion,
                    )

                if (
                    employmentVersion !=
                    command.baseEmploymentVersion
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYMENT_VERSION_CONFLICT",
                        message =
                            "The employment record changed. Refresh before retrying.",
                        status =
                            HttpStatus.CONFLICT,
                        currentVersion =
                            employmentVersion,
                    )
                }

                val now = clock.instant()
                val today =
                    LocalDate.now(clock)
                val blockers =
                    completionBlockers(
                        case = case,
                        employee = employee,
                        at = now,
                        today = today,
                    )

                if (blockers.isNotEmpty()) {
                    throw ProductApiException(
                        code =
                            "OFFBOARDING_BLOCKERS_REMAIN",
                        message =
                            "Offboarding cannot be completed while blockers remain.",
                        status =
                            HttpStatus.CONFLICT,
                        details =
                            buildJsonObject {
                                putJsonArray(
                                    "blockers",
                                ) {
                                    blockers.forEach {
                                        add(
                                            JsonPrimitive(
                                                it,
                                            ),
                                        )
                                    }
                                }
                            },
                        currentVersion =
                            case.version,
                    )
                }

                if (
                    !persistence.endEmployment(
                        employmentId =
                            employmentId,
                        expectedVersion =
                            command.baseEmploymentVersion,
                        endDate =
                            case.lastWorkingDate,
                        at = now,
                    )
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYMENT_VERSION_CONFLICT",
                        message =
                            "The employment record changed while completing offboarding.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence
                        .transitionEmployeeToFormer(
                            employeeId =
                                employee.employeeId,
                            expectedVersion =
                                command.baseEmployeeVersion,
                            endDate =
                                case.lastWorkingDate,
                            at = now,
                        )
                ) {
                    throw offboardingError(
                        code =
                            "EMPLOYEE_VERSION_CONFLICT",
                        message =
                            "The employee changed while completing offboarding.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.completeCase(
                        caseId =
                            case.caseId,
                        expectedVersion =
                            command.baseCaseVersion,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                ) {
                    throw offboardingError(
                        code =
                            "OFFBOARDING_VERSION_CONFLICT",
                        message =
                            "The offboarding case changed while completing.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_OFFBOARDED",
                        targetType =
                            "EMPLOYEE",
                        targetId =
                            employee.employeeId,
                        previousStateRef =
                            "OFFBOARDING",
                        newStateRef =
                            "FORMER",
                        safeDiffJson =
                            """{"fields":["employeeState","employmentState","endDate","offboardingCase"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                events.publishEvent(
                    EmploymentEnded(
                        caseId =
                            case.caseId,
                        organizationId =
                            case.organizationId,
                        employeeId =
                            employee.employeeId,
                        employmentId =
                            employmentId,
                        endDate =
                            case.lastWorkingDate,
                        sourceVersion =
                            employmentVersion + 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeOffboarded(
                        caseId =
                            case.caseId,
                        organizationId =
                            case.organizationId,
                        employeeId =
                            employee.employeeId,
                        endDate =
                            case.lastWorkingDate,
                        sourceVersion =
                            employee.employeeVersion + 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EMPLOYEE_OFFBOARDED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                case.caseId.toString(),
                            )
                            put(
                                "employeeId",
                                employee.employeeId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return OffboardingCommandResult(
            offboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId =
                        command.caseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun adminCase(
        actorUserId: UUID,
        employeeId: UUID,
    ): OffboardingCaseView {
        val employee =
            requireEmployee(employeeId)
        requireManage(
            actorUserId,
            employee.organizationId,
        )
        val case =
            persistence.latestForEmployee(
                employeeId,
            )
                ?: throw offboardingError(
                    code =
                        "OFFBOARDING_CASE_NOT_FOUND",
                    message =
                        "No offboarding case exists for the employee.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        return evaluate(
            case,
            employee,
        )
    }

    fun adminCaseById(
        actorUserId: UUID,
        caseId: UUID,
    ): OffboardingCaseView {
        val case = requireCase(caseId)
        requireManage(
            actorUserId,
            case.organizationId,
        )
        val employee =
            requireEmployee(
                case.employeeId,
            )
        return evaluate(
            case,
            employee,
        )
    }

    private fun resolve(
        command:
            ResolveOffboardingClearanceCommand,
        allowedTypes:
            Set<OffboardingClearanceType>,
    ): OffboardingCommandResult {
        requirePositiveVersion(
            command.baseCaseVersion,
            "baseCaseVersion",
        )

        if (
            command.clearanceType !in
            allowedTypes
        ) {
            throw offboardingError(
                code =
                    "OFFBOARDING_CLEARANCE_TYPE_NOT_ALLOWED",
                message =
                    "This clearance type cannot be resolved through this command.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        if (
            command.resolution !in
            setOf(
                OffboardingClearanceState.CLEAR,
                OffboardingClearanceState
                    .NOT_APPLICABLE,
                OffboardingClearanceState
                    .EXCEPTION_ACCEPTED,
            )
        ) {
            throw offboardingError(
                code =
                    "OFFBOARDING_CLEARANCE_RESOLUTION_INVALID",
                message =
                    "The clearance resolution is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val reason =
            optionalText(
                command.reason,
                500,
            )

        if (
            command.resolution ==
            OffboardingClearanceState
                .EXCEPTION_ACCEPTED &&
            reason == null
        ) {
            throw offboardingError(
                code =
                    "OFFBOARDING_CLEARANCE_REASON_REQUIRED",
                message =
                    "An accepted exception requires a reason.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val initial =
            requireCase(
                command.caseId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.caseId,
                    command.baseCaseVersion,
                    command.clearanceType,
                    command.resolution,
                    reason ?: "",
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
                        "PEOPLE_RESOLVE_OFFBOARDING_CLEARANCE",
                    targetType =
                        "OFFBOARDING_CASE",
                    targetId =
                        command.caseId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val case =
                    requireOpenCaseLocked(
                        command.caseId,
                        command.baseCaseVersion,
                    )
                requireManage(
                    command.actorUserId,
                    case.organizationId,
                )

                val now = clock.instant()

                if (
                    !persistence.resolveClearance(
                        caseId =
                            case.caseId,
                        type =
                            command.clearanceType,
                        resolution =
                            command.resolution,
                        source =
                            OffboardingClearanceSource
                                .PEOPLE,
                        reason = reason,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                ) {
                    throw offboardingError(
                        code =
                            "OFFBOARDING_CLEARANCE_NOT_FOUND",
                        message =
                            "The offboarding clearance slot does not exist.",
                        status =
                            HttpStatus.NOT_FOUND,
                    )
                }

                if (
                    !persistence.bumpCaseVersion(
                        caseId =
                            case.caseId,
                        expectedVersion =
                            command.baseCaseVersion,
                        at = now,
                    )
                ) {
                    throw offboardingError(
                        code =
                            "OFFBOARDING_VERSION_CONFLICT",
                        message =
                            "The offboarding case changed while resolving clearance.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "OFFBOARDING_CLEARANCE_RESOLVED",
                        targetType =
                            "OFFBOARDING_CASE",
                        targetId =
                            case.caseId,
                        safeDiffJson =
                            """{"fields":["clearanceType","resolution"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        reason =
                            if (
                                command.resolution ==
                                OffboardingClearanceState
                                    .EXCEPTION_ACCEPTED
                            ) {
                                reason
                            } else {
                                null
                            },
                    ),
                )
                events.publishEvent(
                    OffboardingClearanceResolved(
                        caseId =
                            case.caseId,
                        organizationId =
                            case.organizationId,
                        employeeId =
                            case.employeeId,
                        clearanceType =
                            command.clearanceType,
                        resolution =
                            command.resolution,
                        sourceVersion =
                            case.version + 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "OFFBOARDING_CLEARANCE_RESOLVED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "caseId",
                                case.caseId.toString(),
                            )
                            put(
                                "clearanceType",
                                command.clearanceType
                                    .name,
                            )
                        }.toString(),
                )
            }

        return OffboardingCommandResult(
            offboarding =
                adminCaseById(
                    actorUserId =
                        command.actorUserId,
                    caseId =
                        command.caseId,
                ),
            replayed =
                execution.replayed,
        )
    }

    private fun evaluate(
        case: OffboardingCaseRecord,
        employee:
            OffboardingEmployeeContext,
    ): OffboardingCaseView {
        val now = clock.instant()
        val facts =
            accessFacts(
                employee,
                now,
            )
        val stored =
            persistence.clearances(
                case.caseId,
            )
        val visible =
            stored.map {
                clearance ->
                if (
                    clearance.type ==
                    OffboardingClearanceType.ACCESS
                ) {
                    clearance.toView(
                        state =
                            if (facts.clear) {
                                OffboardingClearanceState
                                    .CLEAR
                            } else {
                                OffboardingClearanceState
                                    .PENDING
                            },
                    )
                } else {
                    clearance.toView()
                }
            }

        val blockers =
            completionBlockers(
                case = case,
                employee = employee,
                at = now,
                today =
                    LocalDate.now(clock),
                effectiveClearances =
                    visible,
            )

        return OffboardingCaseView(
            caseId = case.caseId,
            organizationId =
                case.organizationId,
            employeeId =
                employee.employeeId,
            employeeCode =
                employee.employeeCode,
            employeeDisplayName =
                employee.displayName,
            employeeState =
                employee.state,
            employeeVersion =
                employee.employeeVersion,
            activeEmploymentId =
                employee.activeEmploymentId,
            employmentStartDate =
                employee.employmentStartDate,
            employmentVersion =
                employee.employmentVersion,
            state = case.state,
            lastWorkingDate =
                case.lastWorkingDate,
            reasonCategoryCode =
                case.reasonCategoryCode,
            note = case.note,
            clearances = visible,
            accessFacts = facts,
            canComplete =
                blockers.isEmpty() &&
                    case.state ==
                    OffboardingCaseState.OPEN,
            blockers = blockers,
            caseVersion = case.version,
            startedAt = case.startedAt,
            completedAt =
                case.completedAt,
        )
    }

    private fun completionBlockers(
        case: OffboardingCaseRecord,
        employee:
            OffboardingEmployeeContext,
        at: java.time.Instant,
        today: LocalDate,
        effectiveClearances:
            List<OffboardingClearanceView>? =
            null,
    ): List<String> {
        if (
            case.state !=
            OffboardingCaseState.OPEN
        ) {
            return listOf(
                "CASE_NOT_OPEN",
            )
        }

        val blockers =
            mutableListOf<String>()

        if (
            employee.state !=
            EmployeeState.OFFBOARDING
        ) {
            blockers +=
                "EMPLOYEE_NOT_OFFBOARDING"
        }

        if (
            today <
            case.lastWorkingDate
        ) {
            blockers +=
                "LAST_WORKING_DATE_NOT_REACHED"
        }

        if (
            employee.activeEmploymentId ==
            null
        ) {
            blockers +=
                "ACTIVE_EMPLOYMENT_REQUIRED"
        }

        val facts =
            accessFacts(
                employee,
                at,
            )

        if (!facts.clear) {
            blockers +=
                "ACCESS_NOT_CLEAR"
        }

        val clearances =
            effectiveClearances
                ?: persistence
                    .clearances(
                        case.caseId,
                    )
                    .map {
                        clearance ->
                        if (
                            clearance.type ==
                            OffboardingClearanceType
                                .ACCESS
                        ) {
                            clearance.toView(
                                state =
                                    if (facts.clear) {
                                        OffboardingClearanceState
                                            .CLEAR
                                    } else {
                                        OffboardingClearanceState
                                            .PENDING
                                    },
                            )
                        } else {
                            clearance.toView()
                        }
                    }

        val resolvedStates =
            setOf(
                OffboardingClearanceState.CLEAR,
                OffboardingClearanceState
                    .NOT_APPLICABLE,
                OffboardingClearanceState
                    .EXCEPTION_ACCEPTED,
            )

        OffboardingClearanceType.entries
            .forEach {
                type ->
                val clearance =
                    clearances.firstOrNull {
                        it.type == type
                    }
                if (
                    clearance == null ||
                    clearance.state !in
                    resolvedStates
                ) {
                    blockers +=
                        "CLEARANCE_${type.name}_UNRESOLVED"
                }
            }

        return blockers.distinct()
    }

    private fun accessFacts(
        employee:
            OffboardingEmployeeContext,
        at: java.time.Instant,
    ): OffboardingAccessFacts {
        val identity =
            identityAccess.snapshot(
                personId =
                    employee.personId,
                organizationId =
                    employee.organizationId,
                at = at,
            )
        val currentAssignment =
            workforce.currentForEmployee(
                employee.employeeId,
                at,
            )

        return OffboardingAccessFacts(
            linkedIdentityPresent =
                identity.identityIds
                    .isNotEmpty(),
            activeOrganizationMemberships =
                identity
                    .activeOrganizationMemberships,
            activeSessions =
                identity.activeSessions,
            currentWorkforceAssignmentPresent =
                currentAssignment != null,
        )
    }

    private fun OffboardingClearanceRecord
        .toView(
            state: OffboardingClearanceState =
                this.state,
        ): OffboardingClearanceView =
        OffboardingClearanceView(
            type = type,
            state = state,
            source = source,
            reason = reason,
            resolvedAt = resolvedAt,
            version = version,
        )

    private fun requireEmployee(
        employeeId: UUID,
    ): OffboardingEmployeeContext =
        persistence.employee(employeeId)
            ?: throw offboardingError(
                code =
                    "EMPLOYEE_NOT_FOUND",
                message =
                    "The employee does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requireEmployeeLocked(
        employeeId: UUID,
    ): OffboardingEmployeeContext =
        persistence.employee(
            employeeId =
                employeeId,
            forUpdate = true,
        )
            ?: throw offboardingError(
                code =
                    "EMPLOYEE_NOT_FOUND",
                message =
                    "The employee does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requireCase(
        caseId: UUID,
    ): OffboardingCaseRecord =
        persistence.case(caseId)
            ?: throw offboardingError(
                code =
                    "OFFBOARDING_CASE_NOT_FOUND",
                message =
                    "The offboarding case does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requireOpenCaseLocked(
        caseId: UUID,
        expectedVersion: Long,
    ): OffboardingCaseRecord {
        val case =
            persistence.case(
                caseId = caseId,
                forUpdate = true,
            )
                ?: throw offboardingError(
                    code =
                        "OFFBOARDING_CASE_NOT_FOUND",
                    message =
                        "The offboarding case does not exist.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        if (
            case.state !=
            OffboardingCaseState.OPEN
        ) {
            throw offboardingError(
                code =
                    "OFFBOARDING_CASE_NOT_OPEN",
                message =
                    "The offboarding case is not open.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        if (
            case.version !=
            expectedVersion
        ) {
            throw offboardingError(
                code =
                    "OFFBOARDING_VERSION_CONFLICT",
                message =
                    "The offboarding case changed. Refresh before retrying.",
                status =
                    HttpStatus.CONFLICT,
                currentVersion =
                    case.version,
            )
        }

        return case
    }

    private fun requireManage(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !authorization.canManagePeople(
                actorUserId =
                    actorUserId,
                organizationId =
                    organizationId,
            )
        ) {
            throw offboardingError(
                code =
                    "PEOPLE_ACCESS_DENIED",
                message =
                    "You are not authorized for this People action.",
                status =
                    HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun requirePositiveVersion(
        value: Long,
        field: String,
    ) {
        if (value < 1) {
            throw offboardingError(
                code =
                    "INVALID_BASE_VERSION",
                message =
                    "$field must be positive.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun normalizeCode(
        value: String,
        errorCode: String,
    ): String {
        val normalized =
            value.trim()
                .uppercase(Locale.ROOT)

        if (
            !Regex(
                "^[A-Z][A-Z0-9_-]{0,79}$",
            ).matches(normalized)
        ) {
            throw offboardingError(
                code = errorCode,
                message =
                    "The offboarding code is invalid.",
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
            throw offboardingError(
                code =
                    "INVALID_TEXT",
                message =
                    "A text value exceeds its allowed length.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        return normalized
    }

    private fun deterministicId(
        prefix: String,
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            "$prefix:$operationId"
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )

    private fun offboardingError(
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

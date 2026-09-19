package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
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
class PeopleService(
    private val persistence:
        PeoplePersistencePort,
    private val authorization:
        PeopleAuthorizationPort,
    private val sourceAuthority:
        RoleTeamSourceAuthorityPort,
    private val idempotency:
        IdempotentCommandExecutor,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun createEmployee(
        command: CreateEmployeeCommand,
    ): PeopleCommandResult {
        requireManage(
            actorUserId =
                command.actorUserId,
            organizationId =
                command.organizationId,
        )

        val normalized =
            command.copy(
                displayName =
                    requiredText(
                        command.displayName,
                        160,
                        "INVALID_DISPLAY_NAME",
                    ),
                employeeCode =
                    requiredText(
                        command.employeeCode,
                        64,
                        "INVALID_EMPLOYEE_CODE",
                    ).uppercase(
                        Locale.ROOT,
                    ),
                employmentTypeCode =
                    normalizeEmploymentType(
                        command.employmentTypeCode,
                    ),
                legalName =
                    optionalText(
                        command.legalName,
                        240,
                    ),
                mobile =
                    optionalText(
                        command.mobile,
                        64,
                    ),
                email =
                    optionalText(
                        command.email,
                        320,
                    )?.lowercase(
                        Locale.ROOT,
                    ),
            )

        val personId =
            deterministicId(
                "person",
                normalized.operationId,
            )
        val employeeId =
            deterministicId(
                "employee",
                normalized.operationId,
            )
        val employmentId =
            deterministicId(
                "employment",
                normalized.operationId,
            )
        val fingerprint =
            IdempotencyKeyContract
                .fingerprint(
                    listOf(
                        normalized.organizationId,
                        normalized.displayName,
                        normalized.employeeCode,
                        normalized.startDate,
                        normalized.employmentTypeCode,
                        normalized.linkedUserIdentityId,
                        normalized.hireDate,
                        normalized.legalName,
                        normalized.mobile,
                        normalized.email,
                    ).joinToString("|"),
                )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        normalized.operationId,
                    actorUserId =
                        normalized.actorUserId,
                    commandType =
                        "PEOPLE_CREATE_EMPLOYEE",
                    targetType = "EMPLOYEE",
                    targetId = employeeId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        normalized.correlationId,
                ),
            ) {
                val now =
                    clock.instant()

                if (
                    !persistence
                        .isOrganizationActive(
                            normalized
                                .organizationId,
                        )
                ) {
                    throw peopleError(
                        code =
                            "ORGANIZATION_NOT_ACTIVE",
                        message =
                            "The organization is not active.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    persistence
                        .employeeCodeExists(
                            organizationId =
                                normalized
                                    .organizationId,
                            employeeCode =
                                normalized
                                    .employeeCode,
                        )
                ) {
                    throw peopleError(
                        code =
                            "EMPLOYEE_CODE_CONFLICT",
                        message =
                            "The employee code is already in use.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                normalized
                    .linkedUserIdentityId
                    ?.let { identityId ->
                        requireIdentityAvailableForNewPerson(
                            identityId =
                                identityId,
                            organizationId =
                                normalized
                                    .organizationId,
                        )
                    }

                try {
                    persistence
                        .createEmployeeCore(
                            CreateEmployeePersistenceInput(
                                personId =
                                    personId,
                                employeeId =
                                    employeeId,
                                employmentId =
                                    employmentId,
                                organizationId =
                                    normalized
                                        .organizationId,
                                employeeCode =
                                    normalized
                                        .employeeCode,
                                displayName =
                                    normalized
                                        .displayName,
                                legalName =
                                    normalized
                                        .legalName,
                                mobile =
                                    normalized.mobile,
                                email =
                                    normalized.email,
                                hireDate =
                                    normalized.hireDate,
                                startDate =
                                    normalized.startDate,
                                employmentTypeCode =
                                    normalized
                                        .employmentTypeCode,
                                createdAt = now,
                            ),
                        )

                    normalized
                        .linkedUserIdentityId
                        ?.let { identityId ->
                            if (
                                !persistence
                                    .attachIdentityToPerson(
                                        identityId =
                                            identityId,
                                        personId =
                                            personId,
                                    )
                            ) {
                                throw peopleError(
                                    code =
                                        "IDENTITY_LINK_CONFLICT",
                                    message =
                                        "The identity could not be linked to this employee.",
                                    status =
                                        HttpStatus.CONFLICT,
                                )
                            }
                        }
                } catch (
                    failure:
                        DataIntegrityViolationException,
                ) {
                    throw peopleError(
                        code =
                            "EMPLOYEE_CONSTRAINT_CONFLICT",
                        message =
                            "The employee could not be created because the current People state conflicts.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            normalized.actorUserId,
                        action =
                            "EMPLOYEE_CREATED",
                        targetType =
                            "EMPLOYEE",
                        targetId = employeeId,
                        newStateRef =
                            "PREBOARDING",
                        safeDiffJson =
                            """{"fields":["employeeCode","employment","profile"]}""",
                        occurredAt = now,
                        correlationId =
                            normalized
                                .correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeCreated(
                        employeeId =
                            employeeId,
                        organizationId =
                            normalized
                                .organizationId,
                        sourceVersion = 1,
                        actorUserId =
                            normalized
                                .actorUserId,
                        occurredAt = now,
                        correlationId =
                            normalized
                                .correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EMPLOYEE_CREATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "employeeId",
                                employeeId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return PeopleCommandResult(
            employee =
                requireNotNull(
                    persistence.loadEmployee(
                        employeeId =
                            employeeId,
                        at = clock.instant(),
                    ),
                ),
            replayed =
                execution.replayed,
        )
    }

    fun linkIdentity(
        command:
            LinkEmployeeIdentityCommand,
    ): PeopleCommandResult {
        val current =
            requireEmployee(
                command.employeeId,
            )
        requireManage(
            actorUserId =
                command.actorUserId,
            organizationId =
                current.organizationId,
        )
        requirePositiveVersion(
            command.baseVersion,
        )

        val fingerprint =
            IdempotencyKeyContract
                .fingerprint(
                    listOf(
                        command.employeeId,
                        command.baseVersion,
                        command.userIdentityId,
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
                        "PEOPLE_LINK_IDENTITY",
                    targetType = "EMPLOYEE",
                    targetId =
                        command.employeeId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val now =
                    clock.instant()
                val fresh =
                    requireEmployee(
                        command.employeeId,
                    )

                requireVersion(
                    expected =
                        command.baseVersion,
                    current =
                        fresh.employeeVersion,
                )
                requireIdentityMembership(
                    identityId =
                        command.userIdentityId,
                    organizationId =
                        fresh.organizationId,
                )

                val identity =
                    persistence.identityLink(
                        command.userIdentityId,
                    )
                        ?: throw peopleError(
                            code =
                                "IDENTITY_NOT_FOUND",
                            message =
                                "The identity does not exist.",
                            status =
                                HttpStatus.NOT_FOUND,
                        )

                if (
                    identity.status !=
                    "ACTIVE"
                ) {
                    throw peopleError(
                        code =
                            "IDENTITY_NOT_ACTIVE",
                        message =
                            "The identity is not active.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                if (
                    identity.personId != null &&
                    identity.personId !=
                    fresh.personId
                ) {
                    throw peopleError(
                        code =
                            "IDENTITY_LINK_CONFLICT",
                        message =
                            "The identity is already linked to another person.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                val alreadyLinked =
                    identity.personId ==
                        fresh.personId

                if (!alreadyLinked) {
                    if (
                        !persistence
                            .attachIdentityToPerson(
                                identityId =
                                    command
                                        .userIdentityId,
                                personId =
                                    fresh.personId,
                            )
                    ) {
                        throw peopleError(
                            code =
                                "IDENTITY_LINK_CONFLICT",
                            message =
                                "The identity could not be linked.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                    }

                    if (
                        !persistence
                            .bumpEmployeeVersion(
                                employeeId =
                                    fresh.employeeId,
                                expectedVersion =
                                    command
                                        .baseVersion,
                                at = now,
                            )
                    ) {
                        throw versionConflict(
                            fresh.employeeVersion,
                        )
                    }

                    audit.append(
                        AuditEventRecord(
                            actorUserId =
                                command.actorUserId,
                            action =
                                "EMPLOYEE_IDENTITY_LINKED",
                            targetType =
                                "EMPLOYEE",
                            targetId =
                                fresh.employeeId,
                            safeDiffJson =
                                """{"fields":["identityLink"]}""",
                            occurredAt = now,
                            correlationId =
                                command
                                    .correlationId,
                        ),
                    )
                    events.publishEvent(
                        EmployeeIdentityLinked(
                            employeeId =
                                fresh.employeeId,
                            organizationId =
                                fresh.organizationId,
                            sourceVersion =
                                fresh.employeeVersion +
                                    1,
                            actorUserId =
                                command.actorUserId,
                            occurredAt = now,
                            correlationId =
                                command
                                    .correlationId,
                        ),
                    )
                }

                IdempotentCommandOutcome(
                    resultCode =
                        if (alreadyLinked) {
                            "EMPLOYEE_IDENTITY_ALREADY_LINKED"
                        } else {
                            "EMPLOYEE_IDENTITY_LINKED"
                        },
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "employeeId",
                                fresh.employeeId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return PeopleCommandResult(
            employee =
                requireEmployee(
                    command.employeeId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun updateProfile(
        command:
            UpdateEmployeeProfileCommand,
    ): PeopleCommandResult {
        val current =
            requireEmployee(
                command.employeeId,
            )
        requireManage(
            actorUserId =
                command.actorUserId,
            organizationId =
                current.organizationId,
        )
        requirePositiveVersion(
            command.baseVersion,
        )

        val displayName =
            requiredText(
                command.displayName,
                160,
                "INVALID_DISPLAY_NAME",
            )
        val legalName =
            optionalText(
                command.legalName,
                240,
            )
        val mobile =
            optionalText(
                command.mobile,
                64,
            )
        val email =
            optionalText(
                command.email,
                320,
            )?.lowercase(
                Locale.ROOT,
            )
        val fingerprint =
            IdempotencyKeyContract
                .fingerprint(
                    listOf(
                        command.employeeId,
                        command.baseVersion,
                        displayName,
                        legalName,
                        mobile,
                        email,
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
                        "PEOPLE_UPDATE_PROFILE",
                    targetType = "EMPLOYEE",
                    targetId =
                        command.employeeId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val now =
                    clock.instant()
                val fresh =
                    requireEmployee(
                        command.employeeId,
                    )
                requireVersion(
                    expected =
                        command.baseVersion,
                    current =
                        fresh.employeeVersion,
                )

                if (
                    !persistence
                        .updateProfile(
                            employeeId =
                                fresh.employeeId,
                            personId =
                                fresh.personId,
                            expectedEmployeeVersion =
                                command
                                    .baseVersion,
                            displayName =
                                displayName,
                            legalName =
                                legalName,
                            mobile = mobile,
                            email = email,
                            at = now,
                        )
                ) {
                    throw versionConflict(
                        fresh.employeeVersion,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_PROFILE_UPDATED",
                        targetType =
                            "EMPLOYEE",
                        targetId =
                            fresh.employeeId,
                        safeDiffJson =
                            """{"fields":["displayName","legalName","mobile","email"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeProfileUpdated(
                        employeeId =
                            fresh.employeeId,
                        organizationId =
                            fresh.organizationId,
                        sourceVersion =
                            fresh.employeeVersion +
                                1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EMPLOYEE_PROFILE_UPDATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "employeeId",
                                fresh.employeeId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return PeopleCommandResult(
            employee =
                requireEmployee(
                    command.employeeId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun directory(
        actorUserId: UUID,
        organizationId: UUID,
        limit: Int,
    ): List<EmployeeDirectoryRecord> {
        if (
            !authorization
                .canViewDirectory(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw forbidden()
        }

        return persistence
            .listDirectory(
                organizationId =
                    organizationId,
                at = clock.instant(),
                limit = limit,
            )
    }

    fun canManagePeople(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean =
        authorization.canManagePeople(
            actorUserId = actorUserId,
            organizationId = organizationId,
        )

    fun detail(
        actorUserId: UUID,
        employeeId: UUID,
    ): EmployeeAggregateSnapshot {
        val employee =
            requireEmployee(
                employeeId,
            )
        requireManage(
            actorUserId =
                actorUserId,
            organizationId =
                employee.organizationId,
        )
        return employee
    }

    fun ownProfile(
        actorUserId: UUID,
        organizationId: UUID,
    ): EmployeeAggregateSnapshot {
        if (
            !authorization
                .canViewDirectory(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw forbidden()
        }

        return persistence
            .loadOwnEmployee(
                identityId =
                    actorUserId,
                organizationId =
                    organizationId,
                at = clock.instant(),
            )
            ?: throw peopleError(
                code =
                    "EMPLOYEE_PROFILE_NOT_FOUND",
                message =
                    "No employee profile is linked to this identity in the organization.",
                status =
                    HttpStatus.NOT_FOUND,
            )
    }

    private fun requireEmployee(
        employeeId: UUID,
    ): EmployeeAggregateSnapshot =
        persistence.loadEmployee(
            employeeId =
                employeeId,
            at = clock.instant(),
        )
            ?: throw peopleError(
                code =
                    "EMPLOYEE_NOT_FOUND",
                message =
                    "The employee does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requireManage(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !authorization
                .canManagePeople(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw forbidden()
        }
    }

    private fun requireIdentityAvailableForNewPerson(
        identityId: UUID,
        organizationId: UUID,
    ) {
        requireIdentityMembership(
            identityId =
                identityId,
            organizationId =
                organizationId,
        )

        val identity =
            persistence.identityLink(
                identityId,
            )
                ?: throw peopleError(
                    code =
                        "IDENTITY_NOT_FOUND",
                    message =
                        "The identity does not exist.",
                    status =
                        HttpStatus.NOT_FOUND,
                )

        if (
            identity.status !=
            "ACTIVE"
        ) {
            throw peopleError(
                code =
                    "IDENTITY_NOT_ACTIVE",
                message =
                    "The identity is not active.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        if (identity.personId != null) {
            throw peopleError(
                code =
                    "IDENTITY_ALREADY_LINKED",
                message =
                    "The identity is already linked to a person.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireIdentityMembership(
        identityId: UUID,
        organizationId: UUID,
    ) {
        if (
            !sourceAuthority
                .isOrganizationMemberCurrent(
                    identityId =
                        identityId,
                    organizationId =
                        organizationId,
                    at = clock.instant(),
                )
        ) {
            throw peopleError(
                code =
                    "IDENTITY_ORGANIZATION_MISMATCH",
                message =
                    "The identity is not an active member of the employee organization.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireVersion(
        expected: Long,
        current: Long,
    ) {
        if (expected != current) {
            throw versionConflict(
                current,
            )
        }
    }

    private fun requirePositiveVersion(
        value: Long,
    ) {
        if (value < 1) {
            throw peopleError(
                code =
                    "INVALID_BASE_VERSION",
                message =
                    "baseVersion must be positive.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun versionConflict(
        current: Long,
    ): ProductApiException =
        peopleError(
            code =
                "EMPLOYEE_VERSION_CONFLICT",
            message =
                "The employee has changed. Refresh before retrying.",
            status =
                HttpStatus.CONFLICT,
            currentVersion =
                current,
        )

    private fun forbidden():
        ProductApiException =
        peopleError(
            code = "PEOPLE_ACCESS_DENIED",
            message =
                "You are not authorized for this People action.",
            status =
                HttpStatus.FORBIDDEN,
        )

    private fun requiredText(
        value: String,
        maxLength: Int,
        code: String,
    ): String {
        val normalized =
            value.trim()
        if (
            normalized.isEmpty() ||
            normalized.length >
            maxLength
        ) {
            throw peopleError(
                code = code,
                message =
                    "A required People field is invalid.",
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
            throw peopleError(
                code =
                    "INVALID_PEOPLE_FIELD",
                message =
                    "A People field exceeds its allowed length.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun normalizeEmploymentType(
        value: String?,
    ): String? {
        val normalized =
            optionalText(
                value,
                80,
            )?.uppercase(
                Locale.ROOT,
            )
                ?: return null

        if (
            !Regex(
                "^[A-Z][A-Z0-9_-]{0,79}$",
            ).matches(
                normalized,
            )
        ) {
            throw peopleError(
                code =
                    "INVALID_EMPLOYMENT_TYPE_CODE",
                message =
                    "employmentTypeCode is invalid.",
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

    private fun peopleError(
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

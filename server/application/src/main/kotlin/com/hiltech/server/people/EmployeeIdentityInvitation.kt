package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.EmployeeIdentityProvisioningPort
import com.hiltech.server.identity.EmployeeIdentityProvisioningRequest
import com.hiltech.server.identity.EmployeeIdentityProvisioningResult
import com.hiltech.server.identity.IdentityProvisioningDeliveryMode
import com.hiltech.server.identity.IdentityProvisioningDeliveryState
import com.hiltech.server.platform.ProductApiException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

enum class EmployeeIdentityInvitationState {
    PENDING_PROVIDER,
    PROVIDER_CREATED,
    LOCAL_PENDING,
    READY,
    FAILED_RETRYABLE,
}

data class EmployeeIdentityInvitationSnapshot(
    val invitationId: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val requestedLogin: String,
    val authProvider: String,
    val providerSubject: String?,
    val userIdentityId: UUID?,
    val state: EmployeeIdentityInvitationState,
    val deliveryMode:
        IdentityProvisioningDeliveryMode,
    val deliveryState:
        IdentityProvisioningDeliveryState,
    val safeFailureCode: String?,
    val createdAt: Instant,
    val createdByUserId: UUID,
    val updatedAt: Instant,
    val version: Long,
)

data class ProvisionEmployeeIdentityCommand(
    val operationId: UUID,
    val employeeId: UUID,
    val baseEmployeeVersion: Long,
    val requestedLogin: String,
    val deliveryMode:
        IdentityProvisioningDeliveryMode,
    val actorUserId: UUID,
    val correlationId: String,
)

data class EmployeeIdentityInvitationCommandResult(
    val invitation:
        EmployeeIdentityInvitationSnapshot,
    val temporaryCredential: String?,
    val replayed: Boolean,
)

interface EmployeeIdentityInvitationPersistencePort {
    fun byOperationId(
        operationId: UUID,
    ): EmployeeIdentityInvitationSnapshot?

    fun currentForEmployee(
        employeeId: UUID,
    ): EmployeeIdentityInvitationSnapshot?

    fun employeeHasLinkedIdentity(
        employeeId: UUID,
    ): Boolean

    fun insertPending(
        invitationId: UUID,
        operationId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        requestedLogin: String,
        authProvider: String,
        deliveryMode:
            IdentityProvisioningDeliveryMode,
        actorUserId: UUID,
        at: Instant,
    )

    fun markReady(
        invitationId: UUID,
        providerSubject: String,
        userIdentityId: UUID,
        deliveryState:
            IdentityProvisioningDeliveryState,
        at: Instant,
    ): Boolean

    fun markRetryableFailure(
        invitationId: UUID,
        providerSubject: String?,
        deliveryState:
            IdentityProvisioningDeliveryState,
        safeFailureCode: String,
        at: Instant,
    ): Boolean
}

@Component
class JdbcEmployeeIdentityInvitationPersistence(
    private val jdbc: JdbcTemplate,
) : EmployeeIdentityInvitationPersistencePort {
    override fun byOperationId(
        operationId: UUID,
    ): EmployeeIdentityInvitationSnapshot? =
        query(
            "eii.operation_id = ?",
            arrayOf(operationId),
        )

    override fun currentForEmployee(
        employeeId: UUID,
    ): EmployeeIdentityInvitationSnapshot? =
        query(
            """
            eii.employee_id = ?
            AND eii.state IN (
                'PENDING_PROVIDER',
                'PROVIDER_CREATED',
                'LOCAL_PENDING',
                'READY',
                'FAILED_RETRYABLE'
            )
            """.trimIndent(),
            arrayOf(employeeId),
        )

    override fun employeeHasLinkedIdentity(
        employeeId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM employee e
                JOIN user_identity ui
                  ON ui.person_id = e.person_id
                WHERE e.id = ?
                  AND ui.status IN (
                      'PENDING',
                      'ACTIVE',
                      'LOCKED'
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            employeeId,
        ) == true

    override fun insertPending(
        invitationId: UUID,
        operationId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        requestedLogin: String,
        authProvider: String,
        deliveryMode:
            IdentityProvisioningDeliveryMode,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO employee_identity_invitation (
                id,
                operation_id,
                organization_id,
                employee_id,
                requested_login,
                auth_provider,
                provider_subject,
                user_identity_id,
                state,
                delivery_mode,
                delivery_state,
                safe_failure_code,
                created_at,
                created_by_user_id,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?, ?,
                NULL, NULL,
                'PENDING_PROVIDER',
                ?,
                'PENDING',
                NULL,
                ?, ?, ?, 1
            )
            """.trimIndent(),
            invitationId,
            operationId,
            organizationId,
            employeeId,
            requestedLogin,
            authProvider,
            deliveryMode.name,
            timestamp,
            actorUserId,
            timestamp,
        )
    }

    override fun markReady(
        invitationId: UUID,
        providerSubject: String,
        userIdentityId: UUID,
        deliveryState:
            IdentityProvisioningDeliveryState,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee_identity_invitation
            SET provider_subject = ?,
                user_identity_id = ?,
                state = 'READY',
                delivery_state = ?,
                safe_failure_code = NULL,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state IN (
                  'PENDING_PROVIDER',
                  'PROVIDER_CREATED',
                  'LOCAL_PENDING',
                  'FAILED_RETRYABLE',
                  'READY'
              )
            """.trimIndent(),
            providerSubject,
            userIdentityId,
            deliveryState.name,
            at.atOffset(ZoneOffset.UTC),
            invitationId,
        ) == 1

    override fun markRetryableFailure(
        invitationId: UUID,
        providerSubject: String?,
        deliveryState:
            IdentityProvisioningDeliveryState,
        safeFailureCode: String,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee_identity_invitation
            SET provider_subject =
                    COALESCE(
                        ?,
                        provider_subject
                    ),
                state = 'FAILED_RETRYABLE',
                delivery_state = ?,
                safe_failure_code = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state <> 'READY'
            """.trimIndent(),
            providerSubject,
            deliveryState.name,
            safeFailureCode,
            at.atOffset(ZoneOffset.UTC),
            invitationId,
        ) == 1

    private fun query(
        predicate: String,
        args: Array<Any>,
    ): EmployeeIdentityInvitationSnapshot? =
        jdbc.query(
            """
            SELECT
                eii.id,
                eii.operation_id,
                eii.organization_id,
                eii.employee_id,
                eii.requested_login,
                eii.auth_provider,
                eii.provider_subject,
                eii.user_identity_id,
                eii.state,
                eii.delivery_mode,
                eii.delivery_state,
                eii.safe_failure_code,
                eii.created_at,
                eii.created_by_user_id,
                eii.updated_at,
                eii.version
            FROM employee_identity_invitation eii
            WHERE $predicate
            ORDER BY
                eii.created_at DESC,
                eii.id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                EmployeeIdentityInvitationSnapshot(
                    invitationId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    operationId =
                        rs.getObject(
                            "operation_id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    employeeId =
                        rs.getObject(
                            "employee_id",
                            UUID::class.java,
                        ),
                    requestedLogin =
                        rs.getString(
                            "requested_login",
                        ),
                    authProvider =
                        rs.getString(
                            "auth_provider",
                        ),
                    providerSubject =
                        rs.getString(
                            "provider_subject",
                        ),
                    userIdentityId =
                        rs.getObject(
                            "user_identity_id",
                            UUID::class.java,
                        ),
                    state =
                        EmployeeIdentityInvitationState
                            .valueOf(
                                rs.getString(
                                    "state",
                                ),
                            ),
                    deliveryMode =
                        IdentityProvisioningDeliveryMode
                            .valueOf(
                                rs.getString(
                                    "delivery_mode",
                                ),
                            ),
                    deliveryState =
                        IdentityProvisioningDeliveryState
                            .valueOf(
                                rs.getString(
                                    "delivery_state",
                                ),
                            ),
                    safeFailureCode =
                        rs.getString(
                            "safe_failure_code",
                        ),
                    createdAt =
                        rs.getObject(
                            "created_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    createdByUserId =
                        rs.getObject(
                            "created_by_user_id",
                            UUID::class.java,
                        ),
                    updatedAt =
                        rs.getObject(
                            "updated_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    version =
                        rs.getLong(
                            "version",
                        ),
                )
            },
            *args,
        ).singleOrNull()
}

@Component
class EmployeeIdentityInvitationService(
    private val onboarding:
        OnboardingPersistencePort,
    private val persistence:
        EmployeeIdentityInvitationPersistencePort,
    private val authorization:
        PeopleAuthorizationPort,
    private val provisioning:
        EmployeeIdentityProvisioningPort,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun provision(
        command:
            ProvisionEmployeeIdentityCommand,
    ): EmployeeIdentityInvitationCommandResult {
        if (command.baseEmployeeVersion < 1) {
            throw invitationError(
                code =
                    "INVALID_BASE_VERSION",
                message =
                    "baseEmployeeVersion must be positive.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val login =
            normalizeLogin(
                command.requestedLogin,
            )
        val employee =
            onboarding.employee(
                command.employeeId,
            )
                ?: throw invitationError(
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
            employee.employeeState !=
            EmployeeState.PREBOARDING
        ) {
            throw invitationError(
                code =
                    "EMPLOYEE_NOT_PREBOARDING",
                message =
                    "Identity provisioning is only available during preboarding.",
                status =
                    HttpStatus.CONFLICT,
            )
        }

        if (
            employee.employeeVersion !=
            command.baseEmployeeVersion
        ) {
            throw invitationError(
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

        val existing =
            persistence.byOperationId(
                command.operationId,
            )

        if (existing != null) {
            requireSameRequest(
                existing = existing,
                employee = employee,
                login = login,
                mode =
                    command.deliveryMode,
            )

            if (
                existing.state ==
                EmployeeIdentityInvitationState
                    .READY
            ) {
                if (
                    existing.deliveryMode ==
                    IdentityProvisioningDeliveryMode
                        .TEMPORARY_PASSWORD_HANDOFF
                ) {
                    throw invitationError(
                        code =
                            "TEMPORARY_CREDENTIAL_NOT_REPLAYABLE",
                        message =
                            "The temporary credential was already issued and cannot be replayed.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                return EmployeeIdentityInvitationCommandResult(
                    invitation = existing,
                    temporaryCredential = null,
                    replayed = true,
                )
            }
        } else {
            if (
                persistence
                    .employeeHasLinkedIdentity(
                        employee.employeeId,
                    )
            ) {
                throw invitationError(
                    code =
                        "EMPLOYEE_IDENTITY_ALREADY_LINKED",
                    message =
                        "The employee already has an identity link.",
                    status =
                        HttpStatus.CONFLICT,
                )
            }

            persistence
                .currentForEmployee(
                    employee.employeeId,
                )
                ?.let {
                    current ->
                    if (
                        current.operationId !=
                        command.operationId
                    ) {
                        throw invitationError(
                            code =
                                "EMPLOYEE_IDENTITY_INVITATION_EXISTS",
                            message =
                                "The employee already has an identity provisioning attempt.",
                            status =
                                HttpStatus.CONFLICT,
                            currentVersion =
                                current.version,
                        )
                    }
                }

            try {
                persistence.insertPending(
                    invitationId =
                        invitationId(
                            command.operationId,
                        ),
                    operationId =
                        command.operationId,
                    organizationId =
                        employee.organizationId,
                    employeeId =
                        employee.employeeId,
                    requestedLogin =
                        login,
                    authProvider =
                        "OIDC",
                    deliveryMode =
                        command.deliveryMode,
                    actorUserId =
                        command.actorUserId,
                    at = clock.instant(),
                )
            } catch (
                failure:
                    org.springframework.dao
                        .DataIntegrityViolationException,
            ) {
                val concurrent =
                    persistence.byOperationId(
                        command.operationId,
                    )
                        ?: throw invitationError(
                            code =
                                "EMPLOYEE_IDENTITY_INVITATION_CONFLICT",
                            message =
                                "The identity invitation changed concurrently.",
                            status =
                                HttpStatus.CONFLICT,
                        )
                requireSameRequest(
                    existing =
                        concurrent,
                    employee =
                        employee,
                    login = login,
                    mode =
                        command.deliveryMode,
                )
            }
        }

        val invitation =
            persistence.byOperationId(
                command.operationId,
            )
                ?: throw invitationError(
                    code =
                        "EMPLOYEE_IDENTITY_INVITATION_UNAVAILABLE",
                    message =
                        "The identity invitation could not be loaded.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )

        val result =
            provisioning.provision(
                EmployeeIdentityProvisioningRequest(
                    invitationId =
                        invitation.invitationId,
                    personId =
                        employee.personId,
                    organizationId =
                        employee.organizationId,
                    requestedLogin =
                        login,
                    actorIdentityId =
                        command.actorUserId,
                    deliveryMode =
                        command.deliveryMode,
                ),
            )

        return when (result) {
            is EmployeeIdentityProvisioningResult
                .Ready -> {
                val now = clock.instant()

                if (
                    !persistence.markReady(
                        invitationId =
                            invitation.invitationId,
                        providerSubject =
                            result.providerSubject,
                        userIdentityId =
                            result.userIdentityId,
                        deliveryState =
                            result.deliveryState,
                        at = now,
                    )
                ) {
                    throw invitationError(
                        code =
                            "EMPLOYEE_IDENTITY_INVITATION_CONFLICT",
                        message =
                            "The identity invitation changed while provisioning.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                val ready =
                    requireNotNull(
                        persistence
                            .byOperationId(
                                command.operationId,
                            ),
                    )

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_IDENTITY_PROVISIONED",
                        targetType =
                            "EMPLOYEE",
                        targetId =
                            employee.employeeId,
                        safeDiffJson =
                            """{"fields":["identityLink","organizationMembership","deliveryState"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeIdentityProvisioned(
                        invitationId =
                            ready.invitationId,
                        organizationId =
                            ready.organizationId,
                        employeeId =
                            ready.employeeId,
                        userIdentityId =
                            result.userIdentityId,
                        sourceVersion =
                            ready.version,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                EmployeeIdentityInvitationCommandResult(
                    invitation = ready,
                    temporaryCredential =
                        result
                            .temporaryCredential,
                    replayed = false,
                )
            }

            is EmployeeIdentityProvisioningResult
                .RetryableFailure -> {
                val now = clock.instant()
                persistence
                    .markRetryableFailure(
                        invitationId =
                            invitation.invitationId,
                        providerSubject =
                            result.providerSubject,
                        deliveryState =
                            result.deliveryState,
                        safeFailureCode =
                            result.safeCode,
                        at = now,
                    )

                throw invitationError(
                    code =
                        result.safeCode,
                    message =
                        "Identity provisioning is not available yet.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
            }
        }
    }

    private fun requireSameRequest(
        existing:
            EmployeeIdentityInvitationSnapshot,
        employee:
            OnboardingEmployeeContext,
        login: String,
        mode:
            IdentityProvisioningDeliveryMode,
    ) {
        if (
            existing.employeeId !=
            employee.employeeId ||
            existing.organizationId !=
            employee.organizationId ||
            existing.requestedLogin !=
            login ||
            existing.deliveryMode != mode
        ) {
            throw invitationError(
                code =
                    "IDEMPOTENCY_KEY_REUSED",
                message =
                    "This operation ID is already bound to another identity invitation.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
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
            throw invitationError(
                code =
                    "PEOPLE_ACCESS_DENIED",
                message =
                    "You are not authorized for this People action.",
                status =
                    HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun normalizeLogin(
        value: String,
    ): String {
        val trimmed = value.trim()
        if (
            trimmed.length !in 3..320 ||
            trimmed.any {
                it.isISOControl()
            }
        ) {
            throw invitationError(
                code =
                    "INVALID_IDENTITY_LOGIN",
                message =
                    "The requested login is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        return if (
            trimmed.contains("@")
        ) {
            trimmed.lowercase(
                Locale.ROOT,
            )
        } else {
            trimmed
        }
    }

    private fun invitationId(
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            (
                "employee-identity-invitation:" +
                    operationId
            ).toByteArray(
                StandardCharsets.UTF_8,
            ),
        )

    private fun invitationError(
        code: String,
        message: String,
        status: HttpStatus,
        retryable: Boolean = false,
        currentVersion: Long? = null,
    ): ProductApiException =
        ProductApiException(
            code = code,
            message = message,
            status = status,
            retryable = retryable,
            currentVersion =
                currentVersion,
        )
}

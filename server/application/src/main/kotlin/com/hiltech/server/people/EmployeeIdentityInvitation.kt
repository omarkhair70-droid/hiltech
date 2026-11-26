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
import org.springframework.jdbc.core.ConnectionCallback
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

enum class EmployeeIdentityAccountState {
    NONE,
    PENDING,
    LINKED,
    RESTRICTED,
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

data class EmployeeIdentityInvitationPending(
    val operationId: String,
    val requestedLogin: String,
    val deliveryMode: String,
)

data class EmployeeIdentityInvitationAvailability(
    val employeeId: String,
    val organizationId: String,
    val status: String,
    val deliveryModes: List<String>,
    val pending: EmployeeIdentityInvitationPending?,
)

interface EmployeeIdentityInvitationPersistencePort {
    fun <T> serializeProvisioning(employeeId: UUID, provision: () -> T): T

    fun byOperationId(
        operationId: UUID,
    ): EmployeeIdentityInvitationSnapshot?

    fun currentForEmployee(
        employeeId: UUID,
    ): EmployeeIdentityInvitationSnapshot?

    fun accountState(employeeId: UUID, at: Instant): EmployeeIdentityAccountState

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
    override fun <T> serializeProvisioning(employeeId: UUID, provision: () -> T): T =
        jdbc.execute(ConnectionCallback { connection ->
            val lockKey = "hiltech.employee-identity:$employeeId"
            val claimed = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { query ->
                query.setString(1, lockKey)
                query.executeQuery().use { it.next() && it.getBoolean(1) }
            }
            if (!claimed) {
                throw ProductApiException(
                    code = "EMPLOYEE_IDENTITY_INVITATION_IN_PROGRESS",
                    message = "Account setup is already running for this employee.",
                    status = HttpStatus.CONFLICT,
                    retryable = true,
                )
            }
            try {
                provision()
            } finally {
                connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { query ->
                    query.setString(1, lockKey)
                    query.execute()
                }
            }
        }) ?: error("Employee identity operation did not produce a result.")

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

    override fun accountState(employeeId: UUID, at: Instant): EmployeeIdentityAccountState =
        jdbc.query(
            """
            SELECT ui.status,
                   EXISTS (
                       SELECT 1 FROM organization_membership m
                       JOIN organization o ON o.id = m.organization_id
                       WHERE m.organization_id = e.organization_id AND m.user_identity_id = ui.id
                         AND o.status = 'ACTIVE' AND o.organization_type = 'HILTECH'
                         AND m.state = 'ACTIVE' AND m.valid_from <= ?
                         AND (m.valid_until IS NULL OR m.valid_until > ?)
                   ) AS current_member,
                   EXISTS (
                       SELECT 1 FROM organization_membership m
                       JOIN organization o ON o.id = m.organization_id
                       WHERE m.organization_id = e.organization_id AND m.user_identity_id = ui.id
                         AND o.status = 'ACTIVE' AND o.organization_type = 'HILTECH'
                         AND m.state = 'PENDING' AND m.valid_from <= ?
                         AND (m.valid_until IS NULL OR m.valid_until > ?)
                   ) AS pending_member
            FROM employee e JOIN user_identity ui ON ui.person_id = e.person_id
            WHERE e.id = ?
            """.trimIndent(),
            { rs, _ ->
                when {
                    rs.getString("status") == "ACTIVE" && rs.getBoolean("current_member") -> EmployeeIdentityAccountState.LINKED
                    rs.getString("status") in setOf("PENDING", "ACTIVE") &&
                        (rs.getBoolean("current_member") || rs.getBoolean("pending_member")) -> EmployeeIdentityAccountState.PENDING
                    else -> EmployeeIdentityAccountState.RESTRICTED
                }
            },
            at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC), employeeId,
        ).let { states ->
            when {
                EmployeeIdentityAccountState.LINKED in states -> EmployeeIdentityAccountState.LINKED
                EmployeeIdentityAccountState.PENDING in states -> EmployeeIdentityAccountState.PENDING
                states.isNotEmpty() -> EmployeeIdentityAccountState.RESTRICTED
                else -> EmployeeIdentityAccountState.NONE
            }
        }

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
    fun availability(employeeId: UUID, actorUserId: UUID): EmployeeIdentityInvitationAvailability {
        val employee = onboarding.employee(employeeId) ?: throw invitationError(
            code = "EMPLOYEE_NOT_FOUND",
            message = "The employee does not exist.",
            status = HttpStatus.NOT_FOUND,
        )
        requireManage(actorUserId, employee.organizationId)

        val current = persistence.currentForEmployee(employeeId)
        val pending = current?.takeUnless { it.state == EmployeeIdentityInvitationState.READY }
        val account = persistence.accountState(employeeId, clock.instant())
        val modes = provisioning.availableDeliveryModes()
        val status = when {
            account == EmployeeIdentityAccountState.RESTRICTED -> "ACCOUNT_RESTRICTED"
            account == EmployeeIdentityAccountState.LINKED -> "LINKED"
            employee.employeeState !in setOf(EmployeeState.PREBOARDING, EmployeeState.ACTIVE) -> "NOT_ELIGIBLE"
            pending != null && pending.deliveryMode in modes -> "CONTINUE_INVITATION"
            pending != null -> "DELIVERY_UNAVAILABLE"
            current?.state == EmployeeIdentityInvitationState.READY || account == EmployeeIdentityAccountState.PENDING -> "ACCOUNT_ATTENTION"
            modes.isEmpty() -> "DELIVERY_UNAVAILABLE"
            else -> "READY_TO_INVITE"
        }
        return EmployeeIdentityInvitationAvailability(
            employeeId = employeeId.toString(),
            organizationId = employee.organizationId.toString(),
            status = status,
            deliveryModes = when (status) {
                "READY_TO_INVITE" -> modes.map { it.name }
                "CONTINUE_INVITATION" -> listOf(requireNotNull(pending).deliveryMode.name)
                else -> emptyList()
            },
            pending = pending?.takeIf { status == "CONTINUE_INVITATION" }?.let {
                EmployeeIdentityInvitationPending(it.operationId.toString(), it.requestedLogin, it.deliveryMode.name)
            },
        )
    }

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
        if (command.deliveryMode == IdentityProvisioningDeliveryMode.EMAIL_ACTION_LINK &&
            (login.count { it == '@' } != 1 || login.startsWith('@') || login.endsWith('@') || login.any(Char::isWhitespace))
        ) {
            throw invitationError(
                code = "IDENTITY_EMAIL_REQUIRED",
                message = "Enter the employee’s email address to send a setup link.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
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

        return persistence.serializeProvisioning(employee.employeeId) {
            val current = onboarding.employee(employee.employeeId)
                ?.takeIf { it.organizationId == employee.organizationId }
                ?: throw invitationError(
                    code = "EMPLOYEE_NOT_FOUND",
                    message = "The employee does not exist.",
                    status = HttpStatus.NOT_FOUND,
                )
            provisionAuthorized(command, current, login)
        }
    }

    private fun provisionAuthorized(
        command: ProvisionEmployeeIdentityCommand,
        employee: OnboardingEmployeeContext,
        login: String,
    ): EmployeeIdentityInvitationCommandResult {
        // Authority can change while another request owns the employee provisioning lock.
        requireManage(command.actorUserId, employee.organizationId)
        val existing = persistence.byOperationId(command.operationId)
        existing?.let { requireSameRequest(it, employee, login, command.deliveryMode) }
        val account = persistence.accountState(employee.employeeId, clock.instant())
        if (account == EmployeeIdentityAccountState.RESTRICTED) {
            throw invitationError(
                code = "EMPLOYEE_ACCOUNT_RESTRICTED",
                message = "The employee has an existing restricted account. Review that account before continuing.",
                status = HttpStatus.CONFLICT,
            )
        }

        if (existing?.state == EmployeeIdentityInvitationState.READY) {
            if (existing.deliveryMode == IdentityProvisioningDeliveryMode.TEMPORARY_PASSWORD_HANDOFF) {
                throw invitationError(
                    code = "TEMPORARY_CREDENTIAL_NOT_REPLAYABLE",
                    message = "The temporary credential was already issued and cannot be replayed.",
                    status = HttpStatus.CONFLICT,
                )
            }
            return EmployeeIdentityInvitationCommandResult(existing, null, replayed = true)
        }

        if (
            employee.employeeState !in setOf(EmployeeState.PREBOARDING, EmployeeState.ACTIVE)
        ) {
            throw invitationError(
                code =
                    "EMPLOYEE_ACCOUNT_NOT_ELIGIBLE",
                message =
                    "Account setup is available for preboarding and active employees.",
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

        if (command.deliveryMode !in provisioning.availableDeliveryModes()) {
            throw invitationError(
                code = "IDENTITY_DELIVERY_NOT_CONFIGURED",
                message = "The selected account delivery method is unavailable.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
            )
        }

        if (existing == null) {
            if (account != EmployeeIdentityAccountState.NONE) {
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
                val sourceConflict = result.safeCode in setOf(
                    "IDENTITY_ACCOUNT_RESTRICTED", "IDENTITY_PERSON_ALREADY_LINKED", "IDENTITY_MEMBERSHIP_CONFLICT",
                    "IDENTITY_PROVIDER_SUBJECT_CONFLICT", "IDENTITY_EMPLOYEE_NOT_ELIGIBLE",
                    "IDENTITY_LOCAL_ACTIVATION_FAILED", "IDENTITY_MEMBERSHIP_ACTIVATION_FAILED",
                )
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
                        if (sourceConflict) "The existing account or membership changed. Review it before continuing."
                        else "Identity provisioning is not available yet.",
                    status =
                        if (sourceConflict) HttpStatus.CONFLICT else HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = !sourceConflict,
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

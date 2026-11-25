package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeopleAuthorizationPort
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
class CompanyResponsibilityService(
    private val employees: ResponsibilityEmployeeDirectoryPort,
    private val people: PeopleAuthorizationPort,
    private val authority: ResponsibilityAdministrationAuthorityPort,
    private val source: RoleTeamSourceAuthorityPort,
    private val persistence: CompanyResponsibilityPersistencePort,
    private val projection: CompanyResponsibilityProjectionPort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun current(actor: UUID, employeeId: UUID): EmployeeCompanyResponsibilitiesResponse {
        val employee = employee(employeeId)
        val now = clock.instant()
        val ceiling = authority.grantableKeys(actor, employee.organizationId, now)
        val fullCatalog = people.canManagePeople(actor, employee.organizationId)
        if (ceiling.isEmpty() && !fullCatalog) throw problem("RESPONSIBILITY_DIRECTORY_DENIED", "Responsibility administration access is required.", HttpStatus.FORBIDDEN)
        val identity = employee.linkedIdentityId?.takeIf { source.isOrganizationMemberCurrent(it, employee.organizationId, now) }
        val assignable = identity != null && employee.employeeState in assignableStates
        val bindings = if (identity == null) emptyList() else persistence.bindings(employee.organizationId, now)
            .filter { binding ->
                binding.current && binding.organizationId == employee.organizationId && (fullCatalog || binding.authorityKey in ceiling) && when (binding.principalType) {
                    "USER" -> binding.principalId == identity
                    "TEAM" -> source.isTeamMemberCurrent(identity, binding.principalId, now)
                    else -> false
                }
            }.map { binding ->
                CompanyResponsibilityBindingResponse(
                    binding.bindingId.toString(), binding.authorityKey.name, binding.authorityKey.domain,
                    if (binding.principalType == "USER") "DIRECT" else "TEAM",
                    binding.principalType == "USER" && binding.authorityKey in ceiling,
                    binding.current, binding.version,
                )
            }
        return EmployeeCompanyResponsibilitiesResponse(
            employee.employeeId.toString(), employee.organizationId.toString(), employee.employeeVersion,
            identity?.toString(), if (identity == null) "NO_CURRENT_LINKED_ACCOUNT" else "CURRENT",
            asOf = now.toString(),
            catalog = CompanyResponsibilityKey.entries.map { key ->
                CompanyResponsibilityCatalogResponse(key.name, key.domain, assignable && key in ceiling)
            }, bindings = bindings, viewScope = if (fullCatalog) "FULL_CATALOG" else "DELEGATED_KEYS",
            canDelegateAdministration = authority.delegableKeys(actor, employee.organizationId, now).isNotEmpty(),
        )
    }

    fun grant(command: CompanyResponsibilityCommand): CompanyResponsibilityCommandResponse = execute(command, ending = false)
    fun end(command: CompanyResponsibilityCommand): CompanyResponsibilityCommandResponse = execute(command, ending = true)

    private fun execute(command: CompanyResponsibilityCommand, ending: Boolean): CompanyResponsibilityCommandResponse {
        if (command.baseEmployeeVersion < 1 || (ending && (command.baseBindingVersion ?: 0) < 1)) {
            throw problem("RESPONSIBILITY_INVALID_VERSION", "A current record version is required.", HttpStatus.BAD_REQUEST)
        }
        if (!ending && (command.bindingId != null || command.baseBindingVersion != null)) {
            throw problem("RESPONSIBILITY_INVALID_GRANT", "A new assignment cannot contain an existing binding.", HttpStatus.BAD_REQUEST)
        }
        if (ending && command.bindingId == null) {
            throw problem("RESPONSIBILITY_BINDING_REQUIRED", "An exact assignment is required.", HttpStatus.BAD_REQUEST)
        }
        // Always before receipt replay: possessing an old operation ID is not authority.
        requireAuthority(command)
        val bindingId = if (ending) requireNotNull(command.bindingId) else UUID.nameUUIDFromBytes(
            "company-responsibility:${command.operationId}".toByteArray(StandardCharsets.UTF_8),
        )
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("action", if (ending) "END" else "GRANT")
            put("employeeId", command.employeeId.toString())
            put("organizationId", command.organizationId.toString())
            put("employeeVersion", command.baseEmployeeVersion)
            put("identityId", command.expectedIdentityId.toString())
            put("authorityKey", command.authorityKey.name)
            put("bindingId", bindingId.toString())
            command.baseBindingVersion?.let { put("bindingVersion", it) }
        }.toString())
        val resultCode = if (ending) "COMPANY_RESPONSIBILITY_ENDED" else "COMPANY_RESPONSIBILITY_GRANTED"
        val result = idempotency.execute(IdempotentCommandSpec(
            operationId = command.operationId, actorUserId = command.actorUserId,
            commandType = if (ending) "COMPANY_RESPONSIBILITY_END" else "COMPANY_RESPONSIBILITY_GRANT",
            targetType = "EMPLOYEE_RESPONSIBILITY", targetId = bindingId,
            requestFingerprint = fingerprint, correlationId = command.correlationId,
        )) {
            persistence.lockOrganization(command.organizationId)
            persistence.lockEmployee(command.employeeId)
            val employee = requireAuthority(command)
            val now = clock.instant()
            requireTarget(command, employee, now, granting = !ending)
            val binding = if (ending) {
                val before = requireBinding(command, bindingId, now)
                if (before.principalType != "USER" || before.principalId != command.expectedIdentityId) {
                    throw problem("RESPONSIBILITY_DIRECT_ASSIGNMENT_REQUIRED", "Only this employee’s direct assignment can be ended here.", HttpStatus.CONFLICT)
                }
                if (!before.current || before.version != command.baseBindingVersion || !persistence.end(before, now)) {
                    throw problem("RESPONSIBILITY_VERSION_CONFLICT", "The assignment changed; refresh before ending it.", HttpStatus.CONFLICT)
                }
                requireBinding(command, bindingId, now)
            } else {
                if (persistence.bindings(command.organizationId, now).any {
                        it.current && it.organizationId == command.organizationId && it.principalType == "USER" &&
                            it.principalId == command.expectedIdentityId && it.authorityKey == command.authorityKey
                    }) {
                    throw problem("RESPONSIBILITY_ALREADY_ASSIGNED", "This direct responsibility is already assigned.", HttpStatus.CONFLICT)
                }
                try {
                    persistence.grant(command.authorityKey, bindingId, command.organizationId, command.expectedIdentityId,
                        command.actorUserId, command.operationId, now)
                } catch (_: DataIntegrityViolationException) {
                    throw problem("RESPONSIBILITY_ASSIGNMENT_CONFLICT", "The assignment conflicts with current source records.", HttpStatus.CONFLICT)
                }
                requireBinding(command, bindingId, now)
            }
            projection.changed(binding, now)
            audit.append(AuditEventRecord(
                actorUserId = command.actorUserId, action = resultCode,
                targetType = "EMPLOYEE_RESPONSIBILITY", targetId = bindingId,
                previousStateRef = if (ending) command.authorityKey.name else null,
                newStateRef = if (ending) "ENDED" else command.authorityKey.name,
                safeDiffJson = """{"fields":["employeeId","organizationId","identityId","authorityKey","active","version"]}""",
                occurredAt = now, correlationId = command.correlationId,
            ))
            IdempotentCommandOutcome(resultCode, buildJsonObject { put("bindingId", bindingId.toString()) }.toString())
        }
        // A receipt is not a promise that the responsibility is still current.
        val binding = requireBinding(command, bindingId, clock.instant())
        return CompanyResponsibilityCommandResponse(
            command.employeeId.toString(), command.organizationId.toString(), command.expectedIdentityId.toString(),
            command.authorityKey.name, bindingId.toString(), result.outcome.resultCode,
            binding.current, binding.version, result.replayed, command.operationId.toString(),
        )
    }

    private fun requireAuthority(command: CompanyResponsibilityCommand): ResponsibilityEmployeeContext {
        val employee = employee(command.employeeId)
        if (employee.organizationId != command.organizationId ||
            command.authorityKey !in authority.grantableKeys(command.actorUserId, command.organizationId, clock.instant())) {
            throw problem("RESPONSIBILITY_ADMINISTRATION_DENIED", "This responsibility is outside your current delegation authority.", HttpStatus.FORBIDDEN)
        }
        return employee
    }

    private fun requireTarget(command: CompanyResponsibilityCommand, employee: ResponsibilityEmployeeContext, at: Instant, granting: Boolean) {
        if (employee.employeeVersion != command.baseEmployeeVersion || employee.linkedIdentityId != command.expectedIdentityId) {
            throw problem("RESPONSIBILITY_EMPLOYEE_CHANGED", "The employee or linked account changed; refresh the record.", HttpStatus.CONFLICT)
        }
        if ((granting && employee.employeeState !in assignableStates) ||
            !source.isOrganizationMemberCurrent(command.expectedIdentityId, command.organizationId, at)) {
            throw problem("RESPONSIBILITY_ACCOUNT_NOT_CURRENT", "A current employee account is required.", HttpStatus.CONFLICT)
        }
    }

    private fun requireBinding(command: CompanyResponsibilityCommand, bindingId: UUID, at: Instant): CompanyResponsibilityBinding {
        val binding = persistence.binding(command.authorityKey, bindingId, at)
        if (binding == null || binding.organizationId != command.organizationId || binding.authorityKey != command.authorityKey) {
            throw problem("RESPONSIBILITY_BINDING_UNAVAILABLE", "The assignment is unavailable.", HttpStatus.NOT_FOUND)
        }
        return binding
    }

    private fun problem(code: String, message: String, status: HttpStatus) = ProductApiException(code, message, status)

    private fun employee(employeeId: UUID): ResponsibilityEmployeeContext = employees.employee(employeeId)
        ?: throw problem("RESPONSIBILITY_EMPLOYEE_UNAVAILABLE", "The employee is unavailable.", HttpStatus.NOT_FOUND)

    companion object {
        private val assignableStates = setOf(EmployeeState.PREBOARDING, EmployeeState.ACTIVE)
    }
}

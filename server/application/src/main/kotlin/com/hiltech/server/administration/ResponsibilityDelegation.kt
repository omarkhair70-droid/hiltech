package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class ResponsibilityDelegationBinding(
    val id: UUID, val organizationId: UUID, val identityId: UUID, val authorityKey: CompanyResponsibilityKey,
    val canDelegateAdministration: Boolean, val current: Boolean, val version: Long,
)
interface ResponsibilityDelegationPersistencePort {
    fun lockOrganization(organizationId: UUID)
    fun lockEmployee(employeeId: UUID)
    fun currentBindings(organizationId: UUID, identityId: UUID, at: Instant): List<ResponsibilityDelegationBinding>
    fun binding(id: UUID, at: Instant): ResponsibilityDelegationBinding?
    fun hasOtherCurrentDelegator(organizationId: UUID, key: CompanyResponsibilityKey, exceptId: UUID, at: Instant): Boolean
    fun grant(id: UUID, organizationId: UUID, identityId: UUID, key: CompanyResponsibilityKey,
              onward: Boolean, actor: UUID, operationId: UUID, at: Instant)
    fun end(binding: ResponsibilityDelegationBinding, at: Instant): Boolean
}
data class ResponsibilityDelegationBindingResponse(
    val bindingId: String, val authorityKey: String, val canDelegateAdministration: Boolean,
    val current: Boolean, val version: Long, val canEnd: Boolean,
)
data class EmployeeResponsibilityDelegationResponse(
    val employeeId: String, val organizationId: String, val employeeVersion: Long, val linkedIdentityId: String?,
    val available: Boolean, val canGrant: Boolean, val delegableAuthorityKeys: List<String>,
    val bindings: List<ResponsibilityDelegationBindingResponse>,
)
data class ResponsibilityDelegationCommand(
    val operationId: UUID, val employeeId: UUID, val organizationId: UUID, val expectedIdentityId: UUID,
    val baseEmployeeVersion: Long, val authorityKeys: Set<CompanyResponsibilityKey>, val canDelegateAdministration: Boolean,
    val bindingId: UUID? = null, val baseBindingVersion: Long? = null, val actor: UUID, val correlationId: String,
)
data class ResponsibilityDelegationReceipt(
    val operationId: String, val employeeId: String, val organizationId: String, val identityId: String,
    val resultCode: String, val bindings: List<ResponsibilityDelegationBindingResponse>, val replayed: Boolean,
)

@Component
class ResponsibilityDelegationService(
    private val employees: ResponsibilityEmployeeDirectoryPort,
    private val authority: ResponsibilityAdministrationAuthorityPort,
    private val source: RoleTeamSourceAuthorityPort,
    private val persistence: ResponsibilityDelegationPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun current(actor: UUID, employeeId: UUID): EmployeeResponsibilityDelegationResponse {
        val employee = target(employeeId)
        val at = clock.instant()
        val ceiling = authority.delegableKeys(actor, employee.organizationId, at)
        if (ceiling.isEmpty()) throw denied()
        val identity = employee.linkedIdentityId?.takeIf { source.isOrganizationMemberCurrent(it, employee.organizationId, at) }
        val bindings = identity?.let { persistence.currentBindings(employee.organizationId, it, at) }.orEmpty()
            .filter { it.authorityKey in ceiling }.map { binding -> binding.response(
                !binding.canDelegateAdministration || persistence.hasOtherCurrentDelegator(employee.organizationId, binding.authorityKey, binding.id, at),
            ) }
        return EmployeeResponsibilityDelegationResponse(employeeId.toString(), employee.organizationId.toString(), employee.employeeVersion,
            identity?.toString(), true, identity != null && identity != actor && employee.employeeState in assignableStates,
            ceiling.map { it.name }.sorted(), bindings)
    }

    fun grant(command: ResponsibilityDelegationCommand) = execute(command, false)
    fun end(command: ResponsibilityDelegationCommand) = execute(command, true)

    private fun execute(command: ResponsibilityDelegationCommand, ending: Boolean): ResponsibilityDelegationReceipt {
        if (command.authorityKeys.isEmpty() || command.baseEmployeeVersion < 1 ||
            (ending && (command.authorityKeys.size != 1 || command.bindingId == null || (command.baseBindingVersion ?: 0) < 1)) ||
            (!ending && (command.bindingId != null || command.baseBindingVersion != null))) {
            throw ProductApiException("RESPONSIBILITY_DELEGATION_INVALID", "A valid scoped delegation change is required.", HttpStatus.BAD_REQUEST)
        }
        requireAuthority(command)
        if (!ending && command.expectedIdentityId == command.actor) {
            throw ProductApiException("RESPONSIBILITY_GOVERNANCE_SELF_GRANT_DENIED", "Administration authority cannot be self-granted or self-upgraded.", HttpStatus.FORBIDDEN)
        }
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("action", if (ending) "END" else "GRANT")
            put("employeeId", command.employeeId.toString()); put("organizationId", command.organizationId.toString())
            put("identityId", command.expectedIdentityId.toString()); put("employeeVersion", command.baseEmployeeVersion)
            put("keys", command.authorityKeys.map { it.name }.sorted().joinToString(","))
            put("onward", command.canDelegateAdministration); put("bindingId", command.bindingId?.toString())
            command.baseBindingVersion?.let { put("bindingVersion", it) }
        }.toString())
        val resultCode = if (ending) "RESPONSIBILITY_DELEGATION_ENDED" else "RESPONSIBILITY_DELEGATION_GRANTED"
        val result = commands.execute(IdempotentCommandSpec(command.operationId, command.actor,
            if (ending) "RESPONSIBILITY_DELEGATION_END" else "RESPONSIBILITY_DELEGATION_GRANT",
            "EMPLOYEE_ADMINISTRATION", command.employeeId, fingerprint, command.correlationId)) {
            persistence.lockOrganization(command.organizationId)
            persistence.lockEmployee(command.employeeId)
            val employee = requireAuthority(command)
            val at = clock.instant()
            if (employee.employeeVersion != command.baseEmployeeVersion || employee.linkedIdentityId != command.expectedIdentityId) {
                throw conflict("RESPONSIBILITY_EMPLOYEE_CHANGED", "The employee account changed; refresh first.")
            }
            if ((!ending && employee.employeeState !in assignableStates) ||
                !source.isOrganizationMemberCurrent(command.expectedIdentityId, command.organizationId, at)) {
                throw conflict("RESPONSIBILITY_ACCOUNT_NOT_CURRENT", "A current employee account is required.")
            }
            val ids = if (ending) {
                val binding = persistence.binding(requireNotNull(command.bindingId), at)
                if (binding == null || binding.organizationId != command.organizationId || binding.identityId != command.expectedIdentityId ||
                    binding.authorityKey !in command.authorityKeys) throw conflict("RESPONSIBILITY_DELEGATION_UNAVAILABLE", "The exact delegation is unavailable.")
                if (!binding.current || binding.version != command.baseBindingVersion) throw conflict("RESPONSIBILITY_VERSION_CONFLICT", "The delegation changed; refresh first.")
                if (binding.canDelegateAdministration && !persistence.hasOtherCurrentDelegator(command.organizationId, binding.authorityKey, binding.id, at)) {
                    throw conflict("RESPONSIBILITY_DELEGATION_TRANSFER_REQUIRED", "Delegate this administration to another current employee before ending the last delegating administrator.")
                }
                if (!persistence.end(binding, at)) throw conflict("RESPONSIBILITY_VERSION_CONFLICT", "The delegation changed; refresh first.")
                listOf(binding.id)
            } else {
                val existing = persistence.currentBindings(command.organizationId, command.expectedIdentityId, at)
                if (existing.any { it.authorityKey in command.authorityKeys }) throw conflict("RESPONSIBILITY_DELEGATION_EXISTS", "One of these administration responsibilities is already assigned.")
                command.authorityKeys.sortedBy { it.name }.map { key ->
                    UUID.nameUUIDFromBytes("responsibility-delegation:${command.operationId}:${key.name}".toByteArray(StandardCharsets.UTF_8)).also { id ->
                        persistence.grant(id, command.organizationId, command.expectedIdentityId, key,
                            command.canDelegateAdministration, command.actor, command.operationId, at)
                    }
                }
            }
            ids.forEach { id -> audit.append(AuditEventRecord(
                actorUserId = command.actor, action = resultCode, targetType = "RESPONSIBILITY_ADMINISTRATION_BINDING", targetId = id,
                newStateRef = if (ending) "ENDED" else if (command.canDelegateAdministration) "DELEGATING_ADMINISTRATOR" else "RESPONSIBILITY_ADMINISTRATOR",
                safeDiffJson = """{"fields":["employeeId","identityId","authorityKey","canDelegateAdministration","active","version"]}""",
                occurredAt = at, correlationId = command.correlationId,
            )) }
            IdempotentCommandOutcome(resultCode, buildJsonObject { put("bindingIds", buildJsonArray { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) } }) }.toString())
        }
        val ids = Json.parseToJsonElement(requireNotNull(result.outcome.resultPayloadJson)).jsonObject.getValue("bindingIds").jsonArray
        val rows = ids.map { id -> requireNotNull(persistence.binding(UUID.fromString(id.jsonPrimitive.content), clock.instant())).response(false) }
        return ResponsibilityDelegationReceipt(command.operationId.toString(), command.employeeId.toString(), command.organizationId.toString(),
            command.expectedIdentityId.toString(), result.outcome.resultCode, rows, result.replayed)
    }

    private fun requireAuthority(command: ResponsibilityDelegationCommand): ResponsibilityEmployeeContext {
        val employee = target(command.employeeId)
        val ceiling = authority.delegableKeys(command.actor, command.organizationId, clock.instant())
        if (employee.organizationId != command.organizationId || !ceiling.containsAll(command.authorityKeys)) throw denied()
        return employee
    }
    private fun target(id: UUID) = employees.employee(id)
        ?: throw ProductApiException("RESPONSIBILITY_EMPLOYEE_UNAVAILABLE", "The employee is unavailable.", HttpStatus.NOT_FOUND)
    private fun denied() = ProductApiException("RESPONSIBILITY_ADMINISTRATION_DELEGATION_DENIED", "Explicit onward delegation authority is required.", HttpStatus.FORBIDDEN)
    private fun conflict(code: String, message: String) = ProductApiException(code, message, HttpStatus.CONFLICT)
    private fun ResponsibilityDelegationBinding.response(canEnd: Boolean) = ResponsibilityDelegationBindingResponse(
        id.toString(), authorityKey.name, canDelegateAdministration, current, version, canEnd)
    companion object { private val assignableStates = setOf(EmployeeState.ACTIVE, EmployeeState.PREBOARDING) }
}

@Component
class JdbcResponsibilityDelegationPersistence(private val jdbc: JdbcTemplate) : ResponsibilityDelegationPersistencePort {
    override fun lockOrganization(organizationId: UUID) {
        check(jdbc.query("SELECT id FROM organization WHERE id=? AND organization_type='HILTECH' AND status='ACTIVE' FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, organizationId).isNotEmpty())
    }
    override fun lockEmployee(employeeId: UUID) {
        check(jdbc.query("SELECT id FROM employee WHERE id=? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, employeeId).isNotEmpty())
    }
    override fun currentBindings(organizationId: UUID, identityId: UUID, at: Instant) =
        read("b.organization_id=? AND b.administrator_identity_id=?", arrayOf(organizationId, identityId), at).filter { it.current }
    override fun binding(id: UUID, at: Instant) = read("b.id=?", arrayOf(id), at).singleOrNull()
    private fun read(where: String, args: Array<out Any>, at: Instant): List<ResponsibilityDelegationBinding> {
        val now = at.atOffset(ZoneOffset.UTC)
        return jdbc.query("""SELECT b.*,(b.active=true AND b.effective_from<=? AND (b.effective_to IS NULL OR b.effective_to>=?)
            AND o.status='ACTIVE' AND o.organization_type='HILTECH' AND u.status='ACTIVE' AND EXISTS (
              SELECT 1 FROM organization_membership m WHERE m.organization_id=b.organization_id AND m.user_identity_id=b.administrator_identity_id
                AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>=?))) AS authority_current
            FROM responsibility_administration_binding b JOIN organization o ON o.id=b.organization_id
            JOIN user_identity u ON u.id=b.administrator_identity_id WHERE $where ORDER BY b.authority_key,b.id""".trimIndent(),
            { rs, _ -> ResponsibilityDelegationBinding(rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
                rs.getObject("administrator_identity_id", UUID::class.java), CompanyResponsibilityKey.valueOf(rs.getString("authority_key")),
                rs.getBoolean("can_delegate_administration"), rs.getBoolean("authority_current"), rs.getLong("version")) }, now, now, now, now, *args)
    }
    override fun hasOtherCurrentDelegator(organizationId: UUID, key: CompanyResponsibilityKey, exceptId: UUID, at: Instant): Boolean =
        read("b.organization_id=? AND b.authority_key=? AND b.id<>? AND b.can_delegate_administration=true",
            arrayOf<Any>(organizationId, key.name, exceptId), at).any { it.current }
    override fun grant(id: UUID, organizationId: UUID, identityId: UUID, key: CompanyResponsibilityKey, onward: Boolean,
                       actor: UUID, operationId: UUID, at: Instant) {
        jdbc.update("""INSERT INTO responsibility_administration_binding
            (id,organization_id,administrator_identity_id,authority_key,can_delegate_administration,active,effective_from,
             source_approval_ref,created_by_user_id,created_at,version) VALUES (?,?,?,?,?,true,?,?,?,?,1)""".trimIndent(),
            id, organizationId, identityId, key.name, onward, at.atOffset(ZoneOffset.UTC), "DELEGATION_COMMAND:$operationId", actor, at.atOffset(ZoneOffset.UTC))
    }
    override fun end(binding: ResponsibilityDelegationBinding, at: Instant): Boolean = jdbc.update(
        "UPDATE responsibility_administration_binding SET active=false,effective_to=?,version=version+1 WHERE id=? AND version=? AND active=true",
        at.atOffset(ZoneOffset.UTC), binding.id, binding.version) == 1
}

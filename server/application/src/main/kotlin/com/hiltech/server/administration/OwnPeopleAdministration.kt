package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.people.PeopleAuthorizationPort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class ActivateOwnPeopleAdministrationCommand(val operationId: UUID, val organizationId: UUID, val actorUserId: UUID, val correlationId: String)
data class ActivateOwnPeopleAdministrationRequest(val operationId: UUID)
data class OwnPeopleAdministrationReceipt(
    val operationId: String, val organizationId: String, val identityId: String, val authorityKey: String,
    val bindingId: String, val current: Boolean, val accessReady: Boolean, val version: Long, val replayed: Boolean,
)

/** Fixed ordinary role self-assignment. It never creates or extends administration delegation. */
@Component
class OwnPeopleAdministrationService(
    private val authority: ResponsibilityAdministrationAuthorityPort,
    private val source: RoleTeamSourceAuthorityPort,
    private val people: PeopleAuthorizationPort,
    private val persistence: CompanyResponsibilityPersistencePort,
    private val projection: CompanyResponsibilityProjectionPort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun availability(actor: UUID, organizationId: UUID): String {
        if (people.canManagePeople(actor, organizationId)) return "CURRENT"
        val at = clock.instant()
        if (CompanyResponsibilityKey.PEOPLE_ADMIN !in authority.grantableKeys(actor, organizationId, at) ||
            !source.isOrganizationMemberCurrent(actor, organizationId, at)) return "NOT_DELEGATED"
        val assigned = persistence.bindings(organizationId, at).any {
            it.current && it.organizationId == organizationId && it.authorityKey == CompanyResponsibilityKey.PEOPLE_ADMIN &&
                (it.principalType == "USER" && it.principalId == actor ||
                    it.principalType == "TEAM" && source.isTeamMemberCurrent(actor, it.principalId, at))
        }
        return if (assigned) "ASSIGNED" else "AVAILABLE"
    }

    fun activate(command: ActivateOwnPeopleAdministrationCommand): OwnPeopleAdministrationReceipt {
        requireAuthority(command)
        val key = CompanyResponsibilityKey.PEOPLE_ADMIN
        val newBindingId = UUID.nameUUIDFromBytes(("own-people-administration:" + command.operationId).toByteArray(Charsets.UTF_8))
        val fingerprint = IdempotencyKeyContract.fingerprint(buildJsonObject {
            put("organizationId", command.organizationId.toString())
            put("identityId", command.actorUserId.toString())
            put("authorityKey", key.name)
        }.toString())
        val execution = idempotency.execute(IdempotentCommandSpec(command.operationId, command.actorUserId,
            "OWN_PEOPLE_ADMINISTRATION_ACTIVATE", "ORGANIZATION", command.organizationId, fingerprint, command.correlationId)) {
            persistence.lockOrganization(command.organizationId)
            requireAuthority(command)
            val at = clock.instant()
            val existing = persistence.bindings(command.organizationId, at).singleOrNull {
                it.current && it.organizationId == command.organizationId && it.authorityKey == key &&
                    it.principalType == "USER" && it.principalId == command.actorUserId
            }
            val binding = existing ?: run {
                persistence.grant(key, newBindingId, command.organizationId, command.actorUserId,
                    command.actorUserId, command.operationId, at)
                requireNotNull(persistence.binding(key, newBindingId, at))
            }
            projection.changed(binding, at)
            val resultCode = if (existing == null) "OWN_PEOPLE_ADMINISTRATION_ACTIVATED" else "OWN_PEOPLE_ADMINISTRATION_CONFIRMED"
            audit.append(AuditEventRecord(actorUserId = command.actorUserId, action = resultCode,
                targetType = "PEOPLE_AUTHORITY_BINDING", targetId = binding.bindingId, newStateRef = key.name,
                safeDiffJson = """{"fields":["organizationId","identityId","authorityKey","active","version"]}""",
                occurredAt = at, correlationId = command.correlationId))
            IdempotentCommandOutcome(resultCode, buildJsonObject { put("bindingId", binding.bindingId.toString()) }.toString())
        }
        val bindingId = UUID.fromString(Json.parseToJsonElement(requireNotNull(execution.outcome.resultPayloadJson))
            .jsonObject.getValue("bindingId").jsonPrimitive.content)
        val binding = persistence.binding(key, bindingId, clock.instant())?.takeIf {
            it.organizationId == command.organizationId && it.principalType == "USER" && it.principalId == command.actorUserId
        } ?: throw ProductApiException("RESPONSIBILITY_BINDING_UNAVAILABLE", "The recorded People assignment is unavailable.", HttpStatus.CONFLICT)
        return OwnPeopleAdministrationReceipt(command.operationId.toString(), command.organizationId.toString(), command.actorUserId.toString(),
            key.name, bindingId.toString(), binding.current, people.canManagePeople(command.actorUserId, command.organizationId), binding.version, execution.replayed)
    }

    private fun requireAuthority(command: ActivateOwnPeopleAdministrationCommand) {
        val at = clock.instant()
        if (CompanyResponsibilityKey.PEOPLE_ADMIN !in authority.grantableKeys(command.actorUserId, command.organizationId, at) ||
            !source.isOrganizationMemberCurrent(command.actorUserId, command.organizationId, at)) {
            throw ProductApiException("RESPONSIBILITY_ADMINISTRATION_DENIED", "Current explicit People delegation is required.", HttpStatus.FORBIDDEN)
        }
    }
}

@RestController
class OwnPeopleAdministrationController(private val service: OwnPeopleAdministrationService, private val sessions: IdentitySessionService) {
    @PostMapping("/v1/organizations/{organizationId}/administration/own-people-administration")
    fun activate(servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable organizationId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String, @RequestHeader("X-Device-Installation-Id") installationId: UUID,
        @RequestBody request: ActivateOwnPeopleAdministrationRequest): ResponseEntity<OwnPeopleAdministrationReceipt> {
        val context = HiltechRequestContext.current(servletRequest)
        val actor = context.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
        IdempotencyKeyContract.requireMatches(idempotencyKey, request.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.activate(
            ActivateOwnPeopleAdministrationCommand(request.operationId, organizationId, actor, context.correlationId)))
    }
}

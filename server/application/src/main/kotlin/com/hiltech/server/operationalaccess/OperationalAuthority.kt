package com.hiltech.server.operationalaccess

import com.hiltech.server.approval.ApprovalPersistencePort
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class OperationalAuthorityOrigin { OPERATIONAL, LEGACY_APPROVAL_BINDING }
data class OperationalAuthorityProof(val bindingId: UUID, val organizationId: UUID, val authorityKey: String,
    val version: Long, val origin: OperationalAuthorityOrigin)
data class LegacyOperationalBinding(val bindingId: UUID, val organizationId: UUID, val responsibility: OperationalResponsibility,
    val principalType: String, val principalId: UUID, val version: Long)

interface OperationalAuthorityPort {
    fun current(actor: UUID, organizationId: UUID, responsibility: OperationalResponsibility, at: Instant): OperationalAuthorityProof?
}
interface LegacyOperationalAuthorityDirectoryPort {
    /** Read-only legacy sources; only the specific configured operation is implied by each binding. */
    fun bindings(organizationId: UUID, at: Instant): List<LegacyOperationalBinding>
}

/** Either an explicitly assigned operational source or the operation's existing configured source. */
fun currentOperationalAuthority(source: OperationalAuthorityPort?, approvals: ApprovalPersistencePort, actor: UUID,
    organizationId: UUID, property: String, configuredKey: String?, at: Instant): OperationalAuthorityProof? {
    val role = OperationalResponsibility.forProperty(property)
    if (!approvals.isActiveOrganizationMember(organizationId, actor, at)) return null
    source?.current(actor, organizationId, role, at)?.let { proof ->
        if (proof.organizationId == organizationId && proof.authorityKey == role.name && proof.origin == OperationalAuthorityOrigin.OPERATIONAL) return proof
    }
    val legacyKey = OperationalResponsibility.configuredKey(property, configuredKey)
    if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(legacyKey)) return null
    val legacy = approvals.resolveCurrentAuthority(organizationId, legacyKey, at) ?: return null
    if (legacy.organizationId != organizationId || legacy.authorityKey != legacyKey || !approvals.actorMatchesAuthority(actor, legacy, at)) return null
    return OperationalAuthorityProof(legacy.id, organizationId, legacyKey, legacy.version, OperationalAuthorityOrigin.LEGACY_APPROVAL_BINDING)
}

@Component
class JdbcOperationalAuthority(private val jdbc: JdbcTemplate) : OperationalAuthorityPort {
    override fun current(actor: UUID, organizationId: UUID, responsibility: OperationalResponsibility, at: Instant): OperationalAuthorityProof? {
        val now = at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """SELECT b.id,b.organization_id,b.authority_key,b.version
               FROM operational_authority_binding b JOIN organization o ON o.id=b.organization_id
               JOIN user_identity u ON u.id=? AND u.status='ACTIVE'
               WHERE b.organization_id=? AND b.authority_key=? AND b.active=true
                 AND o.status='ACTIVE' AND o.organization_type='HILTECH'
                 AND b.effective_from<=? AND (b.effective_to IS NULL OR b.effective_to>?)
                 AND EXISTS (SELECT 1 FROM organization_membership m WHERE m.organization_id=b.organization_id
                   AND m.user_identity_id=u.id AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?))
                 AND ((b.principal_type='USER' AND b.principal_user_id=u.id) OR (b.principal_type='TEAM' AND EXISTS (
                   SELECT 1 FROM team t JOIN team_membership tm ON tm.team_id=t.id
                   WHERE t.id=b.principal_team_id AND t.organization_id=b.organization_id AND t.active=true
                     AND tm.user_identity_id=u.id AND tm.valid_from<=? AND (tm.valid_until IS NULL OR tm.valid_until>?))))
               ORDER BY CASE WHEN b.principal_type='USER' THEN 0 ELSE 1 END,b.id LIMIT 1""".trimIndent(),
            { rs, _ -> OperationalAuthorityProof(rs.getObject("id", UUID::class.java), rs.getObject("organization_id", UUID::class.java),
                rs.getString("authority_key"), rs.getLong("version"), OperationalAuthorityOrigin.OPERATIONAL) },
            actor, organizationId, responsibility.name, now, now, now, now, now, now,
        ).singleOrNull()
    }
}

@Component
class JdbcLegacyOperationalAuthorityDirectory(private val jdbc: JdbcTemplate, private val environment: Environment) : LegacyOperationalAuthorityDirectoryPort {
    override fun bindings(organizationId: UUID, at: Instant): List<LegacyOperationalBinding> {
        val mapped = OperationalResponsibility.entries.associateWith { role -> role.propertyNames.map {
            OperationalResponsibility.configuredKey(it, environment.getProperty(it))
        }.filter { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }.toSet() }
        val now = at.atOffset(ZoneOffset.UTC)
        val rows = jdbc.query(
            """SELECT b.id,b.organization_id,b.authority_key,b.principal_type,
                 COALESCE(b.principal_user_id,b.principal_team_id) AS principal_id,b.version
               FROM approval_authority_binding b JOIN organization o ON o.id=b.organization_id
               WHERE b.organization_id=? AND b.active=true AND b.effective_from<=?
                 AND (b.effective_to IS NULL OR b.effective_to>?) AND o.status='ACTIVE' AND o.organization_type='HILTECH'""".trimIndent(),
            { rs, _ -> LegacyRow(rs.getObject("id", UUID::class.java), rs.getString("authority_key"), rs.getString("principal_type"),
                rs.getObject("principal_id", UUID::class.java), rs.getLong("version")) }, organizationId, now, now)
        return mapped.flatMap { (role, keys) -> rows.filter { it.key in keys }.map {
            LegacyOperationalBinding(it.id, organizationId, role, it.type, it.principal, it.version)
        } }
    }
    private data class LegacyRow(val id: UUID, val key: String, val type: String, val principal: UUID, val version: Long)
}

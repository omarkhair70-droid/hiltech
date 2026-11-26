package com.hiltech.server.payroll

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * Payroll grants do not follow automatically from People admin, project manager, or Finance.
 * Provisioning these bindings requires real company authority outside this draft feature.
 * A current source membership AND the existing OpenFGA membership must pass.
 */
interface Payroll9aAuthorityPort {
    fun canRead(actorId: UUID, organizationId: UUID): Boolean
    fun canPrepare(actorId: UUID, organizationId: UUID): Boolean
    fun canReview(actorId: UUID, organizationId: UUID): Boolean
}

@Component
class JdbcPayroll9aAuthority(
    private val jdbc: JdbcTemplate,
    private val source: RoleTeamSourceAuthorityPort,
    private val authorizationProvider: ObjectProvider<AuthorizationCheckPort>,
    private val clock: Clock,
) : Payroll9aAuthorityPort {
    override fun canRead(actorId: UUID, organizationId: UUID): Boolean =
        allowed(actorId, organizationId, "READ")

    override fun canPrepare(actorId: UUID, organizationId: UUID): Boolean =
        allowed(actorId, organizationId, "PREPARE")

    override fun canReview(actorId: UUID, organizationId: UUID): Boolean =
        allowed(actorId, organizationId, "REVIEW")

    private fun allowed(
        actorId: UUID,
        organizationId: UUID,
        requested: String,
    ): Boolean {
        val at = clock.instant()
        if (!source.isOrganizationMemberCurrent(actorId, organizationId, at)) return false
        val provider = authorizationProvider.ifAvailable ?: return false
        val member = RoleTeamAuthorizationRelations.organizationMember(actorId, organizationId)
        if (
            !provider.isAllowed(
                AuthorizationCheckRequest(
                    checkTuple = member,
                    failClosedGuardTuples = listOf(member),
                ),
            )
        ) return false

        // No implicit company-owner exception. Zero explicit grants = deny.
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM payroll_authority_binding a
                JOIN organization o ON o.id = a.organization_id
                JOIN user_identity u ON u.id = a.identity_id
                WHERE a.organization_id = ?
                  AND a.identity_id = ?
                  AND a.active = true
                  AND (a.capability = ?
                       OR (? = 'READ' AND a.capability IN ('PREPARE', 'REVIEW')))
                  AND a.valid_from <= ?
                  AND (a.valid_until IS NULL OR a.valid_until >= ?)
                  AND o.status = 'ACTIVE'
                  AND u.status = 'ACTIVE'
            )
            """.trimIndent(),
            Boolean::class.java,
            organizationId,
            actorId,
            requested,
            requested,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) == true
    }
}

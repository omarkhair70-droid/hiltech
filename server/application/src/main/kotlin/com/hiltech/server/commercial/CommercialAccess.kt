package com.hiltech.server.commercial

import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * No implicit SALES access from employment or project membership.
 * Only explicit company-owned, currently valid grants count. This has no grant API.
 */
@Component
class CommercialAccess(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    @Value("\${hiltech.commercial.enabled:false}")
    private val enabled: Boolean,
) {
    fun require(actor: UUID, company: UUID, vararg permissions: String) {
        if (!enabled) {
            throw ProductApiException(
                code = "COMMERCIAL_DISABLED",
                message = "Commercial operations are not enabled.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
        if (permissions.none { hasPermission(actor, company, it) }) {
            throw ProductApiException(
                code = "COMMERCIAL_NOT_AUTHORIZED",
                message = "Commercial record is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }
    }

    fun capabilities(actor: UUID, company: UUID): Map<String, Boolean> {
        require(actor, company, "SALES_VIEW", "SALES_WRITE", "SALES_RELEASE", "SALES_AWARD")
        return mapOf(
            "canWrite" to hasPermission(actor, company, "SALES_WRITE"),
            "canCost" to hasPermission(actor, company, "SALES_COST_VIEW"),
            "canRelease" to hasPermission(actor, company, "SALES_RELEASE"),
            "canAward" to hasPermission(actor, company, "SALES_AWARD"),
        )
    }

    private fun hasPermission(actor: UUID, company: UUID, permission: String): Boolean {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM organization o
              JOIN organization_membership m ON m.organization_id=o.id
              JOIN user_identity u ON u.id=m.user_identity_id
              JOIN commercial_authority_binding b ON b.organization_id=o.id
              WHERE o.id=? AND o.organization_type='HILTECH' AND o.status='ACTIVE'
                AND u.id=? AND u.status='ACTIVE'
                AND m.state='ACTIVE' AND m.valid_from <= ?
                AND (m.valid_until IS NULL OR m.valid_until >= ?)
                AND b.authority_key=? AND b.active=true
                AND b.effective_from <= ?
                AND (b.effective_until IS NULL OR b.effective_until >= ?)
                AND (
                  (b.principal_type='USER' AND b.principal_user_id=u.id)
                  OR (b.principal_type='TEAM' AND EXISTS (
                    SELECT 1 FROM team t
                    JOIN team_membership tm ON tm.team_id=t.id
                    WHERE t.id=b.principal_team_id
                      AND t.organization_id=o.id AND t.active=true
                      AND tm.user_identity_id=u.id
                      AND tm.valid_from <= ?
                      AND (tm.valid_until IS NULL OR tm.valid_until >= ?)
                  ))
                )
            )
            """.trimIndent(),
            Boolean::class.java,
            company, actor, now, now, permission, now, now, now, now,
        ) == true
    }
}

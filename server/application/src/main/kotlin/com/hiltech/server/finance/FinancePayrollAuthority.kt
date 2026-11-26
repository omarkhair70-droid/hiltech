package com.hiltech.server.finance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Operational responsibilities; independent result approval additionally uses ApprovalEngine. */
interface FinancePayrollAuthorityPort {
    fun canPrepare(actor: UUID, organizationId: UUID): Boolean
    fun canReview(actor: UUID, organizationId: UUID): Boolean
}

@Component
class CurrentFinancePayrollAuthority(private val jdbc: JdbcTemplate, private val clock: Clock) : FinancePayrollAuthorityPort {
    override fun canPrepare(actor: UUID, organizationId: UUID): Boolean = has(actor, organizationId, "PREPARE")
    override fun canReview(actor: UUID, organizationId: UUID): Boolean = has(actor, organizationId, "REVIEW")

    private fun has(actor: UUID, org: UUID, key: String): Boolean {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """SELECT EXISTS (
                SELECT 1 FROM finance_payroll_authority_binding b
                JOIN organization o ON o.id=b.organization_id
                JOIN user_identity u ON u.id=b.identity_id
                WHERE b.organization_id=? AND b.identity_id=? AND b.authority_key=?
                  AND b.active AND b.effective_from<=? AND (b.effective_to IS NULL OR b.effective_to>?)
                  AND o.organization_type='HILTECH' AND o.status='ACTIVE' AND u.status='ACTIVE'
                  AND EXISTS (SELECT 1 FROM organization_membership m
                    WHERE m.organization_id=b.organization_id AND m.user_identity_id=b.identity_id
                      AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?))
            )""", Boolean::class.java, org, actor, key, at, at, at, at,
        ) == true
    }
}

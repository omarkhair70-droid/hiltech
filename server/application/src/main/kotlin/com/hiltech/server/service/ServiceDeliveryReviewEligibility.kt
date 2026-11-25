package com.hiltech.server.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Eligibility to be named by policy administration; it grants no project or document access. */
fun interface ServiceDeliveryReviewEligibilityPort {
    fun isEligible(actor: UUID, organizationId: UUID): Boolean
}

@Component
class CurrentServiceDeliveryReviewEligibility(private val jdbc: JdbcTemplate, private val clock: Clock) : ServiceDeliveryReviewEligibilityPort {
    override fun isEligible(actor: UUID, organizationId: UUID): Boolean {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """SELECT EXISTS (
                SELECT 1 FROM user_identity u JOIN organization_membership m ON m.user_identity_id=u.id
                JOIN organization o ON o.id=m.organization_id
                JOIN employee e ON e.person_id=u.person_id AND e.organization_id=o.id
                WHERE u.id=? AND o.id=? AND u.status='ACTIVE'
                  AND o.organization_type='HILTECH' AND o.status='ACTIVE'
                  AND e.state='ACTIVE' AND m.state='ACTIVE' AND m.valid_from<=?
                  AND (m.valid_until IS NULL OR m.valid_until>?)
            )""", Boolean::class.java, actor, organizationId, at, at,
        ) == true
    }
}

package com.hiltech.server.procurement

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

internal const val PROCUREMENT_PO_REVIEW_POLICY = "PROCUREMENT_PO_REVIEW"
internal const val PROCUREMENT_PO_REVIEWER = "PROCUREMENT_PO_REVIEWER"
internal fun procurementReviewPolicyKey(configured: String): String = configured.trim().ifEmpty { PROCUREMENT_PO_REVIEW_POLICY }

@Component
class ProcurementReviewPolicyConfiguration(
    @Value("\${hiltech.procurement.approval-policy-key:PROCUREMENT_PO_REVIEW}") configuredKey: String,
) {
    val key = procurementReviewPolicyKey(configuredKey)
}

/** Eligibility for a named review assignment. It grants no buying, issuing or broad price access. */
fun interface ProcurementCandidateReviewEligibilityPort {
    fun isEligible(actor: UUID, organizationId: UUID): Boolean
}

@Component
class CurrentProcurementCandidateReviewEligibility(private val jdbc: JdbcTemplate, private val clock: Clock) : ProcurementCandidateReviewEligibilityPort {
    override fun isEligible(actor: UUID, organizationId: UUID): Boolean {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        return jdbc.queryForObject(
            """SELECT EXISTS (
                 SELECT 1 FROM user_identity u JOIN organization_membership m ON m.user_identity_id=u.id
                 JOIN organization o ON o.id=m.organization_id
                 JOIN employee e ON e.organization_id=o.id AND e.person_id=u.person_id
                 WHERE u.id=? AND o.id=? AND u.status='ACTIVE' AND e.state='ACTIVE'
                   AND o.status='ACTIVE' AND o.organization_type='HILTECH'
                   AND m.state='ACTIVE' AND m.valid_from<=? AND (m.valid_until IS NULL OR m.valid_until>?)
               )""".trimIndent(), Boolean::class.java, actor, organizationId, at, at,
        ) == true
    }
}

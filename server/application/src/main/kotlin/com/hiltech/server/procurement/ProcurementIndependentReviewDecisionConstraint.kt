package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalDecisionConstraint
import com.hiltech.server.approval.ApprovalDecisionContext
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.CompanyReviewPolicyKeys
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Source currentness remains in each source guard; even the generic decision route must preserve independence. */
@Component
class ProcurementIndependentReviewDecisionConstraint(
    private val jdbc: JdbcTemplate,
    private val policies: ApprovalPersistencePort,
    private val eligibility: ProcurementCandidateReviewEligibilityPort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalDecisionConstraint {
    override fun supports(subjectType: String): Boolean = subjectType in properties

    override fun canDecide(actor: UUID, context: ApprovalDecisionContext, decision: ApprovalDecisionType): Boolean {
        val property = properties[context.subjectType] ?: return false
        if (context.subjectVersion != 1L || actor == context.requesterUserId ||
            !eligibility.isEligible(actor, context.organizationId)) return false
        val key = CompanyReviewPolicyKeys.resolve(environment, property)
        if (context.policyKey != key) return false
        val policy = policies.loadActivePolicy(context.organizationId, key, clock.instant()) ?: return false
        if (policy.versionNumber != context.policyVersion || policy.authorityKey != context.authorityKey ||
            !policy.approvalRequired || policy.mode != ApprovalPolicyMode.SINGLE) return false
        val sql = when (context.subjectType) {
            "PROCUREMENT_PO_DELIVERY_AMENDMENT" ->
                "SELECT proposed_by_user_id FROM procurement_po_delivery_amendment_proposal WHERE id=? AND organization_id=?"
            "SERVICE_ORDER_CANDIDATE" ->
                "SELECT created_by_user_id FROM procurement_service_order_candidate WHERE id=? AND organization_id=?"
            else -> return false
        }
        val maker = jdbc.query(sql, { rs, _ -> rs.getObject(1, UUID::class.java) }, context.subjectId, context.organizationId).singleOrNull()
            ?: return false
        return actor != maker
    }

    private companion object {
        val properties = mapOf(
            "PROCUREMENT_PO_DELIVERY_AMENDMENT" to "hiltech.procurement.po.delivery-amendment-policy-key",
            "SERVICE_ORDER_CANDIDATE" to "hiltech.procurement.service-orders.approval-policy-key",
        )
    }
}

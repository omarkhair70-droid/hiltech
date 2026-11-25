package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalDecisionConstraint
import com.hiltech.server.approval.ApprovalDecisionContext
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/** Generic approval requests cannot bypass payroll's independent maker/reviewer contract. */
@Component
class PayrollReviewDecisionConstraint(
    private val jdbc: JdbcTemplate,
    private val authority: Payroll9aAuthorityPort,
    private val policies: ApprovalPersistencePort,
    private val runPolicy: PayrollRunPolicyConfiguration,
    private val clock: Clock,
) : ApprovalDecisionConstraint {
    override fun supports(subjectType: String): Boolean = subjectType in setOf(PayrollSourcePolicy.SUBJECT_TYPE, "PAYROLL_RUN_VERSION")
    override fun canDecide(actor: UUID, context: ApprovalDecisionContext, decision: ApprovalDecisionType): Boolean {
        if (!supports(context.subjectType) || context.subjectVersion != 1L || actor == context.requesterUserId ||
            !authority.canReview(actor, context.organizationId)) return false
        val source = context.subjectType == PayrollSourcePolicy.SUBJECT_TYPE
        val key = if (source) PayrollSourcePolicy.REVIEW_POLICY else runPolicy.key
        if (context.policyKey != key) return false
        val policy = policies.loadActivePolicy(context.organizationId, key, clock.instant()) ?: return false
        if (!policy.approvalRequired || policy.mode != ApprovalPolicyMode.SINGLE || policy.versionNumber != context.policyVersion ||
            policy.authorityKey != context.authorityKey || (source && (!policy.reasonRequired || policy.authorityKey != "PAYROLL_SOURCE_REVIEWER"))) return false
        val maker = jdbc.query(
            if (source) "SELECT prepared_by_user_id FROM payroll_source_record WHERE id=? AND organization_id=?"
            else "SELECT created_by_user_id FROM payroll_run_version WHERE id=? AND organization_id=?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, context.subjectId, context.organizationId,
        ).singleOrNull() ?: return false
        return actor != maker
    }
}

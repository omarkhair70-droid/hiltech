package com.hiltech.server.administration

import com.hiltech.server.approval.ApprovalPolicyMaintenanceAuthorityPort
import com.hiltech.server.approval.ApprovalPolicyMaintenanceScope
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CompanyReviewPolicyMaintenanceAuthority(
    private val authority: OrganizationAdministrationAuthorityPort,
    private val payroll: PayrollRunPolicyConfiguration,
    private val procurement: ProcurementReviewPolicyConfiguration,
) : ApprovalPolicyMaintenanceAuthorityPort {
    override fun canSupersedePending(actor: UUID, scope: ApprovalPolicyMaintenanceScope): Boolean {
        val supported = CompanyReviewWorkflow.entries.any { workflow ->
            val key = when (workflow) {
                CompanyReviewWorkflow.PAYROLL_RUN -> payroll.key
                CompanyReviewWorkflow.PROCUREMENT_PO -> procurement.key
                else -> workflow.policyKey
            }
            workflow.subjectType == scope.subjectType && key == scope.policyKey
        }
        return supported && authority.canManageApprovalPolicies(actor, scope.organizationId)
    }
}

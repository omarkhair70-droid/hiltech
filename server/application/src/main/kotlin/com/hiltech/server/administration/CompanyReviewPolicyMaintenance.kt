package com.hiltech.server.administration

import com.hiltech.server.approval.ApprovalPolicyMaintenanceAuthorityPort
import com.hiltech.server.approval.ApprovalPolicyMaintenanceScope
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@Component
class CompanyReviewPolicyMaintenanceAuthority @Autowired constructor(
    private val authority: OrganizationAdministrationAuthorityPort,
    private val payroll: PayrollRunPolicyConfiguration,
    private val procurement: ProcurementReviewPolicyConfiguration,
    workflowConfiguration: CompanyReviewWorkflowConfiguration? = null,
) : ApprovalPolicyMaintenanceAuthorityPort {
    private val workflows = workflowConfiguration ?: CompanyReviewWorkflowConfiguration(null, payroll, procurement)
    override fun canSupersedePending(actor: UUID, scope: ApprovalPolicyMaintenanceScope): Boolean {
        val supported = CompanyReviewWorkflow.entries.any { workflow ->
            workflow.subjectType == scope.subjectType && workflows.key(workflow) == scope.policyKey
        }
        return supported && authority.canManageApprovalPolicies(actor, scope.organizationId)
    }
}

package com.hiltech.server.administration

import java.time.Instant
import java.util.UUID

/** Fixed workflow catalog: API callers cannot supply policy modes, authority keys, or source SQL. */
enum class CompanyReviewWorkflow(
    val policyKey: String, val authorityKey: String, val subjectType: String,
    val reviewerTable: String? = null, val reviewerKeyColumn: String? = null,
    val reviewerFromColumn: String? = null, val reviewerToColumn: String? = null,
) {
    PAYROLL_SOURCE("PAYROLL_SOURCE_REVIEW", "PAYROLL_SOURCE_REVIEWER", "PAYROLL_SOURCE_RECORD",
        "payroll_authority_binding", "capability", "valid_from", "valid_until"),
    FINANCE_PAYROLL_RESULT("FINANCE_PAYROLL_RESULT_REVIEW", "FINANCE_PAYROLL_RESULT_REVIEWER", "FINANCE_PAYROLL_RESULT",
        "finance_payroll_authority_binding", "authority_key", "effective_from", "effective_to"),
    PAYROLL_RUN("PAYROLL_RUN_REVIEW", "PAYROLL_RUN_REVIEWER", "PAYROLL_RUN_VERSION",
        "payroll_authority_binding", "capability", "valid_from", "valid_until"),
    PROCUREMENT_PO("PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER", "PROCUREMENT_PO_CANDIDATE"),
    SERVICE_HANDOVER("SERVICE_HANDOVER_REVIEW", "SERVICE_HANDOVER_REVIEWER", "SERVICE_HANDOVER_ACTION"),
    SERVICE_WARRANTY("SERVICE_WARRANTY_REVIEW", "SERVICE_WARRANTY_REVIEWER", "SERVICE_WARRANTY_ACTION"),
}

interface CompanyReviewCandidateAuthorityPort {
    fun canReview(workflow: CompanyReviewWorkflow, actor: UUID, organizationId: UUID): Boolean
}

data class PayrollReviewPolicyRecord(
    val id: UUID, val revision: Int, val version: Long, val contractMatches: Boolean, val current: Boolean,
    val authorityKey: String? = null,
)
data class PayrollReviewerBindingRecord(
    val id: UUID, val version: Long, val principalType: String, val principalId: UUID,
    val displayName: String?, val employeeId: UUID?, val current: Boolean,
)
data class PayrollReviewSetupRecord(
    val policy: PayrollReviewPolicyRecord?, val reviewer: PayrollReviewerBindingRecord?, val pendingReviews: Int,
    val pendingPolicies: List<CompanyPendingReviewPolicy> = emptyList(),
)
data class CompanyPendingReviewPolicy(val policyId: String, val policyRevision: Int, val policyVersion: Long, val pendingReviews: Int)
data class PayrollReviewerCandidate(
    val employeeId: UUID, val identityId: UUID, val employeeVersion: Long, val displayName: String, val employeeCode: String,
)
data class PayrollReviewPolicyInstallation(val policyId: UUID, val policyRevision: Int, val bindingId: UUID)

interface PayrollReviewPolicyPersistencePort {
    fun lockOrganization(organizationId: UUID)
    fun lockEmployee(employeeId: UUID)
    fun current(organizationId: UUID, workflow: CompanyReviewWorkflow, at: Instant): PayrollReviewSetupRecord
    fun candidates(organizationId: UUID, workflow: CompanyReviewWorkflow, query: String, at: Instant, limit: Int): List<PayrollReviewerCandidate>
    fun candidate(organizationId: UUID, workflow: CompanyReviewWorkflow, employeeId: UUID, identityId: UUID, at: Instant): PayrollReviewerCandidate?
    fun reviewerIdentities(organizationId: UUID, workflow: CompanyReviewWorkflow, reviewer: PayrollReviewerBindingRecord, at: Instant): List<UUID>
    fun replace(organizationId: UUID, workflow: CompanyReviewWorkflow, previous: PayrollReviewSetupRecord, reviewer: PayrollReviewerCandidate,
                actor: UUID, operationId: UUID, at: Instant): PayrollReviewPolicyInstallation
}

data class PayrollReviewSetupResponse(
    val organizationId: String, val status: String, val policyId: String?, val policyRevision: Int?, val policyVersion: Long?,
    val bindingId: String?, val bindingVersion: Long?, val reviewerPrincipalType: String?, val reviewerDisplayName: String?,
    val reviewerEmployeeId: String?, val pendingReviews: Int, val asOf: String, val workflow: String,
    val canConfigure: Boolean = true,
    val pendingPolicies: List<CompanyPendingReviewPolicy> = emptyList(),
)
data class PayrollReviewerCandidateResponse(
    val employeeId: String, val identityId: String, val employeeVersion: Long, val displayName: String, val employeeCode: String,
)
data class PayrollReviewerCandidatesResponse(val items: List<PayrollReviewerCandidateResponse>, val hasMore: Boolean)
data class ConfigurePayrollReviewPolicyCommand(
    val operationId: UUID, val organizationId: UUID, val reviewerEmployeeId: UUID, val expectedReviewerIdentityId: UUID,
    val baseEmployeeVersion: Long, val expectedPolicyId: UUID?, val expectedPolicyVersion: Long?,
    val expectedBindingId: UUID?, val expectedBindingVersion: Long?, val actorUserId: UUID, val correlationId: String,
    val workflow: CompanyReviewWorkflow,
    val pendingPoliciesToSupersede: List<CompanyPendingReviewPolicy> = emptyList(),
    val supersedeReason: String? = null,
)
data class ConfigurePayrollReviewPolicyResponse(
    val operationId: String, val organizationId: String, val policyId: String, val policyRevision: Int,
    val bindingId: String, val current: Boolean, val replayed: Boolean,
    val workflow: String,
)

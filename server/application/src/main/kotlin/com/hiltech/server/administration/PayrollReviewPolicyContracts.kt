package com.hiltech.server.administration

import java.time.Instant
import java.util.UUID

/** Fixed workflow catalog: API callers cannot supply policy modes, authority keys, or source SQL. */
enum class CompanyReviewWorkflow(
    val policyKey: String, val authorityKey: String, val subjectType: String,
    val reviewerTable: String? = null, val reviewerKeyColumn: String? = null,
    val reviewerFromColumn: String? = null, val reviewerToColumn: String? = null,
    val configurationProperty: String? = null,
) {
    PAYROLL_SOURCE("PAYROLL_SOURCE_REVIEW", "PAYROLL_SOURCE_REVIEWER", "PAYROLL_SOURCE_RECORD",
        "payroll_authority_binding", "capability", "valid_from", "valid_until"),
    FINANCE_PAYROLL_RESULT("FINANCE_PAYROLL_RESULT_REVIEW", "FINANCE_PAYROLL_RESULT_REVIEWER", "FINANCE_PAYROLL_RESULT",
        "finance_payroll_authority_binding", "authority_key", "effective_from", "effective_to"),
    PAYROLL_RUN("PAYROLL_RUN_REVIEW", "PAYROLL_RUN_REVIEWER", "PAYROLL_RUN_VERSION",
        "payroll_authority_binding", "capability", "valid_from", "valid_until", "hiltech.payroll.run-approval-policy-key"),
    PROCUREMENT_PO("PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER", "PROCUREMENT_PO_CANDIDATE",
        configurationProperty = "hiltech.procurement.approval-policy-key"),
    SERVICE_HANDOVER("SERVICE_HANDOVER_REVIEW", "SERVICE_HANDOVER_REVIEWER", "SERVICE_HANDOVER_ACTION"),
    SERVICE_WARRANTY("SERVICE_WARRANTY_REVIEW", "SERVICE_WARRANTY_REVIEWER", "SERVICE_WARRANTY_ACTION"),
    PROCUREMENT_PO_DELIVERY("PROCUREMENT_PO_DELIVERY_REVIEW", "PROCUREMENT_PO_DELIVERY_REVIEWER", "PROCUREMENT_PO_DELIVERY_AMENDMENT",
        configurationProperty = "hiltech.procurement.po.delivery-amendment-policy-key"),
    PROCUREMENT_SERVICE_ORDER("PROCUREMENT_SERVICE_ORDER_REVIEW", "PROCUREMENT_SERVICE_ORDER_REVIEWER", "SERVICE_ORDER_CANDIDATE",
        configurationProperty = "hiltech.procurement.service-orders.approval-policy-key"),
    FINANCE_EMPLOYEE_ADVANCE("FINANCE_EMPLOYEE_ADVANCE_REVIEW", "FINANCE_EMPLOYEE_ADVANCE_REVIEWER", "FINANCE_EMPLOYEE_ADVANCE_POLICY_REVIEW",
        configurationProperty = "hiltech.finance.advances.policy-review.policy-key"),
    FINANCE_IMPREST("FINANCE_IMPREST_REVIEW", "FINANCE_IMPREST_REVIEWER", "FINANCE_FINANCIAL_IMPREST_POLICY_REVIEW",
        configurationProperty = "hiltech.finance.imprests.policy-review.policy-key"),
    FINANCE_EXPENSE_DOCUMENTATION("FINANCE_EXPENSE_DOCUMENTATION_REVIEW", "FINANCE_EXPENSE_DOCUMENTATION_REVIEWER", "FINANCE_EXPENSE_DOCUMENTATION_REVIEW",
        configurationProperty = "hiltech.finance.expenses.documentation-review.policy-key"),
    FINANCE_SUPPLIER_ACCOUNTING("FINANCE_SUPPLIER_ACCOUNTING_REVIEW", "FINANCE_SUPPLIER_ACCOUNTING_REVIEWER", "FINANCE_SUPPLIER_ACCOUNTING_REVIEW",
        configurationProperty = "hiltech.finance.supplier-accounting-review.policy-key"),
    FINANCE_AP_POSTING("FINANCE_AP_POSTING_REVIEW", "FINANCE_AP_POSTING_REVIEWER", "FINANCE_AP_JOURNAL_POSTING",
        configurationProperty = "hiltech.finance.ap-posting.policy-key"),
    FINANCE_AP_REVERSAL("FINANCE_AP_REVERSAL_REVIEW", "FINANCE_AP_REVERSAL_REVIEWER", "FINANCE_AP_JOURNAL_REVERSAL",
        configurationProperty = "hiltech.finance.ap-reversal.policy-key"),
    FINANCE_AR_INVOICE("FINANCE_AR_INVOICE_REVIEW", "FINANCE_AR_INVOICE_REVIEWER", "FINANCE_AR_INVOICE_ISSUANCE",
        configurationProperty = "hiltech.finance.receivables.invoice-issuance.policy-key"),
    FINANCE_AR_POSTING("FINANCE_AR_POSTING_REVIEW", "FINANCE_AR_POSTING_REVIEWER", "FINANCE_AR_JOURNAL_POSTING",
        configurationProperty = "hiltech.finance.receivables.ar-posting.policy-key"),
    FINANCE_EMPLOYEE_FUNDING("FINANCE_EMPLOYEE_FUNDING_REVIEW", "FINANCE_EMPLOYEE_FUNDING_REVIEWER", "FINANCE_EMPLOYEE_FUNDING",
        configurationProperty = "hiltech.finance.employee-funding.policy-key"),
    FINANCE_EMPLOYEE_SETTLEMENT("FINANCE_EMPLOYEE_SETTLEMENT_REVIEW", "FINANCE_EMPLOYEE_SETTLEMENT_REVIEWER", "FINANCE_EMPLOYEE_SETTLEMENT",
        configurationProperty = "hiltech.finance.employee-settlement.policy-key"),
    FINANCE_EXPENSE_REIMBURSEMENT("FINANCE_EXPENSE_REIMBURSEMENT_REVIEW", "FINANCE_EXPENSE_REIMBURSEMENT_REVIEWER", "FINANCE_EXPENSE_REIMBURSEMENT",
        configurationProperty = "hiltech.finance.expense-reimbursement.policy-key"),
    FINANCE_BANK_STATEMENT("FINANCE_BANK_STATEMENT_REVIEW", "FINANCE_BANK_STATEMENT_REVIEWER", "FINANCE_BANK_STATEMENT_INTAKE",
        configurationProperty = "hiltech.finance.bank-statement-intake.policy-key"),
    FINANCE_BANK_RECONCILIATION("FINANCE_BANK_RECONCILIATION_REVIEW", "FINANCE_BANK_RECONCILIATION_REVIEWER", "FINANCE_BANK_RECONCILIATION",
        configurationProperty = "hiltech.finance.bank-reconciliation.signoff-policy-key"),
    FINANCE_CASHBOX_OPENING("FINANCE_CASHBOX_OPENING_REVIEW", "FINANCE_CASHBOX_OPENING_REVIEWER", "FINANCE_CASHBOX_OPENING",
        configurationProperty = "hiltech.finance.cashbox.opening-policy-key"),
    FINANCE_CASH_VOUCHER("FINANCE_CASH_VOUCHER_REVIEW", "FINANCE_CASH_VOUCHER_REVIEWER", "FINANCE_CASH_VOUCHER",
        configurationProperty = "hiltech.finance.cashbox.voucher-policy-key"),
    FINANCE_CASH_COUNT("FINANCE_CASH_COUNT_REVIEW", "FINANCE_CASH_COUNT_REVIEWER", "FINANCE_CASHBOX_COUNT",
        configurationProperty = "hiltech.finance.cashbox.count-policy-key"),
    FINANCE_CASH_ALLOCATION("FINANCE_CASH_ALLOCATION_REVIEW", "FINANCE_CASH_ALLOCATION_REVIEWER", "FINANCE_AP_AR_CASH_ALLOCATION",
        configurationProperty = "hiltech.finance.cash-allocation.policy-key"),
    FINANCE_CASH_ALLOCATION_VOID("FINANCE_CASH_ALLOCATION_VOID_REVIEW", "FINANCE_CASH_ALLOCATION_VOID_REVIEWER", "FINANCE_CASH_ALLOCATION_VOID",
        configurationProperty = "hiltech.finance.cash-allocation-void.policy-key"),
    FINANCE_GL_PERIOD_CLOSE("FINANCE_GL_PERIOD_CLOSE_REVIEW", "FINANCE_GL_PERIOD_CLOSE_REVIEWER", "FINANCE_GL_PERIOD_CLOSE",
        configurationProperty = "hiltech.finance.gl-period-close.policy-key"),
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
    val affectedWorkflows: List<CompanyReviewWorkflow> = emptyList(),
    val configurationBlockedReason: String? = null,
)
data class CompanyPendingReviewPolicy(
    val policyId: String, val policyRevision: Int, val policyVersion: Long, val pendingReviews: Int,
    val workflow: String? = null,
)
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
    val affectedWorkflows: List<String> = emptyList(),
    val replacesLegacyAuthority: Boolean = false,
    val configurationBlockedReason: String? = null,
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
    val affectedWorkflowsToConfirm: List<String> = emptyList(),
    val legacyAuthorityReplacementConfirmed: Boolean = false,
)
data class ConfigurePayrollReviewPolicyResponse(
    val operationId: String, val organizationId: String, val policyId: String, val policyRevision: Int,
    val bindingId: String, val current: Boolean, val replayed: Boolean,
    val workflow: String,
)

package com.hiltech.server.approval

import org.springframework.core.env.Environment

/**
 * Source-owned review routes with stable defaults. A key only identifies the
 * workflow: it does not create a policy, appoint a reviewer, or waive a source
 * check. Explicit nonblank installed keys continue to resolve exactly as before.
 */
object CompanyReviewPolicyKeys {
    val defaults: Map<String, String> = linkedMapOf(
        "hiltech.payroll.run-approval-policy-key" to "PAYROLL_RUN_REVIEW",
        "hiltech.procurement.approval-policy-key" to "PROCUREMENT_PO_REVIEW",
        "hiltech.procurement.po.delivery-amendment-policy-key" to "PROCUREMENT_PO_DELIVERY_REVIEW",
        "hiltech.procurement.service-orders.approval-policy-key" to "PROCUREMENT_SERVICE_ORDER_REVIEW",
        "hiltech.finance.advances.policy-review.policy-key" to "FINANCE_EMPLOYEE_ADVANCE_REVIEW",
        "hiltech.finance.imprests.policy-review.policy-key" to "FINANCE_IMPREST_REVIEW",
        "hiltech.finance.expenses.documentation-review.policy-key" to "FINANCE_EXPENSE_DOCUMENTATION_REVIEW",
        "hiltech.finance.supplier-accounting-review.policy-key" to "FINANCE_SUPPLIER_ACCOUNTING_REVIEW",
        "hiltech.finance.ap-posting.policy-key" to "FINANCE_AP_POSTING_REVIEW",
        "hiltech.finance.ap-reversal.policy-key" to "FINANCE_AP_REVERSAL_REVIEW",
        "hiltech.finance.receivables.invoice-issuance.policy-key" to "FINANCE_AR_INVOICE_REVIEW",
        "hiltech.finance.receivables.ar-posting.policy-key" to "FINANCE_AR_POSTING_REVIEW",
        "hiltech.finance.employee-funding.policy-key" to "FINANCE_EMPLOYEE_FUNDING_REVIEW",
        "hiltech.finance.employee-settlement.policy-key" to "FINANCE_EMPLOYEE_SETTLEMENT_REVIEW",
        "hiltech.finance.expense-reimbursement.policy-key" to "FINANCE_EXPENSE_REIMBURSEMENT_REVIEW",
        "hiltech.finance.bank-statement-intake.policy-key" to "FINANCE_BANK_STATEMENT_REVIEW",
        "hiltech.finance.bank-reconciliation.signoff-policy-key" to "FINANCE_BANK_RECONCILIATION_REVIEW",
        "hiltech.finance.cashbox.opening-policy-key" to "FINANCE_CASHBOX_OPENING_REVIEW",
        "hiltech.finance.cashbox.voucher-policy-key" to "FINANCE_CASH_VOUCHER_REVIEW",
        "hiltech.finance.cashbox.count-policy-key" to "FINANCE_CASH_COUNT_REVIEW",
        "hiltech.finance.cash-allocation.policy-key" to "FINANCE_CASH_ALLOCATION_REVIEW",
        "hiltech.finance.cash-allocation-void.policy-key" to "FINANCE_CASH_ALLOCATION_VOID_REVIEW",
        "hiltech.finance.gl-period-close.policy-key" to "FINANCE_GL_PERIOD_CLOSE_REVIEW",
    )

    fun resolve(environment: Environment, property: String): String = resolve(environment.getProperty(property), property)

    fun resolve(configured: String?, property: String): String {
        val default = defaults.getValue(property)
        return configured?.trim().takeUnless { it.isNullOrEmpty() } ?: default
    }
}

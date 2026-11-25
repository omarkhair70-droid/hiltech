package com.hiltech.shared.core.identity

/** Server availability of implemented routes; this never grants permission to read or act. */
enum class ProductOperation(val key: String) {
    COMMERCIAL("commercial"),
    EXECUTIVE_HOME("executive.home"),
    FIELD_PERSONAL_ISSUED_STOCK("field.personal-issued-stock"),
    FIELD_MATERIAL_USAGE_REPORT("field.material-usage-report"),
    FIELD_MATERIAL_USAGE_REVIEW("field.material-usage-review"),
    FIELD_MATERIAL_USAGE_RECONCILIATION("field.material-usage-reconciliation"),
    FIELD_ASSIGNED_ASSET_LOOKUP("field.assigned-asset-lookup"),
    FIELD_ENGINEERING_TESTS("field.engineering-tests"),
    FIELD_WORK_DOCUMENTS("field.work-documents"),
    FIELD_COMPLETION("field.completion"),
    PROCUREMENT_REQUIREMENTS("procurement.requirements"),
    PROCUREMENT_WAREHOUSE_REPLENISHMENT("procurement.warehouse-replenishment"),
    PROCUREMENT_OFFICE_STOCK("procurement.office-stock"),
    PROCUREMENT_ASSET_PARTS("procurement.asset-parts"),
    PROCUREMENT_SOURCING("procurement.sourcing"),
    PROCUREMENT_PO_CANDIDATES("procurement.po-candidates"),
    PROCUREMENT_APPROVAL_REQUEST("procurement.approval-request"),
    PROCUREMENT_PO_ISSUE("procurement.po-issue"),
    PROCUREMENT_PO_RECEIVING("procurement.po-receiving"),
    PROCUREMENT_SUPPLIER_INVOICES("procurement.supplier-invoices"),
    PROCUREMENT_PO_CANCELLATION("procurement.po-cancellation"),
    PROCUREMENT_PO_DELIVERY_AMENDMENTS("procurement.po.delivery-amendments"),
    PROCUREMENT_PO_COMMERCIAL_REPLACEMENT("procurement.po.commercial-replacement"),
    PROCUREMENT_PO_SUPPLIER_COMMUNICATIONS("procurement.po.supplier-communications"),
    PROCUREMENT_SERVICE_NEEDS("procurement.service-needs"),
    PROCUREMENT_SERVICE_SOURCING("procurement.service-sourcing"),
    PROCUREMENT_SERVICE_ORDERS("procurement.service-orders"),
    PROCUREMENT_SERVICE_ACCEPTANCE("procurement.service-acceptance"),
    PROCUREMENT_SERVICE_INVOICES("procurement.service-invoices"),
    PROCUREMENT_SERVICE_SUPPLIER_COMMUNICATIONS("procurement.service-supplier-communications"),
    PROCUREMENT_SUPPLIER_MASTER_DATA("procurement.supplier-master-data"),
    FINANCE_EMPLOYEE_REQUESTS("finance.employee-requests"),
    FINANCE_EMPLOYEE_REVIEW("finance.employee-review"),
    FINANCE_ADVANCES_POLICY_REVIEW("finance.advances.policy-review"),
    FINANCE_IMPRESTS_POLICY_REVIEW("finance.imprests.policy-review"),
    FINANCE_EXPENSE_RECEIPTS_READINESS("finance.expense-receipts.readiness"),
    FINANCE_EXPENSES_DOCUMENTATION_REVIEW("finance.expenses.documentation-review"),
    FINANCE_EMPLOYEE_FUNDING("finance.employee-funding"),
    FINANCE_EMPLOYEE_SETTLEMENT("finance.employee-settlement"),
    FINANCE_EXPENSE_REIMBURSEMENT("finance.expense-reimbursement"),
    FINANCE_PAYABLE_CANDIDATES("finance.payable-candidates"),
    FINANCE_SUPPLIER_ACCOUNTING_REVIEW("finance.supplier-accounting-review"),
    FINANCE_PAYMENT_PREFLIGHT("finance.payment-preflight"),
    FINANCE_AP_POSTING("finance.ap-posting"),
    FINANCE_AP_REVERSAL("finance.ap-reversal"),
    FINANCE_AP_REPORTING("finance.ap-reporting"),
    FINANCE_AP_AR_BALANCES("finance.ap-ar-balances"),
    FINANCE_PROJECT_SOURCES("finance.project-sources"),
    FINANCE_RECEIVABLES_INVOICE_CANDIDATE("finance.receivables.invoice-candidate"),
    FINANCE_RECEIVABLES_INVOICE_ISSUANCE("finance.receivables.invoice-issuance"),
    FINANCE_RECEIVABLES_SALES_AWARD_SOURCE_READINESS("finance.receivables.sales-award-source-readiness"),
    FINANCE_RECEIVABLES_AR_POSTING("finance.receivables.ar-posting"),
    FINANCE_CASHBOX("finance.cashbox"),
    FINANCE_CASHBOX_COUNT("finance.cashbox.count"),
    FINANCE_CASH_ALLOCATION("finance.cash-allocation"),
    FINANCE_CASH_ALLOCATION_VOID("finance.cash-allocation-void"),
    FINANCE_BANK_STATEMENT_INTAKE("finance.bank-statement-intake"),
    FINANCE_BANK_RECONCILIATION_REPORT("finance.bank-reconciliation.report"),
    FINANCE_BANK_RECONCILIATION_SIGNOFF("finance.bank-reconciliation.signoff"),
    FINANCE_GL_PERIOD_CLOSE("finance.gl-period-close"),
    FINANCE_PAYROLL_REVIEW_INTAKE("finance.payroll-review-intake"),
    SERVICE_CONTRACT_SOURCE("service.contract-source"),
    SERVICE_HANDOVER("service.handover"),
    SERVICE_MAINTENANCE("service.maintenance"),
    SERVICE_MAINTENANCE_ASSIGNED_FINDINGS("service.maintenance.assigned-findings"),
    SERVICE_MAINTENANCE_REVIEW("service.maintenance.review"),
    SERVICE_SUPPORT("service.support"),
    SERVICE_SUPPORT_TIME("service.support.time"),
    SERVICE_WARRANTY("service.warranty"),
    PAYROLL_PERIODS("payroll.periods"),
    PAYROLL_CALCULATION("payroll.calculation"),
    PAYROLL_RUN_REVIEW("payroll.run-review"),
    PAYROLL_RELEASE("payroll.release"),
    PAYROLL_FINANCE_HANDOFF("payroll.finance-handoff"),
    PAYROLL_FINANCE_RESULTS("payroll.finance-results"),
    PAYROLL_PAYSLIPS("payroll.payslips"),
}

/**
 * New servers advertise the complete catalog. An explicit pause or an omitted
 * entry in that catalog is authoritative on both native hosts. Older servers
 * have five Boolean capabilities; other existing routes may be probed through
 * their original source checks, without a second set of client feature flags.
 * A successful source preflight and command authorization are still required.
 */
fun IdentityBootstrapDto?.operationAvailable(operation: ProductOperation): Boolean {
    if (this == null || identityStatus != "ACTIVE" || organizations.isEmpty()) return false
    val capabilities = productCapabilities ?: return true
    if (capabilities.operationAvailability.isNotEmpty()) {
        return capabilities.operationAvailability[operation.key] == true
    }
    return when (operation) {
        ProductOperation.EXECUTIVE_HOME -> capabilities.executiveHome
        ProductOperation.FIELD_PERSONAL_ISSUED_STOCK -> capabilities.personalIssuedStock
        ProductOperation.FIELD_MATERIAL_USAGE_REPORT -> capabilities.materialUsageReporting
        ProductOperation.FIELD_ASSIGNED_ASSET_LOOKUP -> capabilities.assignedAssetLookup
        ProductOperation.FIELD_ENGINEERING_TESTS -> capabilities.engineeringTests
        else -> true
    }
}

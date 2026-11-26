package com.hiltech.server.operationalaccess

/** Operation scopes, kept distinct from transaction approval and administration delegation. */
enum class OperationalResponsibility(val domain: String, vararg properties: String) {
    PROCUREMENT_BUYER("PROCUREMENT",
        "hiltech.procurement.sourcing.buyer-authority-key",
        "hiltech.procurement.service-sourcing.buyer-authority-key",
        "hiltech.procurement.service-orders.buyer-authority-key"),
    PROCUREMENT_TECHNICAL_REVIEW("PROCUREMENT", "hiltech.procurement.sourcing.technical-review-authority-key"),
    PROCUREMENT_NEED_REQUEST("PROCUREMENT",
        "hiltech.procurement.office-stock.request-authority-key", "hiltech.procurement.service-needs.request-authority-key"),
    PROCUREMENT_NEED_REVIEW("PROCUREMENT",
        "hiltech.procurement.office-stock.review-authority-key", "hiltech.procurement.service-needs.review-authority-key"),
    PROCUREMENT_ORDER_ISSUE("PROCUREMENT",
        "hiltech.procurement.po.issue-authority-key", "hiltech.procurement.service-orders.issuer-authority-key"),
    PROCUREMENT_SERVICE_ACCEPT("PROCUREMENT", "hiltech.procurement.service-acceptance.authority-key"),
    FINANCE_SUPPLIER_REVIEW("FINANCE",
        "hiltech.procurement.invoice.finance-authority-key", "hiltech.procurement.service-invoices.finance-authority-key",
        "hiltech.finance.payables.review-authority-key"),
    FINANCE_EMPLOYEE_REVIEW("FINANCE", "hiltech.finance.employee-review.authority-key"),
    FINANCE_COMMERCIAL_REVIEW("FINANCE", "hiltech.finance.receivables.sales-award-source-review-authority-key"),
    FINANCE_PAYABLE_PREPARE("FINANCE",
        "hiltech.finance.ap-posting.preparer-authority-key", "hiltech.finance.ap-reversal.preparer-authority-key"),
    FINANCE_PAYABLE_POST("FINANCE",
        "hiltech.finance.ap-posting.operator-authority-key", "hiltech.finance.ap-reversal.operator-authority-key"),
    FINANCE_RECEIVABLE_PREPARE("FINANCE",
        "hiltech.finance.receivables.invoice-candidate.preparer-authority-key",
        "hiltech.finance.receivables.invoice-issuance.preparer-authority-key",
        "hiltech.finance.receivables.ar-posting.preparer-authority-key"),
    FINANCE_RECEIVABLE_EXECUTE("FINANCE",
        "hiltech.finance.receivables.invoice-issuance.operator-authority-key",
        "hiltech.finance.receivables.ar-posting.operator-authority-key"),
    FINANCE_EMPLOYEE_PREPARE("FINANCE",
        "hiltech.finance.employee-funding.preparer-authority-key", "hiltech.finance.employee-settlement.preparer-authority-key",
        "hiltech.finance.expense-reimbursement.preparer-authority-key"),
    FINANCE_EMPLOYEE_EXECUTE("FINANCE",
        "hiltech.finance.employee-funding.operator-authority-key", "hiltech.finance.employee-settlement.operator-authority-key",
        "hiltech.finance.expense-reimbursement.operator-authority-key"),
    FINANCE_BANK_STATEMENT_PREPARE("FINANCE", "hiltech.finance.bank-statement-intake.preparer-authority-key"),
    FINANCE_BANK_STATEMENT_IMPORT("FINANCE", "hiltech.finance.bank-statement-intake.operator-authority-key"),
    FINANCE_BANK_RECONCILIATION_PREPARE("FINANCE", "hiltech.finance.bank-reconciliation.signoff-preparer-authority-key"),
    FINANCE_BANK_RECONCILIATION_FINALIZE("FINANCE", "hiltech.finance.bank-reconciliation.signoff-operator-authority-key"),
    FINANCE_CASH_PREPARE("FINANCE",
        "hiltech.finance.cashbox.preparer-authority-key", "hiltech.finance.cashbox.voucher-preparer-authority-key",
        "hiltech.finance.cashbox.count-preparer-authority-key", "hiltech.finance.cash-allocation.preparer-authority-key",
        "hiltech.finance.cash-allocation-void.preparer-authority-key"),
    FINANCE_CASH_EXECUTE("FINANCE",
        "hiltech.finance.cashbox.voucher-operator-authority-key", "hiltech.finance.cashbox.count-operator-authority-key",
        "hiltech.finance.cash-allocation.operator-authority-key", "hiltech.finance.cash-allocation-void.operator-authority-key"),
    FINANCE_PERIOD_CLOSE_PREPARE("FINANCE", "hiltech.finance.gl-period-close.preparer-authority-key"),
    FINANCE_PERIOD_CLOSE_EXECUTE("FINANCE", "hiltech.finance.gl-period-close.operator-authority-key"),
    FINANCE_REPORT_READ("FINANCE",
        "hiltech.finance.ap-reporting.viewer-authority-key", "hiltech.finance.ap-ar-balances.reader-authority-key",
        "hiltech.finance.project-sources.reader-authority-key", "hiltech.finance.bank-reconciliation.reader-authority-key",
        "hiltech.finance.cashbox.reader-authority-key"),
    ;

    val propertyNames: Set<String> = properties.toSet()
    companion object {
        private val byProperty = entries.flatMap { role -> role.propertyNames.map { it to role } }.toMap().also {
            check(it.size == entries.sumOf { role -> role.propertyNames.size }) { "Each operation property must have exactly one responsibility." }
        }
        fun forProperty(property: String): OperationalResponsibility = requireNotNull(byProperty[property]) { "Unknown operational authority property." }
        fun defaultKey(property: String): String = forProperty(property).name
        fun configuredKey(property: String, configured: String?): String = configured?.trim()?.takeIf(String::isNotEmpty) ?: defaultKey(property)
        fun forCatalogKey(key: String): OperationalResponsibility? = entries.firstOrNull { it.name == key }
    }
}

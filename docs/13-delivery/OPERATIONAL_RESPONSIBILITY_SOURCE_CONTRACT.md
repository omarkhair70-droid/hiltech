# Operating responsibility source contract

Company operating access is assigned through the existing responsibility administration commands. Each grant requires the administrator’s explicit per-key ceiling and a current named employee/account. A source default names an operation; it grants no employee access.

## Current authority

For each property below, the server accepts either a current explicit operational binding for the mapped role or that operation’s existing configured ApprovalEngine authority binding. These are alternatives. An installed nonblank custom key keeps its existing behavior. Blank configuration resolves to the mapped role name; no singleton reviewer binding is created.

Operational bindings support multiple direct USER assignments and existing real TEAM membership. Every operation checks current company membership, binding dates, and team membership where applicable. Company grant/end commands preserve exact versions, current authorization before replay and inside the transaction, idempotency, and audit. No operating grant satisfies an independent approval policy or confers delegation authority.

## Administration and revocation

The employee summary distinguishes direct managed assignments, inherited team access, and read-only existing sources. A legacy binding may cover only some operations within a grouped role. Ending a new direct grant leaves other active sources intact and returns whether access remains through a team or existing source. The UI presents that state rather than claiming all access was removed.

## Issuer receipt lineage

Stock and service order receipts retain the original legacy binding foreign key for historical orders. New orders record exactly one real source: the legacy binding or the explicit operating binding. Current issuer proof includes organization, actor membership, role, validity, and real team membership. Stock/service purchase approval remains separate. Service issuance and stock decision reads exclude the requesting/selection maker from the counted independent decision.

## Closed operation map

| Responsibility | Domain | Existing operation properties |
|---|---|---|
| `PROCUREMENT_BUYER` | PROCUREMENT | `hiltech.procurement.sourcing.buyer-authority-key`, `hiltech.procurement.service-sourcing.buyer-authority-key`, `hiltech.procurement.service-orders.buyer-authority-key` |
| `PROCUREMENT_TECHNICAL_REVIEW` | PROCUREMENT | `hiltech.procurement.sourcing.technical-review-authority-key` |
| `PROCUREMENT_NEED_REQUEST` | PROCUREMENT | `hiltech.procurement.office-stock.request-authority-key`, `hiltech.procurement.service-needs.request-authority-key` |
| `PROCUREMENT_NEED_REVIEW` | PROCUREMENT | `hiltech.procurement.office-stock.review-authority-key`, `hiltech.procurement.service-needs.review-authority-key` |
| `PROCUREMENT_ORDER_ISSUE` | PROCUREMENT | `hiltech.procurement.po.issue-authority-key`, `hiltech.procurement.service-orders.issuer-authority-key` |
| `PROCUREMENT_SERVICE_ACCEPT` | PROCUREMENT | `hiltech.procurement.service-acceptance.authority-key` |
| `FINANCE_SUPPLIER_REVIEW` | FINANCE | `hiltech.procurement.invoice.finance-authority-key`, `hiltech.procurement.service-invoices.finance-authority-key`, `hiltech.finance.payables.review-authority-key` |
| `FINANCE_EMPLOYEE_REVIEW` | FINANCE | `hiltech.finance.employee-review.authority-key` |
| `FINANCE_COMMERCIAL_REVIEW` | FINANCE | `hiltech.finance.receivables.sales-award-source-review-authority-key` |
| `FINANCE_PAYABLE_PREPARE` | FINANCE | `hiltech.finance.ap-posting.preparer-authority-key`, `hiltech.finance.ap-reversal.preparer-authority-key` |
| `FINANCE_PAYABLE_POST` | FINANCE | `hiltech.finance.ap-posting.operator-authority-key`, `hiltech.finance.ap-reversal.operator-authority-key` |
| `FINANCE_RECEIVABLE_PREPARE` | FINANCE | `hiltech.finance.receivables.invoice-candidate.preparer-authority-key`, `hiltech.finance.receivables.invoice-issuance.preparer-authority-key`, `hiltech.finance.receivables.ar-posting.preparer-authority-key` |
| `FINANCE_RECEIVABLE_EXECUTE` | FINANCE | `hiltech.finance.receivables.invoice-issuance.operator-authority-key`, `hiltech.finance.receivables.ar-posting.operator-authority-key` |
| `FINANCE_EMPLOYEE_PREPARE` | FINANCE | `hiltech.finance.employee-funding.preparer-authority-key`, `hiltech.finance.employee-settlement.preparer-authority-key`, `hiltech.finance.expense-reimbursement.preparer-authority-key` |
| `FINANCE_EMPLOYEE_EXECUTE` | FINANCE | `hiltech.finance.employee-funding.operator-authority-key`, `hiltech.finance.employee-settlement.operator-authority-key`, `hiltech.finance.expense-reimbursement.operator-authority-key` |
| `FINANCE_BANK_STATEMENT_PREPARE` | FINANCE | `hiltech.finance.bank-statement-intake.preparer-authority-key` |
| `FINANCE_BANK_STATEMENT_IMPORT` | FINANCE | `hiltech.finance.bank-statement-intake.operator-authority-key` |
| `FINANCE_BANK_RECONCILIATION_PREPARE` | FINANCE | `hiltech.finance.bank-reconciliation.signoff-preparer-authority-key` |
| `FINANCE_BANK_RECONCILIATION_FINALIZE` | FINANCE | `hiltech.finance.bank-reconciliation.signoff-operator-authority-key` |
| `FINANCE_CASH_PREPARE` | FINANCE | `hiltech.finance.cashbox.preparer-authority-key`, `hiltech.finance.cashbox.voucher-preparer-authority-key`, `hiltech.finance.cashbox.count-preparer-authority-key`, `hiltech.finance.cash-allocation.preparer-authority-key`, `hiltech.finance.cash-allocation-void.preparer-authority-key` |
| `FINANCE_CASH_EXECUTE` | FINANCE | `hiltech.finance.cashbox.voucher-operator-authority-key`, `hiltech.finance.cashbox.count-operator-authority-key`, `hiltech.finance.cash-allocation.operator-authority-key`, `hiltech.finance.cash-allocation-void.operator-authority-key` |
| `FINANCE_PERIOD_CLOSE_PREPARE` | FINANCE | `hiltech.finance.gl-period-close.preparer-authority-key` |
| `FINANCE_PERIOD_CLOSE_EXECUTE` | FINANCE | `hiltech.finance.gl-period-close.operator-authority-key` |
| `FINANCE_REPORT_READ` | FINANCE | `hiltech.finance.ap-reporting.viewer-authority-key`, `hiltech.finance.ap-ar-balances.reader-authority-key`, `hiltech.finance.project-sources.reader-authority-key`, `hiltech.finance.bank-reconciliation.reader-authority-key`, `hiltech.finance.cashbox.reader-authority-key` |

## Verification boundary

Synthetic source tests exercise explicit grants, custom legacy bindings, membership revocation, role isolation, evidence access, independent policy requirements, and issuer proof persistence. PostgreSQL contract tests require an explicitly selected disposable database and roll back synthetic rows. Shared client tests cover the closed catalog and remaining-access receipt. None of these tests asserts full role acceptance on a target Windows/Android installation or a company server.

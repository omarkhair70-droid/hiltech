# Phase 8P — Finance internal customer invoice issuance

**Status:** Draft, default OFF. Only after 8O and canonical Phase10
materialization. No company policies, invoice series or tax data seeded.

## Implemented
- `FinanceArInvoiceIssuanceRules`: exact verified current commercial source,
  immutable customer invoice candidate, independent operator, company invoice
  number, protected finalized PDF, due date and amount.
- `finance_ar_invoice_issuance_proposal`: immutable review subject binding
  specific company invoice number + finalized document evidence ID.
- `finance_ar_issued_invoice`: append-only, source-unique and invoice-number-
  unique internal issuance record, enforced at SQL level by separate approval,
  company policy, second actor, exact document evidence and source equality.
- `FinanceArInvoiceIssuanceApprovalSubject`: ApprovalEngine source revalidation
  and version drift guard at approval time; checks active company reviewer,
  exact source, immutable candidate, canonical PDF and number-readiness flag.
- `FinanceArInvoiceIssuanceService`: REST prepare / request review / record /
  status, company membership/authority, approval, idempotency and audit trail.
- JUnit rules/version tests and disposable PostgreSQL contract; dedicated CI
  includes Windows/Android compilation.

## Critical remaining product obligations
- Default OFF: `hiltech.finance.receivables.invoice-issuance.enabled=false`
  and `canonical-invoice-number-ready=false` until actual company invoice
  number provider and authorized evidence upload exist. Unverified user-typed
  invoice numbers must NEVER be treated as legally issued invoices.
- This is an **internal source record only**. It does NOT send invoices, call an
  Egyptian e-invoice authority, record earned revenue, create AR journal,
  move cash or prove delivery/acceptance.
- Add a canonical company invoice-number reservation ledger; verified tax
  computation / legal issue source; positive approved end-to-end Postgres fixture
  and concurrent double-issue test; external issue status if legally required.
- Add source-bound AR journal allocation (chart, fiscal period, separate approval),
  credit notes / corrections, collection confirmation, and bank reconciliation.
- DevOps owns global Flyway numbering, combined CI and deployment. UX owns native
  navigation and role-scoped user journeys. No production activation before both.

## Feature flags/authority needed
- `hiltech.finance.receivables.invoice-issuance.enabled`
- `hiltech.finance.receivables.canonical-invoice-number-ready`
- `hiltech.finance.receivables.invoice-issuance.policy-key`
- `hiltech.finance.receivables.invoice-issuance.preparer-authority-key`
- `hiltech.finance.receivables.invoice-issuance.operator-authority-key`

No default values that grant Finance approval or legal issuance are introduced.

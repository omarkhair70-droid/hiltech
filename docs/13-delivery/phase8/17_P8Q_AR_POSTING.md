# Phase 8Q — Customer AR double-entry recognition

Status: isolated Draft, default-OFF, stacked on 8P.

## Implemented
- `FinanceArJournalRules` validates one full-gross receivable DEBIT and
  explicit revenue/tax CREDITS, six-place decimal precision and exact
  balanced amount with no generated tax values or vendor codes.
- `pending_ar_journal_posting.sql` extends approved chart account kinds with
  CUSTOMER_RECEIVABLE, SALES_REVENUE and TAX_PAYABLE, adds sealed immutable
  journal proposal and exact verified line copy to an append-only posted event,
  with independent company-approval triggers and closed-period locks.
- `FinanceArJournalPostingApprovalSubject` checks signed invoice source,
  source fingerprint, policy/authority binding and exact allocations on
  every ApprovalEngine review decision.
- `FinanceArJournalPostingService`: propose / request approval / post /
  status REST, period locking, real company authority, idempotency and audit.
- Period-close source snapshot updated to V3 for AP, AP reversals and AR;
  cannot quietly close a period with uncounted posted AR events.
- Rules tests, PostgreSQL DDL/forgery test, native KMP API client and CI.

## Safety and acceptance
- Original commercial award ≠ invoice draft ≠ approved invoice issuance
  ≠ receivable ledger ≠ confirmed cash received.
- Does NOT create refunds, customer messages, bank transfers or tax rates.
- Defaults OFF:
  `hiltech.finance.receivables.ar-posting.enabled`;
  `hiltech.finance.receivables.ar-posting.policy-key`;
  `hiltech.finance.receivables.ar-posting.preparer-authority-key`;
  `hiltech.finance.receivables.ar-posting.operator-authority-key`;
  `hiltech.finance.receivables.ar-posting.tax-evidence-ready`.
- Company chart, tax disposition, evidence and independent posting policy
  must be approved before activation.
- Still require *positive* real PostgreSQL full approval and concurrency
  fixture, integration with canonical tax evidence, real P10 source,
  role-native staging, migration numbering and DevOps combined CI.
- Customer credit notes, confirmed collections, invoice aging, advance /
  imprest settlement and bank reconciliation are separate future Finance
  slices; no made-up 'cash settled' or 'outstanding' values.

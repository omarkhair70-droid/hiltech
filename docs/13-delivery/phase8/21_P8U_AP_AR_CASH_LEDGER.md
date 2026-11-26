# HILTECH Phase 8U — Bank source / AP-AR cash / employee settlements

**Engineering state:** Stacked Draft PR #225, unnumbered Phase 8 SQL,
features OFF. This is financial evidence and accounting allocation code;
it never initiates external payments, sends invoices or triggers Payroll 9.

## What is implemented on this branch

1. **Approved bank source:** `pending_bank_statement_intake.sql`,
   company-approved bank account, restricted finalized Evidence, immutable
   proposed transaction lines, review version and independent
   approval. PostgreSQL deferred guards require exact line-count, SHA-256,
   company currency and opening/closing balance equality. Each recorded line
   must equal its pre-approved source.
2. **Real canonical CSV ingestion:** `FinanceBankCsvParser` accepts UTF-8
   bytes of a very specific CSV with columns
   `external_transaction_id,booked_on,flow,currency,amount`. SHA-256 of
   **the exact original bytes** must equal the finalized protected Evidence
   hash. Derived line hashes bind approved transaction rows to original
   file content. Rejects duplicates, corrupted UTF-8, overlarge input,
   spreadsheet-formula IDs, invalid money/date/currency. The
   `/bank-statement-intakes/prepare-csv` endpoint then uses the existing
   independent review workflow. The old unbound manual-row HTTP entry is
   **OFF by default** via `manual-entry-enabled=false`.
   This is NOT universal bank-format support or a bank API connection.
3. **AP/AR allocation:** `pending_ap_ar_cash_allocation.sql`,
   accounting-source and bank transaction row locks, two independent
   company actors and approval engine, no double-use/overpayment.
   Reversed supplier AP cannot be newly allocated.
4. **Balances and variance views:** `finance_ap_ar_source_balance`
   (recognition, allocation, remaining, reversals) and
   `finance_bank_allocation_exception` (unallocated/partial bank lines);
   company-scoped read-only REST. Full allocation is
   **NOT** signed bank reconciliation. AP due dates are never fabricated.
5. **Funded employee advance / company imprest:** independent original
   draft policy and actual observed company bank debit before immutable
   funding entry, separate company approval and native KMP transport.
6. **Employee advance recovery / custody settlement:** independently
   approved source, exact bank-credit cash return or independently
   accepted receipt settlement (no invented cash), immutable audit and
   bank shared-cap enforcement.
7. **Personal expense reimbursement:** separate reviewed receipt documents,
   independent Finance payment approval, and already-imported company
   bank-debit allocation. The employee's personal expense is **not** a
   pre-funded advance; no bogus earlier bank payment. GL expense account
   classification is explicitly **not** inferred.
8. **Testing:** disposable PostgreSQL constraints, immutable evidence
   anti-forgery tests, deterministic review drift, native REST transport,
   positive approved-bank-statement import fixture and strict CSV
   byte-SHA checks are part of CI. The head SHA must pass its latest run;
   earlier successful runs prove only their exact older commits.

## Explicit feature gates — all default OFF

- `hiltech.finance.bank-statement-intake.enabled`
- `hiltech.finance.bank-statement-intake.policy-key`
- `hiltech.finance.bank-statement-intake.preparer-authority-key`
- `hiltech.finance.bank-statement-intake.operator-authority-key`
- `hiltech.finance.bank-statement-intake.manual-entry-enabled` (leave **false**)
- `hiltech.finance.cash-allocation.enabled`, `.policy-key`,
  `.preparer-authority-key`, `.operator-authority-key`
- `hiltech.finance.employee-funding.enabled`, `.policy-key`,
  `.preparer-authority-key`, `.operator-authority-key`
- `hiltech.finance.employee-settlement.enabled`, `.policy-key`,
  `.preparer-authority-key`, `.operator-authority-key`
- `hiltech.finance.expense-reimbursement.enabled`, `.policy-key`,
  `.preparer-authority-key`, `.operator-authority-key`
- `hiltech.finance.ap-ar-balances.enabled`,
  `hiltech.finance.ap-ar-balances.reader-authority-key`
- `hiltech.finance.bank-reconciliation.report-enabled`,
  `hiltech.finance.bank-reconciliation.reader-authority-key`

## What keeps Phase 8 from functional/production closure

- **Finance engineering:** positive source-backed employee funding,
  repayments, reimbursement and AP/AR partial allocations under concurrent
  writers; real invoice credit-note and cash-allocation correction events;
  canonical tax/GL accounts and employee expense recognition; signed bank
  reconciliation approval/event (the variance report is NOT that event);
  final fiscal-close safety after every new ledger type.
- **Bank integration:** canonical CSV works with a matching already uploaded
  file. External bank-specific imports/connectors require their own format,
  custody permissions, statement authenticity and bank-side verification.
  Exact byte hash proves only sameness to the uploaded file, NOT that a
  financial institution issued or settled the movements.
- **Corporate policy:** company signatories, bank accounts, expense
  categories, payroll-deduction mandates and invoice numbering/tax rules
  must originate from real authorized company data. No seeded/guessed policy.
- **DevOps:** assign global Flyway numbers and reconcile the main migration
  contract. The combined `DatabaseMigrationContractTest.kt:89` previously
  failed because its expected migration count did not reflect concurrent
  deliveries. Do not alter history or skip this contract without reconciling.
- **UX & product integration:** wire actual role-scoped Windows/Android
  flows and run approved company fixtures end to end on the unified staging
  build before any flag is enabled.

Do NOT merge or activate based only on unit tests. No external money or
customer tax invoice is sent by any of these endpoints.

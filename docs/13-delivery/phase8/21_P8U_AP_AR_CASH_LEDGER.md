# Phase 8U — Source-confirmed AP/AR cash allocation record

**Status:** Draft, disabled by default. No real bank API command or money
transfer. Stacked on 8T, 8S, 8Q, 8P and original AP journal.

## Implemented
- Immutable `finance_cash_allocation_proposal` and
  `finance_cash_allocation_record` tables; exact approved source and bank
  statement links; unique operation and bank+source proposal.
- PostgreSQL trigger locks source journal and verified imported bank
  transaction, checks AP=bank debit / AR=bank credit, same currency, source
  and statement SHA-256, the remaining bank and journal balances and
  independent approved company policy and distinct reviewer/operator.
- `FinanceCashAllocationApprovalSubject` revalidates the original posted
  journal, imported bank transaction, policy/authority and cumulative source
  and bank balances on every ApprovalEngine decision.
- `FinanceCashAllocationService` exposes:
  propose → request approval → record → status, all company-scoped,
  idempotent, audited and default OFF.
- Independent PostgreSQL contract test and dedicated CI.

## Not yet an end-to-end accounting acceptance
- Manual bank statement source/line import workflow is currently DDL and
  validator, not a completed production bank file ingestion application.
- Must add positive test with real approved source fixture and simultaneous
  two allocations racing for one limited bank line.
- Must add bank allocation reversal/adjustment events (no editing history),
  reconcile bank statement against allocations, and prove final AP/AR aging.
- Financial imprest, employee advance and expense settlement remain separately
  implemented as strict validation only, NOT immutable funded ledger posting.
- Native screens, master DevOps Flyway migration registry and end-to-end
  company acceptance on Windows and Android remain unverified.
- No API call initiates transfers; `moneyTransferredByHiltech=false` and
  `bankReconciled=false` until separate authoritative events exist.

## Feature controls (default OFF)
- `hiltech.finance.cash-allocation.enabled`
- `hiltech.finance.cash-allocation.policy-key`
- `hiltech.finance.cash-allocation.preparer-authority-key`
- `hiltech.finance.cash-allocation.operator-authority-key`


## Follow-up implementation on this branch (manual bank intake + reports)

- `FinanceBankStatementIntakeService` and current-source ApprovalEngine:
  prepare → independent review → record, with company-approved bank register,
  finalized restricted evidence, immutable proposed lines, exact SHA-256 and
  opening/closing balance tie-out. Input is manually parsed from a verified
  bank statement; **it is NOT an automatic CSV parser or bank API connection**.
- Bank proposal lines are sealed before review. PostgreSQL deferred guards
  reject missing/modified lines and reject importing lines not in approved
  prepared source. Bank account and external transaction uniqueness are scoped
  to the company.
- `finance_ap_ar_source_balance`: read-only AP/AR gross, bank allocation,
  remaining source amount, and reversed-AP-with-bank exception. Never
  describes allocation as actual bank reconciliation. AP due date is unknown,
  so no made-up AP aging. Finance role gate default OFF.
- `finance_bank_allocation_exception`: exact unmatched / partially
  allocated statement lines, read-only role-gated REST with safe pagination.
  Even full allocation remains `bankReconciled=false`.
- Disposable PostgreSQL contract additions test these views and schema;
  exact document/line/policy review version has a deterministic drift test.

### Not complete until

1. CI proves the latest commit on the exact target branch.
2. A canonical external bank statement parser/provider is integrated with
   uploads and the verified Evidence target registration flow; current
   prepare endpoint takes previously reviewed, structured bank lines.
3. Positive transaction fixture proves independently approved statement
   import, AP/AR allocation, concurrent bank-source locking and refunds or
   reversals without overstating paid or outstanding values.
4. Employee advances and imprest: Phase8T has strict funded-source rules,
   NOT immutable DB settlement events or full Payroll9 reconciliation.
5. DevOps assigns globally safe Flyway versions, resolves combined CI,
   mounts native screens and runs role-scoped Windows+Android staging E2E.

All feature switches remain OFF. No outbound payment or invoice sending.

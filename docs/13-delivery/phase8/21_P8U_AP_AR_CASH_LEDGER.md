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

# Phase 8D — payment result safety contract, NOT an operating payment service

Date: 2026-11-23
Status: Isolated engineering source/domain contract — **no live money operations, no bank integration**.

## What this branch actually implements
- Kotlin `FinanceBankResultRules.record(current, incoming)` is a **pure, deterministic** reducer for future *already-submitted* approved payment attempts. Its only inputs are typed snapshots/events provided later by an independent, authenticated provider adapter.
- A hypothetical future `UNKNOWN` state is distinct from `CONFIRMED_PAID` and `CONFIRMED_FAILED`. A new bank attempt is never generated or retried by this module. Only independent provider evidence with the **same attempt ID** can resolve `UNKNOWN`.
- All external outcome transitions are checked against the immutable instruction ID, revision, source SHA-256, submitted bank attempt ID, amount and currency. Terminal confirmation/return/reversal requires a valid provider-proof SHA-256; same event ID is replay-safe only when identical in every bound field.
- Confirmed failed/returned/reversed are terminal. Paid may be corrected to returned/reversed with a separate verified result event; no auto-refunding or duplicate payout.
- Out-of-order results require separate exception investigation; no silent adjustment or false reconciliation.
- Dedicated tests: UNKNOWN never paid/retried, same-attempt resolution, proof required, exact last event replay, source/currency/amount/version mismatch, terminal outcomes, out-of-order rejection.

## What is not implemented
- This is **not** an API, bank provider, gateway adapter, approved payment instruction, payment authorization, beneficiary registry, funds transfer, ledger, posting, transaction, payroll outcome, true bank reconciliation or production release.
- No database migration because no real bank, approved company Finance policy, authorized invoice payable, signatories or provider event storage contract exists. This prevents introducing fictitious ledger records or false cash positions.
- Does not claim to reconcile partial payments, fees, FX differences, accounting period closes, bank statements or duplicated *older* events outside the latest-event snapshot; the eventual append-only ledger + unique provider-event store must do so.

## Strict gate for activation
Only integrate this pure transition reducer **after** the company ratifies signed payment authority and an actual supported bank/provider path, Finance creates an approved versioned PaymentInstruction from a recognized payable, a secure event evidence verifier and durable append-only event/instruction store exist, and the full provider webhook/poll + UNKNOWN/dispute/rollback path is tested against real sandbox material. Failure is never automatically retried.

## Dependencies
- Parent PR #179 Finance8C is read-only supplier payment readiness; #175 Finance8B has employee expense/advance/imprest DRAFTS; #164 Finance8A is an unposted accounting review candidate.
- Phase7 PR #174 V0061 collision against Finance8A provisional V0061; Finance8B provisional V0062; DevOps is integration owner.
- No shared People, Procurement, Payroll, Approval, general UI, bank adapter or migration-contract files modified in this child.

# HILTECH Phase 8C — payment preparation preflight (engineering checkpoint)

Date: 2026-11-23

**This slice adds an authorization-gated, source-bound, READ-ONLY payment readiness analysis. It DOES NOT create a payment instruction. It cannot trigger a bank provider, sign a payment, send supplier messages, create a payout batch, or claim reconciliation.**

## Real implementation
- Server `FinancePaymentPreflightService`: only consumes `FinancePayableCandidateService.candidates(actor, org)`. No direct projection of unrelated HR/Bank data and no bypass of the Phase8A Finance current-membership/current-authority guard.
- Every row identifies **exact** Phase7 matched supplier invoice → Phase8A accounting-review candidate ID, supplier ID, invoice ID/number, amount, currency, source hash, and source freshness.
- Mandatory blockers represent missing *implementation/company authority*, not assumed policy: accounting recognition absent, exact approver/beneficiary/signatory absent, no bank provider, no external result/reconciliation contract. Source changed returns an additional blocker. `actionablePaymentInstruction=false`, `paymentExecutionAvailable=false` in every row.
- `FinanceCandidatePulse`: counts **unposted review candidates**, grouped by original currency, with `sourceChangedCount`. Does not mix currencies. Unknown actual bank, paid, approved or booked totals are **null**, never fabricated as 0.
- Typed KMP REST GET-only client; reusable read-only Compose screen for Windows and Android (not mounted; integrator owns navigation).
- Separate OFF-by-default `hiltech.finance.payment-preflight.enabled=false`; current source Finance reviewer authority must also be valid.
- Tests for disabled gate, source equality, stale matching, unknown state, no execution, no false payment counts and currency boundaries. Includes all 8A/8B tests in CI on disposable PostgreSQL and native compile.

## Why only preflight?
The Phase8A objects are **ACCOUNTING_REVIEW_REQUIRED** candidates, not legally/accounting-recognized liabilities. Thus it would be unsound to persist even an apparently ready-to-execute payment instruction or attach bank details to them. Without company policy, signatories, actual payable recognition, a bank supported pathway and result contract, no instruction may be marked prepared-for-payment. The API truthfully tells the financial team WHY.

## Explicitly not implemented / dependent on reality
1. Finance policy source and exact bookkeeping authority approved by company accountant; GL/actual payable recognized separately from candidate.
2. Versioned verified beneficiary/bank account source, dual-control/source+amount+beneficiary approval, non-repudiation, signatory and company bank route.
3. Actual PaymentInstruction model after approved payable, multi-currency/partial allocation rules, locking and duplicate provider-key handling.
4. Provider-executed/result state machine including UNKNOWN, terminal payment proof and bank reconciliation; external never blind-retried.
5. 8D recognized project finance & actual cash statement: 8C pulse **must not be mistaken for P&L/cash**.

## Integration gate
- Parent Finance8B PR #175 and Finance8A PR #164 remain draft; Phase7 S07 PR #174 V0061 conflicts with Finance8A provisional V0061, Finance8B provisional V0062. DevOps to own final Flyway allocation and hardcoded migration-count proof; no numbers assigned here.
- No shared UI shell, People, Procurement, Payment/Bank, Finance approvals or main/DevOps files modified by this child.
- Native entry point must be mounted by UX/DevOps under authorization and separately feature flagged, then tested on real company devices.
- Do not merge or enable live operations on a green isolated CI result.

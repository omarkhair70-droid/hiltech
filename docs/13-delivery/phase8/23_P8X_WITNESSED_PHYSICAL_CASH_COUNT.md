# HILTECH Phase 8X — witnessed physical cash count (Draft, feature OFF)

Scope: internal count observation, **not** closing Finance Phase 8, not bank reconciliation, and not a financial adjustment. Base: Phase 8U/W, `9759d24f`.

## Source-backed workflow

1. An active company cashbox custodian with dedicated count-preparer authority enters the observed physical amount and unique company count reference. The server locks the cashbox and preserves its current currency, expected ledger cash, voucher count and UTC count date. Arbitrary book balances cannot be submitted.
2. An independently configured company authority reviews the **exact** amount, expected ledger snapshot, current custodian, policy revision and count reference. Approval is invalidated by changing any of these values or recording a new voucher.
3. A distinct authorized Finance recorder presents finalized, internal-only supporting count Evidence belonging to the original count proposal. PostgreSQL verifies the approved snapshot, evidence, independent reviewer and current cashbox ledger under a cashbox row lock. Duplicate and edited records fail.
4. `finance_cash_count_variance` displays recorded observed cash less **book cash at the count snapshot**. A positive or negative discrepancy is evidence for an investigation, **not income, expense, or new cash**. Neither `finance_cashbox_balance` nor GL changes because of this slice.
5. Recorded events are immutable and idempotent; KMP Windows/Android transport endpoints never describe a bank transfer or ledger adjustment.

## New contract and feature gates

- DB: `database/phase8/pending_cashbox_count.sql`, **UNNUMBERED**. Run after `pending_cashbox_vouchers.sql` and only after the preexisting AP/AR stack; DevOps must reconcile Flyway numbers with main.
- API: `POST /v1/finance/organizations/{orgId}/cash-counts/prepare`, then `/{id}/request-review`, `/{id}/record`, and `GET /{id}`.
- Required config (not seeded): `hiltech.finance.cashbox.count-enabled=false`, `hiltech.finance.cashbox.count-policy-key`, `hiltech.finance.cashbox.count-preparer-authority-key`, `hiltech.finance.cashbox.count-operator-authority-key`. Read uses existing `hiltech.finance.cashbox.reader-authority-key`.
- Real corporate active approval policies, independent witness, active cashbox custodian, and real signed count Evidence are required. The Evidence must be finalized, object-backed and targeted to `FINANCE_CASHBOX_COUNT`.
- This is an on-demand count snapshot; no invented cashbox shifts, monthly close, or automatic valuation changes.

## Acceptance checklist / remaining scope

- Dedicated test: `FinanceCashCountPostgresContractTest` verifies source-approved zero-opening cashbox, positive independently approved count with real Evidence fixture, observed discrepancy, forged snapshot rejection and record immutability.
- `FinanceCashCountVersionTest` verifies reviewer/source signature invalidation.
- `FinanceCashCountApiClientTest` verifies typed transport and absent monetary side effects.
- Run full server/native CI on exact head; server build alone does not prove Windows/Android user flows.
- Still missing to close Finance: actual bank+cash dual-writer partial AP/AR scenario, signed bank reconciliation/credits and GL classification, company-authorized opening historical cash, adjustment/short-over posting, numbered migration integration, financial role-native UI and E2E in staging. **Do not turn feature flags ON merely because CI passes.**

**Important:** A SHA-256 uploaded-file digest is integrity evidence, not a bank-issued verification of money movement. No production bank or cashier actions are initiated here.

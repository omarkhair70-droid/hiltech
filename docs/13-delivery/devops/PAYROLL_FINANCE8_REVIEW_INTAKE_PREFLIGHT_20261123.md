# HILTECH P9 → Finance8 immutable review intake — isolated preflight

Date: 2026-11-23. Branch: `integration/hiltech-payroll-finance-review-intake-20261123`, based on Finance8U integration #230 at `3be796e8`. **DRAFT / NO COMPANY ACTIVATION.**

## Actual gap closed at engineering-candidate level

Payroll9 already exposed `PayrollFinanceReviewIntakePort`, an immutable run manifest with exact company/run/release/actor, source fingerprint, SHA-256 digest, amounts and employee count. There was **no production Finance bean** implementing that port. At runtime it deliberately returned `PAYROLL_FINANCE_INTAKE_NOT_CONNECTED`. The only prior implementation was a synthetic fake in a Payroll PostgreSQL test.

This integration adds `FinancePayrollReviewIntakeAdapter` as an opt-in Finance module consumer, conditional on `hiltech.finance.payroll-review-intake-enabled=true` (default OFF), with a separate proposed `database/phase8/pending_payroll_review_intake.sql`. It accepts a verified release into an immutable review **envelope only**. It stores Payroll's SHA-256 employee-line manifest and aggregate; salary lines remain inside the Payroll domain. It enforces a unique company+run, company+release and company+operation, exact source+policy match, immutable stored receipt and replay-only identical operation. Concurrent conflicting claims never silently replace a review.

The extra SQL is **pending and unnumbered**. No change to main V0001..V0080, no Oracle or company migration, no activated feature flags, and no bank transfer, beneficiary instruction, withholding, employee paid flag, AP/GL entry, payroll policy or salary figures.

## Source evidence / acceptance

- Test `Payroll9bDraftPostgresContractTest` now supplements its fake handoff with a **real** Finance adapter using disposable PostgreSQL, after applying pending SQL. It checks one real review envelope, immutable replay, conflict on new operation, wrong-company refusal and zero bank-enabled rows.
- `FinancePayrollReviewIntakePolicyTest` checks precise totals, unique beneficiaries, currency/scale consistency, nonnegative amount, exact source shape, no fake flags.
- Dedicated `hiltech-payroll-finance-review-preflight.yml` uses PostgreSQL 18 and JDK 21 to run both test classes.
- P10 `commercial_finance_award_source` is already numbered in V0071 and Finance AR candidate/issuance services consume this source; this PR deliberately does not modify invoice or commercial ownership. Real billing milestone/tax Evidence, approval, posting, customer payment and bank reconciliation remain separate gates.
- P11 `commercial_support_contract_source` already binds a Sales contract to Handover/Service source. It does **not** authorize automatic chargeable maintenance or a made-up warranty billing rule; no service-owned Finance mutation is introduced here.

## Remaining release gates (not implied by CI green)

1. Finance owner #225 to review canonical ownership and the pending DDL, including sensitive-data minimization and contract with its latest updates.
2. DevOps to reconcile new pending DDL with other unnumbered Finance scripts, allocate a unique canonical Flyway version after V0080 and rehearse upgrade/restore on isolated PostgreSQL/staging.
3. Real People/time/wage statutory coverage, company approval keys, scoped Finance reviewer rights and authorized native Windows/Android handoff and read routes.
4. Later Finance payments need separate company-approved bank execution, per-employee result source, reconciliation, real UAT and explicit activation. A review envelope MUST NEVER be interpreted as a paid payroll.

All domain phase owner branches remain untouched. RC #224 and main remain unchanged.
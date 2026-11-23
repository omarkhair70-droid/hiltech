# Phase 9 Payroll — 9A execution checkpoint and parallel integration contract

Date: 2026-11-22
Branch: phase9/payroll-snapshot-20261122
Draft PR: #166
Baseline main: fae1d7f36eb9adeaecfea6ccbd07baba6b2af646
Source specification: PR #161, docs/13-delivery/phase8-13/03_PHASE9_PAYROLL.md,
docs/05-workflows/PAYROLL_FLOW.md, docs/06-data/object-specs/PEOPLE_PAYROLL_OBJECTS.md.
State: **9A IMPLEMENTED IN DRAFT / CI AND HUMAN ACCEPTANCE OPEN**.
Do not mark 9B, 9C, Phase 9 or production payroll complete.

## Exact 9A ownership

Payroll may create its own periods, snapshot Employee and Employment facts with
WorkforceAssignment ID/version, attach source-missing exceptions and append
revisions of reviewer commentary. It does not own Employee, Employment,
WorkforceAssignment, offboarding, People authority, Attendance, Finance Advance,
Finance payment, bank integration or ApprovalEngine.

V0070 is a **provisional numbering reservation**, not a merge-ready migration
sequence. DevOps/integration owner must reconcile actual Phase7 V0039–V0060,
Finance, Sales, Handover and other lane migration claims, then allocate final
monotonic Flyway numbering in a coordinated integration PR. This lane MUST NOT
renumber other people's files independently.

All API endpoints are OFF by default:
hiltech.payroll.periods-enabled=false.
No payroll authority bindings are seeded. They must be provisioned via a
company-approved flow; a People admin/organization owner label is not a grant.
The server also checks current organization membership and existing OpenFGA
membership. No mobile offline cache of restricted payroll responses is allowed.

## Snapshot and source handling

- Period's source dates/code/organization are immutable after creation.
- One transaction snapshots employees whose actual Employment overlaps the period.
- Former employees with period-overlapping Employment are retained for final
  pay review; PREBOARDING employees are excluded.
- Employee, Employment and WorkforceAssignment version identities are stored
  permanently; reads reveal if the original People source has since changed.
- There is no approved compensation ledger, approved attendance/time ledger or
  Finance deduction adapter in current main. A missing adapter is a BLOCKING
  exception, never an implicit zero amount.
- Exception comments are append-only, idempotent and audited, but never clear
  the blocking condition. Source change requires a controlled new candidate
  run, not overwriting history.
- 9A does not create PayrollRun, PayrollEmployeeLine, Payslip, ApprovalRequest,
  payable, transfer instruction, batch, or bank outcome.

## Required versioned Finance contract — Phase 8 owns the truth

Phase8 should expose **read-only approved input references** to Payroll, not
create Payroll-owned copies of Advance financial balances:

ApprovedAdvanceDeductionInput:
- organizationId and employeeId from the canonical People reference
- financeAdvanceId + authoritative Finance sourceVersion
- payrollPeriodId or approved validity/cutoff linkage
- approved installment/deduction plan reference and exact approval version
- approved amount and currency only when validated by HILTECH accountant
- source issued/settlement/currentness check and reversal/supersession marker
- stable idempotency/source fingerprint for exact PayrollRunVersion

This contract is NOT yet active. Missing approval/reference => HOLD; expired
or revoked version => STALE_SOURCE; no supported deduction limit policy =>
CALCULATION_BLOCKED. Payroll cannot mutate Finance Advance outstanding balance,
perform Finance settlement, or infer Egypt-law deduction limits.

For 9C, Finance owns the payment batch, bank execution, payment reference and
reconciliation ledger. Payroll consumes a typed read-only per-employee result
(PENDING/SUCCESS/FAILED/RETURNED/UNKNOWN) keyed by exact PayrollRunVersion,
employee and Finance payment instruction/version, with proof timestamp and
source lineage. UNKNOWN, partially failed and returned results must never be
translated to "PAID" or swallowed. No bank/payment credentials go into a
payslip or generic notification.

## Required 9B and 9C gates

9B:
- Real accountant/HR approved compensation and statutory tax/social insurance
  rules, rounding policy, approved attendance/overtime and deduction-limit policy
- Explicit actual payroll accountant/reviewer and owner approval authority
- Version-specific ApprovalEngine binding and stale-source invalidation
- Negative tests for forbidden employee/coworker data, revoked memberships,
  changed People/attendance/Finance inputs, compensation changes and duplicate
  adjustments; deterministic exact-version calculation
- Business sample acceptance and protected audit/retention

9C:
- Finance's exact payment batch and authoritative outcome contract
- Per-employee partial/UNKNOWN/FAILED/RETURNED recovery and reconciliation
- Private employee-self payslip only after current identity/self-match and
  approved state; no general People directory amount leakage
- Correction runs and final offboarding pay governed by real HR/Finance policy
- No payout until the company has signed the bank/payment authority path

## Native integration

Shared Compose Payroll9aAdminSection and Payroll9aEmployeePendingSection and
the shared typed Payroll9aApiClient compile for Windows and Android but are
**not mounted into the global shell** in this branch. The DevOps/integrator lane
owns global Desktop/Android/MainActivity/Shell wiring and permissions-aware
navigation; do not create an unsafe parallel edit there. Employee-only page
shows no salary or coworker record. Authenticated restricted API is online-only.

## Verification matrix

- Added unit policy and DTO secrecy tests.
- Added PostgreSQL contract test for multi-organization isolation, PREBOARDING
  exclusion, historically ended employee inclusion, revision staleness,
  idempotent period and review operations, and DB immutability.
- Added Phase9 9A workflow for actual PostgreSQL + Windows/Android compilation.
- Existing Bootstrap, DevOps server packaging and OIDC checks should also run
  from the draft PR. Results must be attached by exact head SHA.
- No real company salary sheets, taxpayer IDs, amounts, bank details, payroll
  employee lists, external provider activation or payment actions were used.

## Required integration handoff to DevOps

1. Confirm/reserve the final Flyway migration sequence for all parallel lanes
   and rebase without manipulating other domain history.
2. Review the P8 Finance read-only approved Advance deduction and payment
   outcome interfaces before enabling any payroll computation/payment.
3. Register payroll-only permissions and security-gated navigation; no
   People-admin shortcut.
4. Run exact-head tests on the combined branch (PostgreSQL + Spring + Android
   + Windows), then real accountant/HR device review and safe rollback rehearsal.
5. Maintain feature OFF until company authority and statutory data validated.

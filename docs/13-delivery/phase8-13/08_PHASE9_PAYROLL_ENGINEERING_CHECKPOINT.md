# Phase 9 Payroll — engineering as-built and integration checkpoint

Date: 2026-11-23. Owning PR: #166, independent branch: phase9/payroll-snapshot-20261122.
Plan: #161. Integration/migration owner: DevOps #173. Finance dependency: #164 / Phase8 8B-8D.

## Status definitions

Implemented means source code and tests are in an unmerged draft; it does NOT
mean production deployment, actual company salary calculation, bank transfer,
legal accountant acceptance, or a successful latest-head CI run.

HILTECH reality register RF-021 confirms Ahmed's broad routine Finance/Admin
envelope, Mohamed's exceptional approvals, and no universal numeric thresholds.
RF-010 payroll sheet/calculation/payment source is still marked UNKNOWN. Never
claim real wage amounts, statutory tax, social insurance or bank rules were
verified just because a plan contains their names.

## 9A: periods / source snapshots

- Provisional V0070 migration: restricted Payroll authority, date-bound
  payroll periods, employee/employment/work-assignment source revisions,
  frozen exceptions and append-only reviews with database immutability.
- Current employee facts are selected as-of payroll period and cutoff.
  Historically ended assignments in the covered period remain source-valid.
- Explicit missing compensation, approved time, Finance and offboarding
  review exceptions. A reviewer note NEVER silently resolves missing data.
- Only a separately approved, currently active Payroll authority combined
  with People company membership and security projection may read/prepare.

## 9B: calculation, versions and company release

- Provisional V0071: immutable draft run generations and employee/component
  source references; database FK binds each run line to the exact employee
  snapshot of that period. No mutable balance, paid flag or bank operation.
- Configurable decimal/rounding/currency computation; rejects missing verified
  statutory policy, source scope/version, duplicate component, unapproved
  deduction plan, zero-after-rounding and database numeric overflow.
- **Mandatory source coverage** even when overtime, deductions or Finance
  inputs are genuinely zero: each employee/period must have approved
  COMPENSATION, APPROVED_TIME and FINANCE coverage receipts. Any missing or
  changed receipt version invalidates the previously approved fingerprint.
- Each adapter must return approved coverage and approved monetary rows from
  one atomic read; never source Finance Advance balances from a duplicated
  Payroll table.
- SourceBoundPayrollInputAssembler is default OFF; real compensation,
  approved-time, Finance and statutory/accountant policy ports still need
  verified company-backed implementations before it can calculate real wages.
- Exact-source ApprovalEngine guard rejects stale People or financial source,
  wrong organization/policy and obsolete draft generations.
- Provisional V0072: immutable company release ledger. A routine release
  requires the company-configured NO_APPROVAL_REQUIRED policy and an authorized
  Payroll reviewer; SINGLE exception needs an APPROVED exact-version request.
  ApprovalEngine authority-matches must NOT bypass SINGLE subject decisions.
- PostgreSQL insert guard rejects invalid source fingerprint, older draft,
  inactive/wrong company approval policy or unapproved exception.
- Release means RELEASED_FOR_FINANCE_REVIEW, not PAID, not bank instructions.

## 9C: Finance result reconciliation and employee privacy

- Read-only typed Finance expected instructions and immutable per-employee
  result events: PENDING, SUCCESS, FAILED, RETURNED, UNKNOWN.
- Reviewer status validates that every expected instruction matches exact
  employee, currency and frozen net. A partial, returned, failed or unknown
  result does not mark the whole run paid.
- Employee payslip is self-only via current People identity mapping and
  current organization membership; no coworker lookup or bank evidence
  details in the employee DTO.
- A payslip appears only from the latest durably released run with valid
  source versions, company policy and Finance-result source. A new unapproved
  draft does not erase a previously released payslip.
- Payroll API response caching is disabled (Cache-Control no-store).
- Android and Windows reusable Compose screens: period snapshots, exceptions,
  employee-named run review, history, approval request, company release,
  payment status, private own payslips. Main navigation remains integrator-owned.

## Default-OFF feature gates

- hiltech.payroll.periods-enabled
- hiltech.payroll.draft-runs-enabled
- hiltech.payroll.approval-request-enabled
- hiltech.payroll.release-enabled
- hiltech.payroll.finance-results-enabled
- hiltech.payroll.payslips-enabled
- hiltech.payroll.source-assembly-enabled
- hiltech.payroll.run-approval-policy-key (default empty)

There is NO payroll bank-payment, money-transfer, Finance ledger-write,
or automatic retry endpoint. These feature flags must not be enabled merely
to make a staging demonstration appear complete.

## Exact tests to inspect

Workflow: .github/workflows/phase9-payroll-9a-contract.yml.
- PostgreSQL migration, snapshots, former employees, terminated assignments,
  period cutoff and future assignment exclusion.
- Cross-org isolation, membership revocation, append-only exception/release
  ledger and direct SQL release forgery prevention.
- Deterministic calculation, numeric overflow, zero-after-rounding, duplicate
  source, wrong scope/currency, approved deduction plan and coverage receipts.
- Real PostgreSQL run generation, idempotency, source version/attestation
  invalidation, latest approved release, employee self-only payslip.
- Finance UNKNOWN/SUCCESS/RETURNED and partial payment; safe negative replay.
- Kotlin server bootJar and native Android/Windows compilation.

The older isolated 9A commit 2485d500 PASSED its Phase9 exact-head tests.
Do NOT equate this with the new 9A–9C latest-head pass. Newer Actions have been
queued; only a completed success for the exact current SHA proves the PR.
The shared DevOps server-package job includes a legacy fixed-count assertion
in DatabaseMigrationContractTest.kt line 88. New valid migration files change
that count; integration owner should update it without weakening history
protection or migrating a high version before the Phase7/Finance sequence.

## Merge and production sequence

1. DevOps establish final contiguous append-only Flyway numbering after Phase7
   (provisional V0039–V0060), Finance (#164 V0061 provisional), and parallel
   Sales/Service. The Payroll V0070, V0071 and V0072 are PROVISIONAL.
2. Implement real restricted HR compensation, approved time and Finance input
   coverage ports; Finance owns Advance plans/balances and bank events.
3. Configure real accountant/statutory policy revisions without invented
   rates or salary amounts, with evidence held outside public source control.
4. Configure subject-specific current Payroll grants and ApprovalEngine
   policies; no hardcoded named person, no fake numeric threshold.
5. Confirm exact-head Postgres/server/native tests, cross-role staging privacy,
   Windows/Android route wiring, device permissions and rollback/restore.
6. Get company approval and legal/accounting validation before any production
   access to real wages or approved Finance handoff. Bank actual execution
   remains a separately approved Finance responsibility.

## Honest completion report

9A: implementation code; 9A isolated CI previously PASS.
9B: source coverage, calculation, immutable run, review and release code in PR.
9C: Finance read-only reconciliation and employee-private payslip code in PR.
Actual company-backed salary, attendance, Finance deduction, legal policy and
payment-result adapter: NOT YET CONNECTED or certified.
Latest expanded exact-head CI: must be verified.
Main merge, staging live employee access and production: NOT DONE.


## November 23 continuation — corrections and end of employment

### V0073: no destructive correction to an already released payroll

- Provisional migration `V0073__payroll_correction_link__phase9_9c.sql`
  creates an immutable link from the **last released original run** to one
  newer payroll generation. It references the original release; never edits
  the original gross/net, releases or Finance payment history.
- A replacement run may be released only when its exact source fingerprint
  **differs** from the original and an independent
  `PayrollVerifiedCorrectionSourcePort` provides an APPROVED company reason,
  a versioned HR evidence reference and a versioned Finance clearance.
- Allowed Finance dispositions are
  `NO_PRIOR_TRANSFER_VERIFIED`, `REVERSAL_VERIFIED` and
  `SETTLED_DIFFERENTIAL_AUTHORIZED`. These are **signed source facts**,
  not formulas and not instructions to pay again. Unknown/returned payments
  cannot be silently treated as final clearance.
- The correction record and replacement release share the same database
  transaction and period lock. An insert trigger rejects a wrong company,
  different payroll period, stale generation, wrong source fingerprint,
  missing original release or already released replacement.
- PostgreSQL test models an already released run, a newer unapproved draft,
  a changed Finance coverage source, a failed correction without HR/Finance
  evidence, and an approved link that leaves the original release replayable.
- Source port remains **unconnected** until real HR and Finance evidence exist.

### Payroll eligibility for employees who have left

- Calculation of an OFFBOARDING or FORMER employee now requires a separate
  APPROVED, versioned `PayrollVerifiedSeparationEligibility` from the real
  People offboarding case and company/accountant final-pay scope, in addition
  to regular compensation, approved-time and Finance coverage receipts.
- Any change to the People case version or approved final-pay scope version
  invalidates the run fingerprint. An ACTIVE employee cannot reuse an
  offboarding-only source record.
- Disabled `PayrollPeopleSeparationSource` uses
  `OffboardingPersistencePort.latestForEmployee` and payroll period end
  date, plus a separate `PayrollApprovedFinalPayScopeSourcePort`. It does
  not duplicate HR employee state or create legal severance calculations.
- Offboarding eligibility and final-pay scope ports are not yet live in
  the company; absent approval means the calculation stays blocked.

### Final pay and employee departure

- Read-only, authorization-checked
  `GET /v1/payroll/offboarding/{caseId}/final-pay-readiness` reuses the
  **existing People OffboardingCase and its HR/Finance/PAYROLL clearances**.
- Positive readiness requires a signed matching final-pay source:
  exact case version, last working date, employee and organization; an
  existing released payroll run with a matching frozen net/fingerprint;
  and the authoritative Finance per-employee payment result.
- Pending HR or Finance clearance, missing validated statutory scope,
  missing run release, stale source, wrong Finance instruction, or an
  unconfirmed payment yields explicit blockers — **never a made-up final
  salary, severance entitlement or PAID state**.
- Android/Windows native UI has case navigation, financial source status,
  Arabic review blockers, frozen net only when verified and external
  payment status. Component remains unmounted in global nav until
  UX/DevOps shared-shell integration.
- Default OFF: `hiltech.payroll.final-pay-review-enabled` and
  `hiltech.payroll.separation-source-enabled`.

### Incremental exact-head proof / not-live reminder

The expanded workflow now includes V0073 and
`Payroll9cFinalPayTest`. PostgreSQL migration/partial payout/exception
tests and shared native compilation are required before merging.
A successful **old** 9A workflow cannot certify newer code. The latest
pipeline must be completed at the exact final commit, not just queued.

Neither the correction flow nor final-pay view creates a payment, reopens
an old payment instruction or silently deducts employee advances. Real
Finance bank authority, legal/accountant policy, employee source protection,
consecutive Flyway numbers and staging acceptance remain mandatory.

### Safety and test hardening after correction/final-pay

- Both V0072 company release and V0073 payroll correction INSERT triggers
  now require a current, explicitly approved Payroll REVIEW authority binding
  **and** an active organization membership/identity. HTTP application
  permission checks remain separate and mandatory; direct SQL attempts with
  ordinary company membership but without Payroll REVIEW are rejected.
- The corrected PostgreSQL test seeds a synthetic, restricted REVIEW grant
  and asserts another active employee cannot forge a release through SQL.
- Separated employees require versioned final-pay scope proof and the exact
  canonical People case. A rehired ACTIVE employee cannot inherit an obsolete
  offboarding case; this is covered by a negative test.
- Payroll checks aggregate company run totals before persistence so multiple
  individually representable employee values cannot overflow numeric(20,6).
- New safety test: Payroll9bPeopleSeparationSourceTest. Both that test and
  Payroll9cFinalPayTest are now in the Phase9 exact-head workflow.
- Latest exact-head CI must complete successfully before declaring this PR
  green; queuing/pending is not approval. No global shell, real bank source
  or real HILTECH wage input is claimed as connected.

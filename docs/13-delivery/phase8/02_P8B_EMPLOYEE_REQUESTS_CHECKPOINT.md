# HILTECH Phase 8B | Employee Expense, Advance and FinancialImprest

Date: 2026-11-23
Status: ENGINEERING DRAFT / SELF-SERVICE DRAFT-ONLY; **NOT LIVE FINANCE**.

## Contract and user-facing reality

Three *separate*, company-scoped, immutable draft records now exist:

1. `finance_expense_claim_draft`: personal employee spending claimed for possible reimbursement; requires real merchant, spending date, amount, currency and purpose. Status `DRAFT_RECEIPT_PENDING`. It is **not** a payable and has **not** been supported by accepted receipt Evidence.
2. `finance_employee_advance_draft`: an employee's request for a potential advance; optional proposed settlement note, not an approved payroll deduction or outstanding debt. Status `DRAFT_POLICY_PENDING`. An advance is not outstanding until legally approved funds are actually issued.
3. `finance_financial_imprest_draft`: an employee's proposal to hold/spend *company* money, potentially project linked, optional expected clearance date. Status `DRAFT_CUSTODY_POLICY_PENDING`. **No funds/cash custody exists yet.** Employee shortage is not an automatic advance or salary deduction.

All create operations are actor-bound (no client employeeId), with real active UserIdentity -> Person -> Employee + current Employment + current organization membership checked by the server and Postgres source guard. Organization/project FK and no cross-company project references; append-only history; idempotent command and sensitive audit reason. No shared people/project tables duplicated. All three records are typed separately and not merged into one loose ledger.

## Integrations

Server `hiltech.finance.employee-requests.enabled=false` by default. No new or assumed finance approvers. Employee GET lists only currently authenticated employee's own drafts. Native Compose for employee is an **unmounted** integration-owned Windows/Android module; private online-only API, no offline finance queue. Finance admin queue, reviewer actions, Evidence-based receipt acceptance and any approvals are intentionally withheld for policy validation.

Future contracts — DO NOT fake:
- Expense receipt Evidence bound to exact expense/version and restricted classification, validity/duplicate review, company expense category and project cost source, then Finance reimbursement payable.
- Advance request -> real company approval of exact amount/currency/terms -> payment funding proof -> append-only settlement; Payroll consumes **only** explicitly approved/legal deduction input, never draft.
- FinancialImprest request -> separately authorized custody approval -> actual funding/cash ledger -> spend Evidence/receipts -> settlement revisions/shortage/overage/return, never automatically Payroll.
- Finance 8C prepares payment instructions only after approved payables and real beneficiary/company mandates; 8D provider evidence, UNKNOWN outcome and reconciliation.

## Security & testing

Database V0062 is **PROVISIONAL ONLY** until DevOps assigns a unique Flyway version across all concurrent phases. Finance 8A also uses provisional V0061. Do not merge without numbering.
- Tests for default-off denial, no current organization membership or employment, invalid amount, malformed currency, missing expense merchant.
- PostgreSQL contract constructs real temporary organization, identity, person, active membership/employment/employee and verifies three **independent** drafts, cross-org attempt rejected by DB, mutation rejected; transaction rolled back.
- Workflow builds native Kotlin/Compose Android and Windows and runs these tests with PostgreSQL 18.6 + Java21.

## Still blocked

Not operationally accepted, not connected to navigation, not company-authorized, and cannot submit/approve/fund any expense, salary advance, financial custody, supplier payable, payroll deduction or bank transfer.

Needs real HILTECH accountant/company owner policy: request/review/approval actors, expense categories/receipts, legal deductions, settlement, cash custody, finance document evidence, fee/FX/tax rules, versioned policy and signed operator acceptance on devices.

## Parallel work policy

Child of PR #164 and original Phase7 #162 stack. No direct changes to shared shell/navigation, ApprovalEngine, People, Project, Procurement, DevOps scripts or `DatabaseMigrationContractTest`. Ask DevOps/integration to assign provisional Flyway V0062 and reconcile hardcoded source baseline count after stacking all concurrent lanes. No background work or automatic release.

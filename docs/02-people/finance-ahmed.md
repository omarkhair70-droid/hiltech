# Ahmed — Finance / Admin Brain

Status: `RESEARCHING`

## Product goal

Turn Ahmed from repetitive data-entry / manual coordination into a finance and administration controller operating from one reliable company state.

Exact responsibility boundaries must be verified with HILTECH directly.

## Core principle

> Enter once, flow everywhere.

Finance should consume legitimate operational events instead of asking other teams to repeat information in spreadsheets/messages.

## Questions to answer from reality

- What does Ahmed own today: accounting, payroll, treasury, tax, admin, HR, procurement, banking, petty cash?
- What software/spreadsheets/portals are currently used?
- How are salaries calculated?
- How are employees paid?
- How are advances, deductions, overtime, expenses and reimbursements handled?
- Which bank(s) and corporate payment methods are used?
- Who prepares, reviews and approves payments?
- What reconciliation work happens after payment?
- How are supplier invoices tied to projects and deliveries?
- How are client invoices and collections tracked?
- What reporting does Mohamed request from Ahmed?

## Candidate finance home

Not final UI.

Potential areas:

- cash/bank position,
- receivables,
- payables,
- payroll status,
- supplier invoices,
- client invoices,
- expenses/advances,
- payments awaiting review,
- project cost alerts,
- reconciliation exceptions,
- finance/admin deadlines.

## Payroll product surface — required completeness

Payroll cannot be considered complete at “calculate salaries.”

Must research/define as applicable:

- employee compensation structure,
- salary effective dates,
- attendance/time inputs,
- overtime,
- bonuses,
- deductions,
- advances/loans,
- expense reimbursements,
- manual adjustments,
- draft payroll run,
- validation,
- maker/checker review,
- Mohamed approval rules,
- payment preparation,
- bank/file/API/host-to-host handoff,
- partial/rejected payments,
- reconciliation,
- correction/reversal,
- payslips,
- employee notification,
- payroll history,
- permissions,
- audit trail.

## Example target flow

Operational events can feed finance:

`Approved field overtime`
→ payroll input

`Supplier delivery received`
→ invoice can become eligible for review

`Material consumed on Project X`
→ project cost update

`Approved employee expense`
→ payable/reimbursement input

No event should be retyped merely because finance uses it later.

## Desktop experience hypothesis

Desktop may emphasize:

- dense tables,
- payroll runs,
- reconciliation,
- invoices,
- bulk actions,
- multi-pane document review,
- keyboard shortcuts,
- search/filter/export,
- project cost drill-down,
- maker/checker workflows.

## Mobile experience hypothesis

Mobile may emphasize:

- urgent approvals/reviews,
- payment status,
- finance alerts,
- quick invoice/payment lookup,
- payroll state,
- exceptions,
- remote review when away from desk.

The mobile experience is not intended to reproduce large accounting tables pixel-for-pixel.

## Approval model hypothesis

Potential pattern:

- Ahmed prepares/reviews,
- policy validates conditions,
- Mohamed receives only owner-level approvals according to amount/risk/type,
- low-risk actions may route to delegated approvers,
- all significant transitions are audited.

Exact thresholds and segregation-of-duties rules must come from company reality.

## Banking integration research

Do not assume direct banking API availability.

Research actual HILTECH bank/account products and possible methods:

- corporate banking portal,
- bulk payment file,
- API,
- host-to-host,
- EG-ACH/instant bulk services,
- payment status/reconciliation export.

The desired UX can remain `Prepare → Review → Approve → Pay`, while the integration mechanism may vary.

## Anti-goals

- rebuilding every accounting function without understanding what HILTECH already uses,
- forcing Ahmed to enter data already captured by field/project/warehouse systems,
- mixing confidential payroll/finance data into general employee visibility,
- owner approval on every small routine transaction,
- irreversible payment/accounting actions without audit/correction design.

## Completion requirement for Ahmed product surface

Before freeze, define:

- responsibility boundary,
- current accounting/banking environment,
- finance objects and ledgers,
- payroll lifecycle,
- supplier/client invoice lifecycle,
- project costing relationship,
- approval/maker-checker policy,
- bank/payment integration method,
- reconciliation/correction behavior,
- mobile/desktop boundaries,
- permissions/confidentiality,
- audit/retention requirements.

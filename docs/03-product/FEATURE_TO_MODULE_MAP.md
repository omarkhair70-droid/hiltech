# HILTECH Feature-to-Module Map

Status: FIRST PASS / NOT FROZEN

Purpose:
Connect feature families to owning server modules and primary client feature modules before code.

| Feature prefix | Primary server owner | Primary client area | Important dependencies |
|---|---|---|---|
| CORE-ID | identity / organizations | core/auth | people, permissions |
| CORE-NAV / CORE-ACT | inbox/search/audit + projections | core/navigation/search | all authorized objects |
| CTRL | read projections / domain queries | home/executive | projects, finance, people, warehouse |
| SALES | sales | sales | organizations, approvals, documents |
| WORK | projects + work | projects/work | people, warehouse, assets, engineering |
| FIELD | work | field | sync, documents, assets, warehouse |
| ENG | engineering | engineering | work, documents, assets |
| PEOPLE | people | people/selfservice | identity, organizations, assets, payroll |
| ONB | people | onboarding | identity, permissions, assets |
| WH | warehouse | warehouse | assets, procurement, projects |
| ASSET | assets | assets | warehouse, documents |
| PROC | procurement | procurement | suppliers/orgs, warehouse, approvals, finance |
| FIN | finance | finance | procurement, projects, approvals, integrations |
| PAY | payroll | payroll/selfservice | people, approvals, finance |
| CLIENT | clients/projections | clients | organizations, projects, finance, documents |
| SUPP | procurement + organizations | suppliers | documents, notifications |
| SUBC | work/projects + organizations | subcontractors | documents, warehouse |
| SEC | security | security | integrations, identity |
| DOC | documents | shared documents | object storage, all linking domains |
| APR | approvals | approvals | all subject modules, permissions |
| COMM | inbox + notifications | inbox | all event sources |
| AUTO | automation + domain jobs | hidden/system + contextual | domain modules |
| OFF | server sync endpoints + client sync | core/sync | all offline features |
| INT | integrations | usually contextual | external providers |
| OPS | observability/admin | admin/support | infrastructure |

## Rule
This map assigns primary ownership, not exclusive participation.

Example:
PAY-016 Payment Batch is payroll-owned business grouping, but finance owns authoritative bank execution/reconciliation.

## Freeze gate
Every BUILD_READY feature ID must eventually link to:
- server module,
- client module/surface,
- objects,
- commands/queries,
- events,
- permissions,
- offline classification,
- tests.


# Cross-Module Financial Ownership Clarifications

These rules prevent employee-facing/self-service features from creating split financial truth.

## Employee Advance

Authoritative object/lifecycle owner:
**finance**

Finance owns:
- Advance monetary obligation,
- approved amount/settlement terms,
- issue/payment reference,
- authoritative outstanding amount,
- accepted settlements/returns,
- close/reconciliation.

People owns:
- employee identity/eligibility/context,
- employee self-service request surface,
- HR context where policy permits.

Payroll owns:
- an approved payroll-deduction input/plan,
- applying the approved deduction in an exact PayrollRun version.

Payroll must never derive a deduction merely because an Advance exists.

Dependencies:
`people -> finance context/query`,
`payroll -> finance approved deduction contract`.

No direct cross-module table writes.

## Financial Imprest / Cash Custody

Authoritative owner:
**finance**

Finance owns:
- FinancialImprest,
- ImprestLedgerEntry,
- ImprestSettlement,
- custody balance,
- funding/replenishment,
- spend acceptance,
- cash return,
- shortage/overage resolution,
- reconciliation/clearance/closure.

People owns:
- custodian employee identity/status.

Projects/Procurement may provide:
- project/site/work/cost context,
- requesting context.

They do not own or edit the financial custody ledger.

## Rule

Feature prefix does not override authoritative object ownership.

Examples:
- `PEOPLE-022/023` are employee/custodian experience capabilities, while finance owns the monetary custody state.
- `PAY-009` consumes an approved Advance deduction contract; it does not own the Advance balance.

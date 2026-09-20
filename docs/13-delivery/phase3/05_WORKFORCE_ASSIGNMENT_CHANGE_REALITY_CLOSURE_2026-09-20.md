# Phase 3 / Slice 05 — Workforce Assignment Change Workflow Reality Closure

Date: 2026-09-20  
Status: **PASS / REALITY BOUNDARY CLOSED FOR IMPLEMENTATION CONTRACT**

## Purpose

Close the minimum product and engineering reality for changing an existing HILTECH employee's Team / role / reporting manager without destroying assignment history or silently changing later-domain truth.

Slice 05 is not a generic HR transfer engine.

It is the lifecycle layer over the existing Slice 02 `WorkforceAssignment` model.

Canonical owner intent remains:

`docs/00-program/HILTECH_PRODUCT_OWNERSHIP_PRINCIPLES.md`

---

## Canonical HILTECH inputs reviewed

- `docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`
- `docs/13-delivery/phase3/02_WORKFORCE_ASSIGNMENT_REALITY_CLOSURE_2026-09-20.md`
- `docs/13-delivery/phase3/02_WORKFORCE_ASSIGNMENT_FOUNDATION_SLICE_2026-09-20.md`
- `docs/05-workflows/EMPLOYEE_LIFECYCLE.md`
- `docs/10-design/wireflows/NEW_HIRE_OFFBOARDING_WIREFLOW.md`
- existing `workforce_assignment`, `team_membership`, OpenFGA projection and People authorization runtime.

External product/reference research:

- Oracle HCM Date Effectivity:
  https://docs.oracle.com/en/cloud/saas/human-resources/faamx/date-effectivity.html
- Microsoft Dynamics 365 Human Resources — Positions:
  https://learn.microsoft.com/en-us/dynamics365/human-resources/hr-personnel-positions
- Microsoft Dynamics 365 Human Resources — Personnel actions FAQ:
  https://learn.microsoft.com/en-us/dynamics365/human-resources/hr-personnel-actions-faq
- SAP SuccessFactors — Job Relationships:
  https://help.sap.com/docs/successfactors-employee-central/implementing-employee-central-core/job-relationships
- SAP SuccessFactors — Mass Changes to Job Information and Job Relationships:
  https://help.sap.com/docs/successfactors-employee-central/managing-mass-changes-in-employee-central/mass-changes-to-job-information-and-job-relationship

Common useful pattern confirmed by those systems:

- workforce/job/position relationships are date/effective-history aware;
- transfer/change operations preserve history rather than overwriting prior assignment facts;
- manager/reporting relationships are first-class changeable facts;
- an effective date belongs to the assignment change, not to a destructive rewrite of the old row.

HILTECH adopts the history-preserving pattern, not the enterprise feature surface around it.

---

# Reality conclusions

## 1. Slice 02 remains the only assignment truth

The existing `WorkforceAssignment` row owns:

- employee;
- Team;
- role code/label;
- reporting manager;
- effective-from/effective-to;
- assignment lifecycle state.

Slice 05 must not add:
- role/team/manager fields back onto Employee;
- a Transfer table that becomes a competing source of truth;
- a second reporting hierarchy.

A change creates a new WorkforceAssignment revision and closes the previous one.

---

## 2. Current HILTECH reality does not justify a large personnel-action engine

The repository does not establish:
- formal transfer-request stages;
- promotion/transfer approval matrices;
- mass reassignment policy;
- compensation changes tied automatically to role changes;
- future-effective scheduling operations.

Therefore none of those are invented.

The minimal workflow is an authorized People action with audit/history and safe downstream signals.

---

## 3. Initial Slice 05 change is effective at command execution

External HCM products commonly support future-effective records.

HILTECH's current authorization projection does not yet have a production scheduler that can switch Team/OpenFGA authority at a future instant without either:
- revoking old access early; or
- granting new access early.

Therefore Slice 05 does **not** fake future scheduling.

The first implementation uses the server command timestamp as the new revision's effective boundary.

This still gives true effective-dated history:

`old.effective_to = changeAt`

`new.effective_from = changeAt`

Future-effective scheduling may be added later only with a safe activation/reconciliation mechanism and real business need.

---

## 4. Assignment history is immutable business history

Changing Team/role/manager does not edit the old row in place.

The old assignment becomes `ENDED`.

The new assignment becomes `ACTIVE`.

A revision link should preserve which assignment was superseded.

The old facts remain readable for history/audit.

---

## 5. Authorization follows the new current assignment

Slice 02 already projects People-owned Team membership into the Phase 1 authorization foundation.

On change:

- old People-owned `team_membership` source is ended at the same change timestamp;
- new assignment creates/synchronizes the new People-owned Team membership when Team is present;
- OpenFGA projection is reconciled from current source truth;
- aggregate Team membership semantics must preserve authority when another valid membership source still exists.

Do not infer security from `role_code` text.

Do not convert `reports_to_employee_id` into a Team-manager OpenFGA relation.

Business reporting manager and Phase 1 Team manager remain separate concepts.

---

## 6. Reporting cycles remain invalid

A manager change must reuse the existing reporting-cycle protection.

Examples rejected:

- employee reports to themselves;
- A -> B while B already resolves back to A through current reporting chain.

The cycle check is evaluated against the proposed new current assignment.

---

## 7. Project reassignment is not People truth

The role-change wireflow says Project access should be reviewed.

Phase 4 owns Project/Site/Work assignments.

Slice 05 therefore emits a safe project-impact signal/event only.

It does **not**:
- move Project membership;
- reassign WorkOrders;
- remove site work;
- invent Project tables early.

When Phase 4 exists, it can consume the event and create a review/action if required.

No fake Inbox item is created when there is no known actionable Project object yet.

---

## 8. No automatic payroll/compensation mutation

A role/team/manager change in Slice 05 does not:
- change salary;
- change Pocket Money;
- change overtime rules;
- create payroll inputs;
- change bank/payment data.

Later Payroll/Finance may consume workforce history under their own validated rules.

---

## 9. Human flow

People/Admin representative flow:

`Employee -> Current assignment -> Change assignment -> compare current/new Team/role/manager -> confirm -> new current assignment + preserved history`

The UI must state clearly:

- the change updates People/authorization scope;
- Project/Site/Work assignments are not changed here;
- previous assignment history is preserved.

Employee self-service continues to show the new current Team/role/manager after the change.

---

## 10. No user answer is required before implementation

Unknown facts are correctly scoped out:

- exact reason-code catalog;
- promotion/transfer approval policy;
- future-effective scheduling;
- bulk changes;
- Project impact handling;
- compensation effects.

Those are configuration/later-domain requirements, not blockers for the minimal safe lifecycle.

---

# Reality closure decision

**SLICE 05 REALITY CLOSURE = PASS**

Proceed to the frozen implementation contract.

Do not open Slice 06 Offboarding early.

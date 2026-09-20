# Phase 3 / Slice 06 — Offboarding Skeleton Reality Closure

Date: 2026-09-20  
Status: **PASS / REALITY BOUNDARY CLOSED FOR IMPLEMENTATION CONTRACT**

## Purpose

Close the minimum safe product/engineering reality for an employee leaving HILTECH without pulling Warehouse, Project, Finance or Payroll implementation into People.

Slice 06 is the final Phase 3 slice.

It must create one coordinated People-owned offboarding case, make security revocation real, preserve employment/history, and expose explicit clearance slots for later authoritative domains.

It must not pretend that People owns project transfer, asset custody, final payroll or finance settlement.

Canonical owner intent:

`docs/00-program/HILTECH_PRODUCT_OWNERSHIP_PRINCIPLES.md`

---

## Canonical HILTECH inputs reviewed

- `docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`
- `docs/05-workflows/EMPLOYEE_LIFECYCLE.md`
- `docs/06-data/transition-tables/PEOPLE_PAYROLL_SUPPORT_TRANSITIONS.md`
- `docs/10-design/wireflows/NEW_HIRE_OFFBOARDING_WIREFLOW.md`
- `docs/03-product/role-experiences/HR_ADMIN_EXPERIENCE.md`
- Phase 1 Identity / Session / Device / Organization Membership runtime.
- Slice 02/05 WorkforceAssignment + People-owned Team/OpenFGA projection.
- Slice 01 Employee / Employment states already present:
  - Employee: PREBOARDING / ACTIVE / OFFBOARDING / FORMER.
  - Employment: ACTIVE / ENDED.

External reference research reviewed:

- Oracle HCM — Terminating a Worker:
  https://docs.oracle.com/en/cloud/saas/human-resources/faeii/terminating-a-worker.html
- Microsoft Dynamics 365 Human Resources — Terminate workers business process:
  https://learn.microsoft.com/en-us/dynamics365/guidance/business-processes/hire-to-retire-onboard-terminate-employment
- SAP SuccessFactors — Terminating an Employment:
  https://help.sap.com/docs/successfactors-employee-central/implementing-employee-central-core/terminating-employment
- SAP SuccessFactors — Manage Offboarding:
  https://help.sap.com/docs/successfactors-onboarding/implementing-sap-successfactors-onboarding-with-sap-best-practices/manage-offboarding-4n3

Useful common pattern from those systems:

- termination/offboarding is an explicit lifecycle process, not deleting the employee;
- an effective last-working / termination date is preserved;
- related responsibilities need coordinated follow-up;
- account/access de-provisioning is a distinct operational concern;
- employment history remains after termination.

HILTECH adopts the history-preserving/security-first pattern, not their enterprise workflow breadth.

---

# Reality conclusions

## 1. Employee, Employment and OffboardingCase have different jobs

Employee remains the workforce master.

Employment remains the historical employment relationship.

OffboardingCase coordinates leaving-work actions.

Do not add termination fields to Person.

Do not create a second Employee/Employment record.

Do not delete employee history.

---

## 2. Start Offboarding does not mean Former

The existing lifecycle already distinguishes:

`ACTIVE -> OFFBOARDING -> FORMER`

Starting offboarding records:
- last working date;
- reason category code;
- optional safe note;
- who/when initiated.

It changes Employee to OFFBOARDING.

It does **not** immediately:
- end historical employment;
- delete identity;
- erase assignment history;
- calculate final payroll;
- return assets;
- transfer projects.

---

## 3. Access revocation is independently executable

The existing Phase 1 security runtime already has:
- active HILTECH identity enforcement;
- organization membership state/validity;
- HILTECH session rows with revocation;
- device/session security primitives;
- Team/OpenFGA authorization projection.

Security must not wait for paperwork if access needs to stop.

Slice 06 therefore separates:

1. Start Offboarding.
2. Revoke HILTECH access.
3. Resolve clearances.
4. Complete Offboarding / become FORMER.

This matches the existing wireflow rule that access can be revoked earlier than administrative completion.

---

## 4. Do not revoke the global identity blindly

A `user_identity` is not the Employee object and may represent access beyond one employment context.

Therefore the baseline offboarding integration must:

- end the employee's HILTECH organization membership;
- revoke active HILTECH sessions for the linked identity;
- close People-owned Team membership / assignment authority;
- preserve the identity record and audit history.

Do not automatically delete the Keycloak user.

Do not automatically set `user_identity.status = REVOKED` merely because one HILTECH employment ended.

If later reality proves the identity has no other legitimate access context, a separate identity-deprovision policy may revoke/disable the provider identity.

The core Slice 06 invariant is: the former employee cannot retain active HILTECH organization authority.

---

## 5. Devices are not the primary offboarding authority boundary

Device records are installation/security history.

Organization membership + Team/OpenFGA + active sessions are the access boundary.

Baseline Slice 06:
- revoke all current HILTECH sessions for the linked identity;
- preserve device records;
- do not invent MDM/device-wipe behavior.

A device may be separately revoked by existing Phase 1 security actions if required.

---

## 6. Workforce assignment authority must close

At access revocation/finalization:
- any current WorkforceAssignment for the employee must end;
- the People-owned Team membership sourced by that assignment must stop being current;
- OpenFGA projection must be reconciled;
- old assignment history stays readable.

Do not create a replacement assignment.

Project/Site/Work assignment handling remains Phase 4.

---

## 7. External domains are clearance owners, People is coordinator

Offboarding requires visibility of:
- PROJECT;
- ASSET;
- FINANCE;
- PAYROLL.

But those authoritative implementations are later phases.

People may own a typed clearance slot that says whether the coordinator has:
- not started;
- waiting on the owner domain;
- clear;
- not applicable;
- approved exception.

People must not fabricate:
- asset-return transactions;
- project reassignment;
- final pay calculations;
- expense/advance balances.

A future authoritative domain can update/replace the slot through a typed integration/event boundary.

Until then, an authorized People admin can record an explicit:
- NOT_APPLICABLE, or
- EXCEPTION_ACCEPTED with a reason.

That is an auditable coordination decision, not fake domain truth.

---

## 8. HR and ACCESS are real local clearances

Two clearances are authoritative in Phase 3:

### HR
HR/Admin confirms the People-side exit record/checklist is complete.

### ACCESS
Derived from real security state, not a checkbox:
- HILTECH organization membership ended;
- active HILTECH sessions revoked;
- current People-owned Team/assignment authority ended.

ACCESS cannot be manually marked CLEAR while the security facts disagree.

---

## 9. Becoming FORMER is a guarded final transition

Minimal Phase 3 completion rules:

- Employee is OFFBOARDING.
- offboarding case is OPEN.
- server date is on/after last working date.
- HR clearance is CLEAR.
- ACCESS clearance is CLEAR from authoritative security facts.
- PROJECT / ASSET / FINANCE / PAYROLL slots are each one of:
  - CLEAR;
  - NOT_APPLICABLE;
  - EXCEPTION_ACCEPTED with reason.
- active Employment exists and is ended using the case last-working date.
- current WorkforceAssignment/People-owned Team authority is not left active.
- history/audit remains intact.

Then:
- Employment -> ENDED;
- Employee -> FORMER;
- Employee.end_date = last working date;
- OffboardingCase -> COMPLETED.

---

## 10. Reason categories stay policy/data, not hard-coded legal truth

The repository does not establish HILTECH's exact legal/HR termination reason catalog.

Slice 06 stores a normalized `reason_category_code` and optional note.

Examples from discovery may include resignation/termination/contract-end, but implementation must not freeze Egyptian legal classification or payroll/legal effects.

Those require later HR/legal/accounting validation.

---

## 11. No future scheduler in the skeleton

Last working date is a business fact.

Slice 06 does not create a background scheduler that auto-terminates an employee at midnight.

An authorized completion command runs on/after the last-working date and re-evaluates all completion gates.

If immediate security action is required before that date, Revoke Access is available independently.

---

## 12. Human flow

Representative Windows HR/Admin flow:

`Employee -> Start Offboarding -> see last working date/reason -> see HR + Access + Project + Asset + Finance + Payroll clearance states -> revoke access when appropriate -> resolve coordination slots -> Complete -> FORMER`

The screen must visibly distinguish:
- real system-verified ACCESS;
- manual HR clearance;
- later-domain coordination slots;
- unresolved vs exception accepted.

Employee self-service does not get an admin offboarding command surface.

After access revocation, the employee must fail HILTECH access rather than seeing a misleading active self-service shell.

---

# Explicit non-goals

Do not implement:

- Warehouse return/custody transactions;
- Project/Site/Work reassignment;
- finance settlement;
- payroll/final-pay calculation;
- attendance/leave/overtime;
- physical access-control hardware;
- MDM wipe;
- Keycloak account deletion;
- legal termination workflow/approval matrix;
- future scheduled auto-termination;
- rehire workflow.

---

# Reality closure decision

**SLICE 06 REALITY CLOSURE = PASS**

Proceed to the frozen implementation contract.

No new user answer is required before the minimal safe skeleton is implemented.

Any real legal/payroll/asset/project details remain owned by their later authoritative phases.

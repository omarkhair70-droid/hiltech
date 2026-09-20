# Phase 3 / Slice 04 — Onboarding + Basic Self-Service Reality Closure

Date: 2026-09-20  
Status: **PASS / REALITY BOUNDARY CLOSED FOR IMPLEMENTATION CONTRACT**

## Purpose

Close the minimum real-world/product boundary for Phase 3 Slice 04 without inventing HILTECH HR policy that is not yet known.

Slice 04 is not a generic HR portal.

It has two connected goals:

1. turn an already-known PREBOARDING Employee into a safely activatable HILTECH employee through a visible, configurable onboarding case; and
2. let the employee use the same HILTECH product to see and complete the small set of own-record actions that belong to them.

This closure follows the canonical owner-intent document:

`docs/00-program/HILTECH_PRODUCT_OWNERSHIP_PRINCIPLES.md`

It deliberately separates:
- durable product capability,
- HILTECH-specific checklist configuration,
- identity-provider integration,
- and optional delivery channels.

---

## Canonical inputs reviewed

Repository reality:

- `docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`
- `docs/13-delivery/phase3/01_EMPLOYEE_EMPLOYMENT_CORE_SLICE_2026-09-20.md`
- `docs/13-delivery/phase3/02_WORKFORCE_ASSIGNMENT_FOUNDATION_SLICE_2026-09-20.md`
- `docs/13-delivery/phase3/03_HR_DOCUMENTS_CERTIFICATIONS_SLICE_2026-09-20.md`
- `docs/05-workflows/EMPLOYEE_LIFECYCLE.md`
- `docs/10-design/wireflows/NEW_HIRE_OFFBOARDING_WIREFLOW.md`
- `docs/02-people/new-hire.md`
- `docs/02-people/hr-admin.md`
- `docs/03-product/role-experiences/HR_ADMIN_EXPERIENCE.md`
- Phase 1 native OIDC / bootstrap contracts.

External provider research:

- Keycloak 26.7.4 Admin REST:
  https://www.keycloak.org/docs-api/26.7.4/rest-api/index.html
- Keycloak Server Administration Guide:
  https://www.keycloak.org/docs/latest/server_admin/

Relevant confirmed Keycloak capabilities:
- an administrator can create a user through the Admin REST API;
- per-user required actions can be configured;
- `UPDATE_PASSWORD` can be required on first login;
- a temporary password can force password change at first login;
- `execute-actions-email` can send an action link when an email transport is configured.

These provider capabilities are implementation options behind the HILTECH identity boundary. They do not make email/SMTP a core Slice 04 dependency.

---

# Reality conclusions

## 1. Onboarding starts from an already-known Employee

Phase 3 deliberately did not build an ATS/recruitment product.

Slice 01 already owns:
- Person;
- Employee;
- Employment.

The normal Slice 04 input is therefore an Employee in `PREBOARDING`, not a candidate/application object.

Do not add candidate/interview/offer depth here.

---

## 2. Identity remains separate from Employee

Existing invariant remains:

`UserIdentity != Employee`

The only Identity -> Person link remains:

`user_identity.person_id`

Do not add a second Employee -> Identity foreign key.

Slice 01 already supports linking an existing HILTECH identity.

Slice 04 adds the missing onboarding orchestration for cases where a new employee does not yet have a HILTECH identity.

---

## 3. The exact HILTECH checklist is not verified reality

The repository does not establish one authoritative current list of:
- required employment documents;
- required certifications;
- required training;
- mandatory policy acknowledgements;
- employee-category-specific onboarding rules;
- whether every employee category must have a system account before ACTIVE.

Those are real operating/configuration facts, not architecture.

Therefore Slice 04 must not hard-code a guessed checklist.

The product freezes a typed/versioned onboarding-policy shape.

Actual HILTECH policy rows are activation/seed configuration and can be validated with Mohamed/Ahmed before a real employee pilot.

This does **not** block implementation.

---

## 4. Existing authoritative sources must be reused

Slice 04 must not create duplicate truth.

Requirement evaluation consumes:

- profile/contact facts -> Slice 01 Person/Employee;
- identity link + organization membership -> Phase 1 + Slice 01;
- team/role/manager -> Slice 02 `WorkforceAssignment`;
- employee documents -> Slice 03 `EmployeeDocument`;
- certifications -> Slice 03 `Certification`;
- binary file readiness -> Documents/Evidence;
- manual company-only steps -> Slice 04 explicit manual completion/waiver record.

A checklist row is not allowed to become a second editable copy of those facts.

---

## 5. The employee must see ownership of blockers

The accepted product wireflow already distinguishes:

- actions the employee can complete;
- actions waiting on HILTECH;
- completed items.

This is important for real usability.

A missing manager assignment is not shown as if the employee failed to act.

A submitted document waiting for verification is not shown as “upload it again”.

Slice 04 therefore needs a derived requirement status that can express at least:

- `NEEDS_EMPLOYEE`;
- `WAITING_HILTECH`;
- `SATISFIED`;
- `WAIVED` where policy explicitly allows waiver;
- `BLOCKED` for an invalid/rejected state requiring resolution.

---

## 6. No separate onboarding app

The employee uses the same HILTECH Android/Windows product.

Before ACTIVE, the home/surface may emphasize onboarding.

After activation, the same identity/session continues into the normal role-aware HILTECH experience.

Do not create a separate onboarding application or identity silo.

---

## 7. Basic self-service is own-record only

The minimum useful employee self-service for this slice is:

- own safe profile/contact;
- own current Team / role / reporting manager;
- own onboarding status and blockers;
- own permitted EmployeeDocuments;
- own Certifications;
- own permitted document submission/upload during onboarding.

It is **not**:
- peer HR browsing;
- payroll;
- payslips;
- leave;
- attendance;
- expenses;
- salary;
- bank details;
- disciplinary/performance data.

Those remain later-domain work.

---

## 8. Own document upload must reuse Slice 03 Evidence

Employee document submission must not introduce:
- a second file table;
- public file URLs;
- a second object-storage path;
- client-controlled classification.

Flow remains:

`EmployeeDocument metadata -> existing EMPLOYEE_DOCUMENT Evidence target -> private upload/finalize -> People verification`

The authorization extension is target-specific:
- a People administrator keeps existing management access;
- the linked employee may access only their own explicitly self-service-permitted document types;
- ordinary peers remain denied.

---

## 9. Invitation delivery is not the identity model

No production SMTP/SMS provider is currently a proven HILTECH dependency.

Therefore:

- account provisioning/linking is the core capability;
- email action-link delivery is optional;
- absence of SMTP must not make onboarding architecture unusable;
- no fake “sent” state is allowed.

A safe manual/temporary-credential handoff may be supported as an operational fallback.

Temporary credentials:
- must be temporary at the identity provider;
- must require password change;
- must never be persisted in HILTECH plaintext;
- must never enter audit/activity/telemetry;
- must never be committed to Git;
- should require current People authority and re-authentication when exposed/changed.

Public self-registration remains disabled.

The native client remains Authorization Code + PKCE; do not enable Direct Access Grant/password grant to solve onboarding.

---

## 10. Identity provisioning crosses a provider boundary

Creating a Keycloak account and writing PostgreSQL business identity/membership state is not one database transaction.

Slice 04 must therefore fail closed.

A partially created provider account must never automatically imply HILTECH access.

Safe orchestration principle:

1. create/reserve a durable HILTECH invitation/provisioning intent;
2. create or recover the provider account idempotently;
3. create/link local `UserIdentity` + organization membership in non-active/pending state;
4. enable/finish provider account setup;
5. activate local identity/membership only after the HILTECH-side relationship is valid;
6. if finalization fails, bootstrap remains denied until retry/reconciliation completes.

Exact adapter mechanics may vary, but orphan/provider partial success must remain recoverable and fail closed.

---

## 11. Employee activation is a People transition

The Employee lifecycle already contains `PREBOARDING` and `ACTIVE`.

Slice 04 owns the explicit transition:

`PREBOARDING -> ACTIVE`

Activation:
- is not inferred from first login;
- is not inferred from uploading one document;
- is not inferred from an Employment row already being ACTIVE;
- is an authorized, idempotent, version-checked People command.

At activation time, blocking onboarding requirements are recomputed from current authoritative truth.

Do not trust a stale cached “100% complete” flag.

---

## 12. Policy revision must be pinned

Onboarding policy changes over time.

An onboarding case must pin the exact policy revision it started with.

Changing a later active policy revision must not silently rewrite the checklist under an employee already onboarding.

A future explicit rebase/migration operation can be added if real HILTECH policy requires it.

It is not required in this minimal slice.

---

## 13. Waiver must be explicit, not silent

HILTECH is flexible, but flexibility must be auditable.

A configured requirement may allow waiver.

If waived:
- People authority is required;
- reason is required;
- actor/time are recorded;
- the underlying document/certification truth is not faked.

A waiver satisfies onboarding policy only; it does not manufacture a VERIFIED document or valid Certification.

---

## 14. Real first-use experience

Representative human flow for Slice 04:

### People/Admin

`Employee PREBOARDING -> Start onboarding -> see checklist -> provision/link identity -> review submissions -> satisfy/waive allowed company items -> Activate`

### Employee

`Sign in -> onboarding home -> see “needs you / waiting on HILTECH / complete” -> update safe contact info -> upload allowed document -> see Team/manager/certifications -> wait for verification -> become ACTIVE -> normal HILTECH home`

This is the human flow that must be rendered/reviewed in addition to backend CI.

---

# Deferred / non-blocking facts

The following are deliberately not invented:

- exact HILTECH onboarding checklist;
- exact statutory Egyptian HR document requirements;
- probation rules;
- compensation/bank/payroll onboarding fields;
- exact training catalog;
- exact asset/PPE requirements;
- project/site first-day assignment;
- policy acknowledgement subsystem;
- production email/SMS provider;
- legal retention/deletion policy;
- recruitment/ATS.

These can be added through configuration or later authoritative domains.

---

# Reality closure decision

**SLICE 04 REALITY CLOSURE = PASS**

No additional user answer is required before freezing the implementation contract.

Why:
- unknown checklist content is correctly classified as configuration/activation data;
- existing People/Identity/WorkforceAssignment/Documents sources are authoritative;
- Keycloak provider capabilities are sufficient for a real identity-provisioning adapter;
- external email delivery is optional rather than a blocker;
- later Payroll/Assets/Projects/legal rules are explicitly out of scope.

Next:

`Phase 3 / Slice 04 — Onboarding + Basic Self-Service implementation contract`

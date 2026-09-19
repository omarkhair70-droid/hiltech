# Phase 3 — People / Internal Workforce Core Scope & Reality Closure

Date: 2026-09-20  
Status: **PHASE SCOPE CLOSED / SLICE PLAN FROZEN**

## Why Phase 3 exists

Phase 0–2 established engineering, identity/organization/authorization, and shared product infrastructure.

Phase 3 introduces HILTECH's workforce as first-class business truth so Projects, Work, Warehouse, Field, Finance and Payroll can depend on real employees instead of treating authentication identities as employees.

The goal is not to build a generic HR suite.

The goal is to establish the minimum durable People domain that later HILTECH operations require.

## Canonical reality used

- `docs/01-reality/REALITY_FACTS_REGISTER.md`
  - RF-004 compact field-oriented workforce snapshot;
  - RF-007 compact crews / scalable assignment;
  - RF-020 owner-final with Finance/Admin operational administration;
  - RF-021 broad/flexible mixed duties and exception-driven authority.
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
  - RE-001 field/work allowance belongs to later assignment/payroll context;
  - RE-002 legacy payroll files are evidence, not employee-master truth;
  - RE-006 owner coordination load;
  - RE-010 Arabic-first and conditional tracking.
- `docs/05-workflows/EMPLOYEE_LIFECYCLE.md`
- `docs/06-data/object-specs/PEOPLE_PAYROLL_OBJECTS.md`
- `docs/06-data/transition-tables/PEOPLE_PAYROLL_SUPPORT_TRANSITIONS.md`
- `docs/02-people/HUMAN_MAP.md`
- `docs/02-people/hr-admin.md`
- `docs/02-people/new-hire.md`
- `docs/03-product/role-experiences/HR_ADMIN_EXPERIENCE.md`
- `docs/10-design/wireflows/NEW_HIRE_OFFBOARDING_WIREFLOW.md`
- Phase 1 identity/team foundation and OpenFGA runtime.

## Reality conclusions

### 1. Identity is not Employee

A HILTECH login identity can exist without employment.

External/client/supplier identities also exist.

Therefore:
- `user_identity` remains authentication identity;
- People owns `Person`, `Employee`, `Employment`;
- employee may link to zero or one current HILTECH user identity;
- employee lifecycle must not be inferred from login status alone.

### 2. Existing Team foundation must be reused

Phase 1 already created:
- `team`;
- `team_membership`;
- `organization_membership`.

Phase 3 must not create a second Team model.

People becomes the business command/orchestration boundary for workforce team/manager/role changes while Phase 1 security continues to consume the authoritative relationship state for authorization.

### 3. HILTECH roles are broad and flexible

Current reality includes mixed duties and compact crews.

Therefore:
- do not hard-code a narrow enterprise job-title taxonomy;
- role/team/manager relationships remain configurable/effective-dated;
- one person's job title does not by itself determine every permission or work capability;
- authorization continues to come from explicit role/team/relationship policy.

### 4. Payroll is not Phase 3

Phase 3 may expose employment/payroll eligibility context later, but it must not implement:
- payroll calculation;
- salary components;
- Pocket Money calculation;
- overtime-to-payroll calculation;
- bank/payment execution;
- payslip generation.

Those belong to later phases.

### 5. Attendance, leave, expenses and advances are not Phase 3 core

Although older People discovery documents list them, the frozen implementation order for Phase 3 is narrower.

Do not pull into Phase 3:
- attendance engine;
- timesheets;
- overtime workflow;
- leave workflow;
- expense/reimbursement;
- employee advance/imprest.

They consume People later.

### 6. Recruitment depth is deferred

Candidate/interview/recruitment marketing is not required before internal workforce core.

Phase 3 starts from Person/Employee/Employment and onboarding of a known hire.

### 7. Personal data must remain minimized and scoped

The People domain contains restricted data.

Minimum baseline:
- ordinary operational users see only the workforce fields needed for work;
- legal ID/emergency/payroll/bank/compensation data must not leak into general People reads;
- binary HR documents use the existing Documents/Evidence boundary rather than new public file URLs;
- audit sensitive changes.

### 8. Arabic-first remains required

People/HR surfaces must support Arabic/RTL as a native path.

Codes, emails, IDs and technical tokens remain readable in mixed RTL/LTR content.

### 9. Existing employee-master source is still unknown, but does not block core schema

RF-010 records that current employee master/payroll source of truth is not fully validated.

Therefore:
- do not build a legacy import as Slice 01;
- do not claim Excel is canonical;
- build typed canonical People objects first;
- later import/migration must retain provenance and reconciliation.

### 10. Legal HR policy is deliberately not invented

Exact Egyptian:
- employment contract categories;
- statutory document requirements;
- probation rules;
- retention periods;
- termination/legal rules

are not frozen here.

The product model must leave these configurable/phase-local and must not invent legal semantics.

## Phase 3 slice plan

### Slice 01 — Employee / Employment Core

Build the canonical workforce registry:
- Person;
- Employee;
- Employment;
- optional link to current `user_identity`;
- organization scope;
- employee code;
- lifecycle state baseline;
- workforce directory/detail read models;
- safe create/update commands;
- exact version/idempotency/audit/activity;
- shared Android/Windows read client as needed for later slices.

No payroll/attendance/leave/import.

### Slice 02 — Workforce Assignment / Reporting Structure

Build the business relationship layer:
- reuse existing Team;
- employee↔team assignment;
- role code/label assignment;
- manager/reporting relationship;
- effective dates/history;
- org structure read models;
- authorization recomputation/projection integration.

No duplicate Team table.

### Slice 03 — HR Documents / Certifications

Build:
- employee document metadata/reference;
- certification/training record;
- issued/expiry/verification facts;
- safe employee eligibility read;
- existing Documents/Evidence storage integration.

Do not invent exact HILTECH-required document/certification catalogs; seed only synthetic/test examples until validated.

### Slice 04 — Onboarding + Basic Self-Service

Build:
- onboarding case/checklist;
- missing requirements/blockers;
- identity invitation/link where needed;
- employee sees own profile, team/manager, onboarding state, own allowed documents/certifications;
- activation command gated by configured requirements.

No separate onboarding app.

Exact required onboarding checklist by employee category is phase-local configuration and may require a narrow HILTECH validation before this slice freezes.

### Slice 05 — Role / Team / Manager Change

Build:
- effective-dated workforce change command;
- exact-version/idempotent transition;
- history preserved;
- security/authorization recomputation;
- activity/audit/inbox where action is required;
- project-impact hook only; Project reassignment behavior belongs to Phase 4.

### Slice 06 — Offboarding Skeleton

Build:
- offboarding case;
- last-working-date/reason category baseline;
- HR clearance;
- access/session revocation integration;
- typed external clearance slots/events for Project/Asset/Finance/Payroll;
- former-employee state only under the frozen minimal rules.

Do not implement Warehouse asset clearance, final payroll calculation, or Project transfer inside People before their authoritative modules exist.

## What is explicitly out of Phase 3

- candidate/interview ATS depth;
- attendance/time clock;
- leave;
- overtime;
- expense/advance/imprest;
- payroll;
- salary/compensation engine;
- bank/payment;
- warehouse custody;
- project assignment execution;
- field location tracking;
- disciplinary/performance management;
- medical data;
- full Learning Management System.

## Phase-level verification gates

Phase 3 is complete only when:

1. Person/Employee/Employment are canonical and organization-scoped.
2. identity and employee truth remain distinct.
3. existing Team foundation is reused, not duplicated.
4. team/role/manager changes preserve history and authorization correctness.
5. employee documents/certifications do not leak restricted data.
6. onboarding activation is typed/versioned/audited.
7. self-service is own-record scoped.
8. offboarding revokes access/session authority safely.
9. no later-domain truth is duplicated in People.
10. Android/Windows shared client contracts for Phase 3 surfaces compile and inherited Phase 0–2 regressions remain green.

## Immediate execution

**Slice 01 — Employee / Employment Core** is the next build target.

Its separate implementation contract freezes the minimum schema/commands/read models.

No new user question is required before Slice 01 because the unresolved employee-master/import/legal-policy details are explicitly scoped out.

# Phase 3 / Slice 01 — Employee / Employment Core

Date: 2026-09-20  
Status: **IMPLEMENTATION AUTHORIZED / CONTRACT FROZEN**

## Reality basis

Canonical Phase 3 scope closure:

`docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`

This slice establishes canonical People business truth only.

## Domain ownership

People owns:
- Person;
- Employee;
- Employment.

Identity owns:
- authentication subject;
- sessions/devices;
- login/account state.

Organizations owns:
- organization scope/membership foundation.

People may reference `user_identity.id`, but employment state must not be derived from authentication state.

## Minimum schema

### person

Required:
- `id uuid`;
- `organization_id uuid`;
- `display_name varchar`;
- `legal_name varchar null` — restricted;
- `mobile varchar null` — restricted;
- `email varchar null` — restricted;
- `created_at`;
- `updated_at`;
- `version bigint`.

Rules:
- organization scoped;
- display name required and bounded;
- do not add national ID, bank, salary, medical/emergency fields in Slice 01;
- later highly restricted fields get their own validated contract.

### employee

Required:
- `id uuid`;
- `organization_id uuid`;
- `person_id uuid`;
- `employee_code varchar`;
- `linked_user_identity_id uuid null`;
- `state`;
- `hire_date date null`;
- `end_date date null`;
- `created_at`;
- `updated_at`;
- `version bigint`.

Initial states:
- `PREBOARDING`;
- `ACTIVE`;
- `OFFBOARDING`;
- `FORMER`.

Rules:
- employee code unique per organization;
- one active employee record per person per HILTECH organization in this minimal model;
- one linked user identity cannot be linked to two current employees in the same organization;
- linked identity, when present, must belong to the same organization;
- `FORMER` is not created directly through normal create command;
- state transitions other than initial PREBOARDING/legacy ACTIVE seeding remain later Slice contracts.

### employment

Required:
- `id uuid`;
- `employee_id uuid`;
- `employment_type_code varchar null`;
- `start_date date`;
- `end_date date null`;
- `state`;
- `created_at`;
- `updated_at`;
- `version bigint`.

Initial states:
- `ACTIVE`;
- `ENDED`.

Rules:
- start date required;
- end date cannot precede start date;
- employment is a historical work-relationship period, not a one-row-for-life employee attribute;
- rehire/history must be representable with additional Employment rows;
- the minimal HILTECH baseline allows at most one current ACTIVE Employment per Employee because no concurrent-employment reality is verified today, but the model must not make future concurrent/multiple work relationships impossible without migration;
- no compensation/salary fields;
- `employment_type_code` is a configurable business code, not a hard-coded legal enum;
- Slice 01 does not implement contract/legal validation.

## Commands

### CreateEmployee

Input:
- operationId;
- organizationId;
- displayName;
- employeeCode;
- startDate;
- employmentTypeCode nullable;
- linkedUserIdentityId nullable;
- hireDate nullable;
- safe optional contact fields.

Behavior:
- create Person + Employee + Employment transactionally;
- initial Employee state defaults to PREBOARDING;
- Employment state is ACTIVE as a contractual/admin period record even while employee onboarding state is PREBOARDING;
- validate organization/identity consistency;
- idempotent by operationId;
- emit `EmployeeCreated`.

### LinkEmployeeIdentity

Purpose:
attach an existing HILTECH identity to an employee without conflating the records.

Input:
- operationId;
- employeeId;
- baseVersion;
- userIdentityId.

Rules:
- same organization;
- identity membership must be current/valid;
- exact version;
- idempotent;
- audit;
- emit `EmployeeIdentityLinked`.

### UpdateEmployeeProfile

Allowed Slice 01 fields:
- displayName;
- legalName;
- mobile;
- email.

Rules:
- exact baseVersion;
- authorization required;
- legalName/contact fields remain restricted;
- no role/team/manager changes here.

## Read models

### EmployeeDirectoryItem

Safe operational fields only:
- employeeId;
- employeeCode;
- displayName;
- employeeState;
- linkedIdentity: boolean;
- currentEmploymentTypeCode nullable;
- hireDate nullable;
- version.

No:
- legalName;
- mobile/email by default;
- compensation;
- documents;
- national ID;
- bank/payroll data.

### EmployeeDetail

Authorized People/HR view may add:
- legalName;
- mobile/email;
- employment dates/type code;
- linked identity reference;
- timestamps/version.

Sensitive fields remain permission scoped.

### OwnEmployeeProfile

If logged-in identity is linked:
- own safe profile/contact fields;
- own employee/employment state.

No peer HR/private data.

## API pattern

Use frozen shared conventions:
- `/v1/employees`;
- `GET /v1/employees`;
- `GET /v1/employees/{employeeId}`;
- `GET /v1/me/employee`;
- lifecycle commands use explicit action subroutes;
- `Idempotency-Key`;
- `X-Correlation-Id`;
- baseVersion for concurrency-sensitive updates;
- standard product error envelope.

Do not create generic CRUD PATCH semantics for lifecycle-sensitive state.

## Authorization

Minimum relations/capabilities:
- People admin can create/update authorized employee HR fields;
- employee can read own self-service-safe profile;
- operational users can read only safe directory fields where policy allows;
- cross-organization reads/writes fail closed.

Do not infer People admin purely from job-title text.

Use existing role/team/OpenFGA authorization relationships and explicit People permissions.

## Events

Required:
- `EmployeeCreated`;
- `EmployeeIdentityLinked`;
- `EmployeeProfileUpdated`.

Events carry:
- eventId;
- employeeId;
- organizationId;
- sourceVersion;
- occurredAt;
- actor identity;
- correlationId.

Do not put restricted profile values into shared event payloads.

## Audit / Activity / Inbox / Notification

Audit:
- create;
- identity link;
- restricted profile changes.

Activity:
- employee created/profile lifecycle facts may be projected where useful, without private values.

Inbox/Notification:
- no automatic user notification required in Slice 01.

## Client surface

Desktop:
- People → Employees list;
- employee core detail;
- create employee baseline.

Android:
- own employee profile read only;
- no broad employee-admin mobile UI required in Slice 01.

Shared KMP client:
- Employee directory/detail/self DTOs;
- typed create/link/update contracts where the current client surfaces require them.

## Legacy/import

Out of scope:
- Excel employee import;
- payroll workbook import;
- reconciliation/matching UI.

Later import must create canonical People objects through typed import/review commands with provenance.

## Explicit non-goals

Do not implement:
- Team/manager/role assignment;
- onboarding checklist/activation;
- certifications/documents;
- attendance;
- leave;
- overtime;
- expense/advance;
- compensation;
- payroll;
- offboarding;
- candidate/recruitment;
- project/work assignment;
- warehouse custody.

## Required evidence for VERIFIED

1. Flyway migration creates Person/Employee/Employment constraints.
2. jOOQ generation includes all three.
3. CreateEmployee is atomic and idempotent.
4. duplicate employee code in same organization is rejected safely.
5. same code may exist in another organization only if business scope permits by constraint.
6. multiple historical Employment periods for one Employee are representable, while the current minimal invariant prevents two simultaneously ACTIVE periods.
7. linked identity must belong to same organization/current membership.
8. one current identity cannot link to two current employees in same organization.
9. UpdateEmployeeProfile requires exact version and stale update conflicts.
10. safe directory never returns restricted legal/contact fields.
11. own-profile resolves only through linked current identity.
12. cross-organization reads/writes fail closed.
13. events contain no restricted field values.
14. audit exists for material commands.
15. shared error/idempotency/correlation conventions are reused.
16. real PostgreSQL contract test passes.
17. OpenFGA/authorization contract test passes.
18. inherited Phase 0–2 regression suites remain green.
19. Android/Windows shared-client compile remains green if DTO/client surface is added.

## Contract conclusion

**IMPLEMENTATION AUTHORIZED.**

Build only the Employee / Employment Core described above.

Do not expand into the remaining Phase 3 slices until Slice 01 is verified/merged.

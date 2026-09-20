# Phase 3 / Slice 01 — Employee / Employment Core

Date: 2026-09-20  
Status: **VERIFIED / READY TO MERGE**

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

People owns Person/Employee/Employment. Identity links to Person through the existing `user_identity.person_id`; employment state must not be derived from authentication state. Do not add a second Employee→Identity foreign key for the same relationship.

## Minimum schema

### person

Required:
- `id uuid`;
- `display_name varchar`;
- `legal_name varchar null` — restricted;
- `mobile varchar null` — restricted;
- `email varchar null` — restricted;
- `created_at`;
- `updated_at`;
- `version bigint`.

Rules:
- Person is the human identity independent of organization/employment;
- organization/tenant scoping is enforced through Employee, never by exposing Person directly across organization boundaries;
- display name required and bounded;
- do not add national ID, bank, salary, medical/emergency fields in Slice 01;
- later highly restricted fields get their own validated contract.

### employee

Required:
- `id uuid`;
- `organization_id uuid`;
- `person_id uuid`;
- `employee_code varchar`;
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
- identity linkage is derived through `user_identity.person_id`; do not duplicate it on Employee;
- a linked identity used for HILTECH self-service must have a current membership in the Employee organization;
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
- linkedUserIdentityId nullable — applied by setting the existing `user_identity.person_id` to the created Person after same-organization/current-membership validation;
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
attach an existing HILTECH identity to the Employee's Person through the existing `user_identity.person_id`, without conflating authentication identity with employment.

Input:
- operationId;
- employeeId;
- baseVersion;
- userIdentityId.

Rules:
- target Employee determines the HILTECH organization;
- identity membership in that organization must be current/valid;
- identity `person_id` must be NULL or already equal to this Employee's Person;
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
- linkedIdentity: boolean — derived from current identities whose `person_id = employee.person_id`;
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
- linked identity reference derived from `user_identity.person_id`;
- timestamps/version.

Sensitive fields remain permission scoped.

### OwnEmployeeProfile

If logged-in identity has `user_identity.person_id` matching an Employee Person in an organization where that identity has current membership:
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

Do not infer People admin from job-title text or from the broad `organization.admin` relation.

Slice 01 introduces an explicit effective-dated `PeopleAuthorityBinding` source in PostgreSQL and a scoped OpenFGA `organization.people_admin -> can_manage_people` projection. USER and TEAM principals are supported by the authority binding shape. Current organization/team membership is revalidated at decision time.

Grant/revoke projection uses the existing transactional authorization projection outbox and fail-closed guard. A revoked/expired binding must deny immediately from current PostgreSQL source even before OpenFGA tuple cleanup converges.

Initial real environment seeding of People admin authority is activation/configuration data; do not hard-code Mohamed, Ahmed, titles, or identity IDs in product code.

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
7. `user_identity.person_id` is the only Identity↔Person link; no duplicate Employee identity FK exists.
8. linked identity must have current membership in the Employee organization.
9. one Person maps to at most one Employee within the same organization.
10. UpdateEmployeeProfile requires exact version and stale update conflicts.
11. safe directory never returns restricted legal/contact fields.
12. own-profile resolves only through linked current identity/person and current organization membership.
13. cross-organization reads/writes fail closed.
14. events contain no restricted field values.
15. audit exists for material commands.
16. shared error/idempotency/correlation conventions are reused.
17. real PostgreSQL contract test passes.
18. OpenFGA/authorization contract test passes.
19. People admin grant is sourced by current PostgreSQL PeopleAuthorityBinding and projected through the authorization outbox; pending grant fails closed.
20. revoked/expired People authority denies immediately and converges to OpenFGA tuple removal.
21. broad organization admin/title text is not used as People authority.
22. inherited Phase 0–2 regression suites remain green.
23. Android/Windows shared-client compile remains green if DTO/client surface is added.

## Verification closure

Canonical tested code head:

`59c5cb9fb4d859260ba48a9f8edd5ecce5a97e96`

Exact-head verification:

- Contract — OpenFGA First Slice `35477856791` — **PASS**
- Bootstrap Phase 0 `35477856788` — **PASS**
  - database/jOOQ contract — PASS
  - local PostgreSQL/OpenFGA People contract — PASS
  - shared/client/foundation regressions — PASS
  - evidence-storage / Terraform / supply-chain — PASS
- Phase 1 Native OIDC Production Smoke `35477856782` — **PASS**
  - browser provider smoke — PASS
  - Windows production shell render — PASS
  - Android production shell render — PASS
- Phase 2 Shared Command Runtime `35477856796` — **PASS**

Verified implementation includes:

- Person / Employee / historical Employment persistence;
- one current ACTIVE Employment baseline with rehire/history representable;
- the existing `user_identity.person_id` as the only Identity↔Person link;
- safe employee directory, privileged detail and own-profile reads;
- idempotent employee create / identity-link / profile-update commands;
- exact-version conflict handling;
- audit + safe domain events;
- explicit effective-dated People authority binding source;
- fail-closed OpenFGA People authority projection including grant/revoke convergence;
- shared KMP People contracts/client;
- Android own-profile proof surface;
- Windows People directory/detail/create proof surface.

No Team/Manager, onboarding, certification/document, attendance, leave, payroll, offboarding or legacy-import scope was pulled into Slice 01.

## Contract conclusion

**VERIFIED / READY TO MERGE.**

Merge only the verified closure head after docs-only closure checks. After merge, require post-merge `main` Bootstrap PASS before opening Phase 3 Slice 02 implementation.

# Phase 3 / Slice 04 — Onboarding + Basic Self-Service

Date: 2026-09-20  
Status: **IMPLEMENTATION AUTHORIZED / CONTRACT FROZEN**

## Reality basis

`docs/13-delivery/phase3/04_ONBOARDING_SELF_SERVICE_REALITY_CLOSURE_2026-09-20.md`

## Goal

Implement the minimum real onboarding and own-record experience needed to move a known HILTECH Employee safely from `PREBOARDING` to `ACTIVE`.

The slice must:

- reuse Phase 1 identity/session/bootstrap;
- reuse Slice 01 Person/Employee/Employment;
- reuse Slice 02 WorkforceAssignment;
- reuse Slice 03 EmployeeDocument/Certification + Evidence;
- keep actual HILTECH checklist values configurable;
- make employee-vs-HILTECH blockers visible;
- support identity provisioning without making SMTP/SMS mandatory;
- render a representative Arabic-first onboarding flow.

No separate onboarding app.

---

# 1. Configuration extension

Extend the existing typed/versioned configuration model with:

`family = 'onboarding-policies'`

Add:

## onboarding_policy

- `config_revision_id uuid PK -> config_revision`

No mutable “current checklist” blob.

An OnboardingCase pins one exact active policy revision.

## onboarding_policy_requirement

Minimum fields:

- id;
- config_revision_id;
- requirement_key;
- requirement_type;
- label;
- responsibility;
- blocking;
- waiver_allowed;
- self_service_visible;
- employee_may_submit;
- evidence_required;
- profile_field_code nullable;
- document_type_code nullable;
- certification_type_code nullable;
- manual_confirmation_code nullable;
- sort_order.

Initial `requirement_type` values:

- `PROFILE_FIELD`;
- `IDENTITY_READY`;
- `WORKFORCE_ASSIGNMENT`;
- `EMPLOYEE_DOCUMENT`;
- `CERTIFICATION`;
- `MANUAL_CONFIRMATION`.

Initial `responsibility` values:

- `EMPLOYEE`;
- `HILTECH`;
- `SHARED`.

Type-specific constraints must prevent irrelevant target columns from being populated.

### Implementation clarification — EmployeeDocument Evidence requirement

Implementation review exposed one ambiguity in the frozen text: an onboarding `EMPLOYEE_DOCUMENT` requirement may legitimately be satisfied by verified metadata without binary Evidence, but the original field list did not carry that policy choice explicitly.

Add `evidence_required boolean` to the typed requirement row.

Rules:
- meaningful only for `EMPLOYEE_DOCUMENT`;
- defaults false at the generic schema level;
- real HILTECH policy explicitly decides whether binary Evidence is required for each document requirement;
- when true, only VERIFIED + READY Evidence satisfies the requirement;
- when false, VERIFIED document metadata may satisfy without Evidence;
- this is policy, not client authority, and does not change Slice 03 Evidence security.

This is a narrow contract clarification, not a scope expansion.

Examples:
- `EMPLOYEE_DOCUMENT` requires document_type_code;
- `CERTIFICATION` requires certification_type_code;
- `PROFILE_FIELD` requires a supported safe profile_field_code;
- `MANUAL_CONFIRMATION` requires manual_confirmation_code.

Do not encode real HILTECH statutory document lists in the migration.

Use synthetic/test policy fixtures only until real pilot configuration is approved.

---

# 2. OnboardingCase

Create `onboarding_case`.

Minimum fields:

- id;
- organization_id;
- employee_id;
- policy_config_revision_id;
- state;
- started_at;
- started_by_user_id;
- activated_at nullable;
- activated_by_user_id nullable;
- created_at;
- updated_at;
- version.

Initial states:

- `OPEN`;
- `ACTIVATED`.

Rules:

- Employee belongs to same organization;
- normal case start requires Employee `PREBOARDING`;
- policy revision must be ACTIVE and applicable to the organization/system scope;
- only one OPEN case per Employee;
- historical ACTIVATED case remains immutable history;
- policy revision is pinned and does not silently change when a new revision becomes active;
- READY percentage/state is derived, not a second authoritative lifecycle flag.

No candidate/application object.

---

# 3. Manual requirement resolution

Create `onboarding_manual_requirement_resolution`.

Minimum fields:

- id;
- onboarding_case_id;
- requirement_key;
- resolution;
- reason nullable;
- resolved_at;
- resolved_by_user_id;
- version.

Initial resolutions:

- `SATISFIED`;
- `WAIVED`.

Rules:

- only valid for `MANUAL_CONFIRMATION` requirements, except explicit waiver may target another requirement when its policy row has `waiver_allowed=true`;
- WAIVED always requires nonblank reason;
- People management authority required;
- underlying source truth is never modified/faked by a waiver;
- one current resolution per case/requirement;
- changes remain auditable.

---

# 4. Derived onboarding requirement state

Do not copy authoritative completion state from upstream domains.

The Onboarding read model evaluates current source truth.

Minimum output per requirement:

- requirementKey;
- label;
- responsibility;
- blocking;
- status;
- actionCode nullable;
- sourceStateCode nullable;
- sortOrder.

Initial statuses:

- `NEEDS_EMPLOYEE`;
- `WAITING_HILTECH`;
- `SATISFIED`;
- `WAIVED`;
- `BLOCKED`.

Required evaluation:

## PROFILE_FIELD

Supported initial self-service-safe fields:
- `MOBILE`;
- `EMAIL`.

Present/nonblank -> SATISFIED.

Missing -> NEEDS_EMPLOYEE.

Do not make legalName, national ID, salary, bank, medical or emergency fields self-editable in this slice.

## IDENTITY_READY

Satisfied only when:
- Employee Person has a linked `user_identity`;
- identity is current/usable by HILTECH;
- current organization membership exists for the Employee organization.

No linked/provisioned identity -> WAITING_HILTECH.

First login is not itself the Employee activation event.

## WORKFORCE_ASSIGNMENT

Current valid Slice 02 WorkforceAssignment exists -> SATISFIED.

Missing/invalid -> WAITING_HILTECH.

Do not duplicate Team/manager/role fields in Onboarding.

## EMPLOYEE_DOCUMENT

For configured document_type_code:

- no current document -> NEEDS_EMPLOYEE when employee_may_submit, otherwise WAITING_HILTECH;
- document REJECTED -> NEEDS_EMPLOYEE when resubmission is permitted;
- when `evidence_required=true`, document exists but Evidence is not READY -> NEEDS_EMPLOYEE;
- Evidence READY but business verification is UNVERIFIED -> WAITING_HILTECH;
- when `evidence_required=true`, VERIFIED + READY Evidence -> SATISFIED;
- when `evidence_required=false`, VERIFIED document metadata may satisfy without Evidence.

## CERTIFICATION

For configured certification_type_code:

VERIFIED + validNow -> SATISFIED.

Missing/unverified/expired -> WAITING_HILTECH in this slice because Certification creation/verification remains People-managed.

Do not create a second certification truth.

## MANUAL_CONFIRMATION

Explicit current resolution -> SATISFIED/WAIVED.

Otherwise status follows responsibility:
- EMPLOYEE -> NEEDS_EMPLOYEE only when a supported employee action exists;
- HILTECH -> WAITING_HILTECH;
- SHARED -> BLOCKED unless the contract defines a concrete actor action.

Do not create generic “employee checks a box saying done” for company-controlled work.

---

# 5. Commands

## StartOnboarding

Input:

- operationId;
- employeeId;
- baseEmployeeVersion;
- onboardingPolicyRevisionId;
- actor/correlation.

Rules:

- People management authority;
- Employee PREBOARDING;
- same organization;
- policy valid/active/applicable;
- idempotent;
- one OPEN case;
- emit `OnboardingStarted`;
- audit policy revision ID, not private values.

## ResolveManualOnboardingRequirement

Input:

- operationId;
- onboardingCaseId;
- baseVersion;
- requirementKey;
- resolution = SATISFIED or WAIVED;
- reason nullable;
- actor/correlation.

Rules:
- People management authority;
- exact version;
- configured requirement exists;
- waiver allowed when WAIVED;
- reason required for waiver;
- idempotent;
- emit safe `OnboardingRequirementResolved`.

## ActivateEmployee

Input:

- operationId;
- onboardingCaseId;
- caseBaseVersion;
- employeeBaseVersion;
- actor/correlation.

Rules:

- People management authority;
- Employee must still be PREBOARDING;
- case OPEN;
- recompute every blocking requirement from authoritative current truth inside the command boundary;
- any unsatisfied blocking requirement rejects with typed blockers;
- exact-version/idempotent;
- transition Employee PREBOARDING -> ACTIVE;
- mark case ACTIVATED;
- do not alter Employment history except where already-authoritative lifecycle rules require it;
- emit `EmployeeActivated` and `OnboardingActivated`;
- audit without private document/profile values.

Activation does not:
- calculate payroll;
- issue assets;
- assign Project/Site/Work;
- create bank data;
- infer legal compliance outside configured requirements.

---

# 6. Identity provisioning / invitation

Add a durable HILTECH orchestration record such as `employee_identity_invitation`.

Minimum business fields:

- id;
- organization_id;
- employee_id;
- requested_login/email;
- auth_provider;
- provider_subject nullable;
- user_identity_id nullable;
- state;
- delivery_mode;
- delivery_state;
- created_at;
- created_by_user_id;
- updated_at;
- version.

Provider secret/temporary password is **not** stored.

Initial provisioning states must distinguish at least:

- `PENDING_PROVIDER`;
- `PROVIDER_CREATED`;
- `LOCAL_PENDING`;
- `READY`;
- `FAILED_RETRYABLE`.

Exact names may differ if semantics remain equivalent.

Delivery modes:

- `EMAIL_ACTION_LINK` — only when configured;
- `TEMPORARY_PASSWORD_HANDOFF` — provider-native/manual fallback.

Delivery state must report truth:
- configured/sent/ready;
- not configured;
- failed/retryable.

Never claim an email/SMS was sent when no provider exists.

## ProvisionEmployeeIdentity

Rules:

- People management authority;
- employee PREBOARDING;
- employee has no conflicting linked identity;
- idempotent by HILTECH invitation/operation identity;
- use an Identity-owned provider port; People must not embed Keycloak HTTP calls directly;
- provider account is recoverable by stable HILTECH provisioning identity/marker;
- local `user_identity` uses provider issuer + provider subject;
- link remains through `user_identity.person_id`;
- current organization membership is created/activated through Identity/Organizations ownership;
- partial external success fails closed;
- a provider-created account without valid local ACTIVE identity/membership cannot bootstrap into HILTECH.

Keycloak adapter baseline:
- Admin REST user creation;
- required `UPDATE_PASSWORD` when temporary credential path is used;
- public registration remains disabled;
- no Direct Access Grant.

## Invitation delivery

Email action-link delivery may call Keycloak `execute-actions-email` only when SMTP/email transport is configured.

If it is not configured:
- provisioning may still reach READY;
- delivery reports NOT_CONFIGURED;
- People admin may use the temporary-credential handoff path.

Temporary credential handling:
- credential must be temporary at Keycloak;
- UI/server must not persist plaintext;
- no request/response body secret in audit, activity or telemetry;
- response/handoff screen must use no-store/no-cache behavior;
- re-authentication is required before issuing/resetting a temporary credential;
- repeated/ambiguous credential issuance must fail safely or rotate explicitly; never pretend the same secret can be replayed if it was not retained.

Do not build an SMTP server, SMS gateway or custom password grant in this slice.

---

# 7. Own profile self-service

Add/update own-profile command under the current linked identity.

Initial employee-editable fields:

- mobile;
- email.

Rules:
- own Employee only;
- current organization membership required;
- exact Employee/Person version guard;
- idempotent;
- audit field names only, not private values;
- legalName remains People-admin controlled;
- no salary/bank/national-ID fields.

---

# 8. Own assignment read

Reuse Slice 02.

Self-service onboarding surface may show:
- Team;
- role label/code;
- reporting manager.

Do not create onboarding copies of those fields.

---

# 9. Own EmployeeDocument self-service

Add own-record endpoints/read authorization.

Minimum:

- list own self-service-visible documents;
- create own document metadata only for a policy/document type that permits employee submission;
- reserve/finalize Evidence through existing `EMPLOYEE_DOCUMENT` target;
- read own evidence state/reference required for progress;
- no peer access;
- no direct/public permanent file URL.

People management verification remains admin-only.

Extend `EMPLOYEE_DOCUMENT` Evidence authorization:

allow if either:
- current People management authority; or
- target Employee resolves to current linked actor identity **and** current onboarding/self-service policy permits that document action.

Do not widen WorkOrder Evidence authority.

---

# 10. Own Certification self-service

Employee may read their own certification facts:

- type/label;
- issuer;
- issuedAt;
- validUntil;
- verificationState;
- validNow.

Employee may not verify certifications.

Employee-side Certification creation remains out of this minimal slice unless later reality explicitly requires it.

---

# 11. API/read surfaces

Minimum server surfaces may include equivalent routes:

People/Admin:
- `POST /v1/employees/{employeeId}/onboarding`
- `GET /v1/employees/{employeeId}/onboarding`
- `POST /v1/onboarding/{caseId}/requirements/{requirementKey}/resolve`
- `POST /v1/onboarding/{caseId}/activate`
- `POST /v1/employees/{employeeId}/identity-invitations`

Employee:
- `GET /v1/me/onboarding?organizationId=...`
- `PUT /v1/me/employee/profile`
- `GET /v1/me/employee-documents?organizationId=...`
- `POST /v1/me/employee-documents`
- `GET /v1/me/certifications?organizationId=...`

Exact route spelling may follow current controller conventions, but command/query meaning must remain typed.

All commands use the established:
- Idempotency-Key where semantically replayable;
- correlation ID;
- product error envelope;
- authorization boundary;
- safe telemetry.

---

# 12. Client / human-flow requirement

## Android

Before ACTIVE:
- onboarding becomes the primary useful employee surface after sign-in;
- show progress summary;
- show separate “needs you” and “waiting on HILTECH” groups;
- safe own contact update;
- own allowed document submission/upload;
- team/manager context;
- own certification state.

After ACTIVE:
- onboarding remains viewable as completed history where useful, but normal role-aware experience becomes primary.

## Windows

People/Admin:
- employee onboarding detail;
- current blockers grouped by responsibility;
- identity provision/link action;
- verification/source state;
- manual resolve/waive when allowed;
- Activate action only when current blocking requirements are satisfied.

## Human proof

Required before VERIFIED:
- rendered Arabic-first Android preboarding flow;
- rendered Windows People/Admin onboarding flow;
- employee document submission -> waiting verification -> satisfied transition;
- at least one HILTECH-owned blocker (e.g. WorkforceAssignment) visibly shown as waiting on HILTECH rather than employee fault.

Do not declare this slice complete from server tests alone.

---

# 13. Authorization / privacy

People/Admin actions:
- current explicit People management authority.

Employee actions:
- resolve strictly through linked current identity/person + current organization membership;
- own record only.

Never infer authority from:
- job title;
- role_code text;
- employee code;
- broad organization membership alone.

Sensitive boundaries:
- no peer HR documents;
- no peer onboarding checklist;
- no provider credential leakage;
- no private document signed URL in normal JSON;
- no restricted profile values in shared events/telemetry.

Cross-organization access fails closed.

---

# 14. Events / attention

Required safe events:

- `OnboardingStarted`;
- `OnboardingRequirementResolved`;
- `EmployeeIdentityProvisioned`;
- `EmployeeActivated`;
- `OnboardingActivated`.

Inbox/Work Queue may receive actionable People items when useful, but do not invent a broad HR task engine.

Notification delivery remains provider-neutral.

If email/push/SMS provider is absent, internal onboarding state remains authoritative and usable.

---

# 15. Concurrency / failure behavior

Required:

- StartOnboarding idempotent;
- only one OPEN case per Employee;
- exact-version manual resolution;
- activation exact-version on case + Employee;
- activation recomputes source-backed blockers at command time;
- two concurrent activation attempts cannot double-activate;
- stale onboarding client cannot overwrite newer requirement resolution;
- identity provisioning retry cannot create duplicate provider/local identities;
- provider partial success is recoverable and bootstrap fails closed;
- self-service upload uses existing Evidence idempotency/integrity rules;
- identity/session revocation remains Phase 1 behavior.

---

# 16. Explicit non-goals

Do not implement:

- ATS/recruitment;
- salary/compensation;
- bank/payroll onboarding;
- payroll calculation;
- leave/attendance/overtime;
- expense/advance;
- asset/PPE custody;
- Project/Site/Work assignment;
- policy-document management subsystem;
- legal/statutory checklist assumptions;
- full LMS/training platform;
- public self-registration;
- new password grant;
- new SMS/email provider;
- separate file storage;
- peer employee HR access;
- Slice 05 role/team/manager change workflow;
- Slice 06 offboarding.

---

# 17. Required evidence for VERIFIED

1. V0018 migration creates onboarding case/manual resolution and typed onboarding-policy configuration safely.
2. jOOQ generation compiles.
3. policy revision is pinned to case and later config changes do not silently change the case.
4. no hard-coded real HILTECH checklist is introduced.
5. requirement evaluation consumes Slice 01/02/03 authoritative sources rather than copied flags.
6. employee-vs-HILTECH blocker classification is correct.
7. own profile update is own-record only and restricted to allowed fields.
8. self-service document read/create is own-record + policy scoped.
9. self-service Evidence reserve/finalize reuses existing `EMPLOYEE_DOCUMENT` lifecycle.
10. existing People-admin document authorization remains green.
11. existing WorkOrder Evidence authorization remains green.
12. own certification read leaks no peer/private data.
13. identity provisioning is idempotent and duplicate-safe.
14. provider partial success remains fail-closed.
15. no SMTP configured -> honest delivery state; core onboarding remains usable.
16. temporary credential handling stores/logs no plaintext secret.
17. public registration and Direct Access Grant remain disabled.
18. activation blocks on every unsatisfied blocking requirement.
19. activation is idempotent/exact-version and only PREBOARDING -> ACTIVE.
20. waiver requires policy permission + reason + People authority.
21. cross-organization reads/writes fail closed.
22. audit/events contain no private profile/document/credential values.
23. shared Android/Windows client contracts compile.
24. Android Arabic-first preboarding human flow renders.
25. Windows People/Admin onboarding human flow renders.
26. inherited Phase 0–2 and Phase 3 Slice 01–03 regressions PASS.
27. Spring Modulith boundaries PASS.
28. exact-head CI PASS.

---

# Contract conclusion

**IMPLEMENTATION AUTHORIZED.**

Implement only the Onboarding + Basic Self-Service contract above.

Unknown real HILTECH checklist values remain configuration/activation data and are not a blocker.

The next code slice must not pull Slice 05 assignment-change or Slice 06 offboarding behavior forward.

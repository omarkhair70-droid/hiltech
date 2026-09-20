# Phase 3 / Slice 06 — Offboarding Skeleton

Date: 2026-09-20  
Status: **VERIFIED / MERGED**

## Reality basis

`docs/13-delivery/phase3/06_OFFBOARDING_SKELETON_REALITY_CLOSURE_2026-09-20.md`

## Goal

Implement one safe People-owned offboarding coordinator around the already-frozen Employee, Employment, WorkforceAssignment and Phase 1 Identity/Security truth.

No later-domain business engine is pulled into People.

---

# 1. Schema — V0020

Add `offboarding_case`:

- id uuid PK;
- operation_id uuid UNIQUE;
- organization_id uuid FK organization;
- employee_id uuid + organization composite FK;
- state: OPEN / COMPLETED;
- last_working_date date;
- reason_category_code varchar(80), normalized code format;
- note varchar(500) nullable;
- started_at timestamptz;
- started_by_user_id uuid FK user_identity;
- completed_at timestamptz nullable;
- completed_by_user_id uuid nullable FK user_identity;
- created_at / updated_at;
- version bigint >= 1.

Constraints:

- one OPEN case per employee;
- COMPLETED requires completed_at/completed_by;
- OPEN requires completion fields null;
- last_working_date cannot precede employee hire date when hire date exists.

Add `offboarding_clearance`:

- id uuid PK;
- offboarding_case_id uuid FK;
- clearance_type:
  - HR;
  - ACCESS;
  - PROJECT;
  - ASSET;
  - FINANCE;
  - PAYROLL;
- state:
  - PENDING;
  - CLEAR;
  - NOT_APPLICABLE;
  - EXCEPTION_ACCEPTED;
- reason varchar(500) nullable;
- resolved_at nullable;
- resolved_by_user_id nullable;
- source:
  - PEOPLE;
  - IDENTITY;
  - EXTERNAL_DOMAIN;
- version bigint >= 1;
- unique case + clearance_type.

Rules:
- EXCEPTION_ACCEPTED requires reason.
- ACCESS cannot be written CLEAR through generic manual resolution.
- unresolved PENDING has no resolver/time.
- resolved states require resolver/time except an authoritative system-derived ACCESS update may use the initiating actor/system bridge identity according to the server contract.

No asset/project/payroll/finance business rows are created.

---

# 2. StartOffboarding command

Add typed command:

`StartEmployeeOffboarding`

Input:

- operationId;
- employeeId;
- baseEmployeeVersion;
- lastWorkingDate;
- reasonCategoryCode;
- note nullable;
- actorUserId;
- correlationId.

Rules:

- People manage authority required;
- employee exists in active organization;
- employee state must be ACTIVE;
- active Employment must exist;
- no other OPEN offboarding case;
- exact Employee version;
- lastWorkingDate >= active Employment.startDate;
- deterministic case ID from operationId;
- idempotent request fingerprint;
- Employee becomes OFFBOARDING;
- create all six clearance slots;
- HR/PJT/ASSET/FINANCE/PAYROLL start PENDING;
- ACCESS starts CLEAR only if authoritative access facts are already absent, otherwise PENDING;
- emit/audit `EmployeeOffboardingStarted`.

Do not end Employment here.

Do not end WorkforceAssignment here unless access revocation is explicitly executed in the same command contract — baseline is separate.

---

# 3. RevokeOffboardingAccess command

Add:

`RevokeEmployeeOffboardingAccess`

Input:

- operationId;
- offboardingCaseId;
- baseCaseVersion;
- actorUserId;
- correlationId.

Rules:

- People manage authority;
- case OPEN;
- Employee OFFBOARDING;
- exact case version;
- operation idempotent.

Real effects, in one transaction where local DB state is concerned:

1. resolve linked HILTECH identity for the Employee Person if present;
2. end active HILTECH organization membership for that identity:
   - state ENDED;
   - valid_until = revokedAt;
   - version++;
3. revoke all active HILTECH `identity_session` rows for that identity;
4. end current WorkforceAssignment if present at revokedAt;
5. end People-owned Team membership sourced by that assignment;
6. reconcile OpenFGA Team membership projection;
7. recompute ACCESS clearance from authoritative facts;
8. ACCESS -> CLEAR only when no active HILTECH org membership, no active HILTECH session, and no current People-owned assignment authority remains;
9. audit + event.

Do not delete the Keycloak/provider user.

Do not set global `user_identity.status = REVOKED` in baseline.

Do not wipe/revoke every device automatically.

If there is no linked identity, ACCESS can still become CLEAR after current People-owned assignment authority is ended and no HILTECH identity membership exists.

---

# 4. Identity boundary extension

Phase 1 Identity owns session/security primitives.

Add a narrow server-side administrative port owned by Identity, for People to call:

`EmploymentAccessRevocationPort`

It must support:
- end HILTECH organization membership for one identity/org;
- revoke all active HILTECH sessions for the identity;
- return an authoritative access snapshot.

People must not embed Keycloak admin logic.

People must not reach through public self-service endpoints.

The bridge is an internal module contract with tests.

---

# 5. WorkforceAssignment offboarding close

Add/reuse a narrow People helper:

`endCurrentAssignmentForOffboarding(employeeId, at)`

Rules:

- no replacement revision;
- current ACTIVE assignment -> ENDED;
- effective_to = at;
- version increments;
- corresponding People-owned Team membership valid_until = at;
- OpenFGA aggregate reconciled just after the boundary;
- history preserved.

This is not a Slice 05 role/team change and must not emit a fake replacement assignment.

Emit a safe assignment-ended/offboarding security event if useful; do not manufacture Project reassignment.

---

# 6. HR clearance command

Add:

`ResolveOffboardingHrClearance`

Input:

- operationId;
- caseId;
- baseCaseVersion;
- resolution:
  - CLEAR;
  - NOT_APPLICABLE;
  - EXCEPTION_ACCEPTED;
- reason nullable;
- actorUserId;
- correlationId.

For HR:
- CLEAR is normal.
- EXCEPTION_ACCEPTED requires reason.
- NOT_APPLICABLE is allowed only as an explicit audited decision.

Version the case/clearance.

---

# 7. External clearance command

Add:

`ResolveOffboardingExternalClearance`

Allowed types:
- PROJECT;
- ASSET;
- FINANCE;
- PAYROLL.

Allowed resolutions:
- CLEAR;
- NOT_APPLICABLE;
- EXCEPTION_ACCEPTED.

Rules:

- People manage authority in the skeleton;
- explicit reason required for EXCEPTION_ACCEPTED;
- resolution source = PEOPLE until a later authoritative domain integration updates via its typed port/event;
- audit must make clear this is a coordination resolution, not underlying domain transaction truth.

ACCESS is forbidden through this command.

HR has its own command.

---

# 8. CompleteOffboarding command

Add:

`CompleteEmployeeOffboarding`

Input:

- operationId;
- caseId;
- baseCaseVersion;
- baseEmployeeVersion;
- baseEmploymentVersion;
- actorUserId;
- correlationId.

Re-evaluate all facts inside the transaction.

Preconditions:

- case OPEN;
- Employee OFFBOARDING;
- exact versions;
- server LocalDate >= lastWorkingDate;
- active Employment exists;
- HR clearance resolved to CLEAR / NOT_APPLICABLE / EXCEPTION_ACCEPTED;
- ACCESS = CLEAR from authoritative re-check;
- PROJECT / ASSET / FINANCE / PAYROLL each resolved to CLEAR / NOT_APPLICABLE / EXCEPTION_ACCEPTED;
- no current People-owned WorkforceAssignment authority remains.

Effects:

- active Employment -> ENDED;
- Employment.end_date = lastWorkingDate;
- Employee -> FORMER;
- Employee.end_date = lastWorkingDate;
- case -> COMPLETED;
- audit + `EmployeeOffboarded` / `EmploymentEnded` event;
- history remains.

Failure must return typed blockers, not partially transition to FORMER.

---

# 9. Read model/API

People/Admin:

- `POST /v1/employees/{employeeId}/offboarding`
- `GET /v1/employees/{employeeId}/offboarding`
- `POST /v1/offboarding/{caseId}/access/revoke`
- `POST /v1/offboarding/{caseId}/hr-clearance/resolve`
- `POST /v1/offboarding/{caseId}/clearances/{clearanceType}/resolve`
- `POST /v1/offboarding/{caseId}/complete`

Read model includes:

- case id/state/version;
- employee id/code/display name/state/version;
- active employment id/version/start;
- last working date;
- reason category;
- safe note;
- six clearance slots with source/state/reason/timestamps;
- access facts summary:
  - linked identity present;
  - active HILTECH membership;
  - active sessions count;
  - current workforce assignment present;
- `canComplete`;
- typed blockers.

Do not expose restricted unrelated HR document contents.

---

# 10. Authorization

All offboarding admin commands/reads use current People manage authority.

Employee self-service does not gain offboarding administration.

After HILTECH organization membership/access revocation:
- the departing identity must fail normal HILTECH access/session/bootstrap rules.

Historical People/Admin read remains available to authorized People admins.

---

# 11. Audit/events

Minimum events:

`EmployeeOffboardingStarted`

`EmployeeOffboardingAccessRevoked`

`OffboardingClearanceResolved`

`EmploymentEnded`

`EmployeeOffboarded`

Audit actions:

- EMPLOYEE_OFFBOARDING_STARTED;
- EMPLOYEE_OFFBOARDING_ACCESS_REVOKED;
- OFFBOARDING_CLEARANCE_RESOLVED;
- EMPLOYEE_OFFBOARDED.

Safe diffs only.

Do not put private notes or credential/session secrets in activity/audit payloads.

---

# 12. Windows / human proof

Windows People/Admin representative flow:

1. employee ACTIVE with current employment/assignment;
2. Start Offboarding;
3. employee visibly OFFBOARDING;
4. show last working date + reason;
5. show six clearance cards;
6. ACCESS clearly says whether real access still exists;
7. Revoke Access;
8. ACCESS visibly becomes CLEAR from system facts;
9. Project/Asset/Finance/Payroll resolution clearly says it is coordination status;
10. Complete remains blocked until all required slots resolved and date gate satisfied;
11. successful Complete shows FORMER + preserved history.

The UI must include a visible boundary message:

`Project / Asset / Finance / Payroll clearances are coordination records here; their business transactions remain in their own HILTECH modules.`

No Android admin offboarding surface is required.

---

# 13. Required evidence for VERIFIED

1. V0020 migration PASS.
2. one OPEN case per employee.
3. Start command idempotent/exact-version.
4. ACTIVE -> OFFBOARDING only.
5. active Employment not ended by Start.
6. access revocation ends HILTECH org membership.
7. all active HILTECH sessions revoked.
8. provider/Keycloak user not deleted.
9. device history not blindly wiped.
10. current WorkforceAssignment ends with history preserved.
11. People-owned Team/OpenFGA authority removed correctly.
12. ACCESS cannot be manually spoofed CLEAR.
13. external clearance EXCEPTION_ACCEPTED requires reason.
14. no Project/Asset/Finance/Payroll business rows mutated.
15. date gate blocks early completion.
16. unresolved clearance blocks completion.
17. completion ends active Employment at lastWorkingDate.
18. completion sets Employee FORMER + endDate.
19. FORMER employee cannot retain HILTECH organization authority.
20. commands are idempotent/concurrency-safe.
21. audit/events emitted.
22. shared KMP clients compile.
23. Windows offboarding flow renders.
24. inherited Slice 01–05 / Phase 0–2 / OIDC / OpenFGA regressions PASS.
25. exact-head CI PASS.

---

# Explicit non-goals

No Warehouse custody engine.

No Project reassignment.

No final-pay/payroll calculation.

No finance settlement.

No MDM/device wipe.

No physical access hardware.

No provider account deletion.

No future auto-termination scheduler.

No legal-policy engine.

No rehire.

---

# Verification closure

Canonical tested code head:

`d88f5234bc100c5541c7d27608435e59c5fd986f`

Exact-head verification:

- Bootstrap Phase 0 `35494892676` attempt 2 — **PASS**
  - database-contract — PASS
  - foundation — PASS
  - evidence-storage-contract — PASS
  - supply-chain-contract — PASS
  - terraform-contract — PASS
  - dependency-review — PASS
  - local-platform-contract — PASS
- Phase 2 Shared Command Runtime `35494892627` — **PASS**
- Phase 1 Native OIDC Production Smoke `35494892665` — **PASS**
- Phase 3 Onboarding Human Proof `35494892659` — **PASS**
- Phase 3 Assignment Change Human Proof `35494892649` — **PASS**
- Phase 3 Offboarding Human Proof `35494892672` — **PASS**

The first local-platform attempt exposed an inherited Activity projection contract flake; the exact same Slice 06 head passed the full local-platform contract on rerun. The dedicated Offboarding PostgreSQL contract itself passed and proves the Slice 06 access/history invariants.

Verified implementation includes:

- V0020 `offboarding_case` + typed six-slot clearance schema;
- ACTIVE -> OFFBOARDING -> FORMER lifecycle without deleting Employee/Employment history;
- Start Offboarding leaves active Employment intact;
- real HILTECH organization-membership and session revocation through an internal Identity-owned boundary;
- organization membership revocation projected to OpenFGA rather than leaving stale provider authority;
- current WorkforceAssignment + People-owned Team authority safely closed without fake replacement assignment;
- ACCESS clearance derived from authoritative Identity/People facts and not manually spoofable;
- HR clearance + Project/Asset/Finance/Payroll coordination clearances with explicit source/boundary semantics;
- completion date/version/blocker gates;
- Employment ENDED + Employee FORMER with preserved history;
- provider/Keycloak identity preserved; device history is not blindly wiped;
- no Project, Asset, Finance or Payroll business transaction engine pulled into People;
- shared KMP contracts/client;
- Windows People/Admin offboarding surface;
- dedicated PostgreSQL contract proof;
- rendered Windows human-flow proof for access pending, system-verified access clear and completed former state;
- inherited Slice 01–05 / Phase 0–2 / OIDC/OpenFGA regressions preserved.

# Merge closure

Final closure head:

`4d941970563b84245061d5ab80101550243b6000`

Exact-head verification:

- Bootstrap Phase 0 `35495373713` attempt 2 — **PASS**
- Phase 2 Shared Command Runtime `35495373785` — **PASS**
- Phase 1 Native OIDC Production Smoke `35495373737` — **PASS**
- Phase 3 Onboarding Human Proof `35495373800` — **PASS**
- Phase 3 Assignment Change Human Proof `35495373780` — **PASS**
- Phase 3 Offboarding Human Proof `35495373723` — **PASS**

PR #51 merged at:

`bc6b61cca744c17814e58d5e212812339cd96409`

Post-merge Bootstrap:

- `35495676983` — **PASS**
  - foundation — PASS
  - database-contract — PASS
  - local-platform-contract — PASS
  - evidence-storage-contract — PASS
  - terraform-contract — PASS
  - supply-chain-contract — PASS
  - dependency-review intentionally skipped on main push; exact-head PR review already passed.

# Contract conclusion

**VERIFIED / MERGED.**

Slice 06 is closed.

Phase 3 final gap review:
`docs/13-delivery/phase3/07_PHASE3_FINAL_GAP_REVIEW_2026-09-20.md` — **PASS / NO ADDITIONAL PHASE 3 SLICE REQUIRED**.

Phase 3 is VERIFIED / COMPLETE.

Do not start Phase 4 production code before the owner-facing Phase 4 entry review is discussed and frozen.

# Phase 3 / Slice 05 — Workforce Assignment Change Workflow

Date: 2026-09-20  
Status: **IMPLEMENTATION AUTHORIZED / CONTRACT FROZEN**

## Reality basis

`docs/13-delivery/phase3/05_WORKFORCE_ASSIGNMENT_CHANGE_REALITY_CLOSURE_2026-09-20.md`

## Goal

Implement one safe lifecycle command that replaces the current `WorkforceAssignment` with a new revision while preserving history and synchronizing People-owned Team authorization.

No second role/team/manager model.

No Project reassignment.

No future-effective scheduler.

---

# 1. Schema extension — V0019

Extend `workforce_assignment` with:

- `supersedes_assignment_id uuid NULL REFERENCES workforce_assignment(id) ON DELETE RESTRICT`
- unique non-null `supersedes_assignment_id` so one assignment cannot be superseded twice by competing revisions.

Initial Slice 02 rows remain valid with NULL.

No new transfer/master table.

The revision chain is:

`old assignment <- supersedes_assignment_id - new assignment`

---

# 2. Time semantics

Slice 05 initial change boundary is server command time.

For the old current assignment:

- `state = ENDED`
- `effective_to = changeAt`
- `updated_at = changeAt`
- version increments.

For the new assignment:

- `state = ACTIVE`
- `effective_from = changeAt`
- `effective_to = NULL`
- version starts at 1;
- `supersedes_assignment_id = old.assignment_id`.

The two rows form a contiguous revision boundary.

Do not accept a client-supplied future activation timestamp in this slice.

Future scheduling requires a safe authorization activation mechanism and is deferred.

---

# 3. Change command

Add typed command:

`ChangeWorkforceAssignment`

Input:

- operationId;
- employeeId;
- currentAssignmentId;
- baseAssignmentVersion;
- teamId nullable;
- roleCode;
- roleLabel nullable;
- reportsToEmployeeId nullable;
- actorUserId;
- correlationId.

Rules:

- explicit current People management authority;
- employee exists in same active organization;
- employee is not FORMER;
- currentAssignmentId is the employee's current ACTIVE assignment;
- exact current assignment version must match;
- proposed Team must be active and same organization;
- proposed manager must be valid same-organization employee;
- self-report and reporting cycles rejected;
- command is idempotent;
- no in-place role/team/manager mutation;
- change timestamp comes from server Clock;
- deterministic new assignment ID from operationId;
- old assignment closes and new revision inserts in one transaction;
- conflict/failure leaves one coherent current assignment.

A no-op proposed assignment identical to current Team/role/manager is rejected with a typed `WORKFORCE_ASSIGNMENT_NO_CHANGE` error rather than manufacturing history.

---

# 4. Persistence/concurrency

Required DB behavior:

1. lock organization;
2. lock employee;
3. lock current assignment row;
4. verify current assignment ID/version;
5. validate Team/manager/cycle against current authoritative state;
6. end old assignment;
7. insert new assignment revision;
8. update People-owned Team membership validity/projection;
9. audit/events/outbox;
10. commit.

Concurrent change attempts:

- only one can close the same current assignment version;
- loser receives typed version/current-assignment conflict;
- unique active assignment invariant remains true.

Do not mutate Employee merely to force assignment versioning.

---

# 5. Team membership / authorization synchronization

When old assignment has a People-owned Team membership:

- set its `valid_until = changeAt`;
- increment membership version;
- synchronize the Team-membership authority aggregate.

When new assignment has Team:

- create/reuse deterministic People-owned Team membership sourced by the new assignment;
- `valid_from = changeAt`;
- synchronize Team-membership authority aggregate.

Important ordering:

Perform both source changes before evaluating final projection state where possible, so same-Team role changes do not transiently remove valid Team membership.

If another membership source keeps the same Team relation current, aggregate authority remains PRESENT.

If Team changes:
- old Team relation resolves ABSENT when no other source remains;
- new Team relation resolves PRESENT.

Role code is retained in the People-owned `team_membership.role_in_team` source record but is not treated as a standalone permission.

Reporting manager does not mutate `team.manager_user_identity_id`.

---

# 6. Read/history surfaces

Keep existing current reads.

Add People/Admin assignment history:

`GET /v1/employees/{employeeId}/workforce-assignments`

Output ordered newest-effective-first and includes:

- assignmentId;
- supersedesAssignmentId nullable;
- Team;
- role;
- reporting manager;
- state;
- effectiveFrom/effectiveTo;
- version.

Employee own current assignment continues to use the existing own read.

Own history is not required in this minimal slice.

---

# 7. API

Add equivalent command route:

`POST /v1/employees/{employeeId}/workforce-assignment/change`

Use:
- Idempotency-Key;
- standard correlation ID;
- product error envelope;
- safe telemetry.

Do not expose an arbitrary PATCH over assignment rows.

---

# 8. Events / audit / downstream hook

Emit:

`WorkforceAssignmentChanged`

Minimum safe event fields:

- previousAssignmentId;
- newAssignmentId;
- organizationId;
- employeeId;
- previousTeamId nullable;
- newTeamId nullable;
- previousRoleCode;
- newRoleCode;
- previousReportsToEmployeeId nullable;
- newReportsToEmployeeId nullable;
- sourceVersion;
- occurredAt;
- actorUserId;
- correlationId.

Audit action:

`WORKFORCE_ASSIGNMENT_CHANGED`

Safe diff records field names / identifiers, not unrelated private HR data.

The event is the Phase 4 project-impact hook.

Slice 05 does not modify Project/Site/Work records.

Do not invent Inbox work unless a real current source-domain action exists.

---

# 9. UI / human proof

Windows People/Admin:

- current assignment card;
- Change Assignment action;
- current vs proposed Team / role / manager;
- explicit confirmation;
- visible message: Project/Site/Work assignments are not changed by this People action;
- history list after success.

Android/employee:

- existing own Team/role/manager surface refreshes to the new current assignment;
- no admin change UI.

Required representative human proof before VERIFIED:

1. Windows current -> change -> new current + history.
2. changed Team visibly updates.
3. reporting manager change visibly updates.
4. Project-impact warning is visible.
5. employee self-service current assignment reflects the new current truth.

---

# 10. Explicit non-goals

Do not implement:

- future-effective scheduling;
- background activation scheduler;
- bulk/mass changes;
- transfer request/approval workflow;
- compensation/payroll change;
- Project/Site/Work reassignment;
- project access mutation;
- Team manager model rewrite;
- Employee role/team/manager duplicate fields;
- Slice 06 offboarding.

---

# 11. Required evidence for VERIFIED

1. V0019 migrates safely over Slice 02 data.
2. supersedes revision link is constrained and duplicate supersession is rejected.
3. change command is idempotent.
4. exact current assignment version/current ID required.
5. no-op change rejected.
6. previous row becomes ENDED with preserved history.
7. new row becomes the only ACTIVE assignment.
8. Team same-org/active validation preserved.
9. reporting self/cycle rejection preserved.
10. old People-owned Team membership is time-closed.
11. new People-owned Team membership is synchronized.
12. same-Team role change does not remove valid Team authority.
13. Team change removes old OpenFGA relation only when no other source remains and adds the new one.
14. reporting-manager change does not mutate Team manager authority.
15. history read is People-authorized and organization-safe.
16. `WorkforceAssignmentChanged` + audit emitted with safe fields.
17. no Project/Payroll mutation occurs.
18. shared KMP client contracts compile.
19. Windows change/history flow renders.
20. employee own current assignment still works.
21. inherited Slice 01–04 / Phase 0–2 / OIDC / OpenFGA regressions PASS.
22. exact-head CI PASS.

---

# Contract conclusion

**IMPLEMENTATION AUTHORIZED.**

Implement only the Workforce Assignment Change Workflow contract above.

Do not open Slice 06 Offboarding before Slice 05 is verified/merged.

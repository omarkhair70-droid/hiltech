# Phase 3 / Slice 02 — Workforce Assignment / Reporting Structure

Date: 2026-09-20  
Status: **IMPLEMENTATION AUTHORIZED / CONTRACT FROZEN**

## Reality basis

`docs/13-delivery/phase3/02_WORKFORCE_ASSIGNMENT_REALITY_CLOSURE_2026-09-20.md`

## Domain ownership

People owns:
- `WorkforceAssignment`;
- assignment history;
- business role code/label;
- reporting-manager relationship;
- synchronization of People-generated Team membership.

Organizations/Security own:
- `team`;
- `organization_membership`;
- generic/security `team_membership`;
- OpenFGA authorization projection/runtime.

People must reuse those objects.

## WorkforceAssignment schema

Create `workforce_assignment`.

Minimum fields:
- id;
- organization_id;
- employee_id;
- team_id nullable;
- role_code;
- role_label nullable;
- reports_to_employee_id nullable;
- state;
- effective_from;
- effective_to nullable;
- created_at;
- updated_at;
- version.

Initial states:
- `ACTIVE`;
- `ENDED`.

Rules:
- role_code required, bounded, configurable; no hard-coded enum;
- role_label optional human-facing text;
- team, when present, belongs to same organization and is active;
- employee belongs to same organization and is not FORMER;
- reports_to_employee_id, when present, belongs to same organization;
- employee cannot report to itself;
- active reporting graph must be acyclic;
- effective_to >= effective_from;
- ACTIVE requires effective_to NULL;
- one ACTIVE assignment per Employee in the current baseline;
- history remains in ended rows.

## Existing Team membership integration

Add an optional source reference to existing `team_membership` so People-generated memberships are identifiable, e.g.:

`source_workforce_assignment_id uuid NULL UNIQUE REFERENCES workforce_assignment(id)`

Rules:
- only People-owned membership rows set this field;
- manual/other Team membership rows keep it NULL;
- no second Team membership table.

When an ACTIVE WorkforceAssignment has:
- a Team;
- a currently linked active identity for the Employee;
- current organization membership;

People ensures exactly one current Team membership owned by that assignment.

Use:
- team_id from WorkforceAssignment;
- user_identity_id from the Employee Person link;
- `role_in_team` may carry the assignment role code as non-authoritative display metadata;
- valid_from/valid_until track the People security projection lifetime.

Then call the already verified Phase 1 Team membership authorization projection bridge.

### Change-control clarification — shared tuple aggregation

Implementation review exposed one cross-source edge case: a manual/non-People TeamMembership and a People-owned TeamMembership may legitimately represent the same current `(team,user)` relation. Both map to the same OpenFGA `team#member` tuple.

Projecting each row independently with its row-local version can produce a stale revoke when one source ends while the other remains current.

Therefore Slice 02 extends the Phase 1 bridge with a monotonic **TeamMembershipAuthority aggregate** keyed by `(team_id,user_identity_id)`:

- every TeamMembership mutation/sync increments the aggregate generation under transaction/lock;
- desired PRESENT means at least one current valid TeamMembership source exists for that pair;
- desired ABSENT means no current valid source remains;
- the OpenFGA tuple remains the existing `team.member` tuple;
- manual/non-People rows are never rewritten or adopted by People;
- People-owned rows remain identifiable through `source_workforce_assignment_id`;
- source-version ordering for the shared tuple comes from the aggregate generation, not an individual membership row.

This is a minimal Phase 1 projection hardening required by the first legitimate multi-source Team-membership consumer. It does not change Team semantics or the OpenFGA model.

## Reporting manager vs Team manager

Do not mutate `team.manager_user_identity_id` from `reports_to_employee_id`.

They are separate meanings.

Slice 02 provides reporting hierarchy reads only.

## Commands

### CreateWorkforceAssignment

Input:
- operationId;
- employeeId;
- baseEmployeeVersion or equivalent current-source version guard;
- teamId nullable;
- roleCode;
- roleLabel nullable;
- reportsToEmployeeId nullable;
- effectiveFrom;
- actor/correlation.

Preconditions:
- actor has current People management authority;
- Employee organization matches actor-authorized organization;
- Employee is PREBOARDING or ACTIVE;
- no ACTIVE WorkforceAssignment exists for Employee;
- Team active/same organization if supplied;
- reporting manager valid/same organization/not self/no cycle.

Behavior:
- create ACTIVE WorkforceAssignment transactionally;
- if linked identity + Team exist, synchronize People-owned Team membership;
- emit `WorkforceAssignmentCreated`;
- audit safe changed fields;
- activity may project the material assignment fact without private data.

### SynchronizeWorkforceAssignmentIdentity

Internal/event-driven operation.

Triggered by:
- `EmployeeIdentityLinked`.

Behavior:
- find ACTIVE WorkforceAssignment;
- if Team exists, materialize/synchronize People-owned Team membership and Phase 1 authorization projection;
- idempotent;
- no duplicate Team membership.

No public generic sync endpoint.

## Reads

### CurrentWorkforceAssignment

Authorized People/admin detail:
- assignmentId;
- employeeId;
- teamId + safe Team name/code;
- roleCode;
- roleLabel;
- reportsToEmployeeId + safe display name/code;
- effectiveFrom;
- version.

### OwnWorkforceAssignment

Linked Employee may read own:
- Team;
- role;
- reporting manager;
- effective date.

### OrganizationStructure / safe directory extension

Safe organization-member read may expose:
- employee code/display name;
- Team safe name;
- role label/code;
- reporting manager display name.

No HR/private fields.

## Authorization

Manage:
- current explicit People authority from Slice 01;
- do not use job title or broad organization admin inference.

Read:
- own assignment via linked identity/current membership;
- safe organization structure may be available to current organization members;
- private assignment administration remains People-authorized.

Cross-organization access fails closed.

## Events

Required:
- `WorkforceAssignmentCreated`;
- `WorkforceAssignmentSecuritySynchronized` when a People-owned Team membership is materially created/updated.

Shared event payloads must contain IDs/codes/version/correlation only; no private HR fields.

## Idempotency / concurrency

Reuse Phase 2 command runtime.

Required:
- operationId/idempotency key;
- exact current-source/version guard;
- duplicate create replay returns same result;
- concurrent duplicate assignment creation cannot produce two ACTIVE assignments;
- security synchronization replay creates no duplicate Team membership/projection.

## Explicit non-goals

Do not implement:
- assignment change/end workflow — Slice 05;
- future-dated scheduled changes beyond representing effective timestamps;
- Project/Site/Work assignment;
- shift/crew scheduling;
- attendance;
- payroll;
- Team creation redesign;
- automatic Team-manager mutation;
- permission inference from role_code;
- job/position catalog engine;
- matrix/multiple concurrent assignments.

## Required evidence for VERIFIED

1. migration creates WorkforceAssignment and People source link on Team membership.
2. jOOQ generation compiles.
3. one ACTIVE WorkforceAssignment per Employee is enforced.
4. historical ENDED assignments remain representable.
5. assignment Employee/Team/manager organization consistency is enforced.
6. self-manager is rejected.
7. current reporting cycle creation is rejected.
8. PREBOARDING/ACTIVE Employee may receive assignment; FORMER cannot.
9. role code is configurable and grants no authority itself.
10. existing Team table is reused; no duplicate Team model exists.
11. linked Employee + Team creates exactly one People-owned Team membership.
12. manual and People-owned memberships for the same `(team,user)` aggregate safely to one OpenFGA tuple without stale revoke; manual rows are not rewritten.
13. unlinked Employee assignment creates no fake Team membership.
14. later `EmployeeIdentityLinked` backfills exactly one membership.
15. People-owned membership is projected through the existing Phase 1 OpenFGA pipeline.
16. stale/revoked organization membership fails closed.
17. manual/non-People Team membership is not overwritten by People.
18. own assignment resolves only through linked identity/current organization membership.
19. cross-organization reads/writes fail closed.
20. commands are idempotent/version-safe.
21. safe reads leak no private People fields.
22. inherited Slice 01 + Phase 0–2 regressions PASS.
23. Android/Windows shared client contracts compile if assignment DTOs are added.

## Closure rule

Do not mark VERIFIED from unit tests alone.

Require:
- PostgreSQL migration/constraints;
- real OpenFGA/team projection contract;
- idempotency/concurrency;
- inherited People Slice 01 regression;
- exact-head CI.

## Contract conclusion

**IMPLEMENTATION AUTHORIZED.**

Slice 01 merge/post-merge closure is durably recorded on `main`. Implement only the Slice 02 scope above. Do not open Slice 03 scope until Slice 02 is verified/merged.

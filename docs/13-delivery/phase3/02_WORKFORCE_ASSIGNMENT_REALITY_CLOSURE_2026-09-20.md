# Phase 3 / Slice 02 — Workforce Assignment / Reporting Structure Reality Closure

Date: 2026-09-20  
Status: **REALITY CLOSED / CONTRACT MAY FREEZE**

## Purpose

Slice 01 established Person / Employee / Employment.

Slice 02 must establish the current workforce relationship layer needed by Projects, Warehouse, Field and later HR workflows without duplicating the Phase 1 Team/security model.

## Canonical evidence

- `docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`
- `docs/05-workflows/EMPLOYEE_LIFECYCLE.md`
- `docs/06-data/object-specs/PEOPLE_PAYROLL_OBJECTS.md`
- `docs/06-data/transition-tables/PEOPLE_PAYROLL_SUPPORT_TRANSITIONS.md`
- `docs/01-reality/REALITY_FACTS_REGISTER.md`
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
- Phase 1 `team`, `team_membership`, organization membership and OpenFGA projections.

## Reality conclusions

### 1. HILTECH workforce structure is compact and flexible

Current reality has a small field-heavy workforce, changing crews and broad/mixed duties.

Therefore:
- do not freeze a large enterprise position/job hierarchy;
- do not infer permission from job-title text;
- role is a configurable business code/label;
- the model must preserve history while staying usable for a small company.

### 2. Team already exists and must be reused

Phase 1 owns the canonical `team` object and the security-facing `team_membership` relation.

Slice 02 must not create:
- another Team table;
- another organization membership table;
- a second security membership relation.

### 3. WorkforceAssignment becomes People business truth

People needs one effective-dated business object that answers:

- which Employee is currently assigned;
- which Team they belong to, if any;
- their current business role code/label;
- who they report to, if any;
- when that assignment became effective.

This object is `WorkforceAssignment`.

Current baseline:
- at most one ACTIVE WorkforceAssignment per Employee;
- history is preserved;
- schema must not prevent future concurrent assignments if reality later requires them.

### 4. Business reporting manager is not automatically Team security manager

`WorkforceAssignment.reportsToEmployeeId` is the employee's business reporting relationship.

Existing `team.manager_user_identity_id` is a Team/security authority concept from Phase 1.

Do not silently equate them.

If a future HILTECH policy says a reporting manager is also a Team manager, that must be an explicit policy/command, not inference.

### 5. Team authorization is projected only when an Employee has a linked identity

An Employee can exist and be assigned before having a login.

Therefore:
- WorkforceAssignment must not require `user_identity`;
- if the Employee has a linked active identity, current Team assignment may materialize a People-owned row in existing `team_membership`;
- that row is then projected to OpenFGA through the already verified Phase 1 authorization pipeline;
- if no identity is linked, the business assignment remains valid but there is no login/security Team projection yet.

### 6. People-owned Team membership must be distinguishable from other Team memberships

Existing Phase 1 Team membership may also support non-People/security use cases.

Therefore Slice 02 must mark the Team membership row produced from WorkforceAssignment, rather than claiming ownership of every `team_membership` row.

A source link from `team_membership` to `workforce_assignment` is allowed for People-generated membership.

Manual/other membership rows remain outside People ownership.

### 7. Identity linkage must backfill the current assignment

When Slice 01's `EmployeeIdentityLinked` event occurs:
- if the Employee has a current WorkforceAssignment with a Team;
- and the linked identity/membership is current;
- People must create/synchronize the assignment-owned Team membership and authorization projection idempotently.

### 8. Role code is business metadata, not permission

Role code/label may describe technician, engineer, admin, etc., but:
- it does not itself grant OpenFGA authority;
- no hard-coded role taxonomy is required in Slice 02;
- exact role catalog remains configurable HILTECH seed data.

### 9. Manager/reporting validity

A current `reportsToEmployeeId`:
- belongs to the same organization;
- cannot equal the Employee;
- cannot create a current reporting cycle;
- may point to PREBOARDING or ACTIVE Employee while onboarding is incomplete;
- must not point to OFFBOARDING/FORMER as a new assignment manager.

### 10. Project/work assignment is not People assignment

WorkforceAssignment describes organizational workforce structure.

It does not mean:
- Project assignment;
- Site assignment;
- WorkOrder assignment;
- crew scheduling for a specific job.

Those belong to Phase 4/6.

## No new user question required for minimal Slice 02

The remaining unknowns—exact job titles, final team names, future concurrent assignments, and whether reporting manager should ever imply Team manager—do not block the minimal model because they remain configurable or explicitly non-inferred.

## Reality conclusion

**REALITY CLOSED.**

Slice 02 may freeze and implement one effective-dated WorkforceAssignment model reusing Phase 1 Team/security foundations.

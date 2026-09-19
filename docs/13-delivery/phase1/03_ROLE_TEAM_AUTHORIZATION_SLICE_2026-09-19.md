# Phase 1 / Slice 03 — Role / Team Authorization Integration

Date: 2026-09-19  
Status: **IMPLEMENTING**

## Goal

Connect HILTECH's authoritative organization/team relationships to the already-verified OpenFGA projection and fail-closed decision boundary.

This slice does **not** turn human-readable labels into permissions.

Canonical rule:

`PostgreSQL structural relationship truth → same-transaction projection intent → fail-closed guard → pinned OpenFGA action check`

## Structural relationships in scope

- active organization membership → `organization#member`
- active team membership → `team#member`
- configured team manager identity → `team#manager`

The following are explicitly **not** authorization truth:

- `OrganizationMembership.roleLabel`
- `OrganizationMembership.membershipType`
- `TeamMembership.roleInTeam`
- job titles or UI labels

Those fields may describe context, but authority comes from explicit relationships/policy.

## Commit 01 — typed relation + policy boundary

Implemented:

- canonical tuple factories for organization member, team member, and team manager,
- projection-intent factories using the existing authorization projection/outbox contract,
- direct organization membership authorization check,
- team `can_view` authorization check,
- team `can_manage_membership` authorization check,
- fail-closed guard tuples for the structural relations feeding derived OpenFGA actions,
- unit tests proving member/manager separation and action mapping.

## Next in this slice

1. wire authoritative organization/team relationship mutations to the projection intent writer in the same PostgreSQL transaction,
2. prove PRESENT grant remains denied until projection is APPLIED,
3. prove revoke/expiry is denied immediately before stale OpenFGA cleanup,
4. prove team manager replacement cannot replay stale authority,
5. run PostgreSQL + real OpenFGA integration evidence,
6. expose only the minimal client context required by Phase 1,
7. mark VERIFIED only after the real grant/revoke scenarios pass.

# Phase 1 / Slice 03 — Role / Team Authorization Integration

Date: 2026-09-19  
Status: **VERIFIED**

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

## Commit 02 — source-truth guard + transactional projection bridge

Implemented:

- PostgreSQL source-truth guards for organization member, team member, and team manager,
- identity / organization / team active-state and validity-window checks before OpenFGA,
- explicit organization-context requirement for team member/manager authority,
- MANDATORY-transaction projection bridge for organization membership and team membership,
- team-manager replacement projection with old-manager ABSENT + new-manager PRESENT,
- real PostgreSQL + real OpenFGA contract test wired into the local-platform CI gate,
- pending grants fail closed before tuple application,
- membership expiry/revoke denies from PostgreSQL truth before stale FGA cleanup,
- manager replacement denies old authority immediately and keeps new authority pending until projection,
- stale manager grant outbox cannot replay over a newer replacement.

## Verification closure

Canonical verified head:
`d9278bb5cc57ffe2dab51b595b388dd29769c0a5`

Evidence:
- Bootstrap Phase 0 run `35422210826` — PASS,
- local-platform contract — PASS with real PostgreSQL 18.6 + real OpenFGA 1.20.0,
- Contract — OpenFGA First Slice run `35422210814` — PASS,
- Native OIDC Production Smoke run `35422210824` — PASS,
- foundation / database / evidence / dependency / Terraform / supply-chain gates — PASS.

The real role/team contract proved:
- PRESENT authority stays denied while projection is pending,
- applied organization membership authorizes only through the explicit structural relation,
- expired/revoked team membership denies immediately from PostgreSQL truth while stale FGA still allows,
- cleanup removes the stale FGA tuple,
- manager replacement immediately denies the old manager,
- the replacement manager remains denied until projection applies,
- a stale old-manager grant outbox cannot replay over the newer manager revision.

## Client boundary decision

No client-side role/permission claims were added.

The existing identity bootstrap continues to expose only descriptive organization/team context. `roleLabel`, `membershipType`, and `roleInTeam` remain display/context data. Server-side source truth + OpenFGA remains the enforcement boundary.

Slice 03 is VERIFIED.

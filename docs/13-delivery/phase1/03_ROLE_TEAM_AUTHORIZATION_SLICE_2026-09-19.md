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

## Next in this slice

1. let CI prove the PostgreSQL/OpenFGA scenarios against the production adapters,
2. expose only the minimal client authority context required by Phase 1,
3. record run evidence,
4. mark Slice 03 VERIFIED only after all existing foundation/OIDC/OpenFGA gates remain green.

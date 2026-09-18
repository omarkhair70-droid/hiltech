# ADR-009 — Relationship Authorization

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH permissions are not only global roles.

Access depends on relationships such as:
- employee within HILTECH,
- PM on a project,
- technician assigned to project/work,
- client organization attached to project/invoice,
- supplier organization attached to PO,
- warehouse authority,
- temporary delegation.

A giant hard-coded RBAC matrix alone would become difficult to maintain as external organizations and resource relationships grow.

## Decision

Use **OpenFGA** as the leading object/action relationship-authorization engine.

OpenFGA is responsible for relationship questions such as:
- can this user view this project?
- can this user access this work order?
- can this user checkout this project asset?
- can this external user view this invoice/PO?
- is this user a current delegated approver?

The HILTECH server remains the final enforcement point.

## Evidence

SPIKE-09 / GitHub Actions run 35296729939.

A real OpenFGA v1.20.0 instance accepted a HILTECH-shaped model and all 27 representative allow/deny checks passed.

Temporary delegation grant/revoke worked through relationship tuple lifecycle without model rewrite.

## Explicit Boundary

OpenFGA does **not** replace:
- field-level access filtering,
- business workflow validation,
- approval amount/version policy,
- reauthentication/MFA obligations,
- audit logging,
- offline command revalidation.

Those remain application/domain/security responsibilities.

## Consequences

Positive:
- relationship-aware permissions stay explicit and testable,
- client/supplier organization isolation is modelable,
- project-resource permission inheritance is readable,
- delegation can be data rather than bespoke code branching.

Costs:
- tuple lifecycle becomes operationally important,
- permission checks require consistency/cache policy,
- model changes require controlled migration/testing,
- field-level rules still need separate enforcement.

## Revisit Triggers

Revisit if:
- real HILTECH authority mapping causes relation explosion,
- latency/availability cannot meet requirements,
- operational complexity exceeds the value over an in-process policy model.

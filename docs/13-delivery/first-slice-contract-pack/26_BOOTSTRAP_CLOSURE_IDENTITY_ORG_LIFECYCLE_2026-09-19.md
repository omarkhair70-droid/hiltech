# 26 — Bootstrap Closure: Identity / Organization Physical Lifecycle Values

Date: 2026-09-19  
Status: **ACCEPTED / FIRST-SLICE PHYSICAL-SCHEMA CLOSURE**

## Trigger

Generating V0001 exposed two finite-state fields that existed structurally in the identity/organization model but did not have exact persisted values in the frozen first-slice pack:

- `Organization.status`
- `OrganizationMembership.state`

The Bootstrap rule is to stop rather than invent production CHECK values silently.

## Decision

### UserIdentity

Already defined by the existing identity contract and now treated as exact for the first slice:

- ACTIVE
- LOCKED
- REVOKED
- PENDING

### Organization

`status` values:

- ACTIVE
- INACTIVE

Meaning:
- ACTIVE: organization may participate in current product relationships subject to authorization/policy.
- INACTIVE: organization remains historically addressable but is not eligible for new active operating relationships by default.

### OrganizationMembership

`state` values:

- PENDING
- ACTIVE
- SUSPENDED
- ENDED

Meaning:
- PENDING: relationship is not yet active.
- ACTIVE: membership relationship is currently valid, additionally bounded by validFrom/validUntil.
- SUSPENDED: temporarily non-operative.
- ENDED: historical terminal membership relationship.

## Authorization boundary

These lifecycle values do **not** become authorization truth.

Membership/state remains one input to server context. Object/action permission still requires:
- authenticated HILTECH identity,
- valid device/session policy,
- current organization/context relationship,
- OpenFGA relationship/action,
- application/domain obligations.

A title, membershipType, organization status or role label alone never grants permission.

## Schema consequence

V0001 may now use explicit varchar + CHECK constraints for these finite states.

`membership_type` remains a bounded business code/string because current organization relationship labels are seed/configuration data rather than a frozen authorization enum.

## Boundary

This closure does not change first-slice product scope, workflows, API routes or OpenFGA action semantics. It supplies missing physical persistence values required by the already-frozen identity/authorization contract.

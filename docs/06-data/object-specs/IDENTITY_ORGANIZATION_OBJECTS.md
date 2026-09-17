# Exact Object Specs — Identity & Organizations

Status: DOMAIN DATA MODEL v0.1 / NOT AUTH-SCHEMA-FROZEN

---

# UserIdentity

## Purpose
HILTECH-side identity record linked to external authentication subject.

## Fields
- id: UUID — R
- authProvider: String — R
- authSubject: String — R — HIGHLY_RESTRICTED
- personId: UUID — O
- status: ACTIVE/LOCKED/REVOKED/PENDING
- primaryOrganizationId: UUID — O
- createdAt
- lastAuthenticatedAt — O
- version

## Invariants
- authProvider + authSubject unique.
- revoked identity cannot authorize business commands.
- authentication identity does not imply employment or organization permission.

---

# Device

## Fields
- id
- userIdentityId
- platform: ANDROID/WINDOWS/IOS
- deviceName — O
- installationId
- appVersion
- osVersion
- trustState
- pushTokenRef — SECRET/opaque
- lastSeenAt
- revokedAt — O
- createdAt
- version

Classification: RESTRICTED/HIGHLY_RESTRICTED depending telemetry.

## Invariants
Revoked device cannot receive privileged session continuation.

---

# Session

## Fields
- id
- userIdentityId
- deviceId
- providerSessionRef — O
- createdAt
- lastSeenAt
- expiresAt
- revokedAt — O
- authenticationStrength
- reauthSatisfiedUntil — O

Classification: HIGHLY_RESTRICTED.

Session tokens themselves remain outside normal business persistence where possible.

---

# Organization

## Purpose
Canonical legal/business entity in HILTECH relationship graph.

## Fields
- id
- organizationCode
- legalName
- displayName
- type: HILTECH/CLIENT/SUPPLIER/SUBCONTRACTOR/PARTNER/OTHER
- commercialRegistrationRef — O — HIGHLY_RESTRICTED/RESTRICTED
- taxRegistrationRef — O — HIGHLY_RESTRICTED/RESTRICTED
- primaryAddress — O — RESTRICTED
- status
- createdAt
- version

## Invariants
External organization access always scoped to explicit membership/relationship.

---

# OrganizationMembership

## Fields
- id
- organizationId
- userIdentityId
- membershipType
- title/roleLabel — O
- state
- validFrom
- validUntil — O
- invitedBy — O
- version

## Invariants
Membership does not automatically grant project/object access; authorization relationships still required.

---

# Contact

## Fields
- id
- organizationId
- personId — O
- name
- title
- email — RESTRICTED
- mobile — RESTRICTED
- preferredChannel
- active
- notes — O — RESTRICTED

---

# RoleDefinition

## Fields
- id
- code
- name
- scopeType: GLOBAL/ORG/PROJECT/SITE/etc.
- description
- systemManaged
- active

Roles are policy inputs, not authorization truth by themselves.

---

# Team

## Fields
- id
- organizationId
- code
- name
- managerEmployeeId — O
- parentTeamId — O
- active
- version

No cycles in parent hierarchy.

---

# TeamMembership

## Fields
- id
- teamId
- employeeId
- roleInTeam — O
- validFrom
- validUntil — O

---

# Delegation

## Fields
- id
- delegatorUserId
- delegateUserId
- scopeType
- scopeRef
- permission/action set
- validFrom
- validUntil
- reason
- approvedBy — O
- state
- createdAt

## Invariants
- time-bounded.
- cannot grant authority delegator does not possess.
- auditable.
- critical actions may disallow delegation.

---

# Data Classification

Auth subject/session/device: HIGHLY_RESTRICTED
Organization legal identifiers: RESTRICTED/HIGHLY_RESTRICTED
Contacts: RESTRICTED
Role/team names: INTERNAL
Delegation: RESTRICTED

## Next
Authentication/authorization spikes decide final IdP/session/device boundaries.

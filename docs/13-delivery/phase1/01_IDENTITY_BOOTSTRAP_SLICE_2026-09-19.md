# Phase 1 / Slice 01 — Authenticated Identity Bootstrap

Date: 2026-09-19
Status: **IMPLEMENTING**

## Goal

Turn a valid OIDC subject into the minimum permission-safe HILTECH runtime context required by Android/Desktop.

Flow:

\`OIDC token → UserIdentity → active OrganizationMembership → active Team context → Device → /v1/me/bootstrap\`

## Why first

Every later Phase 1 capability depends on an authenticated subject resolving to an explicit HILTECH identity and current organization context.

Authentication alone must never imply HILTECH access.

## Existing schema reused

No migration is required for this slice.

It intentionally uses the verified Phase 0 V0001 tables:
- \`user_identity\`,
- \`organization\`,
- \`organization_membership\`,
- \`team\`,
- \`team_membership\`,
- \`device\`.

Session/delegation/role-definition persistence remains later Phase 1 work and is not invented in this slice.

## Server contract

### GET /v1/me/bootstrap

Input:
- validated Bearer JWT,
- optional \`X-Device-Installation-Id\`.

Rules:
- issuer + subject must resolve to an existing \`UserIdentity\`,
- identity must be ACTIVE,
- at least one ACTIVE/in-window membership in an ACTIVE organization is required,
- revoked device denies continuation,
- an installation already owned by another identity is a conflict,
- bootstrap returns only active organization/team context.

### PUT /v1/me/device

Registers or touches the current installation for the authenticated HILTECH identity.

Rules:
- installation ID is stable UUID,
- platform is ANDROID/WINDOWS/IOS,
- revoked installation is never silently resurrected,
- installation cannot move between identities,
- metadata refresh increments device version.

## Deny-by-default errors

- \`IDENTITY_NOT_PROVISIONED\`
- \`IDENTITY_NOT_ACTIVE\`
- \`NO_ACTIVE_ORGANIZATION_MEMBERSHIP\`
- \`DEVICE_REVOKED\`
- \`DEVICE_INSTALLATION_CONFLICT\`

## Client contract

Shared KMP client exposes:
- bootstrap context,
- active organizations,
- active teams,
- current device,
- typed identity API failures.

Bearer token and correlation headers are mandatory.

## Deliberate non-scope

This slice does not yet:
- implement native Authorization Code/PKCE UI,
- invent HILTECH role codes,
- persist product-side Session records,
- implement delegation,
- implement access administration UI,
- grant OpenFGA permissions from role labels.

Those close in later Phase 1 slices.

## Verification target

Before slice acceptance:
- server unit tests,
- shared KMP client tests,
- database contract remains green,
- Android/Desktop builds remain green,
- architecture test remains green,
- real PostgreSQL exercise for identity/device constraints is added before final slice closure.

# HILTECH OS

This repository is the single source of truth for the HILTECH digital transformation and HILTECH OS product.

## Current phase

**Repository Bootstrap / Phase 0 — Engineering Foundation.**

The first production slice is frozen at the pre-code contract/design/stack level and Repository Bootstrap is authorized.

Canonical freeze decision:
`docs/13-delivery/first-slice-contract-pack/24_FIRST_SLICE_FREEZE_DECISION_2026-09-19.md`

Canonical stack:
`docs/12-stack/FINAL_STACK.md`

Current implementation rule:

> **Bootstrap the shared foundation once, then ship complete vertical slices.**

The first production vertical remains:

PM Desktop
→ Project / Site / Work Order
→ Warehouse Asset Reservation / Checkout
→ Technician Android
→ Offline Execution / Evidence
→ Reconnect / Sync
→ Supervisor Acceptance
→ PM Read / Audit

Do not expand into later domains before the Bootstrap Verification gates are green.

## Product thesis

HILTECH OS is one product, one authoritative source of truth, with role-aware and context-aware experiences across Android and Windows desktop.

The public marketing website remains separate from this authenticated operating-system repository unless a later explicit decision changes that boundary.

## Frozen first-slice technology baseline

See `docs/12-stack/FINAL_STACK.md`.

Key pins include Kotlin 2.4.20, Compose Multiplatform 1.11.1, AGP 9.3.3, Gradle 9.5.0, Spring Boot 4.1.1, Spring Modulith 2.1.1, Room3 3.0.3, Ktor 3.5.2, PostgreSQL 18.6, jOOQ 3.21.8, Keycloak 26.7.4 and OpenFGA 1.20.0.

## Start here

- Program status: `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- Freeze pack: `docs/13-delivery/first-slice-contract-pack/README.md`
- Freeze → Bootstrap boundary: `docs/13-delivery/first-slice-contract-pack/17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`
- Implementation order: `docs/13-delivery/IMPLEMENTATION_ORDER.md`

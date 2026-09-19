# HILTECH OS

This repository is the single source of truth for the HILTECH digital transformation and HILTECH OS product.

## Current phase

**Phase 1 — Identity, Organization, Permissions Foundation.**

The first production slice is frozen, Repository Bootstrap / Phase 0 is verified and merged, and business vertical implementation is authorized to begin with Phase 1.

Canonical freeze decision:
`docs/13-delivery/first-slice-contract-pack/24_FIRST_SLICE_FREEZE_DECISION_2026-09-19.md`

Canonical stack:
`docs/12-stack/FINAL_STACK.md`

Current implementation rule:

> **The shared foundation is verified. Ship complete vertical slices from the frozen contracts.**

The first production vertical remains:

PM Desktop
→ Project / Site / Work Order
→ Warehouse Asset Reservation / Checkout
→ Technician Android
→ Offline Execution / Evidence
→ Reconnect / Sync
→ Supervisor Acceptance
→ PM Read / Audit

Do not prematurely implement later domains; record useful reality evidence now and consume it when the relevant phase arrives.

## Product thesis

HILTECH OS is one product, one authoritative source of truth, with role-aware and context-aware experiences across Android and Windows desktop.

The public marketing website remains separate from this authenticated operating-system repository unless a later explicit decision changes that boundary.

## Frozen first-slice technology baseline

See `docs/12-stack/FINAL_STACK.md`.

Key pins include Kotlin 2.4.20, Compose Multiplatform 1.11.1, AGP 9.3.3, Gradle 9.5.0, Spring Boot 4.1.1, Spring Modulith 2.1.1, Room3 3.0.3, Ktor 3.5.2, PostgreSQL 18.6, jOOQ 3.21.8, Keycloak 26.7.4 and OpenFGA 1.20.0.

## Start here

- Mandatory agent entrypoint: `AGENTS.md`
- Session/agent continuity: `docs/00-program/AGENT_EXECUTION_PROTOCOL.md`
- Program status: `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- Freeze pack: `docs/13-delivery/first-slice-contract-pack/README.md`
- Implementation order: `docs/13-delivery/IMPLEMENTATION_ORDER.md`


## Reality evidence

Raw internal HILTECH workbooks are not committed by default.

Durable learnings are captured in:
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
- `docs/01-reality/RAW_EVIDENCE_POLICY.md`

Later-phase evidence does not reopen completed phases unless it exposes a genuine cross-cutting contradiction.

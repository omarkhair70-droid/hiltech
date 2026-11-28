# HILTECH OS

This repository is the single source of truth for the HILTECH digital transformation and HILTECH OS product.

## Current engineering source

HILTECH is being consolidated and verified as one integrated product, not
restarted from an earlier phase. Start with [AGENTS.md](AGENTS.md) and the
[current program status](docs/00-program/CURRENT_PROGRAM_STATUS.md).

The integrated candidate is PR #242; PR #243 is already incorporated.
Verify the current unified head before changing code. Do not assume a
historical phase label, a successful build, or a draft PR means a deployed
and accepted release.

Complete existing user journeys across native UI, source-owned operations,
authorization, recovery and both supported platforms. Preserve the historical
contracts and evidence; use Git history for earlier status snapshots.

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

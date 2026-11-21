# Phase 6 — Execution Gate & Evidence Register

Date: 2026-11-21
Status: **PLANNING GATE OPEN / PHASE-5 POST-MERGE AND REALITY ACCEPTANCE SEPARATE**

## Baselines

- Phase-5 PR #111 merged: `967a3fbdca0c84a71a0b76e9913a64a12f0f40d4`.
- Source PR head `ee6c1311f15f055abf2105d13ee0b983b95cf254`: all 12 required workflows PASS.
- Post-merge Bootstrap `44857868201`: result **requires separate verification**.
- Phase-5 engineering Final Gap Review: `docs/13-delivery/phase5/15_PHASE5_FINAL_GAP_REVIEW_2026-11-21.md`.
- Phase-5 real import/Asset/warehouse-operator/cutover acceptance: **not verified**.
- Existing Phase-4 assigned read + Phase-2 Evidence + Phase-0 Room/WorkManager foundation: **implemented**, not yet Phase-6 offline business commands.

## Strict progression

1. Verify `main` post-merge Bootstrap PASS; if red, triage before any Phase-6 production code.
2. Review Phase-6 source reconstruction, owner/operator field facts and six-slice proposal.
3. Freeze scoped S01 contract and first bounded Slice PR from **current** main.
4. Carry verifiable synthetic and actual operator/device evidence separately.
5. Never label `PHASE6 = COMPLETE` merely because a demo or CI proof exists.

## Decision register

| Decision | Current state | Why |
| --- | --- | --- |
| Identity/Work/Project/assignment reused | SOURCE-BOUND | Existing Phase 1–4 |
| Warehouse/Asset/Stock authority reused | SOURCE-BOUND | Existing Phase 5 |
| Room3, WorkManager, Ktor idempotent replay reused | SOURCE-BOUND | Existing bootstrap engine |
| Evidence reserve/upload/finalize reused | SOURCE-BOUND | Phase-2 Evidence |
| Exact technician Start/Block/Resume/Submit transitions | OPEN | Phase 4 exposes read-only bundle and executionCapabilities=false |
| Actual measurement types/tolerance and required evidence | REALITY REQUIRED | Site/WorkType specific |
| Supervisor authority, rework and acceptance | REUSE SOURCE; reality validation open | Phase-4 review semantics exist |
| Site camera/GPS/drawing approval rules | REALITY REQUIRED | Cannot assume all sites allow photo/location |
| Offline material accounting | CONTRACT REQUIRED | Phase-5 stock source remains authoritative |
| Room/command support under role revocation | SECURITY GATE | Server must revalidate current source |

## Explicit no-go

No new field lifecycle command, production migration, new scanning integration, live data import, warehouse cutover or Phase-6 production deployment authorized by this planning PR.

`PHASE6_PLANNING = STARTED`
`PHASE6_PRODUCTION = NOT_STARTED`

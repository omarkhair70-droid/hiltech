# Phase 6 — Scope & Vertical Slice Plan (proposal)

Date: 2026-11-21
Status: **DRAFT / NOT FROZEN / CONTRACT REVIEW REQUIRED BEFORE PRODUCTION CODE**
Base: Phase-5 engineering merge `967a3fbdca0c84a71a0b76e9913a64a12f0f40d4`, pending separately verified post-merge Bootstrap.

## Scope

Technician execution on Android, shared Work/Project/Site truth on Windows, durable offline Room queue and evidence, scoped read/write permissions, field material usage and typed engineering tests, supervisor review/rework and Project progress. The phase MUST reuse existing Phase-1–5 objects, APIs and auth and must not become another standalone field app.

## Candidate sequence (six complete vertical slices; number/order subject to contract freeze)

### S01 — Durable assigned Job Bundle and honest offline Today

- Extend existing `GET /v1/field/today` + `GET /v1/work-orders/{id}/job-bundle` into **authorized, locally durable** Room snapshots with exact WorkOrder/assignment/instruction/binding versions, fetch time, allowed document refs and freshness.
- Current assignment is checked on every online refresh; local content may remain as preserved pending-work evidence after revocation but is not actionable/authoritative.
- Clear download/revision/expiry UI on Android; no Start/Submit actions yet.
- Gates: UI + Room migration test, process restart, revoked assignment, safe data redaction, offline fetch/read, explicit stale-state and no unauthorized cross-user/cache leak.

### S02 — Start / Block / Resume as server-owned Work commands with offline replay

- Freeze exact WorkOrder/field-execution transition contract first; **do not** infer transitions from Phase-4 read-only lifecycle.
- Typed server commands, current-source assignment/permission, version, idempotency/audit and notification/WorkQueue hook where already defined.
- Room pending-command enqueue with stable operationId, WorkManager replay, local-only state and server-confirmed state.
- Gates: offline queue, duplicate replay, process death, 401/403/409, reassignment/cancel conflict, real field UI and Windows source-state proof.

### S03 — Durable local Evidence capture → private upload → verified READY

- Reuse Phase-2 Evidence reserve/upload/finalize API and classification/private storage contract; local SHA-256, secure file refs, policy/evidence-requirement binding.
- Support configured photo/file and typed form evidence **without** invented mandatory proof or new public URLs; do not assume all files can bypass scan.
- Link evidence to WorkOrder, current assignment and exact evidence requirement/policy version.
- Gates: actual device capture, offline process death, upload retry, corrupted/truncated file rejection, size/content-type/security policy, no file loss before server READY, revocation behavior.

### S04 — Field material / scan / engineering measurements and drawing context

- Reuse Phase-5 AssetTag / Stock / issued balance and authorized warehouse movement semantics.
- Offline material-usage *intent* only; consumption/stock reconciliation requires server canonical source and collision checks.
- Type and unit/tolerance schemas for OTDR/Fluke/measurements require actual WorkType and engineering reality; do not invent them.
- Safe QR/manual fallback; cached drawing revision with stale conflict, original revision recorded, no implicit overwrite.
- Gates: exact quantity conservation, source-version conflict, wrong scan, missing issued material, no-camera site, stale drawing, restricted data.

### S05 — Completion dependency graph / supervisor acceptance / rework

- SubmitCompletion may be queued only with required local dependencies; server applies only when linked Evidence is READY, required commands accepted and current Work/assignment/policy compatible.
- Reuse Phase-4 `AcceptWork`, `RequestRework` and current reviewer authority, exact submitted version and progress/health projection.
- Android field and supervisor handling and Windows PM drill into authoritative evidence/progress; no duplicate progress state.
- Gates: prevent premature Submit, rework round-trip, partial/missing evidence, revoked assignment, supervisor denial, PM progress changes only upon accepted authoritative Work.

### S06 — End-to-end durable recovery, security and phase exit

- Verify app kill/restart, flight mode/reconnect, duplicate replay, 429/503, server conflict, reassign/cancel, updated drawing/policy, revoked login, lost device and output-reconciliation with genuine native Android and Windows.
- Verify Room schema forward migrations preserve pending commands and evidence; metrics/trace and support replay diagnostics.
- Phase-6 Final Gap Review, independent exact-head CI and actual technician/supervisor acceptance; no device or source proof may be replaced by synthetic fixtures without explicit status distinction.

## Engineering protocol

For every Slice: own bounded PR from merged main → DB/Room + domain + auth/audit + typed API/shared client + Android/Windows journey → PostgreSQL/OpenFGA and local migration/replay tests → actual UI/device evidence → exact SHA CI → independent gap review → owner-reviewed merge → post-merge Bootstrap. Do not start next Slice until previous gate passes.

## Scope exclusions

- Do not redo Phase 4 Today/Assignment/Review or Phase 5 Stock/Custody/Receiving.
- No offline final Warehouse checkout/stock adjustment, Finance, Procurement PO, general field tracking surveillance, unvalidated GPS/site camera policy, hardware drivers, external clients or NOC.
- Production activation/offline commands remain blocked by HILTECH reality + contract acceptance. Existing architecture spikes prove feasibility, **not** live operator acceptance.

## Freeze decision needed

Before S01 code: validate allowed local retention/visibility and first actual technician Job Bundle; confirm the current scope and six-slice boundaries against Phase 4/5 version/current-source contracts. Before S02–S05: close the narrower real-world state/evidence/test/material policies for that slice.

`PHASE6_SCOPE_PLAN = DRAFT`
`PHASE6_SLICE_COUNT = PROPOSED_6`
`PHASE6_S01_CONTRACT_FREEZE = PENDING_REVIEW`

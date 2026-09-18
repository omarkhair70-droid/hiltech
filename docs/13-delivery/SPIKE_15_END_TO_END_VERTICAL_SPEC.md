# SPIKE-15 — End-to-End Vertical Proof

Status: **READY TO START AFTER SPIKE-05 / SPIKE-07 / SPIKE-13**
Purpose: prove the core HILTECH architecture as one real cross-surface vertical slice before production bootstrap.

This spike is disposable evidence, not production code.

---

# Thesis Under Test

A single HILTECH business fact can move safely through:

PM Desktop
→ authenticated API
→ authoritative PostgreSQL state
→ Android local/offline bundle
→ offline technician actions
→ local evidence/scan
→ WorkManager reconnect
→ idempotent server replay
→ supervisor acceptance
→ PM Desktop refreshed/read model
→ audit/activity/trace

without:
- duplicate business actions,
- silent stale overwrite,
- permission leakage,
- lost offline work,
- fake success before authoritative acceptance,
- hidden binary upload ambiguity.

---

# Exact Representative Scenario

## Actors

- PM user: pm1
- Technician user: tech1
- Supervisor user: supervisor1
- HILTECH organization
- Project: project-a
- Site: site-a
- Asset: fluke-03
- Work Order: wo-42

Synthetic only.
No real client/bank/company secrets.

---

# Stage 1 — Identity / Authorization

Real Keycloak:
- native/public OIDC model from SPIKE-08.
- PKCE/session semantics accepted.

Real OpenFGA:
- PM may create/assign Project A work.
- assigned technician may view/execute Work Order.
- technician may use authorized project asset.
- supervisor may review/accept.
- unrelated user cannot view/act.

Pass:
authorization enforced server-side, not trusted from client role claims.

---

# Stage 2 — PM Desktop Creates / Assigns Work

A JVM/Compose Desktop spike client uses the shared **Ktor Client** candidate.

Commands:
- CreateWorkOrder
- AssignWork

Required request envelope:
- operationId,
- correlation/trace ID,
- baseVersion where applicable,
- authenticated subject context from token/session,
- typed command payload.

Authoritative backend:
- Spring Boot + Spring Modulith,
- PostgreSQL,
- jOOQ,
- audit/event.

Pass:
Work Order becomes ASSIGNED/READY according fixture policy and technician read bundle becomes available.

This stage is the direct proof gate for Ktor Client / ADR-007.

---

# Stage 3 — Technician Android Receives Offline Bundle

Android client:
- KMP shared client,
- Ktor Client,
- Room3/SQLite,
- accepted local schema pattern.

Bundle contains:
- Work Order identity/version,
- project/site context,
- current instructions,
- required asset reference,
- synthetic evidence requirement,
- permission/capability snapshot metadata.

Pass:
bundle is persisted before connectivity is removed.

---

# Stage 4 — Go Offline

The Android test disables network after bundle persistence.

Technician performs locally:

1. StartWork
2. scan / resolve fluke-03
3. capture evidence metadata / local binary fixture
4. ConsumeStock or representative material-use command if fixture includes stock
5. SubmitWorkCompletion

Required:
- commands persist in Room,
- stable operationIds,
- stable local sequence,
- baseVersion where required,
- visible sync state remains pending,
- process restart does not lose work.

---

# Stage 5 — Evidence

Evidence path uses the accepted SPIKE-12 semantics:

- local binary/evidence fixture,
- upload reservation,
- pre-signed object-storage PUT,
- SHA-256,
- finalization,
- evidence READY only after integrity verification.

If Camera SPIKE-05 has passed:
- use its local evidence handoff shape.

Pass:
work completion cannot become authoritative ACCEPTED merely because local evidence file exists.

---

# Stage 6 — Reconnect / WorkManager

WorkManager:
- CONNECTED constraint,
- accepted retry/backoff policy from SPIKE-13,
- replays typed queued commands.

Server must:
- re-authenticate/re-authorize current actor/session,
- re-check object state/version,
- use operationId idempotency,
- persist accepted commands,
- return explicit conflicts/failures.

Pass:
each business action applies once.

---

# Stage 7 — Conflict Subcase

Before reconnect, optional deterministic server mutation:

PM changes Work Order version or assignment.

Expected:
- stale queued command becomes explicit conflict,
- later dependent commands block,
- local evidence remains preserved,
- no last-write-wins overwrite.

This subcase may run separately from the happy-path vertical.

---

# Stage 8 — Supervisor Acceptance

Supervisor loads submitted work.

Command:
AcceptWork

Required:
- online authoritative action,
- exact submitted version,
- permission check,
- evidence completeness check,
- audit/event.

Pass:
Work Order transitions to ACCEPTED.

No technician-side local completion is treated as final acceptance.

---

# Stage 9 — PM Desktop Refresh

PM Desktop queries Project A / Work Order read model using Ktor Client.

Expected:
- accepted work visible,
- progress/readiness projection updated,
- selected object version current,
- activity history contains technician submission + supervisor acceptance.

Pass:
same authoritative state is visible across Android/Desktop without duplicated product truth.

---

# Stage 10 — Audit / Observability

One correlation family must reconstruct:

PM create/assign
→ Android bundle/sync
→ technician completion replay
→ evidence finalization
→ supervisor acceptance
→ PM read update

Required:
- W3C trace/correlation contract,
- operation IDs,
- audit facts,
- no sensitive fixture payload copied into telemetry attributes.

---

# Infrastructure for the Spike

Use disposable CI services/fixtures only:

- PostgreSQL 18.6 line
- Spring Boot 4.1.1
- Spring Modulith 2.1.1
- jOOQ 3.21.8
- Keycloak 26.7.4
- OpenFGA 1.20.0
- S3-compatible disposable test endpoint
- Ktor Client 3.5.2 candidate
- Room3 3.0.3
- WorkManager 2.11.2
- KMP / Compose accepted client line

Exact versions rechecked at implementation time.

---

# Ktor Acceptance Criteria

ADR-007 may become ACCEPTED only if SPIKE-15 proves:

- shared KMP client code compiles for Android + JVM Desktop,
- authenticated HTTP command/query works,
- JSON serialization contract works,
- timeout/failure maps into HILTECH error categories,
- correlation header propagates,
- operationId/idempotency header propagates,
- Desktop + Android can use the same client contract,
- platform-specific engine differences stay behind boundary,
- no UI/business layer imports raw vendor HTTP details.

If it fails materially:
compare alternative networking strategy before final stack freeze.

---

# Pass Criteria

SPIKE-15 ACCEPT only if all are true:

1. PM creates/assigns work.
2. technician receives durable local bundle.
3. technician works offline.
4. process/local durability survives.
5. scan/evidence path is represented.
6. reconnect replays in correct order.
7. duplicate replay does not duplicate business action.
8. stale-version conflict is explicit in conflict run.
9. supervisor acceptance is authoritative.
10. PM Desktop sees accepted state/progress.
11. permissions deny unrelated actor.
12. audit/activity facts exist.
13. trace/correlation joins the workflow.
14. binary evidence integrity is verified.
15. no step requires bypassing the accepted architecture.

---

# Explicit Non-Goals

This spike does not need:
- final production UI polish,
- real client data,
- real bank integration,
- real CCTV/access hardware,
- full payroll,
- full warehouse catalog,
- client portal,
- final infrastructure provider.

It proves the **architectural spine**, not the whole company.

---

# Result Format

At completion write:

docs/13-delivery/spike-results/SPIKE_15_END_TO_END_VERTICAL.md

Decision:
- ACCEPT
- MODIFY
- REJECT

If ACCEPT:
- ADR-007 Networking can be finalized if Ktor criteria pass.
- technical-spike gate closes.
- remaining blockers move to reality/design/exact-contract/infra freeze, not architecture feasibility.
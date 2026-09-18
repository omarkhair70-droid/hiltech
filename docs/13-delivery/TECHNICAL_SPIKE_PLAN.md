# HILTECH Technical Spike Plan

Status: REQUIRED BEFORE FINAL STACK FREEZE

## Purpose
Prove the risky architecture assumptions with disposable/isolated spike code before production bootstrap.

Spike code is evidence, not production foundation unless explicitly promoted after review.

---

# SPIKE-01 — KMP Android + Windows Shared Feature

Prove:
- shared Kotlin module.
- Android app.
- Windows desktop app.
- one shared domain/use-case.
- adaptive/shared UI portion.
- platform-specific code boundary.

Pass:
same object/workflow functions correctly on Android and Windows.

**Result 2026-09-18: ACCEPT — PLATFORM FEASIBILITY PASSED.**

Evidence:
- shared common tests passed,
- Android debug app compiled,
- JVM Desktop compiled,
- Windows EXE packaged,
- Windows MSI packaged,
- CI passed on Linux + Windows.

Tested line:
- Kotlin 2.4.20,
- Compose Multiplatform 1.11.1,
- AGP 9.3.1,
- Android compile/target SDK 36,
- Android min SDK 23,
- JDK 17.

Important:
This accepts the Android + Windows KMP/Compose platform feasibility. It does **not** yet finalize the entire client stack; RTL/adaptive, dense desktop data, local DB/offline, auth, and update spikes remain.

---

# SPIKE-02 — Arabic / RTL Adaptive Layout

Build representative:
- project list/detail.
- payroll row/inspector.
- asset detail.

Test:
- Arabic RTL.
- English LTR.
- mixed IDs/numbers/IP.
- phone.
- large Android/tablet.
- desktop.

Pass:
no structural left/right assumptions or bidi corruption.

---

# SPIKE-03 — Room KMP Local DB

Prove:
- Android DB.
- Desktop DB.
- migrations.
- Flow observation.
- transactional local command queue.
- schema export/testing.

Pass:
same shared repository contract on Android/desktop.

**Result 2026-09-18: ACCEPT — LOCAL DATABASE FEASIBILITY PASSED.**

Evidence:
- Room3 code generation passed.
- real SQLite persistence test passed on Linux JVM Desktop.
- same real SQLite persistence test passed on Windows JVM Desktop.
- Android app compiled with generated Room code.
- BundledSQLiteDriver worked with shared schema/DAO code.

Tested line:
- Room3 3.0.3,
- SQLite bundled driver 2.7.1,
- KSP 2.3.10,
- Kotlin 2.4.20,
- coroutines 1.11.0.

The representative local object was a HILTECH-shaped PendingCommand queue record.
Full restart/reconnect/conflict semantics remain SPIKE-04.

---

# SPIKE-04 — Offline Command Queue

Scenario:
Technician offline:
1. start job.
2. capture evidence metadata.
3. use material.
4. complete.
5. restart app.
6. reconnect.
7. sync.

Also simulate:
PM reassigns/cancels while offline.

Pass:
no loss, duplicate action or false success; conflict surfaced.

**Result 2026-09-18: ACCEPT — CORE OFFLINE QUEUE SEMANTICS PASSED.**

Evidence:
- real SQLite queue survives close/reopen.
- local command order is preserved.
- successfully applied commands are not sent again.
- ambiguous network outcome retries with the same operationId.
- duplicate authoritative application is prevented.
- stale server version becomes CONFLICT.
- later local commands for the same object become BLOCKED_BY_CONFLICT.
- local evidence/payload remains available for resolution.
- tests passed on Linux and Windows.
- Android app compiled with the shared offline sync code.

GitHub Actions run: 35296098137.

Not yet covered:
real HTTP/Ktor transport, final conflict UX, WorkManager/background execution, binary evidence upload.

---

# SPIKE-05 — Android Camera / QR / Evidence

Prove:
- QR scan.
- asset lookup.
- image capture.
- local durable file.
- metadata.
- upload retry.
- permission denial behavior.
- camera-prohibited mode.

Pass:
field flow is usable and recoverable.

---

# SPIKE-06 — Desktop Dense Data

Build:
- 10k+ synthetic rows.
- sort/filter/group.
- selection/bulk.
- logical start/end pinned columns.
- keyboard navigation.
- selected-object inspector.
- RTL.

Pass:
smooth performance and credible finance/warehouse UX.

This spike can determine whether Compose Desktop remains viable or whether specialized alternative is needed.

---

# SPIKE-07 — Windows Packaging / Update

Prove:
- signed or test-signed EXE/MSI.
- install/uninstall.
- deep link.
- update strategy prototype.
- settings/local DB preservation.
- rollback/failed update path.

Pass:
office deployment can be safely operated.

---

# SPIKE-08 — Keycloak Native OIDC

Prove:
- Android authorization-code + PKCE.
- Windows desktop login.
- session refresh.
- logout.
- remote session revoke.
- reauthentication step.
- passkey/WebAuthn path feasibility.

Pass:
no embedded-password anti-pattern; clean native app auth.

---

# SPIKE-09 — OpenFGA HILTECH Model

Model:
- HILTECH employee.
- PM assigned Project A.
- Technician Task A.
- Client Org user.
- Supplier.
- Asset custody.
- temporary delegation.

Queries:
- can user view project?
- approve variation?
- view invoice?
- checkout asset?
- see payroll?

Pass:
model remains understandable/testable without explosive complexity.

If not, reconsider simpler authorization architecture.

**Result 2026-09-18: ACCEPT — OBJECT/ACTION AUTHORIZATION MODEL PASSED.**

Evidence:
- real OpenFGA server accepted the HILTECH model.
- 27 representative allow/deny checks matched expectations.
- client/supplier/internal boundaries stayed isolated.
- payroll visibility remained owner/finance only.
- asset checkout composed technician/project and warehouse/org relationships.
- temporary delegation was granted and revoked by tuple lifecycle without model rewrite.

GitHub Actions run: 35296729939.
Tested OpenFGA: v1.20.0.

Boundary:
field-level sensitive-data redaction remains server/application policy rather than being forced into OpenFGA.

---

# SPIKE-10 — Spring Modulith

Build minimal server modules:
people / work / warehouse / audit.

Prove:
- module boundary verification.
- event publication.
- failed consumer.
- restart.
- outstanding publication recovery.
- architecture test.

Pass:
reliable module interaction without external broker.

---

# SPIKE-11 — PostgreSQL + jOOQ Ledger

Implement:
- asset movement.
- stock movement.
- optimistic version.
- idempotent command.
- concurrent checkout attempt.

Pass:
database constraints + transaction logic prevent impossible states.

**Result 2026-09-18: ACCEPT — AUTHORITATIVE LEDGER / CONCURRENCY THESIS PASSED.**

Evidence on real PostgreSQL:
- concurrent checkout: exactly one APPLIED and one CONFLICT.
- exactly one authoritative custodian.
- exactly one asset movement.
- retry with same operationId returns already-applied semantics without duplicate movement.
- stale version is rejected.
- concurrent stock issue cannot make stock negative.
- final stock/version/ledger remain correct under collision.

GitHub Actions run: 35296301835.

This accepts PostgreSQL constraints + transactional compare-and-update + idempotency as the authoritative-state pattern. Final production schema and jOOQ code-generation conventions remain separate freeze work.

---

# SPIKE-12 — Binary Evidence Pipeline

Prove:
- request upload.
- local binary.
- signed/object-storage upload.
- checksum.
- interrupted upload.
- retry.
- metadata linking.
- unauthorized file access prevention.

Pass:
large evidence does not pass inefficiently through normal JSON API.

**Result 2026-09-18: ACCEPT — S3-PROTOCOL BINARY EVIDENCE PATH PASSED.**

Evidence:
- pre-signed PUT passed.
- SHA-256 expected digest is part of the upload contract.
- correct binary upload finalized successfully.
- finalization recomputed stored bytes before READY.
- truncated/corrupt body could not finalize successfully.
- retry with correct bytes succeeded.
- pre-signed GET returned the evidence.
- unsigned direct read was denied with 403.

GitHub Actions run: 35305216130.

Important:
provider checksum enforcement is defense-in-depth. HILTECH finalization must independently verify stored bytes/size/digest before evidence becomes authoritative.

---

# SPIKE-13 — Android Background Sync

Using WorkManager candidate:
- queue work offline.
- process after connectivity.
- process after app restart.
- retry/backoff.
- battery/OS constraints.
- user-visible sync state.

Pass:
reliable enough for field reality.

---

# SPIKE-14 — Observability

Server:
- trace API -> command -> DB -> event consumer.

Client:
- correlation ID.
- sync failure metric/log through abstraction.

Pass:
one failed workflow can be diagnosed end-to-end without sensitive-data leakage.

**Result 2026-09-18: ACCEPT — END-TO-END TRACE/CORRELATION CONTRACT PASSED.**

Evidence:
- W3C traceparent propagated from client boundary to server.
- client.sync → api.command → command.complete_work → db.transaction / event.listener.audit shared one trace ID.
- parent/child relationships were verified.
- failed listener exported ERROR status.
- client diagnostic preserved correlationId/operationId/error code.
- employee email/phone, salary, bearer token and payload secrets were excluded from telemetry attributes.

GitHub Actions run: 35305996737.
Tested OpenTelemetry Java: 1.66.0.

---

# SPIKE-15 — End-to-End Vertical Proof

Scenario:
PM Desktop creates/assigns work.
Technician Android goes offline.
Technician executes/scans/evidence/completes.
Reconnect sync.
Supervisor accepts.
PM Desktop receives progress.
Audit/activity visible.

Pass:
core architectural thesis proven across surfaces.

---

# Output Per Spike

Each spike gets:
- hypothesis.
- implementation branch/folder.
- exact versions.
- test scenario.
- result.
- performance/ergonomic observations.
- blockers.
- screenshots/logs where useful.
- decision:
  ACCEPT / REJECT / MODIFY.
- ADR update.

## Freeze Rule
No technology is FINAL merely because its isolated hello-world spike works.
It must survive the relevant HILTECH scenario.

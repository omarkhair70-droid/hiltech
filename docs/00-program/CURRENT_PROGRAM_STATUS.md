# HILTECH Program Status

Updated: 2026-09-18

## Overall Stage
PRE-CODE PRODUCT / ARCHITECTURE DISCOVERY

Freeze status: NOT READY

## Meaning
HILTECH is now specified deeply enough that most major business objects, transitions, commands, permissions, offline classes, and architecture boundaries are visible before code. No production capability is COMPLETE and no final stack/schema has been frozen.

---

# Progress by Layer

| Layer | State | Notes |
|---|---|---|
| Program governance | STRONG FIRST PASS | Constitution, completeness, freeze/status controls exist |
| Company reality | EARLY | Internal validation remains the biggest business blocker |
| Human map | FIRST PASS | Main personas covered |
| Role experiences | FIRST PASS | Main personas covered |
| Product map | FIRST PASS | Domains/surfaces mapped |
| Master workflows | STRONG FIRST PASS | Major company lifecycles covered |
| Object/state/event | STRONG SECOND PASS | Exact object specs + transition tables + command catalog |
| Permissions | STRONG SECOND PASS | Object/action + field-level access models |
| Data governance | FIRST PASS | Classification/retention model exists; legal validation pending |
| Automation/notifications | FIRST PASS | Needs exact policy/trigger registry |
| Information architecture | FIRST PASS | Final navigation not frozen |
| UI reference research | PASS 01 + 02 | More visual/component research can continue |
| Mobile surface | FIRST PASS | Not wireframed |
| Desktop surface | FIRST PASS | Not wireframed |
| Design thesis/system | FIRST PASS | Visual tokens/font/colors not frozen |
| Offline/sync | SPIKE-PROVEN CORE | Restart-safe queue, idempotent retry and stale conflict semantics passed; HTTP/background/UX spikes remain |
| Integration/hardware | FIRST PASS | Real vendors/systems unknown |
| API/read models | FIRST PASS | Conventions/error/versioning/read architecture defined |
| Stack | EVIDENCE-BASED PRE-FREEZE | Accepted/proven vs leading/TBD tracked in `docs/12-stack/STACK_VERSION_MATRIX.md`; no final stack yet |
| System architecture | v0.1 | Spike/reality dependent |
| Module ownership | v0.1 | High-level ownership defined |
| Monorepo structure | PROPOSED | Not bootstrapped |
| Technical spikes | ACTIVE — 01/02/03/04/05/06/08/09/10/11/12/13/14 PASSED | Client platform, RTL/adaptive structure, dense desktop, local DB, offline queue, Android camera/evidence, background scheduling, authorization, modular backend events, PostgreSQL concurrency, binary evidence and observability proven; SPIKE-07 remains active before SPIKE-15 |
| Implementation order | NOT FINAL | Depends on spikes/reality |
| Production code | NOT STARTED | Intentionally |

---

# Newly Matured Layers

## Data Model
First-pass exact specs now cover:
- identity/organizations,
- sales/commercial,
- projects/work,
- assets/warehouse,
- people/payroll,
- procurement/finance,
- support/documents/security,
- approvals/notifications/sync/audit,
- maintenance/managed service/NOC.

## Security
Now includes:
- role/context matrix,
- object/action matrix,
- field-level access matrix,
- sensitive-data classification.

## API / Concurrency
Now includes:
- business command catalog,
- idempotency rules,
- optimistic versioning,
- error families,
- read-model architecture,
- ID/versioning conventions.

---

# Highest-Risk Unknowns

1. Actual HILTECH finance/payroll/accounting/bank workflow.
2. Real project-management handoff and field-reporting practice.
3. Warehouse current process and actual physical hardware.
4. Existing CCTV/access-control systems and vendors.
5. Exact organization/authority structure and delegation reality.
6. Android device fleet, camera/QR restrictions and site-security constraints.
7. Android background execution/reconnect reliability — SPIKE-13 accepted; OEM/field-device reliability remains a reality/operations validation item.
8. Windows install/update/rollback operations — SPIKE-07 active.
9. Full cross-surface end-to-end vertical proof — SPIKE-15 pending after remaining isolated gates.
10. Offline conflict ergonomics in real field use.
11. Legal/accounting/privacy/retention requirements.
12. Final visual/navigation/component/Arabic typography system.
13. Exact production DB/API/local schemas after reality validation.
14. Full cross-surface end-to-end vertical proof — SPIKE-15 pending.

No longer architecture unknowns:
- KMP Android + Windows platform feasibility,
- Compose Desktop 50k-row dense-data feasibility,
- Arabic RTL/adaptive structural feasibility,
- Room/SQLite shared local persistence,
- core offline command semantics,
- OpenFGA relationship authorization,
- Spring Modulith durable internal events,
- PostgreSQL concurrency/ledger thesis,
- S3-protocol binary evidence path,
- end-to-end telemetry correlation contract.

---

# Strongest Decisions So Far

High confidence:
- One HILTECH product.
- Same authoritative truth mobile/desktop.
- Role/context-aware experiences.
- Local/offline field support.
- Modular monolith first.
- PostgreSQL relational authoritative store.
- Documents binaries separate from metadata.
- Shared approval system.
- Audit/history first-class.
- No microservices/Redis/Temporal/search cluster by default.
- Product completeness is strict and layered.
- Critical state changes are typed commands/transitions, not arbitrary CRUD.
- Critical physical/financial history is append/audit oriented.
- Bank/external integration result is authoritative for external actions.
- Field-level security matters independently of object visibility.
- Business version and optimistic technical version are separate concepts.

Proven / accepted directions:
- Kotlin Multiplatform + Compose Multiplatform — SPIKE-01 + SPIKE-02 + SPIKE-06 accepted across Android/Windows, RTL/adaptive structure and dense desktop feasibility.
- Room3 + SQLite/BundledSQLiteDriver — SPIKE-03 accepted.
- Offline typed-command semantics — SPIKE-04 accepted.
- Spring Boot + Spring Modulith modular monolith — SPIKE-10 accepted.
- PostgreSQL authoritative transactional store — SPIKE-11 accepted.
- OpenFGA object/action relationship authorization — SPIKE-09 accepted.
- S3-compatible binary-evidence protocol — SPIKE-12 accepted.
- OpenTelemetry correlation/safe-telemetry contract — SPIKE-14 accepted.

Leading but still spike/freeze dependent:
- Ktor Client / exact HTTP client integration.
- jOOQ — **ADR-005 accepted** as the PostgreSQL SQL/persistence access layer; generated-schema/codegen conventions still freeze with exact schemas.
- Keycloak 26.7.4 + native OIDC Authorization Code/PKCE — **SPIKE-08 accepted**.
- WorkManager 2.11.2 background execution — **SPIKE-13 accepted**.
- exact Windows update/distribution strategy — SPIKE-07 active.
- exact production object-storage provider remains intentionally open.

---

# Immediate Next Work

1. Reality validation with Mohamed/Ahmed/project/warehouse/field.
2. Exact approval-policy / automation-policy modeling.
3. Representative wireflows and navigation candidates.
4. Technical spikes after enough reality validation.
5. Convert validated object specs into exact DB/API/local schemas.
6. Final ADRs/stack only after proof.

No production code yet by design.


---

# 2026-09-18 Delivery / Validation Update

Completed pre-code delivery artifacts:
- PRE_CODE_ENDGAME.md
- IMPLEMENTATION_ORDER.md
- TEST_STRATEGY.md
- CI_GATES.md
- RELEASE_STRATEGY.md
- MIGRATION_ROLLBACK_STRATEGY.md
- OPERATIONAL_RUNBOOKS_INDEX.md
- ADR control index

Reality validation is now reduced to a finite External Facts Register and interview pack.

There is no general management-approval blocker recorded.

Remaining freeze blockers are evidence-based:
1. unresolved real-company facts for affected domains,
2. technical spike results,
3. final design/RTL pass,
4. final stack/ADR/schema lock derived from those results.

Work that does not depend on those facts may continue immediately.


---

# Technical Spike Evidence — 2026-09-18

## SPIKE-01 — KMP Android + Windows
Decision: **ACCEPT — platform feasibility passed.**

Proven in GitHub Actions:
- shared KMP tests,
- Android debug build,
- JVM Desktop compile,
- Windows EXE,
- Windows MSI.

This does not mark the client stack FINAL. Remaining relevant spikes still gate final freeze.

## SPIKE-03 — Room KMP Local DB
Decision: **ACCEPT — local database feasibility passed.**

GitHub Actions run 35295195896 proved:
- Room3 KMP code generation,
- real SQLite persistence on Linux JVM Desktop,
- real SQLite persistence on Windows JVM Desktop,
- Android compile using generated Room code,
- shared PendingCommand schema/DAO with BundledSQLiteDriver.

Full offline business lifecycle remains SPIKE-04.


## SPIKE-04 — Offline Command Queue
Decision: **ACCEPT — core offline queue semantics passed.**

GitHub Actions run 35296098137 proved:
- restart-safe real SQLite queue,
- ordered replay,
- idempotent ambiguous retry using operationId,
- no duplicate authoritative business action,
- stale-version conflict,
- blocking of dependent later local work,
- preservation of local evidence/payload,
- Linux + Windows tests,
- Android compile with shared sync code.

Remaining: real HTTP/Ktor transport, WorkManager, binary upload and final conflict UX.

## SPIKE-11 — PostgreSQL + jOOQ Ledger
Decision: **ACCEPT — authoritative ledger/concurrency thesis passed.**

GitHub Actions run 35296301835 proved on real PostgreSQL:
- one-winner concurrent asset checkout,
- optimistic-version conflict,
- idempotent operationId retry,
- append ledger integrity,
- stock cannot go negative under concurrent issue,
- final quantity/version/movement count remain correct.

Production schema/codegen remains future freeze work.


## SPIKE-09 — OpenFGA HILTECH Authorization
Decision: **ACCEPT — object/action authorization model passed.**

GitHub Actions run 35296729939:
- real OpenFGA server,
- 8 HILTECH-shaped object types,
- 31 base relationship tuples,
- 27 representative allow/deny checks,
- internal/client/supplier isolation,
- payroll/finance boundaries,
- project/work/asset authorization,
- temporary delegation grant and revoke.

Field-level sensitive-data filtering remains server-side policy.


## SPIKE-10 — Spring Modulith
Decision: **ACCEPT — modular monolith + durable module event recovery passed.**

GitHub Actions run 35304479330 proved:
- module-boundary verification,
- transactional internal event publication,
- listener failure leaves publication incomplete/durable,
- process/context restart preserves failed publication,
- explicit official resubmission recovers the event,
- audit projection writes once,
- incomplete publication registry clears.

Operational note:
explicit controlled resubmission is preferred over assuming automatic startup replay.


## SPIKE-12 — Binary Evidence Pipeline
Decision: **ACCEPT — S3-protocol binary evidence path passed.**

GitHub Actions run 35305216130 proved:
- direct pre-signed binary upload,
- signed expected SHA-256 contract,
- provider-independent finalization integrity check,
- corrupt/truncated body cannot become READY,
- correct retry succeeds,
- pre-signed download succeeds,
- unsigned direct read is denied.

Production object-storage provider remains intentionally unselected.


## SPIKE-06 — Desktop Dense Data
Decision: **ACCEPT — Compose Desktop dense-data feasibility passed.**

GitHub Actions run 35305553872 proved:
- 50k synthetic payroll data handling,
- sort/filter/group,
- 10k-row bulk selection,
- desktop compile,
- real Compose Desktop first-frame render under Xvfb.

This removes the main current feasibility trigger for abandoning Compose Desktop, while final dense UX/performance budgets remain design/freeze work.


## SPIKE-14 — Observability
Decision: **ACCEPT — end-to-end trace/correlation contract passed.**

GitHub Actions run 35305996737 proved:
- W3C client→server trace propagation,
- API/command/DB/event-listener trace continuity,
- failed listener ERROR status,
- correlationId + operationId continuity,
- safe client sync-failure diagnostics,
- no tested payroll/PII/auth/payload leakage into telemetry attributes.

Collector/backend/vendor and production sampling remain open infrastructure decisions.


## SPIKE-02 — Arabic / RTL Adaptive Layout
Decision: **ACCEPT — RTL/adaptive structural feasibility passed.**

GitHub Actions run 35305946228 proved:
- deterministic phone/tablet/desktop layout modes,
- explicit LTR isolation inside Arabic text for IDs/IPs/codes,
- Android compile using the same shared UI,
- Desktop compile,
- real RTL renders at 360 / 800 / 1440 widths,
- real desktop LTR render.

Final Arabic font, exact pane ordering, navigation and visual system remain design decisions.


## SPIKE-08 — Keycloak Native OIDC
Decision: **ACCEPT — native OIDC identity/session architecture passed.**

GitHub Actions run 35307236838 proved:
- Keycloak 26.7.4 over HTTPS,
- Android private-use redirect + PKCE S256,
- Windows loopback redirect through real Chromium,
- browser SSO reuse,
- prompt=login credential re-auth,
- refresh,
- logout refresh invalidation,
- remote admin session revoke,
- WebAuthn/passwordless registration paths,
- Direct Access Grant disabled for the native client.

Offline business work remains the local command queue; long-lived Keycloak offline tokens are not the default architecture.


## SPIKE-05 — Android Camera / QR / Evidence
Decision: **ACCEPT — Android camera/QR/local-evidence path passed.**

GitHub Actions run 35313483156 proved on a real API 36 emulator:
- QR asset lookup path,
- permission-denied fallback,
- camera-prohibited-site fallback with manual asset ID,
- no forbidden image creation in prohibited mode,
- real CameraX capture,
- durable local JPEG,
- SHA-256 metadata,
- LOCAL_READY / PENDING_UPLOAD handoff to the accepted binary-evidence pipeline.

Room and offline regressions passed on the same branch.


## SPIKE-13 — Android Background Sync
Decision: **ACCEPT — WorkManager background execution path passed.**

GitHub Actions run 35315342936 proved on a real API 36 emulator:
- CONNECTED-constrained work survives process death,
- reconnect resumes without a foreground Activity,
- retry/backoff reaches a later successful attempt,
- battery-not-low constraints delay work until recovery,
- durable QUEUED / RETRYABLE / SYNCED user-observable state,
- Room/offline regressions remain green.

WorkManager is accepted as the Android scheduling layer; Room/local command state remains authoritative for business work.

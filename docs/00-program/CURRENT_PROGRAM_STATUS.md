# HILTECH Program Status

Updated: 2026-09-18

## Overall Stage
**FIRST-SLICE PRE-CODE FREEZE CLOSURE**

Freeze status: **NOT READY — PRIMARY BLOCKER IS ACTUAL RENDERED DESIGN PROOF**

## Meaning
The first production slice has moved beyond broad product/architecture discovery.

Technical feasibility is closed and the core first-slice Configuration / Project / Site / Work / Asset / Warehouse / Evidence / Offline / Authorization / HTTP / Database contracts are now structurally defined.

Provider architecture is also selected:
- OCI infrastructure baseline,
- OCI Object Storage/KMS/Secrets,
- staged DR,
- DigiCert OV + KeyLocker,
- HILTECH Update Service.

The remaining pre-code work is a narrow Freeze closure:
1. actual rendered design/RTL/conflict/adaptive proof,
2. final stack/version + CI-action pin review,
3. contradiction cleanup if either review exposes one,
4. First-Slice Freeze Review.

Production code is still intentionally not started.

---

# Progress by Layer

| Layer | State | Notes |
|---|---|---|
| Program governance | STRONG FIRST PASS | Constitution, completeness, freeze/status controls exist |
| Company reality | REPRESENTATIVE FIRST-SLICE COVERAGE | Internal redacted fixtures validate Project/Field/Warehouse structure; remaining reality is seed/terminology/legal/device validation for affected scope |
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
| Mobile surface | LOW-FI PARTIAL / SPECS COMPLETE | Existing M01–M05; M06/M07 exact build pack ready; actual canvas continuation Figma quota-blocked |
| Desktop surface | LOW-FI PARTIAL / SPECS COMPLETE | D01/D02 existing; Configuration/Review and remaining proof specified; actual canvas continuation quota-blocked |
| Design thesis/system | FIRST PASS / VISUAL FREEZE OPEN | Structural interaction contract strong; final rendered RTL/conflict/adaptive proof still blocks Freeze |
| Offline/sync | SPIKE-PROVEN CORE | Room queue, idempotent retry, stale conflict, WorkManager reconnect and real Ktor replay proven; final field UX/policy remains |
| Integration/hardware | FIRST PASS | Real vendors/systems unknown |
| API/read models | FIRST-SLICE CONTRACT v0.3 | /v1 routes, DTO/error/cursor/visibility/OpenAPI publication semantics closed for first slice |
| Stack | ADVANCED PRE-FREEZE | Technical stack proven; OCI provider baseline accepted via ADR-014; Windows/ops/cutover validation still blocks FINAL_STACK |
| System architecture | FIRST-SLICE CONTRACT-READY | Core architecture proven; provider instantiation now OCI baseline |
| Module ownership | v0.1 | High-level ownership defined |
| Monorepo structure | PROPOSED | Not bootstrapped |
| Technical spikes | CLOSED — 01/02/03/04/05/06/07/08/09/10/11/12/13/14/15 PASSED | Full end-to-end architectural vertical and Ktor/shared networking accepted; remaining work is freeze closure, not technical feasibility |
| Implementation order | PROPOSED v0.2 | Technical spikes closed; first-slice contract/provider/design Freeze closure remains |
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
8. Windows install/update/rollback operations — SPIKE-07 / ADR-012 accepted; production certificate/distribution channel remains an operations decision.
9. Full cross-surface end-to-end vertical proof — SPIKE-15 accepted; no longer an architecture unknown.
10. Offline conflict ergonomics in real field use.
11. Legal/accounting/privacy/retention requirements.
12. Final visual/navigation/component/Arabic typography system.
13. Exact production DB/API/local schemas after reality validation.

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
- Ktor Client 3.5.2 shared networking — **SPIKE-15 / ADR-007 accepted**.
- Full PM Desktop → Android offline/process-death/reconnect → Supervisor → PM authoritative vertical — **SPIKE-15 accepted**.

Still freeze-dependent:
- production Flyway SQL/jOOQ generated code are post-Freeze bootstrap artifacts; their pre-code generation/constraint conventions are already contract-defined.
- Keycloak 26.7.4 + native OIDC Authorization Code/PKCE — **SPIKE-08 accepted**.
- WorkManager 2.11.2 background execution — **SPIKE-13 accepted**.
- Windows MSI installer-swap operational baseline — **SPIKE-07 / ADR-012 accepted**; exact enterprise distribution/updater and production signing remain open.
- OCI production infrastructure baseline — **ADR-014 accepted PRE-FREEZE**; Jeddah tenancy/quota/latency validation remains.
- OCI Object Storage + KMS + Secret Management accepted provider baseline.
- OpenTelemetry Collector → OCI observability baseline accepted while telemetry contract stays vendor-neutral.
- DigiCert OV + KeyLocker production Windows signing accepted via ADR-019.
- HILTECH Update Service + private OCI artifact distribution accepted; MDM remains optional adapter.
- staged recovery: PILOT PITR/cross-region backup; STABLE Jeddah→Riyadh Warm Standby with 5-minute enforced RPO target.

---

# Immediate Next Work

Technical-spike continuation: **CLOSED.**

Configurable operating model:
- normal company policy/operating choices are configuration, not hard-coded freeze values,
- freeze typed policy schemas + invariants + audit/version semantics,
- reality sessions validate coverage and produce initial seed configuration,
- canonical model: `docs/03-product/CONFIGURABLE_OPERATING_MODEL.md`.

Canonical next-step artifacts:
- `docs/01-reality/FIRST_PRODUCTION_SLICE_REALITY_CLOSURE.md` — minimum Project/Field/Warehouse/Authority reality evidence.
- `docs/13-delivery/FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md` — readiness/blocker map.
- `docs/13-delivery/first-slice-contract-pack/README.md` — exact freeze artifacts that feed repository bootstrap.
- `docs/13-delivery/first-slice-contract-pack/14_FREEZE_GAP_REGISTER.md` — current narrow remaining blockers; use this instead of broad old “reality/technical unknown” language.

Contract conversion status:
- Configuration structural contract v0.3 complete.
- Project/Site/Work core contract v0.3 structurally closed.
- Asset/Warehouse/Stock core contract structurally closed.
- API HTTP semantic contract v0.3 closed.
- PostgreSQL table + DDL/constraint + Flyway/jOOQ generation contracts defined.
- Room/offline local storage/retry/migration contract v0.3 defined.
- OpenFGA model executable and CI-green; Postgres↔FGA fail-closed consistency contract defined.
- Evidence storage/security contract structurally closed.
- OCI infrastructure/provider baseline accepted via ADR-014 and first-slice infrastructure contract.
- Representative internal reality fixtures added.
- remaining work is primarily visual/design proof + OCI cutover/ops + Windows signing/distribution + final stack/version review.

Immediate pre-code continuation:
1. Complete actual rendered first-slice low-fi / RTL / conflict / adaptive proof when Figma MCP quota permits.
2. Run final explicit stack/version + CI-action pin review; create FINAL_STACK.md only when that review passes.
3. Perform First-Slice Freeze Review.
4. After Freeze: repository bootstrap → generated DB/API/Room/Auth/IaC/test foundations → production vertical implementation.

OCI tenancy/quota/latency, DigiCert issuance, signed-MSI staging, PITR/DR rehearsal and detailed observability settings remain production activation/cutover work unless they expose a contract contradiction.

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
2. final design/RTL pass,
3. exact DB/API/local/auth/file contract freeze,
4. remaining provider/runtime/signing/version decisions,
5. final stack/ADR/schema lock derived from those results.

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


## SPIKE-07 — Windows Packaging / Update / Rollback
Decision: **ACCEPT — Windows MSI operational lifecycle passed.**

GitHub Actions run 35317815398 proved:
- v1/v2 MSI packaging,
- disposable Authenticode signer identity verification,
- silent install/uninstall,
- packaged-app launch,
- `hiltech://` deep-link delivery,
- corrupt-update safety,
- LocalAppData preservation,
- controlled v1 -> v2 installer swap,
- rollback to retained v1,
- state preservation through final uninstall.

ADR-012 accepts Compose Desktop/jpackage MSI + controlled installer-swap as the pre-freeze Windows operational baseline. Production certificate/provider and enterprise distribution/updater remain open operations decisions.


## SPIKE-15 — End-to-End Architectural Vertical
Decision: **ACCEPT — core architectural thesis passed end to end.**

GitHub Actions run **35323209954** proved:
- PM Desktop create/assign,
- server-side outsider denial,
- Keycloak-authenticated actors,
- OpenFGA authorization,
- PostgreSQL/jOOQ authoritative state,
- Android durable bundle,
- Room offline command queue,
- local evidence preservation,
- process death,
- WorkManager reconnect/replay,
- S3-compatible evidence upload + SHA-256 finalization,
- idempotent duplicate replay,
- supervisor exact-version acceptance,
- PM authoritative read + audit trace,
- Spring Modulith projections,
- stale-version conflict,
- dependent-command blocking,
- authoritative state not overwritten.

Companion regressions on the same head:
- Room3 35323209973 — PASS,
- Offline Queue 35323209948 — PASS,
- Android Background Sync 35323209949 — PASS.

Final marker:
`HILTECH_SPIKE15_PASS desktop_create=PASS android_bundle=PASS offline=PASS evidence=PASS workmanager=PASS idempotency=PASS conflict=PASS authz=PASS supervisor=PASS desktop_read=PASS trace=PASS ktor=PASS`

ADR-007 accepts Ktor Client 3.5.2 as the shared Android/Desktop networking boundary.

**Technical-spike gate 01–15 is closed.**

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
| Stack | RESEARCH PASS 01 | Leading candidates, no final stack |
| System architecture | v0.1 | Spike/reality dependent |
| Module ownership | v0.1 | High-level ownership defined |
| Monorepo structure | PROPOSED | Not bootstrapped |
| Technical spikes | ACTIVE — 01/03/04/09/10/11 PASSED | Client platform, local DB, offline queue, authorization, modular backend events and PostgreSQL concurrency proven; remaining spikes active |
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
2. Real project management handoff and field reporting.
3. Warehouse current process and physical hardware.
4. Existing CCTV/access systems.
5. Exact organization/authority structure.
6. Android devices and site restrictions.
7. KMP/Compose Desktop dense-data viability.
8. Native authentication/authorization model.
9. Offline conflict ergonomics in real field use.
10. Legal/accounting/privacy retention requirements.
11. Final visual/navigation/component system.
12. Exact DB/API schemas after reality validation.

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

Leading but still spike-dependent:
- Kotlin Multiplatform + Compose Multiplatform — **SPIKE-01 feasibility ACCEPTED**; final stack still depends on RTL/dense-data/offline/auth evidence.
- Room/SQLite — **SPIKE-03 feasibility ACCEPTED** with real SQLite tests on Linux/Windows and Android generated-code compile.
- Ktor Client.
- Spring Boot + Spring Modulith.
- jOOQ — SPIKE-11 runtime/transaction use passed; final code-generation conventions still to freeze.
- Keycloak.
- OpenFGA — **SPIKE-09 authorization model ACCEPTED** for object/action authorization; field-level redaction stays server-side.

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

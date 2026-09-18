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
| Offline/sync | STRONG ARCH MODEL | Conflict + capability classification + commands; spike required |
| Integration/hardware | FIRST PASS | Real vendors/systems unknown |
| API/read models | FIRST PASS | Conventions/error/versioning/read architecture defined |
| Stack | RESEARCH PASS 01 | Leading candidates, no final stack |
| System architecture | v0.1 | Spike/reality dependent |
| Module ownership | v0.1 | High-level ownership defined |
| Monorepo structure | PROPOSED | Not bootstrapped |
| Technical spikes | ACTIVE — SPIKE-01 PASSED | Android + Windows KMP feasibility proven; remaining spikes active |
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
- Room/SQLite — active spike; not yet accepted.
- Ktor Client.
- Spring Boot + Spring Modulith.
- jOOQ.
- Keycloak.
- OpenFGA.

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
Status: RUNNING on isolated branch/PR.
Uses a HILTECH-shaped PendingCommand local queue with Room3 + BundledSQLiteDriver.

# HILTECH Program Status

Updated: 2026-09-18

## Overall Stage
PRE-CODE PRODUCT / ARCHITECTURE DISCOVERY

Freeze status: NOT READY

## Meaning
The product is becoming increasingly specified, but no production capability is COMPLETE and no final technical stack has been frozen.

---

# Progress by Layer

| Layer | State | Notes |
|---|---|---|
| Program governance | STRONG FIRST PASS | Constitution, decisions, completeness, freeze rules exist |
| Company reality | EARLY | Needs internal validation |
| Human map | FIRST PASS | Main personas covered |
| Role experiences | FIRST PASS | Main personas covered |
| Product map | FIRST PASS | Domains/surfaces mapped |
| Master workflows | FIRST PASS | Project/employee/warehouse/procurement/payroll/approval |
| Object/state/event | FIRST PASS | Needs reality validation and transition tables |
| Permissions | FIRST PASS | Needs real authority validation |
| Automation/notifications | FIRST PASS | Needs event matrix/policies |
| Information architecture | FIRST PASS | Nav not frozen |
| UI reference research | PASS 01 + 02 | Further visual/component research continues |
| Mobile surface | FIRST PASS | Not wireframed |
| Desktop surface | FIRST PASS | Not wireframed |
| Design thesis/system | FIRST PASS | Visual tokens not frozen |
| Offline/sync | ARCH MODEL v0.1 | Spike required |
| Integration/hardware | FIRST PASS | Real vendors unknown |
| Stack | RESEARCH PASS 01 | Leading candidates, no final stack |
| System architecture | v0.1 | Spike/reality dependent |
| Module ownership | v0.1 | Some boundaries need proof |
| Monorepo structure | PROPOSED | Not bootstrapped |
| Technical spikes | PLANNED | Not executed |
| Implementation order | NOT FINAL | Depends on spikes/reality |
| Production code | NOT STARTED | Intentionally |

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
9. Offline conflict ergonomics.
10. First release/pilot boundary.

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
- UI research/design system before production screens.
- Product completeness is strict and layered.

Leading but still spike-dependent:
- Kotlin Multiplatform + Compose Multiplatform.
- Room/SQLite.
- Ktor Client.
- Spring Boot + Spring Modulith.
- jOOQ.
- Keycloak.
- OpenFGA.

---

# Immediate Next Work

1. Reality validation with actual HILTECH workflows/evidence.
2. Continue missing workflow detail: finance/payment, client service, maintenance/support.
3. Build representative wireflow/prototypes (not final visual UI).
4. Prepare ADRs from accepted decisions.
5. Execute technical spikes after enough reality validation.
6. Freeze first implementation slice.

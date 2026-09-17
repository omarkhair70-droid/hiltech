# HILTECH Program Status

Updated: 2026-09-18

## Overall Stage
PRE-CODE PRODUCT / ARCHITECTURE DISCOVERY

Freeze status: NOT READY

## Meaning
The product is increasingly specified at workflow, state, permission, offline, and architecture level. No production capability is COMPLETE and no final technical stack has been frozen.

---

# Progress by Layer

| Layer | State | Notes |
|---|---|---|
| Program governance | STRONG FIRST PASS | Constitution, completeness, freeze/status controls exist |
| Company reality | EARLY | Internal validation still required |
| Human map | FIRST PASS | Main personas covered |
| Role experiences | FIRST PASS | Main personas covered |
| Product map | FIRST PASS | Domains/surfaces mapped |
| Master workflows | STRONG FIRST PASS | Project/employee/warehouse/procurement/payroll/approval/finance/support/maintenance/docs/security |
| Object/state/event | SECOND PASS STARTED | Transition tables now exist for high-risk objects |
| Permissions | SECOND PASS STARTED | Object × Action matrix added |
| Automation/notifications | FIRST PASS | Needs exact event-to-policy matrix |
| Information architecture | FIRST PASS | Final navigation not frozen |
| UI reference research | PASS 01 + 02 | More component/visual research may continue |
| Mobile surface | FIRST PASS | Not wireframed |
| Desktop surface | FIRST PASS | Not wireframed |
| Design thesis/system | FIRST PASS | Visual tokens/font/colors not frozen |
| Offline/sync | STRONG ARCH MODEL | Conflict + classification matrix exist; spike required |
| Integration/hardware | FIRST PASS | Real vendors/systems unknown |
| Stack | RESEARCH PASS 01 | Leading candidates, no final stack |
| System architecture | v0.1 | Spike/reality dependent |
| Module ownership | v0.1 | High-level ownership defined |
| Monorepo structure | PROPOSED | Not bootstrapped |
| Technical spikes | PLANNED | Not executed |
| Implementation order | NOT FINAL | Depends on spikes/reality |
| Production code | NOT STARTED | Intentionally |

---

# Newly Added High-Detail Models

## Workflows
- Finance & Payment Lifecycle
- Client Support & Service Lifecycle
- Maintenance & Managed Service Lifecycle
- Documents / Evidence / Handover Lifecycle
- Security & Facilities Lifecycle

## Transition tables
- Project & Work
- Asset & Warehouse
- Procurement & Finance
- People / Payroll / Support

## Security / Offline
- Object × Action Permission Matrix
- Offline Classification Matrix

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
10. Exact object fields/data classification/retention rules.
11. First production implementation order after freeze.

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
- Critical state changes are commands/transitions, not arbitrary CRUD.
- Financial/physical history should be append/audit oriented.
- Bank/external integration result is authoritative for external actions.

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

1. Define exact high-risk object fields + sensitivity + invariants.
2. Define data classification / retention model.
3. Expand exact transition/action tables as object catalog becomes precise.
4. Reality validation with Mohamed/Ahmed/project/warehouse/field.
5. Representative wireflows/prototypes.
6. Execute technical spikes after enough reality validation.
7. Finalize ADRs/stack only after proof.

No production code yet by design.

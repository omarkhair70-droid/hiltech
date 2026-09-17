# HILTECH OS — Research Register

Status: `RESEARCHING`

This register tracks external and internal research that informs product decisions. Research notes should capture source/date/findings/what we adopt/what we reject, not just links.

## R-001 — Unified product vs app-per-persona

**State:** in progress

Initial direction: one HILTECH system with role-aware/context-aware surfaces.

Reference categories already reviewed conceptually:

- construction/project operating platforms,
- field-service systems,
- connected operations / fleet / asset systems,
- enterprise employee/finance platforms,
- network/site management products.

Further work required: deeper teardown of role models, cross-device continuity, permissions, offline behavior, and enterprise deployment patterns.

## R-002 — Warehouse / tool / asset management

**State:** in progress

Topics:

- serialized high-value tools,
- QR/barcode tagging,
- custody / checkout / return,
- reservation,
- repair / damage / calibration,
- consumable stock ledgers,
- warehouse access logging,
- BLE/UWB/GPS for selected asset classes,
- site/warehouse movement history.

Further work required: evaluate real HILTECH inventory and decide which tracking technologies are justified by value/risk.

## R-003 — Offline-first field architecture

**State:** in progress

Current product conclusion: field work cannot assume constant network availability.

Research areas:

- local authoritative UI state,
- queued writes,
- attachment/photo synchronization,
- conflict resolution,
- device storage security,
- background work constraints,
- which operations must remain online-only.

## R-004 — Cross-platform client stack

**State:** in progress

Current candidate: Kotlin Multiplatform + Compose Multiplatform.

Must validate against:

- Android quality and hardware integration,
- Windows desktop UX,
- local database and background work,
- camera / QR / biometric integration,
- printing / exports where needed,
- adaptive layout,
- future iOS,
- motion/animation performance,
- packaging, deployment and updates.

Alternatives are not closed yet.

## R-005 — Backend architecture

**State:** in progress

Current direction under evaluation:

- Kotlin/JVM,
- modular monolith,
- PostgreSQL,
- durable workflow only where justified,
- fine-grained authorization only if complexity warrants it,
- explicit audit and ledger models.

Need to compare operational complexity, hosting, observability, maintenance burden, migration strategy, and team velocity.

## R-006 — Enterprise UX that is not visually dead

**State:** in progress

Research areas:

- dense desktop information architecture,
- adaptive mobile information architecture,
- meaningful motion tied to state,
- data visualization,
- bilingual Arabic/English typography,
- domain iconography,
- haptics and scanner interactions,
- keyboard-first desktop workflows,
- accessibility and motion-reduction modes.

## R-007 — Finance / payroll / banking

**State:** in progress

Need to research the actual HILTECH banking setup before deciding integration method.

Topics:

- maker/checker approval,
- bulk payroll/payment rails,
- file upload vs API vs host-to-host,
- payment status callbacks,
- reconciliation,
- reversals/failures,
- payslips and payroll history,
- segregation of duties.

## R-008 — CCTV / NVR / access control

**State:** in progress

Goal: HILTECH as a control plane over supported security hardware, not a replacement NVR.

Need actual installed brands/models/protocols before architecture decisions.

Topics:

- camera streams,
- event metadata,
- door/access events,
- remote permissions,
- event-to-camera correlation,
- retention/privacy/security boundaries.

## Research completion rule

A research area is not complete until it produces one or more repository decisions, rejected alternatives, unresolved risks, and concrete implications for product/architecture.

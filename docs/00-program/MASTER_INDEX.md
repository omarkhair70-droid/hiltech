# HILTECH OS — Master Index

Status: `RESEARCHING / PRE-FREEZE`
Last major planning update: 2026-09-18

This is the navigation and control document for the entire pre-code phase.

## Phase objective

Before production coding starts, the repository must contain enough validated detail that implementation can proceed primarily as engineering, testing, infrastructure, and deployment rather than rediscovering product requirements.

The current strategy is:

**Design the whole company. Ship complete vertical slices.**

## Planning sequence

1. Company Reality Map
2. Human Map
3. Business / Operational Pain Map
4. Master Workflows
5. Domain Map
6. Object + State Map
7. Event Map
8. Automation Map
9. Permission Map
10. Complete Role Experiences
11. Information Architecture
12. UI / UX Reference Research
13. Mobile / Desktop Surface Map
14. Offline + Sync Architecture
15. Integration + Hardware Map
16. Design System / Motion / Accessibility
17. Stack Research
18. Technical Architecture
19. Module Ownership + Dependencies
20. Reality Validation
21. Technical Spikes
22. ADR Decisions
23. Final Stack + Version Matrix
24. Representative UI Prototypes
25. Feature-to-Module/API/Schema/File Plan
26. Release Dependency Graph
27. Implementation Order
28. Freeze Review
29. Repository Bootstrap
30. Production Build

## Repository map

- `00-program/` — constitution, decisions, research register, open questions, freeze/status control
- `01-reality/` — company reality and validation
- `02-people/` — human/persona maps
- `03-product/` — product map, feature catalog, role experiences, completeness and surfaces
- `04-domains/` — domain detail as it becomes frozen
- `05-workflows/` — project, payroll, procurement, warehouse, employee, approval, automation and events
- `06-data/` — objects, states, events, ledgers, ownership, audit
- `07-security/` — identity, authorization, permissions, privacy boundaries
- `08-integrations/` — bank, CCTV/NVR, access control, QR, telemetry, messaging, hardware
- `09-offline-sync/` — offline-first, sync engine, conflict policy
- `10-design/` — IA, research, design system, typography, iconography, motion, visualization, accessibility
- `11-architecture/` — system/client/backend/database/infrastructure/observability/modules/monorepo
- `12-stack/` — technology research, candidate versions, eventual final stack
- `13-delivery/` — Definition of COMPLETE, dependency graph, spikes, implementation/release strategy
- `14-research/` — sourced external product/technical reference research

## Program control documents

Start here:

1. `CURRENT_PROGRAM_STATUS.md` — where the program actually stands.
2. `FREEZE_CHECKLIST.md` — exact remaining blockers to freeze.
3. `PROJECT_CONSTITUTION.md` — non-negotiable product laws.
4. `DECISION_LOG.md` — decision status and rationale.
5. `OPEN_QUESTIONS.md` — unresolved questions.
6. `../13-delivery/DEFINITION_OF_COMPLETE.md` — what COMPLETE actually means.
7. `../01-reality/REALITY_VALIDATION_PLAN.md` — how assumptions become verified reality.

## Current planning coverage

### Governance
Strong first pass:
- project constitution
- strict completion model
- freeze checklist
- dependency graph
- research/decision controls

### People
First-pass personas and role experiences exist for:
- Mohamed / Owner
- Ahmed / Finance-Admin
- Project Manager
- Engineer
- Supervisor
- Technician
- Warehouse
- Procurement
- HR/Admin
- New Hire
- Sales/Tenders
- Client
- Supplier
- Subcontractor

### Master workflows
First pass:
- Project Lifecycle
- Employee Lifecycle
- Warehouse / Asset Custody
- Procurement
- Payroll
- Shared Approval System
- Automation Map
- Cross-Domain Event Map

Still needs deeper dedicated workflow detail:
- finance/payment lifecycle
- client support/service lifecycle
- maintenance/managed-service lifecycle

### Data
First pass:
- Object Model
- State Machines
- Event Map
- module ownership

Still required:
- exact first-release object fields
- transition tables
- invariants
- retention
- ledgers/schema

### Security
First pass:
- Permission Map
- Authorization Matrix
- deny-by-default model
- external organization boundaries

Still required:
- reality validation
- re-auth/device-trust rules
- authorization spike
- test matrix

### Product surfaces
First pass:
- Mobile Surface Map
- Desktop Surface Map
- Cross-Device Continuity
- Feature Catalog
- Feature-to-Module Map

### UX / Design
Research/model coverage:
- UI Reference Pass 01
- UI Reference Pass 02
- Information Architecture
- Design Thesis
- Design System Architecture
- Status Semantics
- Typography/Bidi
- Motion
- Iconography
- Data Visualization
- Accessibility
- Adaptive Layout
- Dense Desktop Interaction

Not frozen:
- visual tokens
- final font
- colors
- navigation
- components
- final wireframes/prototypes
- motion timings

### Offline / Sync
Architecture model exists:
- Offline-First Model
- Sync Engine
- Conflict Policy

Still requires technical proof.

### Integrations / Hardware
First pass:
- Integration Map
- Hardware Strategy

Real vendor/system inventory still required.

### Stack / Architecture
Current leading direction:
- Kotlin Multiplatform + Compose Multiplatform
- Room/SQLite
- Ktor Client
- Kotlin/JVM + Spring Boot
- Spring Modulith modular monolith
- PostgreSQL
- jOOQ candidate
- Flyway
- Keycloak candidate
- OpenFGA candidate
- OpenTelemetry server
- S3-compatible object storage

Conditional only when evidence requires:
- Temporal
- Redis
- dedicated broker
- OpenSearch

No `FINAL_STACK.md` exists yet by design.

Architecture models now exist for:
- System
- Client
- Backend
- Database
- Infrastructure
- Observability
- Module Ownership
- Module Dependency Graph
- Monorepo Structure

### Technical proof
`TECHNICAL_SPIKE_PLAN.md` defines 15 required spikes before final stack freeze.

No production code has started intentionally.

## Current known product thesis

- One HILTECH product, not one app per persona.
- Same authoritative business truth across mobile and desktop.
- Experiences are role-aware and context-aware.
- The system must remove work, not merely digitize paperwork.
- Physical operations matter as much as digital records.
- HILTECH HQ itself can become an early customer of HILTECH OS.
- Public website and authenticated OS have different jobs.
- Enter once; flow everywhere.
- Management by exception.
- Important physical/financial history is auditable.
- Mobile is not desktop miniaturized.
- Offline is a business workflow concern, not a cache feature.
- Motion communicates state.
- Arabic/RTL is first-class architecture/design.
- No premature microservices or infrastructure theatre.

## COMPLETE rule

A document being complete does not make a product capability complete.

A capability may only be labelled COMPLETE after the relevant product, domain, experience, technical structure, integrations, security, implementation, testing, observability, deployment, release, and operational requirements are satisfied according to `docs/13-delivery/DEFINITION_OF_COMPLETE.md`.

Before production implementation begins in earnest, major first-release capabilities should be at least BUILD-READY.

## Freeze rule

If an important operational question still has the answer “we will think about it later,” the relevant domain is not frozen.

If implementation would still require choosing fundamental data ownership, permissions, libraries, module boundaries, edge cases, offline policy, workflow semantics, security, or failure handling, the capability is not BUILD-READY.

## Current freeze status

**NOT READY.**

The two major gates now are:
1. Validate the model against actual HILTECH reality.
2. Prove the highest-risk technical assumptions through the planned spikes.

See `CURRENT_PROGRAM_STATUS.md` and `FREEZE_CHECKLIST.md`.

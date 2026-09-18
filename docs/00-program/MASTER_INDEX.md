# HILTECH OS — Master Index

Status: `FIRST-SLICE CONTRACT CLOSURE / PRE-FREEZE`
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
8. `../11-architecture/CROSS_CUTTING_BUILD_CONTRACTS.md` — implementation-facing API/sync/security/file contract candidate.
9. `../12-stack/STACK_VERSION_MATRIX.md` — evidence-based pre-freeze version ledger.
10. `../13-delivery/FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md` — post-spike readiness/blocker map for the first production vertical.
11. `../01-reality/FIRST_PRODUCTION_SLICE_REALITY_CLOSURE.md` — minimum real-company evidence required to freeze the first vertical.
12. `../13-delivery/first-slice-contract-pack/README.md` — fillable DB/API/local/auth/file/UI/test/freeze contract pack consumed by repository bootstrap.
13. `../03-product/CONFIGURABLE_OPERATING_MODEL.md` — canonical rule separating configurable company policy from hard-coded product invariants.
14. `../10-design/FIRST_SLICE_DESIGN_FREEZE_EXECUTION_PACK_2026-09-18.md` — canvas-ready visual proof queue; actual Figma render currently quota-blocked.
15. `../13-delivery/first-slice-contract-pack/14_FREEZE_GAP_REGISTER.md` — narrow authoritative blocker list before First-Slice Freeze.
16. `../13-delivery/first-slice-contract-pack/21_FINAL_PRE_FREEZE_CONSISTENCY_REVIEW.md` — cross-contract review: consistency PASS, Freeze still gated.
17. `../12-stack/FINAL_STACK_VERSION_REVIEW_2026-09-18.md` — final version review; only AGP 9.3 patch validation remains before FINAL_STACK.md.

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
Strong first pass:
- Project Lifecycle
- Employee Lifecycle
- Warehouse / Asset Custody
- Procurement
- Payroll
- Employee Advance / Financial Imprest
- Finance / Payment Lifecycle
- Client Support / Service Lifecycle
- Maintenance / Managed Service Lifecycle
- Document / Handover Lifecycle
- Security / Facilities Lifecycle
- Shared Approval System
- Automation Map
- Cross-Domain Event Map

Remaining work is reality validation and implementation-scope contract freezing, not discovery of those lifecycle families from zero.

### Data
Strong pre-freeze model:
- Object Model
- exact object-spec first passes
- State Machines
- transition tables
- command catalog
- Event Map
- module ownership
- data classification / retention model

First-slice implementation contracts now define:
- domain/configuration fields and invariants,
- DB table/constraint/index generation contract,
- HTTP routes/DTO/errors/read models,
- Room/offline queue/cache semantics,
- evidence/file metadata and storage protocol.

Remaining data work is mainly pilot seed/import mapping and any legal retention rule for an actually enabled feature.

### Security
Strong pre-freeze model:
- Permission Map
- Authorization Matrix
- object × action matrix
- field-level access matrix
- deny-by-default model
- external organization boundaries
- OpenFGA authorization proof / ADR-009

First-slice security now includes:
- executable OpenFGA model + CI fixtures,
- fail-closed PostgreSQL→OpenFGA projection contract,
- re-auth/offline replay obligations,
- field-level projection rules,
- negative authorization test families.

Current authority names/relationships are seed/configuration unless they reveal a missing relationship type.

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
Architecture model + core technical proof exist:
- Offline-First Model
- Sync Engine
- Conflict Policy
- restart-safe Room/SQLite command queue
- idempotent replay
- stale-version conflicts
- dependent-command blocking

SPIKE-04 accepted the core offline command semantics.
SPIKE-13 accepted Android WorkManager reconnect/background scheduling behavior on the tested API 36 emulator path.
OEM/field-device behavior remains reality/operations validation rather than an unresolved architecture choice.

### Integrations / Hardware
First pass:
- Integration Map
- Hardware Strategy

Real vendor/system inventory still required.

### Stack / Architecture

Accepted / spike-proven directions:
- Kotlin Multiplatform + Compose Multiplatform
- Room3 + SQLite/BundledSQLiteDriver
- Kotlin/JVM + Spring Boot
- Spring Modulith modular monolith
- PostgreSQL
- jOOQ
- Keycloak native OIDC / PKCE
- OpenFGA object/action authorization
- OpenTelemetry-compatible correlation contract
- S3-compatible binary evidence protocol

Accepted / proven additional direction:
- Ktor Client 3.5.2 shared networking — SPIKE-15 / ADR-007.

Accepted pre-freeze provider/runtime directions:
- Flyway global migration convention with Spring Boot managed version line
- OCI / ADR-014
- OCI Database with PostgreSQL
- OCI Object Storage + KMS + Secret Management
- OCI Container Instances preferred / Compute fallback
- Terraform + OCI Provider / Resource Manager
- DigiCert OV + KeyLocker / ADR-019
- HILTECH Update Service
- staged OCI backup/DR contract

Explicitly not baseline unless evidence requires:
- Temporal
- Redis
- dedicated broker
- OpenSearch

Canonical pre-freeze version ledger:
- `../12-stack/STACK_VERSION_MATRIX.md`

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

Accepted:
- SPIKE-01 KMP Android + Windows
- SPIKE-02 Arabic / RTL adaptive layout
- SPIKE-03 Room KMP local DB
- SPIKE-04 offline command queue
- SPIKE-05 Android Camera / QR / Evidence
- SPIKE-06 dense Desktop
- SPIKE-07 Windows packaging/update/rollback
- SPIKE-08 Keycloak native OIDC
- SPIKE-09 OpenFGA authorization
- SPIKE-10 Spring Modulith
- SPIKE-11 PostgreSQL + jOOQ ledger/concurrency
- SPIKE-12 binary evidence pipeline
- SPIKE-13 Android background sync
- SPIKE-14 observability
- SPIKE-15 end-to-end architectural vertical + Ktor networking

**Technical-spike gate: CLOSED / ACCEPTED.**

Final vertical evidence:
- GitHub Actions run 35323209954,
- companion Room/offline/WorkManager regressions all green,
- ADR-007 accepted.

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

The remaining first-slice pre-code gates are:
1. Complete actual rendered low-fi / RTL / conflict / adaptive visual proof.
2. Run the final explicit stack/version and CI-action pin review.
3. Resolve any contradiction exposed by those reviews.
4. Call First-Slice Freeze Review.
5. Only then repository bootstrap / production implementation.

OCI tenancy/quota/latency, DigiCert issuance, signed-MSI staging and DR rehearsals are activation/cutover gates unless they reveal a contract contradiction.

See `CURRENT_PROGRAM_STATUS.md` and `FREEZE_CHECKLIST.md`.

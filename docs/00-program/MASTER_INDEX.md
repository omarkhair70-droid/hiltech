# HILTECH OS — Master Index

Status: `RESEARCHING`

This is the navigation and control document for the entire pre-code phase.

## Phase objective

Before coding starts, the repository must contain enough validated detail that implementation can proceed primarily as engineering, testing, infrastructure, and deployment rather than rediscovering product requirements.

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
16. Design System
17. Motion System
18. Technical Architecture
19. Final Stack + Version Matrix
20. Release Dependency Graph
21. Implementation Order
22. Freeze Review
23. Build

## Repository map

- `00-program/` — constitution, decisions, research register, open questions, freeze checklist
- `01-reality/` — company reality, current operations, pain points, systems, business/regulatory context
- `02-people/` — all human roles and complete role experiences
- `03-product/` — product map, feature catalog, completeness matrix, mobile/desktop surfaces
- `04-domains/` — people, projects, field work, finance, payroll, warehouse, assets, procurement, clients, security, documents, communication
- `05-workflows/` — project, payroll, procurement, warehouse, employee, approval, client/service lifecycles and event map
- `06-data/` — objects, states, events, ledgers, ownership, audit
- `07-security/` — identity, permissions, device trust, privacy boundaries
- `08-integrations/` — banking, CCTV/NVR, access control, QR/barcode, GPS/IoT, messaging
- `09-offline-sync/` — local-first boundaries, synchronization, conflicts
- `10-design/` — IA, UI reference research, design system, typography, iconography, motion, visualization, accessibility
- `11-architecture/` — system, client, backend, database, infrastructure, observability, ADRs
- `12-stack/` — research, final choices, versions, rationale
- `13-delivery/` — dependency graph, implementation order, tests, release strategy, strict Definition of COMPLETE
- `14-research/` — sourced market/product/technical research

## Current completed planning artifacts

### Program / governance
- `PROJECT_CONSTITUTION.md`
- `DECISION_LOG.md`
- `OPEN_QUESTIONS.md`
- `RESEARCH_REGISTER.md`
- `DEFINITION_OF_COMPLETE.md`

### People — first-pass maps
- Owner / Mohamed
- Finance / Ahmed
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
- Supplier/Subcontractor

These persona maps are not product-complete; they are research/design models pending validation and downstream architecture.

### Master workflows — first-pass models
- Project Lifecycle
- Employee Lifecycle
- Warehouse & Asset Custody
- Procurement
- Shared Approval System
- Payroll
- Cross-Domain Event Map

### Data model seeds
- Cross-Domain Object Model
- Core State Machines

### Design planning
- Dedicated UI / UX Reference Research Plan

## Current known product thesis

- One HILTECH product, not one app per persona.
- Same source of truth across mobile and desktop.
- Experiences are role-aware and context-aware.
- The system must reduce work, not merely digitize paperwork.
- Physical operations matter as much as digital records: sites, warehouse, tools, expensive equipment, cameras, doors, vehicles and field work belong in the system model.
- HILTECH HQ itself can become an early customer of HILTECH OS.
- Public website and authenticated HILTECH OS have different jobs but belong to the same company/product ecosystem.
- Design the whole company; ship complete vertical slices.
- Enter once; flow everywhere.
- Management by exception for executive/finance control.
- Important physical and financial history must be auditable.

## COMPLETE rule

A document being complete does not make a capability complete.

A capability may only be labelled COMPLETE after the relevant product, domain, experience, technical structure, integrations, security, tests, observability, deployment, and release requirements are satisfied according to `docs/13-delivery/DEFINITION_OF_COMPLETE.md`.

Before implementation begins in earnest, major capabilities should be at least BUILD-READY.

## Freeze rule

If an important operational question still has the answer “we will think about it later,” the relevant domain is not frozen.

If implementation would still require choosing the core data model, permissions, libraries, module ownership, edge cases, offline policy, or workflow semantics, the capability is not BUILD-READY.

# HILTECH OS — Master Index

Status: `RESEARCHING`

This is the navigation and control document for the entire pre-code phase.

## Phase objective

Before coding starts, the repository must contain enough validated detail that implementation can proceed primarily as engineering, testing, infrastructure, and deployment rather than rediscovering product requirements.

## Planning sequence

1. Company Reality Map
2. Human Map
3. Business / Operational Pain Map
4. Domain Map
5. Object + State Map
6. Event Map
7. Automation Map
8. Permission Map
9. Complete Role Experiences
10. Mobile / Desktop Surface Map
11. Offline + Sync Architecture
12. Integration + Hardware Map
13. Design System
14. Motion System
15. Technical Architecture
16. Final Stack + Version Matrix
17. Release Dependency Graph
18. Implementation Order
19. Freeze Review
20. Build

## Repository map

Planned documentation areas:

- `00-program/` — constitution, decisions, research register, open questions, freeze checklist
- `01-reality/` — company reality, current operations, pain points, systems, business/regulatory context
- `02-people/` — all human roles and complete role experiences
- `03-product/` — product map, feature catalog, completeness matrix, mobile/desktop surfaces
- `04-domains/` — people, projects, field work, finance, payroll, warehouse, assets, procurement, clients, security, documents, communication
- `05-workflows/` — project, payroll, procurement, warehouse, employee and client lifecycles
- `06-data/` — objects, states, events, ledgers, ownership, audit
- `07-security/` — identity, permissions, device trust, privacy boundaries
- `08-integrations/` — banking, CCTV/NVR, access control, QR/barcode, GPS/IoT, messaging
- `09-offline-sync/` — local-first boundaries, synchronization, conflicts
- `10-design/` — IA, design system, typography, iconography, motion, visualization, accessibility
- `11-architecture/` — system, client, backend, database, infrastructure, observability, ADRs
- `12-stack/` — research, final choices, versions, rationale
- `13-delivery/` — dependency graph, implementation order, tests, release strategy, definition of done
- `14-research/` — sourced market/product/technical research

## Current known product thesis

- One HILTECH product, not one app per persona.
- Same source of truth across mobile and desktop.
- Experiences are role-aware and context-aware.
- The system must reduce work, not merely digitize paperwork.
- Physical operations matter as much as digital records: sites, warehouse, tools, expensive equipment, cameras, doors, vehicles and field work belong in the system model.
- HILTECH HQ itself can become an early customer of HILTECH OS.
- Public website and authenticated HILTECH OS have different jobs but belong to the same company/product ecosystem.

## Freeze rule

If an important operational question still has the answer “we will think about it later,” the relevant domain is not frozen.

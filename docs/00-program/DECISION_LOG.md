# HILTECH OS — Decision Log

This file records decisions, reasons, and current status. It is not a chat transcript.

## D-001 — Repository is the project source of truth

**Status:** `VALIDATED`

All important research, product decisions, architecture, stack choices, workflows, feature inventories, and implementation ordering must be represented in this repository.

## D-002 — Pre-code planning phase can take 1–2+ weeks

**Status:** `VALIDATED`

The current objective is not speed to first code. The objective is reducing product and architecture uncertainty before implementation.

## D-003 — One HILTECH product

**Status:** `VALIDATED`

Do not default to separate Owner, Finance, Client, Crew, Warehouse, or NOC apps. Build one HILTECH system with role/context-aware experiences.

Reason: shared company truth, reduced fragmentation, fewer duplicate records, easier cross-role workflows, and stronger product identity.

## D-004 — Public website remains a separate public surface

**Status:** `VALIDATED`

The marketing/company website has a different job from the authenticated HILTECH operating system. It can remain web-first and connect users into the authenticated product.

## D-005 — Desktop and mobile are live peers

**Status:** `VALIDATED`

Desktop is not simply “admin” and mobile is not simply “field.” The same identity and source of truth serve both, with form-factor-specific UX.

## D-006 — Start product modeling from humans and work, not screens

**Status:** `VALIDATED`

Before drawing navigation, map each persona’s day, decisions, information inputs, outputs, pain, automation opportunities, and downstream effects.

## D-007 — Mohamed + Ahmed form an initial control-brain study

**Status:** `VALIDATED` as research starting point, not as final build order.

Mohamed (owner/management) and Ahmed (finance/admin) are key to understanding control, approvals, finance, payroll, and the goal of reducing routine management work.

## D-008 — Warehouse and physical assets are core, not optional extras

**Status:** `VALIDATED`

The company has high-value tools/equipment and a real warehouse operation. Asset custody, stock, checkout/return, loss, damage, repair, calibration, reservation, and access history belong in the core product model.

## D-009 — Cameras/access/security can become HILTECH integrations

**Status:** `PROPOSED`

HILTECH should be able to become a control plane over supported CCTV/NVR/access-control systems where technically and operationally justified. HILTECH should not reinvent camera/NVR technology unnecessarily.

## D-010 — Product completeness is different from release scope

**Status:** `VALIDATED`

A complete domain/product surface should be designed before implementation. Individual releases can still ship smaller end-to-end slices.

## D-011 — “Complete” requires lifecycle, states, errors, permissions, history and audit

**Status:** `VALIDATED`

A screen is not a finished feature. Relevant normal/error/offline/integration/permission/reversal/history behavior must be defined.

## D-012 — Local/offline intelligence is required for field use

**Status:** `VALIDATED` at product level.

Field workflows must be designed to work through unreliable connectivity where appropriate. Exact technical implementation remains under research.

## D-013 — Client technology direction: Kotlin Multiplatform + Compose Multiplatform

**Status:** `ACCEPTED / PRE-FREEZE`

Accepted by technical evidence across the current client spike line:
- Android + Windows shared client feasibility,
- Arabic/RTL adaptive structure,
- Room/SQLite shared persistence,
- offline command semantics,
- dense Compose Desktop,
- Android camera/evidence,
- Android WorkManager background scheduling.

This accepts the client architecture direction. It is still not a FINAL_STACK declaration; Windows operational closure, SPIKE-15 integration, final design contracts, and final version re-check remain freeze gates.

## D-014 — Backend direction: Kotlin/JVM modular monolith

**Status:** `ACCEPTED / PRE-FREEZE`

Accepted evidence-based baseline:
- Kotlin/JVM + Java 21,
- Spring Boot,
- Spring Modulith modular monolith,
- PostgreSQL authoritative relational store,
- jOOQ persistence/SQL layer,
- Keycloak native OIDC identity,
- OpenFGA object/action authorization,
- S3-compatible binary evidence protocol,
- OpenTelemetry-compatible correlation contract.

Exact production schemas, providers, deployment/runtime packaging and final version pins remain freeze decisions.

## D-015 — Do not begin with microservices

**Status:** `ACCEPTED`.

Prefer a well-bounded modular monolith initially. Split services only when scale, isolation, or operational evidence requires it.

## D-016 — Motion and visual quality are first-class product architecture concerns

**Status:** `VALIDATED`

The application should not become visually dead because it is enterprise software. Motion should express system state and continuity. Typography, bilingual behavior, iconography, responsive/adaptive interaction, charts, and haptics must be researched deliberately.

## D-017 — Implementation starts only after a deliberate freeze review

**Status:** `VALIDATED`

No domain is frozen while significant questions remain undocumented or answered only by “later.”


## D-018 — Internal company operating system is the build priority; client-facing surface is last

**Status:** `VALIDATED BY OWNER INPUT / 2026-09-18`

Mohamed stated that the internal HILTECH system used by management, finance/admin, project delivery, field staff, warehouse, procurement, HR, and company operations is more important than the external client-facing experience.

Implications:

- Design the whole product now so client requirements are not forgotten.
- Build and stabilize the internal operating system first.
- The external Client Portal / My HILTECH surface is intentionally one of the final build layers.
- Internal client data is still core early: client organizations, contracts, project relationships, invoices, approvals, and support context remain necessary for internal operations.
- “Client last” means the **external client login/experience**, not removing client-related business objects from the internal core.

This decision supersedes any accidental implication that Client UX should be an early implementation slice.

## D-019 — Employee cash advances and financial imprest/custody are separate concepts

**Status:** `VALIDATED AS REQUIRED PRODUCT COVERAGE`

HILTECH must distinguish:

1. **Employee Advance / سلفة**
   - money advanced to an employee,
   - outstanding balance,
   - settlement or payroll deduction,
   - approval and finance history.

2. **Financial Imprest / عهدة مالية**
   - company money temporarily entrusted to an employee/project/site for operational spending,
   - opening amount,
   - permitted purpose/context,
   - spending lines/receipts,
   - remaining cash,
   - replenishment if policy allows,
   - settlement,
   - return of unused amount,
   - shortages/overages,
   - approval/reconciliation/audit.

3. **Physical Asset Custody / عهدة معدات**
   - tools/equipment assigned to a person/site,
   - checkout/return/transfer,
   - damage/loss,
   - offboarding clearance.

These must not be collapsed into one generic “advance” object.

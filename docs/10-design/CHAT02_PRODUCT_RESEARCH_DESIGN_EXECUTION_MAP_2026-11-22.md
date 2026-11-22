# Chat 02 — HILTECH Product, Research & Design: Execution Map

Date: 2026-11-22
Status: ACTIVE PLAN / NOT A DESIGN FREEZE / NOT A RELEASE AUTHORIZATION
Tracking: Issue #128; cross-lane Phase 6 #117; real device gate #116; core scope through Phase 13 #103.
Independent branch: ux/128-baseline-role-first-20261122 / Draft PR #134.
This file is a **tracking and completion contract**, not proof that any unchecked task was performed.

## Why this map exists
The first UX exploration focused early on warehouse source-driven presentation. It yielded valid native Compose evidence but got ahead of systematic closure of research, product-wide IA/navigation and shared foundations. Correct the sequencing without discarding the evidence and without blocking Engineering.
The source of truth is actual repo and tests; never treat chat notes or pretty previews as an operational acceptance.

## Working rules
1. Investigate existing reality and role tasks before choosing universal navigation, tokens or visual style.
2. Preserve native Kotlin/Compose Android+Windows, shared/core, server authority, authorization/audit/ledger, honest offline/sync states and existing frozen contracts. The existing live app remains the default until reviewed integration.
3. Every proposed change has: exact source evidence, affected role/task, hypothesis, A/B or alternative, tradeoffs, source contract, test captures, success/fail criteria, reviewed decision.
4. Separate **research approval**, **native source integration**, **real-device validation**, **operator acceptance**, and **merge/release**.
5. Work on independent UX branch; do not alter Phase 6 S04 stacked branches. Reconcile/rebase with branch head only after coordination and exact-head tests. No unilateral merge/deploy.
6. Functional correctness/accessibility improvements can be reviewed during Phases 6–13; cross-product identity, typography, color and signature motion visual freeze belongs after core Phase 13 completion unless owner explicitly changes sequencing.
7. Do not duplicate data models/services or invent camera scanning, provider notifications, transport, authority, telemetry, new Phase 13 APIs or fake user data.

## Snapshot at creation (verified, 2026-11-22)
- Main: c30fb707186cc0bbb38ba95b924737bd1bc56997, PR #134 open/Draft on separate branch.
- Phase 0–5 functional delivery from program evidence; current Phase6 implementation in parallel. Do not infer Phase6 total completion.
- Existing documentation: docs/10-design/DESIGN_THESIS.md, DESIGN_SYSTEM.md, INFORMATION_ARCHITECTURE.md, MOBILE_NAVIGATION_CANDIDATES.md, DESKTOP_NAVIGATION_CANDIDATES.md, UI_REFERENCE_RESEARCH_PLAN.md, SCREEN_SURFACE_INVENTORY.md, TYPOGRAPHY_AND_BIDI_RESEARCH.md, ACCESSIBILITY.md, STATUS_SEMANTICS.md, MOTION_RESEARCH.md, ADAPTIVE_LAYOUT_RESEARCH.md.
- Research plan status itself **PLANNED**, IA **RESEARCHING/NOT FROZEN**, mobile/desktop navigation **EXPLORATION/NOT FROZEN**, design tokens **NOT VISUALLY FROZEN**.
- Warehouse A/B: actual native Compose code and synthetic screenshot research, plus headless Desktop Compose pointer TODAY -> CODE_LOOKUP -> TODAY proof. Last verified tested code head c4f508cfcb8c2c3ae535c7f339c4756ef1dc1c34, BUILD SUCCESSFUL; PR research head advanced to later documentation. **Not Android device testing, real operator acceptance, full Quick Scan -> Asset Passport journey, shipped UI or final visual direction.**
- Remote QA PNGs stored locally on an authorized Windows machine; not claimed committed to GitHub.
- Public-facing full search / role navigation / notification experience not yet audited or accepted end-to-end.

## Milestone contract — not the business Phases
### UX-00 — SOURCE & REALITY BASELINE — PARTIAL
Deliverables:
- Exact main and active PR/issue map; actual Android/Windows app entry wiring and source permissions.
- One role-by-screen-by-state inventory for Mohamed, admin, PM, engineer, storekeeper, technician, mapped to implemented vs planned vs blocked.
- Screenshot/interaction evidence manifest for actual native build (sign-in, Home, Work, objects, search, Inbox/attention, offline, role-denied) with SHA, environment and synthetic/real distinctions.
Exit only when: there is an auditable inventory for all currently implemented surfaces; missing run/device evidence is explicitly OPEN, never masked.
Current gaps: exact-main authenticated Windows+Android runtime, real role screens, device/keyboard/scaling operator verification; only selective Phase5/6 CI synthetic renders reviewed.

### UX-01 — DEEP REFERENCE RESEARCH — NOT CLOSED
Research 6 repo-defined tracks: enterprise OS/work systems, construction/field workflows, asset/warehouse, finance/payroll, industrial network/observability, consumer-grade quality. Include current versions and sources.
Deliverables: linked reference library, 2–4 useful/anti-patterns per track with why/why-not for HILTECH, mobile/Windows and accessibility/performance impact; pattern and anti-pattern matrix, thesis critique, objective tradeoff hypotheses.
Exit only when: every track has referenced evidence tied to actual HILTECH task; no visual motif copied without problem mapping and counter-example.
Some narrow reference comparisons exist for warehouse, Fluent/Carbon/Adaptive; **not equivalent to closing 6-track research**.

### UX-02 — PRODUCT IA + ROLE/AUTHORITY MAP — NOT CLOSED
Deliverables: object/relationship map; each role's 3–5 most frequent tasks/exception routes; canonical Home/Work/Explore(or Search)/Inbox/Me semantics; which data is source authoritative and which read cache; multi-role switch; access denied/feature unavailable; deep links/back stack.
Exit only when: each major role can trace entry -> task -> object -> source decision -> back, with state/permission source and future Phase13 boundaries identified. One product, not disconnected role mini-apps.
Current docs describe the concept, not validated native journeys.

### UX-03 — GLOBAL NAVIGATION: ANDROID + WINDOWS — NOT CLOSED
Baseline: existing long-column HiltechShell and operator sections. Re-test original candidates rather than accept them because written.
Deliverables:
- Android native role-aware global/context/primary action design: touch targets, system back, deep links, one-hand field capture, offline.
- Windows native context workspace, list/detail inspector, search/command, keyboard focus, high-density triage, window-size adaptations.
- Cross-device continuity rules, multi-role behavior, breadcrumbs/recent context, error-return routes; don't turn global navigation into giant ERP sidebar.
- A/B native Compose prototype on actual wiring or isolated opt-in with same source fixtures; real interaction trace and screenshots at exact SHA.
Exit only when: critical role journeys do not disappear down a single scroll, source truth and authorization untouched, back/keyboard/RTL/busy/fail states exercised; UX owner reviews decision. DO NOT freeze yet merely from synthetic renders.
**Current warehouse tabs are only a bounded local proof, not full app navigation.**

### UX-04 — REUSABLE SEMANTIC FOUNDATIONS — NOT CLOSED
Deliverables: tokens/components (semantic state vs decoration; typography Arabic/LTR IDs; light/dark/high contrast; spacing and compact vs touch-dense layouts; focus/selected/disabled; animation reduced motion; content states).
Design criteria: exact units, honest source status LOCAL/QUEUED/SYNCING/SERVER_CONFIRMED/CONFLICT/DENIED, no false successes, no naked debug text in human primary path.
Exit only when: minimal components in actual native Compose demo are compared on representative real source states with accessibility, density, bidi and performance evidence; initial design contract reviewed. Final signature visual freeze remains after Phase13 core.

### UX-05 — OPERATIONAL ROLE JOURNEYS — PARTIAL / ROLLING THROUGH PHASES
Use one bounded reviewed PR per highest-value problem after UX-00–03 evidence (parallel safety fixes allowed):
- Warehouse: Today -> code lookup/scan only where supported -> authoritative Asset Passport/Stock -> custody/location provenance -> return to context. Currently tab navigation PASS headless; detail focus and freshness NOT VERIFIED.
- Technician: Today -> WorkOrder -> offline begin/block/resume -> Evidence queue/replay/conflict/review; Phase6 engineering dependencies gate unavailable steps.
- PM/Engineer: Project/Site/Work -> drawings/revisions -> readiness/test evidence -> authoritative state; coordinate with Phase6.
- People/Admin: roster/approval/requests and sensitive state; avoid fictional payroll features until corresponding implementation.
- Mohamed: executive decision/exception contract now; full Company Brain follows Phase13 canonical scope.
Exit per role: source-current state and authorized action verified in native Android/Windows as applicable, real failure-recovery, operator review, cross-context return, no mutation/provenance regression.

### UX-06 — INBOX / ATTENTION / NOTIFICATIONS — NOT CLOSED
Treat this independently from Navigation. Existing Phase2 contracts distinguish source Work Queue (current actionable attention), Inbox (durable attention/read state), and Notifications (delivery intent/provider-neutral; no paid SMS/email/push assumed). 
Deliverables: event-to-visibility/acknowledgement and action routing, unread/seen/actioned semantics, no phantom urgent/badge, priority and deduplication, denied/revoked/expired/conflict and no-provider notification states, Android/Windows entry and deep-link context.
Exit only when: operator can identify what needs action vs already seen/read, return from Inbox to source, and synthetic/real test proves no false confirmation. Notification delivery providers depend on later explicit authorization/implementation.

### UX-07 — EVIDENCE, QA & PERFORMANCE GATES — PARTIAL / CROSS-CUTTING
Every candidate: precise baselines; native exact-head captures on phone/tablet/Windows, content and interaction states, CI/test reports, keyboard/Voice/TalkBack, Android scaling, RTL/LTR, window resize, offline/role revocation, busy/retry/empty/denied, performance (startup/recomposition/jank) measured not guessed, source authority inspection, small blinded operator exercise.
Exit: reproducible exact-head pass and explicit negative cases; no screenshots as a substitute for device or live backend evidence. #116 physical device gate remains independent.

### UX-08 — PHASE13 WHOLE-PRODUCT POLISH & RELEASE RECOMMENDATION — BLOCKED ON PHASE13 CORE
After core Phase13 functionalities and approved roles/workflows, complete coherent visual language, typography/color/density refinements, meaningful motion and sound only where appropriate, components throughout app, regression and field validation.
Exit: owner/product approval, native Android+Windows acceptance, change-control and reviewed merges; no unilateral deployment.

## Execution priority and dependency map
- NOW: close UX-00 and UX-01 evidence in parallel; UX-02 role/authority structure; UX-03 nav hypotheses/same-source comparison, using warehouse as one evidence lane rather than monopolizing program.
- IN PARALLEL: UX-05 functional/operational friction defects as features land through Phase 6–13, and UX-07 safety tests.
- THEN (based on evidence): UX-04 minimal semantic foundation; UX-06 Inbox and notification contract (not fictitious push provider).
- AFTER Phase13 core: UX-08 full visual polish & product acceptance.
- Reopen earlier decisions only with grounded contradiction. No arbitrary count of UI variants or aesthetic ratings accepted as Done.

## Reporting template per UX checkpoint (post in #128)
1. Milestone ID / exact SHA / branch / PR / state (NOT STARTED, RESEARCHED, IMPLEMENTED UNTESTED, TESTED SYNTHETIC, TESTED NATIVE, REVIEWED, ACCEPTED).
2. Evidence reviewed (links and device/fixture type) vs unknown.
3. Task + role + problem and proposed/design alternatives.
4. Actual code/test/capture changes; PASS/FAIL per acceptance gate.
5. Regressions/risks/impact on Engineering/DevOps.
6. Specific next gate and who must approve; never imply a draft experiment is the release.

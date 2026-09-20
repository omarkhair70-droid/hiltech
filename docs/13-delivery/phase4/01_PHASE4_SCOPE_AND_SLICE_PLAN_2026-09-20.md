# Phase 4 — Projects / Sites / Work Core — Scope & Slice Plan Closure

Date: 2026-09-20  
Status: **PASS / PHASE 4 SCOPE FROZEN / SLICE PLAN FROZEN / NO PRODUCTION CODE YET**

## Purpose

Freeze the Phase 4 product boundary and execution order after:
- repository reality reconstruction;
- owner review;
- first-slice contract review;
- Phase 1–3 foundation review;
- current mature Project/Field-Service reference review.

This file does **not** authorize all Phase 4 implementation at once.

Each slice still requires its own reality/contract closure before production code.

Canonical owner entry review:

`docs/13-delivery/phase4/00_PHASE4_OWNER_ENTRY_REALITY_RECONSTRUCTION_2026-09-20.md`

---

# 1. Phase 4 mission

Phase 4 makes HILTECH OS authoritative for:

`Project -> Site / ProjectSite -> planning structure -> WorkOrder -> WorkAssignment -> Readiness -> Review -> Accepted Progress / Project Health`

The phase solves:

- what delivery work exists;
- where it belongs;
- who/which current authority owns it;
- whether it is ready;
- what blocks it;
- how it is reviewed;
- how accepted work changes project progress;
- how management sees exceptions without reconstructing reality from calls/chats/spreadsheets.

It is **not** a generic task manager.

It is also **not** the full field/offline execution phase.

---

# 2. Inputs Phase 4 must consume, not duplicate

## Phase 1
- Identity;
- Organization;
- Team;
- OpenFGA relationship authorization;
- device/session/access truth.

## Phase 2
- shared HTTP / command runtime;
- Evidence metadata/binary lifecycle;
- Activity;
- Approval;
- Inbox / Work Queue;
- Notification abstraction;
- audit/idempotency/concurrency patterns.

## Phase 3
- Person / Employee / Employment;
- WorkforceAssignment;
- reporting structure;
- HR certifications where WorkType eligibility consumes them;
- onboarding/offboarding lifecycle;
- People-owned Team authority.

Phase 4 must reference these truths.

It must not create:
- a second employee model;
- a second Team model;
- a second approval engine;
- a second evidence store;
- a second task/inbox truth;
- direct role-name authorization shortcuts.

---

# 3. Configurability law

HILTECH is operationally flexible.

Therefore flexible business behavior belongs in versioned policy/configuration when that is its natural domain.

Examples:
- whether a Project may temporarily operate without a named PM;
- assignment mode;
- WorkType eligibility;
- readiness requirements;
- evidence requirements;
- review authority;
- checklist/instruction templates;
- health thresholds/signals;
- approval/waiver routes.

This does **not** mean everything is configurable.

Hard invariants remain code/domain laws when needed for:
- data integrity;
- security;
- audit/history;
- idempotency/concurrency;
- authorization boundaries;
- stale-write protection;
- module ownership;
- privacy/sensitive-field protection.

---

# 4. Frozen structural decisions

The following are carried forward from the accepted first-slice contracts and owner review.

## Project
- Project is the canonical delivery aggregate.
- Project lifecycle is authoritative business state.
- baseline changes are versioned.
- accepted operational progress is derived, not manually authoritative.
- Project health is explainable/derived.
- Project responsibility/PM relation is policy/context driven, never hard-coded to a named person.

## Site
- Site is durable physical/client location truth.
- Site survives Project closure.
- later Support/Warranty/Maintenance may reuse it.

## ProjectSite
- Project-specific relationship to Site.
- owns project-specific access/contact/notes/lifecycle.
- closing ProjectSite never deletes Site.

## Area / Room / Zone
- optional hierarchy below Site.
- no requirement that every job use a deep hierarchy.

## Milestone
- project checkpoint/acceptance/schedule concept.
- not the technician executable job.

## WorkPackage
- planning/grouping unit.
- not automatically equal to executable work.

## WorkOrder
- canonical executable work object.
- lifecycle and readiness are independent.
- instructions/policy bindings are versioned.
- assignment history is first class.

## Task / checklist
- optional finer execution structure.
- materialized only when WorkType/policy needs it.
- no universal forced micro-task bureaucracy.

---

# 5. Phase boundary contracts

## Phase 4 owns

- Project identity/lifecycle/baseline;
- Site / ProjectSite;
- optional Area structure;
- Milestone;
- WorkPackage;
- WorkOrder;
- WorkType/policy binding;
- readiness evaluation framework;
- WorkAssignment;
- Work blocker state;
- review/rework history needed for accepted work;
- accepted-work operational progress;
- explainable Project health;
- Project/PM/management work views;
- basic Android assigned-work read/context surface.

## Phase 5 owns

Authoritative:
- stock identity;
- inventory availability;
- material reservation;
- warehouse location;
- tool/asset custody;
- issue/return;
- consumption;
- equipment state.

Phase 4 may carry requirement references/integration slots.

It must not invent stock truth before Phase 5.

## Phase 6 owns

Full Field / Offline execution:
- durable Technician Job Bundle;
- offline StartWork;
- offline Block/Resume;
- field Evidence capture/upload/finalize;
- offline SubmitCompletion;
- sync/outbox/inbox orchestration;
- stale/conflict UX;
- process-death recovery;
- field tracking when configured.

Phase 4 must not absorb this engine merely to accelerate demos.

## Later phases

Phase 7:
- wider reporting/analytics/management surfaces.

Phase 8/10:
- commercial measurement / claim / invoice / tender/award truth.

Phase 11:
- handover/warranty/support/maintenance depth.

Operational accepted-work progress must remain distinct from commercial measured/claim progress.

---

# 6. Phase 4 slice plan

## Slice 01 — Project + Site / ProjectSite Core

Goal:
establish durable delivery identity and project/site relationships.

Must include:
- Project schema/lifecycle foundation;
- Project human code allocation through active CodePolicy;
- authorized Project creation path with optional source references;
- Project responsibility / PM relationship;
- Site durable master;
- ProjectSite association;
- project/site authorization hooks;
- audit/events/idempotency/versioning;
- Windows My Projects / Project header;
- project/site safe read models;
- human render proof.

Must not include:
- WorkOrder engine;
- Warehouse;
- Field/offline execution.

Exit:
one authorized user can create/open a Project, attach/reuse a Site, progress through the Slice-01 allowed lifecycle boundary, and see exact authoritative responsibility/context.

---

## Slice 02 — Project Planning Structure

Goal:
represent how delivery is decomposed before executable work.

Must include:
- optional Areas;
- Milestones;
- WorkPackages;
- baseline-version-safe planning;
- dependency/reference shape required by current contract;
- planning tree Windows surface;
- lifecycle/authorization/audit/version tests.

Must not invent:
- material availability;
- full technician execution;
- commercial claim calculation.

Exit:
a Project can be decomposed into stable planning structure without duplicating WorkOrder truth or later-domain truth.

---

## Slice 03 — WorkOrder + WorkType / Policy Binding

Goal:
create the canonical executable work object and explainable policy context.

Must include:
- WorkOrder schema;
- lifecycle/readiness separation;
- CodePolicy-driven work code;
- WorkTypeDefinition;
- normalized versioned WorkPolicyBinding history;
- versioned WorkInstructionRevision;
- checklist materialization;
- requirement-instance framework;
- create/plan WorkOrder commands;
- safe Project/Site/Area/Package relationship checks;
- Windows planning/edit surface;
- contract tests.

Must preserve:
- no mutable policy overwrite of old completed work;
- no opaque mutable checklist JSON as runtime truth.

Exit:
planned WorkOrder is fully explainable from exact bound revisions.

---

## Slice 04 — Readiness + WorkAssignment

Goal:
turn planned work into routed/assigned work without hard-coded people.

Must include:
- readiness evaluation;
- typed blockers / Waiting On explanation;
- AssignmentPolicy;
- eligible-target evaluation from current Phase 3 People/Team truth;
- WorkAssignment history;
- assign/reassign;
- USER / TEAM / CREW / SUBCONTRACTOR_ORGANIZATION target shape per policy;
- Work Queue projection for genuine current actions;
- authorization/OpenFGA;
- Windows assignment/readiness flow;
- regression proof that stale/offboarded/ineligible people cannot remain eligible.

Material/equipment requirements use honest Phase 5 integration state.

They do not fabricate availability.

Exit:
system can explain:
- whether work is assignable;
- who/what is eligible;
- why it is blocked;
- who owns the next decision.

---

## Slice 05 — Review / Rework / Accepted Progress / Project Health

Goal:
close the management loop from submitted/reviewable work to accepted project truth.

Must include only the Phase-4-owned review/acceptance boundary needed before the full Phase 6 field engine.

Must include:
- ReviewPolicy relationship;
- review/rework history;
- AcceptWork / RequestRework authority contract;
- exact-version review protection;
- accepted-weight Project progress;
- explainable ProjectHealth signals;
- PM Command Center / Waiting On / exceptions;
- management-by-exception surface;
- Activity/Work Queue integration;
- Windows human proof.

Where Phase 6 execution is not yet implemented:
- synthetic/controlled server-authoritative fixtures may prove review/progress calculations;
- UI must not pretend field capture/offline execution already exists.

Exit:
accepted work changes Project progress and health automatically and management can drill from risk to authoritative source object.

---

## Slice 06 — Basic Assigned Work Mobile Read / Phase 5–6 Handoff

Goal:
prove the same Phase 4 truth reaches Android without pretending Phase 6 is already complete.

Must include:
- Android Today / Assigned Work;
- safe Project/Site context;
- WorkOrder identity/status/readiness;
- instructions/current revision metadata;
- current assignment;
- blocker/read-only waiting context;
- safe document/context references as available;
- Arabic/RTL/adaptive proof;
- authorization/field projection tests.

Must not include:
- fake offline completion;
- local-only authoritative status changes;
- duplicate Evidence capture engine;
- hidden Phase 6 sync implementation.

Exit:
an assigned field employee can open the same authoritative work context on Android that PM/management see on Windows.

---

# 7. Project creation before Phase 10

Phase 4 may create Project through an authorized Project command.

Requirements:
- explicit source type/reference when known;
- source may be INTERNAL / IMPORT / future AWARD relation according to policy;
- no fake Opportunity/Tender/Award record is invented;
- future Phase 10 may make `CreateProjectFromAward` the normal path;
- existing Project ID/history remains stable.

The current path is therefore a real supported path, not temporary throwaway schema.

---

# 8. Responsibility / PM policy

The product must always be able to resolve current Project authority for required actions.

First-slice contract remains:

ACTIVE normally requires Project Manager / responsible authority unless active Project policy explicitly permits another temporary responsibility mode.

Valid implementation must:
- support current named PM relation when configured;
- not hard-code one employee;
- allow future team/owner/temporary-unassigned policy without schema replacement;
- keep exact history when responsibility changes.

---

# 9. Assignment policy

Assignment mode is configuration, not one global behavior.

Policy may allow:
- AUTO;
- SUGGEST_CONFIRM;
- MANUAL_ELIGIBLE;
- appropriate TEAM / CREW / SUBCONTRACTOR variants.

The system owns:
- eligibility;
- current employee/team truth;
- authorization;
- readiness;
- stale-state protection;
- audit/history.

Human judgment is invoked only where active policy says so.

---

# 10. Seed configuration policy

Synthetic/representative WorkTypes may be used for:
- development;
- contract proof;
- rendered UI;
- automated tests.

Rules:
- mark them as synthetic/seed;
- never claim them as final HILTECH operating catalog;
- no domain schema depends on knowing the entire real catalog now;
- real WorkTypes can be activated/configured later.

---

# 11. Research comparison retained

Current official reference review supports, but does not dictate, the design.

Microsoft Dynamics 365 Field Service demonstrates mature separation between:
- Work Order;
- scheduling/resource requirement;
- resource booking/assignment;
- technician execution;
- supervisor/review;
- configurable work-order/booking states.

It also supports manual scheduling, scheduling assistance and optimization rather than forcing one assignment mode.

Oracle Project Management demonstrates:
- project task structures;
- task/resource assignments;
- dependencies;
- milestones;
- reusable work-plan templates.

HILTECH intentionally keeps only the concepts justified by its own reality and contract pack.

References:
- https://learn.microsoft.com/en-us/dynamics365/field-service/work-order-status-booking-status
- https://learn.microsoft.com/en-us/dynamics365/field-service/field-service-architecture
- https://learn.microsoft.com/en-us/dynamics365/field-service/universal-resource-scheduling-for-field-service
- https://docs.oracle.com/en/cloud/saas/project-management/25c/oapem/work-plan-templates.html
- https://docs.oracle.com/en/cloud/saas/project-management/25c/oapem/project-milestones.html

---

# 12. Phase 4 Definition of Green

Phase 4 is not complete because all six slice titles were touched.

It closes only when:

1. every Slice 01–06 contract is VERIFIED / MERGED;
2. all authoritative schema/state boundaries are implemented;
3. Project/Site/Work authorization is proven;
4. no duplicate People/Team/Approval/Evidence/Work Queue truth exists;
5. accepted-work progress is deterministic/version safe;
6. Project health is explainable from source signals;
7. Project/Work history survives change/reassignment/review;
8. later Phase 5/6 dependencies remain honest boundaries;
9. Windows Project/PM flow has real rendered proof;
10. Android assigned-work read has real rendered proof;
11. Arabic/RTL/adaptive requirements are represented;
12. relevant Phase 0–3 regressions PASS;
13. exact-head CI PASS for every merge;
14. post-merge Bootstrap PASS;
15. Phase 4 final gap review finds no additional required Phase 4 slice.

Only then:

`PHASE_4 = VERIFIED / COMPLETE`

---

# 13. Current decision

`PHASE_4_SCOPE_CLOSURE = PASS`

`PHASE_4_SLICE_PLAN = FROZEN`

`PHASE_4_SLICE_COUNT = 6`

`PHASE_5_BOUNDARY = PRESERVED`

`PHASE_6_BOUNDARY = PRESERVED`

`FLEXIBLE_OPERATING_BEHAVIOR = VERSIONED_POLICY_CONFIGURATION`

`NEXT = SLICE_01_PROJECT_SITE_CORE_REALITY_AND_IMPLEMENTATION_CONTRACT_CLOSURE`

Slice 01 production code is **not yet authorized** by this file alone.

First close Slice 01 reality + implementation contract over the frozen Phase 4 boundary.

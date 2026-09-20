# Phase 4 / Slice 02 — Project Planning Structure

Date: 2026-09-20  
Status: **REALITY CLOSED / IMPLEMENTATION CONTRACT FROZEN / PRODUCTION CODE AUTHORIZED ONLY FOR SLICE 02**

## 1. Goal

Represent the baseline delivery structure before executable WorkOrders exist.

The hierarchy is:

`Project -> ProjectSite/Site -> optional Area -> Milestone / WorkPackage`

This Slice does not create WorkOrder runtime.

## 2. Existing truth reused

- Project/Site/ProjectSite from Slice 01.
- `area` table scaffold from V0004.
- Project responsibility authorization from Slice 01.
- Project.baselineVersion.
- Project lifecycle currently stops at PLANNING.
- Project planning docs already define Area, Milestone, WorkPackage and dependency concepts.

## 3. Area

Area remains durable physical hierarchy under Site.

V0022 hardens Area with:
- `organization_id` for HILTECH tenant consistency;
- composite consistency back to Site;
- createdAt/createdBy/updatedAt if absent;
- unique tenant/site/code;
- cycle prevention in service transaction + integration test.

Area fields:
- id;
- organizationId;
- siteId;
- parentAreaId?;
- typeCode;
- code;
- name;
- sequence?;
- restrictedAccess;
- createdAt/by;
- updatedAt;
- version.

Area is not Project-specific. Work scope references Area through ProjectSite/Site context.

## 4. Milestone

Create `milestone`.

Fields:
- id;
- organizationId;
- projectId;
- code;
- name;
- state: PLANNED / ACHIEVED / CANCELLED;
- plannedDate?;
- actualDate?;
- sequence?;
- clientVisible;
- acceptanceRequirement?;
- baselineVersion;
- createdAt/by;
- updatedAt;
- version.

Slice 02 only creates/updates PLANNED milestones while Project is PLANNING.

ACHIEVED behavior is not implemented in Slice 02. It is reserved for later authoritative work/review outcomes.

Unique:
- organizationId + projectId + code.

## 5. WorkPackage

Create `work_package`.

Fields:
- id;
- organizationId;
- projectId;
- projectSiteId?;
- siteId?;
- milestoneId?;
- code;
- name;
- description?;
- ownerPrincipalType?: EMPLOYEE / TEAM;
- ownerEmployeeId?;
- ownerTeamId?;
- state: PLANNED / ACTIVE / COMPLETED / CANCELLED;
- plannedStart?;
- plannedEnd?;
- sequence?;
- baselineVersion;
- createdAt/by;
- updatedAt;
- version.

Rules:
- Slice 02 creates/updates only PLANNED package state.
- siteId/projectSiteId must match Project tenant/client context when present.
- owner is descriptive planning responsibility, not an authorization grant.
- if ownerPrincipalType is EMPLOYEE or TEAM the referenced source must be current/same organization.
- plannedEnd >= plannedStart.
- unique Project + code.

## 6. Planning dependencies

Create normalized `project_plan_dependency`.

It supports dependencies between Milestone and WorkPackage without polymorphic orphan refs.

Fields:
- id;
- organizationId;
- projectId;
- predecessorMilestoneId?;
- predecessorWorkPackageId?;
- successorMilestoneId?;
- successorWorkPackageId?;
- dependencyType: FINISH_TO_START;
- lagMinutes: Long = 0;
- createdAt/by;
- version.

Checks:
- exactly one predecessor ref;
- exactly one successor ref;
- predecessor != successor;
- both nodes belong to same Project;
- no directed cycle;
- no duplicate logical edge.

Only FINISH_TO_START has execution semantics in Phase 4. New dependency semantics require explicit typed extension, not free-form codes.

## 7. Baseline safety

Planning mutation is allowed only while Project.lifecycleState == PLANNING.

Every planning command carries:
- operationId;
- baseProjectVersion;
- baseObjectVersion when updating a child;
- expectedBaselineVersion;
- clientOccurredAt.

At creation/update:
- row.baselineVersion = current Project.baselineVersion.

`MarkProjectReady`:
- requires exact Project version + baselineVersion;
- requires current responsible Project authority;
- requires at least one ProjectSite;
- requires at least one non-cancelled Milestone or WorkPackage;
- requires no invalid/cyclic planning dependency;
- requires all WorkPackages reference valid current Project/Site/Milestone context;
- freezes planning mutation under the current baseline;
- transitions PLANNING -> READY;
- emits `project.ready`.

Slice 02 does not increment baselineVersion.

After READY, generic Area/Milestone/WorkPackage mutation is denied. Future controlled variation/baseline change owns post-activation scope changes.

## 8. Commands

- CreateArea
- UpdateArea
- CreateMilestone
- UpdateMilestone
- CreateWorkPackage
- UpdateWorkPackage
- AddPlanDependency
- RemovePlanDependency
- MarkProjectReady

Delete semantics:
- no physical delete after referenced/ready.
- before READY an unreferenced draft planning node may be removed only through audited command or marked CANCELLED according object type.
- implementation should prefer state/history over destructive delete where history already matters.

## 9. Routes

- GET `/v1/projects/{projectId}/plan`
- POST `/v1/projects/{projectId}/areas`
- PUT `/v1/areas/{areaId}`
- POST `/v1/projects/{projectId}/milestones`
- PUT `/v1/milestones/{milestoneId}`
- POST `/v1/projects/{projectId}/work-packages`
- PUT `/v1/work-packages/{workPackageId}`
- POST `/v1/projects/{projectId}/plan-dependencies`
- DELETE `/v1/projects/{projectId}/plan-dependencies/{dependencyId}`
- POST `/v1/projects/{projectId}/mark-ready`

No generic lifecycle PATCH.

## 10. Authorization

Reads:
- Project `can_view`.

Mutations:
- Project `can_manage`.

PostgreSQL source guard remains mandatory:
- actor must still have current Project authority;
- stale/pending FGA tuple cannot override source truth.

Planning owner fields never grant permission.

## 11. Read model

`ProjectPlanReadModel`:
- Project identity/state/version/baselineVersion;
- ProjectSites;
- Area trees grouped by Site;
- Milestones ordered;
- WorkPackages ordered/grouped;
- dependency edges;
- validation summary;
- `canEditPlan`;
- `readyGate` with typed missing/invalid reasons.

No:
- readiness;
- assignee eligibility;
- material availability;
- Project progress/health;
- finance.

## 12. Windows

Add Project Planning Tree:
- Site/Area navigator;
- Milestone timeline/list;
- WorkPackage list/tree;
- dependency inspector;
- edit panel;
- READY gate panel;
- stale-version UX.

Arabic RTL/adaptive proof required.

## 13. Migration

Exact intended file:
`V0022__projects_planning__slice02.sql`.

Must:
- not rewrite V0004;
- backfill Area organizationId from Site.organization_id;
- add all FKs/indexes atomically;
- preserve clean migration on empty DB and upgrade from V0021.

## 14. Tests

- Area cross-tenant denied.
- Area cycle rejected.
- duplicate Area code rejected.
- Milestone/WorkPackage cross-Project relation rejected.
- WorkPackage site/projectSite mismatch rejected.
- dependency self-edge rejected.
- dependency cycle rejected.
- stale child version rejected.
- stale Project/baseline version rejected.
- unauthorized plan mutation denied.
- MarkReady blocked on invalid/empty required plan.
- MarkReady success PLANNING -> READY.
- mutation after READY denied.
- safe read model excludes later-domain fake signals.
- Windows render proves plan tree + READY gate + conflict state.

## 15. Exit

Slice 02 closes when a Project can be decomposed into stable Site/Area/Milestone/WorkPackage planning truth, the initial baseline is safely frozen at READY, and no WorkOrder/Warehouse/field truth has been invented.

`SLICE02_CONTRACT = FROZEN`

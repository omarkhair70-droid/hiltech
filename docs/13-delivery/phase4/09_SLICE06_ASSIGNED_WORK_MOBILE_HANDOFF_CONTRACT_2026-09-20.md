# Phase 4 / Slice 06 — Basic Assigned Work Mobile Read / Phase 5–6 Handoff

Date: 2026-09-20  
Status: **REALITY CLOSED / IMPLEMENTATION CONTRACT FROZEN / PRODUCTION CODE AUTHORIZED ONLY FOR SLICE 06**

## 1. Goal

Prove the same authoritative Work truth reaches an assigned field employee on Android without pretending Phase 6 execution/offline sync is complete.

This is an authenticated read vertical.

## 2. Android Today

`GET /v1/field/today`

Returns current assigned WorkOrders for the authenticated identity through current assignment source truth.

Included:
- WorkOrder id/code/title;
- lifecycle;
- readiness;
- planned start/end;
- priority;
- Project id/code/name;
- Site id/code/name;
- Area safe label if present;
- assignment target/context;
- blocker/waiting summary;
- instruction revision number;
- bundle freshness/version;
- whether the item is currently actionable vs waiting.

Excluded:
- unrelated Projects;
- commercial/internal budget;
- HR private data;
- Site restricted fields not required by field context;
- warehouse availability not yet authoritative;
- false offline/sync state.

## 3. TechnicianJobBundle v1

`GET /v1/work-orders/{workOrderId}/job-bundle`

Phase-4 bundle is read-only.

Includes:
- WorkOrder identity/lifecycle/readiness/version;
- Project safe context;
- Site/ProjectSite safe context;
- Area safe context;
- current assignment;
- exact WorkPolicyBinding revision summary;
- current WorkInstructionRevision;
- checklist item definitions/current server state;
- readiness requirement instances;
- evidence requirement definitions;
- required material/asset references with honest integration state;
- approved/current safe document refs where authoritative source exists;
- blocker summary;
- freshness/asOf;
- `executionCapabilities`.

`executionCapabilities` in Slice 06:
- startOffline = false;
- blockOffline = false;
- resumeOffline = false;
- evidenceCaptureOffline = false;
- submitOffline = false;
- authoritativeOfflineQueue = false.

The UI must not show execution buttons as if Phase 6 exists.

## 4. Read authorization

A Today/bundle item is visible only when:
- authenticated identity current;
- organization membership current;
- WorkAssignment current;
- assignment target resolves to the identity via USER / TEAM / CREW / authorized SUBCONTRACTOR membership;
- Project/Site field context is allowed.

Ended/replaced assignment removes the item immediately from source truth even if OpenFGA delete is pending.

## 5. Assignment target resolution

USER:
- exact linked user.

TEAM:
- current Team membership.

CREW:
- current WorkCrew membership resolving Employee -> current identity.

SUBCONTRACTOR_ORGANIZATION:
- current membership of that subcontractor organization only when external identity access is enabled by existing identity policy. If no such production identity path exists, HILTECH must not fabricate mobile access; the assignment remains valid business truth but no interactive user bundle is emitted.

## 6. Material/equipment handoff

Requirements may appear as:
- REQUIRED;
- SATISFIED when an authoritative source really exists;
- BLOCKED;
- SOURCE_PENDING_PHASE5.

Before Phase 5, UI must never say:
- stock available;
- reserved;
- issued;
- checked out

unless those states already come from an authoritative implemented source.

## 7. Document/evidence handoff

Slice 06 may show:
- required evidence definitions;
- existing authorized Evidence metadata/read;
- current authorized document revision refs.

It does not:
- capture Evidence;
- reserve/upload binaries as technician workflow;
- finalize offline evidence;
- queue completion.

Those are Phase 6.

## 8. Local behavior

Slice 06 may use ordinary presentation-state caching needed by the existing KMP app.

It must not introduce the Phase-6 durable authoritative command queue.

No label like "Started on this device", "Waiting to sync", or "Completed on this device" exists in Slice 06 because those require Phase-6 local command truth.

## 9. Android UX

Arabic-first / RTL / adaptive.

Today card:
- code/title;
- time;
- Site;
- status/readiness;
- important blocker;
- assignment context.

Detail:
- context;
- instructions;
- checklist/read-only requirements;
- documents;
- blockers;
- current resource requirement state;
- exact message when action belongs to later phase or another role.

Minimal typing. No ERP-style giant table.

## 10. Shared client

Add shared KMP DTO/client for:
- Today;
- JobBundle.

DTOs are safe projections, not mirrors of restricted database rows.

## 11. Tests

Server:
- only current assignments returned.
- ended/replaced assignment disappears.
- Team resolution.
- Crew resolution.
- no-login Employee assignment does not invent mobile user access.
- subcontractor interactive access fails closed when unsupported.
- Project/Site safe projection.
- restricted fields omitted.
- Phase-5 resource truth not fabricated.
- bundle exact WorkOrder/config/instruction versions.

Shared:
- authenticated route/path/header contract.
- DTO compatibility.

Android:
- Arabic RTL Today render.
- Assigned Work detail render.
- waiting/blocked states.
- no execution/offline controls.
- no fake synced state.
- assignment revoke/remove render.

## 12. Phase 6 handoff

Phase 6 extends this same Work truth with:
- durable Job Bundle persistence;
- offline StartWork;
- Block/Resume;
- Evidence capture/upload/finalize;
- SubmitCompletion;
- outbox/inbox sync;
- conflict UX;
- process-death recovery;
- field tracking when configured.

Phase 6 must not create a second WorkOrder model.

## 13. Phase 5 handoff

Phase 5 supplies authoritative:
- asset/tool availability;
- reservation;
- custody;
- stock;
- issue/return/consume;
- storage location.

Readiness requirement integration consumes those facts without moving ownership into Work.

## 14. Exit

An assigned field identity can open the same authoritative Work context on Android that PM/management see on Windows, while every unsupported Warehouse/offline-execution capability is explicitly honest.

`SLICE06_CONTRACT = FROZEN`

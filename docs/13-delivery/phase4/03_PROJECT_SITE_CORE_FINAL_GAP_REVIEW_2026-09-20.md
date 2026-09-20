# Phase 4 / Slice 01 — Project + Site / ProjectSite Core — Final Gap Review

Date: 2026-09-20

Status: **PASS / VERIFIED / MERGED / NO ADDITIONAL SLICE 01 WORK REQUIRED**

This document closes Phase 4 / Slice 01 after production implementation, exact-head regression proof, rendered Windows proof, merge, post-merge Bootstrap, and a final contract-to-code gap review.

Canonical frozen contract:

`docs/13-delivery/phase4/02_PROJECT_SITE_CORE_REALITY_AND_IMPLEMENTATION_CONTRACT_2026-09-20.md`

---

## 1. Final production shape

Slice 01 now implements the frozen delivery identity vertical:

`Project -> ProjectSite -> Site`

Production behavior includes:

- forward-only `V0021__projects_sites__slice01.sql`;
- explicit Project source semantics: `INTERNAL | IMPORT`;
- Project human-code allocation through active `CodePolicy`;
- effective-dated `PROJECT_MANAGER` responsibility history with `EMPLOYEE | TEAM` principals;
- no authoritative mutable `project.project_manager_id`;
- durable Site master with explicit HILTECH organization tenancy;
- ProjectSite same-tenant composite integrity;
- Project administration source truth through `project_authority_binding`;
- OpenFGA Project/Site relations and fail-closed authorization projection;
- Project lifecycle limited to `DRAFT -> KICKOFF -> PLANNING`;
- CreateProject / Project detail / My Projects / metadata update / responsibility change;
- CreateSite / Site detail / Site update;
- ProjectSite attach using either an existing Site or an explicit typed create-and-attach Site mode;
- idempotent commands, optimistic versions, audit and safe domain events;
- shared KMP DTO/API client;
- Windows My Projects + Project header/detail + responsibility + Site/ProjectSite + Kickoff flow;
- Arabic RTL human render proof and stale-version conflict render.

No READY/ACTIVE, WorkOrder, Warehouse, material/equipment truth, field execution or offline completion was pulled forward.

---

## 2. Controlled implementation refinements preserved

Two controlled pre-code amendments remain part of the accepted Slice 01 contract.

### Project administration truth

`project_authority_binding` is the Projects-owned source for `PROJECT_ADMIN` authority.

No permission is inferred from:
- membershipType;
- roleLabel;
- WorkforceAssignment.roleCode;
- employee/job title;
- named-person hard-code.

OpenFGA derives `can_manage_projects` from explicit Project administration authority.

### Site tenancy

Site now carries explicit owning HILTECH `organization_id` in addition to `client_organization_id`.

ProjectSite carries `organization_id` and composite foreign keys proving Project and Site share the same HILTECH tenant.

---

## 3. Cross-cutting consumer migration

Removing the pre-Phase-3 `project_manager_id` scaffold exposed one inherited consumer in Activity authorization.

The implementation was corrected rather than preserving dual truth:

- Activity authorization now consumes current `project_responsibility`;
- Employee responsibility resolves through current Person/UserIdentity linkage;
- Team responsibility uses current Team membership;
- ended responsibility denies from PostgreSQL source truth even if a stale OpenFGA tuple still exists.

This is a valid cross-cutting compatibility repair and does not reopen Phase 2 or Phase 3.

---

## 4. PR #56 — primary implementation

PR:
`#56 — Phase 4 Slice 01: Project + Site / ProjectSite Core`

Canonical tested head before merge:

`eb2532e0e146ff7220511e7bd7124d26a241568c`

Exact-head evidence:

- Phase 4 — Project Site Core `35513928924` — **PASS**
  - PostgreSQL + OpenFGA Project/Site production contract — PASS
  - Windows Project/Site human render — PASS
- Bootstrap Phase 0 `35513928975` — **PASS**
- Contract — OpenFGA First Slice `35513928950` — **PASS**
- Phase 2 — Shared Command Runtime `35513929038` — **PASS**
- Phase 1 — Native OIDC Production Smoke `35513928898` — **PASS**
- Phase 3 — Onboarding Human Proof `35513928921` — **PASS**
- Phase 3 — Assignment Change Human Proof `35513928995` — **PASS after rerun of the cancelled render job**
- Phase 3 — Offboarding Human Proof `35513929036` — **PASS**

Merge commit:

`aed66c5b0fa4dacb4d931be8c584fbcd08146f7a`

Post-merge Bootstrap:

`35514946464` — **PASS**

---

## 5. Final gap review after PR #56

The post-merge contract review found two narrow Slice-01 gaps.

### Gap A — My Projects / Project header content

The frozen Windows contract requires:
- client;
- planned dates where present.

The first implementation rendered lifecycle/responsibility correctly but omitted these fields from the My Projects row and did not show planned dates explicitly in the selected Project header.

Resolution:
- My Projects now renders client + planned date range;
- Project header renders planned date range explicitly.

### Gap B — typed create-and-attach Site mode

The frozen route contract allows:

`POST /v1/projects/{projectId}/sites`

to attach an existing Site **or** create+attach through an explicit typed mode.

The first implementation exposed separate CreateSite + attach-existing behavior, but the attach request itself did not expose the typed create mode.

Resolution:
- request accepts exactly one of `siteId` or `createSite`;
- dual/empty mode is rejected;
- create mode derives HILTECH tenant + client context from the already-authorized Project;
- Site creation uses a deterministic child operation ID, so retry is safe;
- ProjectSite attachment keeps the caller's operation ID and base Project version;
- shared KMP DTO/client mirrors the same contract;
- shared client test proves existing/create modes and rejects ambiguous dual mode.

No schema replacement or later-slice behavior was required.

---

## 6. PR #57 — final gap closure

PR:

`#57 — Phase 4 Slice 01: final gap closure`

Final exact tested Slice 01 code head:

`89c19605405dad912457dc22286751c2eb379752`

Exact-head evidence:

- Phase 4 — Project Site Core `35515218944` — **PASS**
- Bootstrap Phase 0 `35515218905` — **PASS**
- Phase 1 — Native OIDC Production Smoke `35515218910` — **PASS**
  - first Android render attempt hit emulator/UI startup timing;
  - the failed Android job was rerun;
  - final workflow conclusion is PASS;
  - Desktop shell + provider browser smoke were also PASS.
- Phase 3 — Onboarding Human Proof `35515218926` — **PASS**
- Phase 3 — Assignment Change Human Proof `35515218940` — **PASS**
- Phase 3 — Offboarding Human Proof `35515218945` — **PASS**

The dedicated Phase 4 workflow includes both:
- production PostgreSQL/OpenFGA contract proof;
- Desktop Project/Site rendered human proof.

Merge commit:

`e6b87dd4a6a4f66e72190a5d1d3c632b414f359e`

Final post-merge Bootstrap on `main`:

`35515882737` — **PASS**

---

## 7. Contract-to-code final checklist

| Slice 01 obligation | Final result |
|---|---|
| Forward-only Project/Site migration | PASS |
| CodePolicy Project code allocation | PASS |
| INTERNAL / IMPORT source provenance | PASS |
| Employee/Team Project responsibility history | PASS |
| Project admin explicit source truth | PASS |
| Site HILTECH tenancy | PASS |
| ProjectSite tenant/client integrity | PASS |
| DRAFT -> KICKOFF -> PLANNING only | PASS |
| No READY/ACTIVE endpoint | PASS |
| Idempotency / optimistic version | PASS |
| Audit / safe domain events | PASS |
| OpenFGA + fail-closed projection | PASS |
| Ended responsibility immediate deny | PASS |
| My Projects safe authorized list | PASS |
| Client + planned dates in My Projects/header | PASS |
| Site create / reuse / attach | PASS |
| Typed existing/create ProjectSite request modes | PASS |
| Restricted Site/ProjectSite fields | PASS |
| Shared KMP DTO/API client | PASS |
| Windows Arabic RTL render | PASS |
| stale-version conflict render | PASS |
| No fake Project health/progress/readiness | PASS |
| Phase 5 Warehouse boundary preserved | PASS |
| Phase 6 Field/Offline boundary preserved | PASS |

---

## 8. Final gap decision

No additional Phase 4 / Slice 01 implementation is required.

Remaining work belongs to the already-frozen later boundaries.

Next:

**Phase 4 / Slice 02 — Project Planning Structure — Reality + Implementation Contract Closure**

Slice 02 must close its own exact implementation contract before production code begins.

It owns:
- optional Areas;
- Milestones;
- WorkPackages;
- baseline-version-safe planning;
- dependency/reference shape;
- Windows planning tree;
- lifecycle/authorization/audit/version behavior required for planning.

It must not pull WorkOrder, Warehouse, technician execution, material availability or commercial claims forward.

---

`PHASE4_SLICE01_FINAL_GAP_REVIEW = PASS`

`PHASE4_SLICE01_PRODUCTION = VERIFIED_AND_MERGED`

`PHASE4_SLICE01_FINAL_TESTED_HEAD = 89c19605405dad912457dc22286751c2eb379752`

`PHASE4_SLICE01_FINAL_MERGE = e6b87dd4a6a4f66e72190a5d1d3c632b414f359e`

`PHASE4_SLICE01_POST_MERGE_BOOTSTRAP = 35515882737_PASS`

`ADDITIONAL_SLICE01_WORK_REQUIRED = NO`

`NEXT = PHASE4_SLICE02_REALITY_AND_IMPLEMENTATION_CONTRACT_CLOSURE`

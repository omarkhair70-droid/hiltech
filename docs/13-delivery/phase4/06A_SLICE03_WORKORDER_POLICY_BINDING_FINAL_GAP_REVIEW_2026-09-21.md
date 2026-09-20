# Phase 4 / Slice 03 — WorkOrder + WorkType / Policy Binding — Final Gap Review

Date: 2026-09-21

Status: **PASS / PRE-MERGE VERIFIED / NO ADDITIONAL SLICE 03 IMPLEMENTATION GAP FOUND**

Canonical frozen contracts:

- `docs/13-delivery/phase4/06_SLICE03_WORKORDER_POLICY_BINDING_CONTRACT_2026-09-20.md`
- `docs/13-delivery/phase4/10_PHASE4_COMPLETION_GATES_AND_CODEX_HANDOFF_2026-09-20.md`

This document records the final contract-to-code review for PR #61 before the final evidence-record commit is merged.

The production code reviewed here is exact head:

`9ba0a26796c85cc59999138431f1bceef1639ab7`

The commit that adds this document is documentation-only. PR #61 must still pass all applicable exact-head gates again after this document is added before Ready/Merge.

---

## 1. Final production shape

Slice 03 implements the frozen WorkOrder planning and revision-binding vertical without pulling assignment or execution forward.

Production behavior includes:

- forward-only `V0023__work_order_policy_binding__slice03.sql`;
- WorkOrder authoritative organization/baseline/project/site/project-site/area/work-package context;
- same-tenant composite foreign keys and WorkPackage/Site compatibility enforcement;
- current WorkPolicyBinding and WorkInstructionRevision same-WorkOrder pointer integrity;
- one-current WorkPolicyBinding uniqueness;
- revisioned WorkType / AssignmentPolicy / ReadinessPolicy / EvidencePolicy / ReviewPolicy binding;
- optional TrackingPolicy / checklist / instruction template binding;
- DOCUMENT requirement-template binding added forward-only;
- WorkOrder CodePolicy allocation with PROJECT sequence scope and manual-override enforcement;
- DRAFT creation and DRAFT -> PLANNED planning transition;
- readiness retained as NOT_EVALUATED in Slice 03;
- checklist instances materialized as PENDING;
- READINESS / EVIDENCE / ASSET / MATERIAL / DOCUMENT requirement instances materialized without fabricated satisfaction;
- append-only WorkInstructionRevision history;
- optional planning-only WorkTask create/update;
- normalized FINISH_TO_START WorkOrder dependency add/remove with cycle rejection;
- READY -> ACTIVE Project activation gate;
- audit/domain-event/idempotency/optimistic-version behavior;
- PostgreSQL + OpenFGA authorization contract;
- shared KMP Work DTO/client;
- Windows Arabic RTL Work planning/editor human proof.

No assignment execution, readiness evaluation engine, Phase-5 resource truth, technician execution, offline queue, evidence submission execution, or sync/conflict engine was introduced.

---

## 2. Migration closure

`DatabaseMigrationContractTest.emptyPostgresMigratesThroughPriorSliceThenV0023AndRejectsInvalidStates` proves the forward path explicitly:

1. starts from empty PostgreSQL;
2. migrates through Flyway target `22`;
3. asserts 22 prior migrations executed;
4. applies the repository migration set again;
5. asserts exactly one additional migration executes: V0023.

This proves both empty-database progression and prior-Slice-schema -> V0023 progression without editing old migrations.

Bootstrap Phase 0 runs `DatabaseMigrationContractTest` in both its database-contract and local-platform-contract paths.

---

## 3. Context and schema integrity

V0023 enforces:

- WorkOrder `organization_id` and `baseline_version` as authoritative non-null context;
- Project/organization composite FK;
- Site/organization composite FK;
- ProjectSite/organization/project/site composite FK;
- Area/organization/site composite FK;
- WorkPackage/organization/project composite FK;
- WorkPackage/Site compatibility trigger;
- same-WorkOrder current policy pointer;
- same-WorkOrder current instruction pointer;
- WorkTask -> WorkOrder tenant context;
- WorkOrder dependency -> Project/organization and predecessor/successor context;
- no self dependency;
- FINISH_TO_START only;
- unique dependency edge.

The PostgreSQL/OpenFGA integration contract also rejects an invalid WorkOrder Site / ProjectSite / Area / WorkPackage context before creation.

No parallel Project/Work truth was introduced.

---

## 4. Work configuration binding and history

Final review confirms:

- WorkType selection is exact revision-aware;
- bound typed policies/templates must resolve as ACTIVE compatible configuration;
- organization/system scope compatibility is enforced;
- planning snapshots exact revisions into WorkPolicyBinding;
- one current unsuperseded binding is enforced;
- initial instruction revision and later planning-only revision history are append-only;
- checklist and requirements materialize from bound revisions;
- DOCUMENT is included with the other four requirement families;
- config supersession does not rewrite historical WorkOrder binding.

The PostgreSQL/OpenFGA contract explicitly supersedes the active WorkType configuration after planning and then verifies the historical WorkOrder still reports revision 1.

---

## 5. Command and mutation proof

Frozen Slice 03 commands/routes are present:

- CreateWorkOrder;
- UpdateWorkOrderDetails;
- PlanWork;
- ReviseWorkInstruction;
- CreateWorkTask;
- UpdateWorkTask;
- AddWorkDependency;
- RemoveWorkDependency;
- ActivateProject.

Contract coverage proves:

- unauthorized WorkOrder create denied;
- manual code denied when CodePolicy disallows override;
- generated PROJECT-scoped WorkOrder codes;
- idempotent WorkOrder create;
- stale WorkOrder update rejected;
- unauthorized PlanWork denied;
- stale PlanWork rejected;
- idempotent PlanWork without duplicate checklist/requirement materialization;
- instruction revision append-only;
- WorkTask creation;
- WorkTask update and idempotent update replay;
- second WorkOrder code allocation;
- activation blocked while included current-baseline work remains DRAFT;
- dependency creation;
- dependency cycle rejection;
- dependency removal and idempotent removal replay;
- successful READY -> ACTIVE Project activation;
- current binding uniqueness;
- audit evidence for planning, task update, dependency removal and activation;
- historical binding stability after configuration supersession.

---

## 6. Authorization

Create/plan/mutation paths preserve both layers required by the frozen contract:

- OpenFGA Project `can_create_work`;
- PostgreSQL current-source Project authority guard.

No permission is inferred from descriptive employee/job-title fields and no Work assignment relation is created by Slice 03.

---

## 7. Shared client and Windows proof

The shared KMP Work client carries typed routes, canonical UUID validation, installation identity, and Idempotency-Key propagation.

Dedicated workflow:

`Phase 4 — WorkOrder Policy Binding`

Run on reviewed production-code head:

`35545718479` — **PASS**

Jobs:

- `work-order-policy-contract` — PASS;
- `desktop-work-planning-render` — PASS.

Rendered artifact:

- name: `hiltech-phase4-work-planning-evidence`;
- artifact id: `10616353258`;
- digest: `sha256:e92d91bcc6cb0ef29aa8f5e7e9d26d1743cd9e35c2d0718e7e7d7b03fa52178d`.

The render gate proves:

- bound revisions;
- DRAFT activation gate;
- READY activation state;
- Arabic RTL;
- requirements remain PENDING;
- readiness remains NOT_EVALUATED;
- no fake assignment;
- no fake Phase-5 resource availability;
- WorkType selector;
- schedule editor;
- dependency editor;
- WorkTask update control.

---

## 8. Exact-head regression evidence before evidence-record commit

Reviewed production-code head:

`9ba0a26796c85cc59999138431f1bceef1639ab7`

All applicable PR workflows were GREEN on this same head:

| Workflow | Run | Result |
|---|---:|---|
| Phase 4 — WorkOrder Policy Binding | 35545718479 | PASS |
| Bootstrap Phase 0 | 35545718419 | PASS |
| Phase 4 — Project Planning Structure | 35545718434 | PASS |
| Phase 4 — Project Site Core | 35545718418 | PASS |
| Phase 2 — Shared Command Runtime | 35545718449 | PASS |
| Phase 3 — Onboarding Human Proof | 35545718572 | PASS |
| Phase 3 — Assignment Change Human Proof | 35545718440 | PASS |
| Phase 3 — Offboarding Human Proof | 35545718417 | PASS |
| Phase 1 — Native OIDC Production Smoke | 35545718505 | PASS |

There were no unresolved deterministic failures on this head.

---

## 9. Contract-to-code final checklist

| Slice 03 obligation | Review result |
|---|---|
| Forward-only V0023 | PASS |
| Empty DB through prior schema then V0023 | PASS |
| WorkOrder same-tenant context | PASS |
| WorkPackage FK / Site compatibility | PASS |
| CodePolicy allocation / manual override | PASS |
| Exact revision config binding | PASS |
| One current WorkPolicyBinding | PASS |
| Historical config supersession safety | PASS |
| Instruction append-only revision | PASS |
| Checklist exact materialization | PASS |
| READINESS/EVIDENCE/ASSET/MATERIAL/DOCUMENT requirements | PASS |
| Requirement state not fabricated | PASS |
| WorkTask planning-only create/update | PASS |
| WorkTask idempotent update replay | PASS |
| Work dependency add/remove | PASS |
| Work dependency cycle protection | PASS |
| WorkOrder update/plan stale conflict | PASS |
| Unauthorized create/plan deny | PASS |
| Project activation DRAFT/unbound gate | PASS |
| Project READY -> ACTIVE | PASS |
| Audit/events | PASS |
| Shared KMP Work client | PASS |
| Windows Work planning/editor proof | PASS |
| Arabic RTL proof | PASS |
| No fake assignment/readiness evaluation | PASS |
| No Phase-5 Warehouse/material/asset truth | PASS |
| No Phase-6 field/offline execution | PASS |

---

## 10. Final gap decision

No genuine Slice-03-owned production gap remains after the final contract-to-code review.

No frozen business semantics need reopening.

No Slice 04 / Phase 5 / Phase 6 behavior should be pulled into this PR.

After this evidence-record document is committed:

1. require all applicable workflows GREEN on that new exact head;
2. mark PR #61 Ready;
3. merge only that exact tested head;
4. verify post-merge Bootstrap on remote `main`;
5. record the final merge/post-merge evidence in PR #61;
6. only then branch Slice 04 from the new `main`.

---

`PHASE4_SLICE03_FINAL_GAP_REVIEW = PASS`

`PHASE4_SLICE03_PRODUCTION_CODE_REVIEWED_HEAD = 9ba0a26796c85cc59999138431f1bceef1639ab7`

`PHASE4_SLICE03_ADDITIONAL_IMPLEMENTATION_REQUIRED = NO`

`PHASE4_SLICE03_BOUNDARIES_PRESERVED = YES`

`NEXT_GATE = FINAL_EVIDENCE_RECORD_HEAD_CI`

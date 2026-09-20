# Phase 4 — Completion Gates + Codex Execution Handoff

Date: 2026-09-20  
Status: **FROZEN EXECUTION INSTRUCTION**

## 1. Current state

Slice 01:
**VERIFIED / MERGED / CLOSED**

Remaining production execution:
- Slice 02
- Slice 03
- Slice 04
- Slice 05
- Slice 06

The product decisions for those Slices are frozen in the Phase-4 contract set.

## 2. Required Slice closure pattern

A Slice is COMPLETE only when all applicable gates are green on one exact commit:

1. forward migration applies from clean DB;
2. forward migration applies from prior Slice schema;
3. server compile/unit tests;
4. PostgreSQL integration/domain contract;
5. authorization/OpenFGA contract if relations changed;
6. idempotency replay tests;
7. optimistic version/stale conflict tests;
8. audit/event tests;
9. shared KMP DTO/client tests;
10. Windows render proof when Windows changes;
11. Android render proof when Android changes;
12. Phase 0–3 regression workflows;
13. Slice-specific workflow;
14. final gap review against frozen Slice contract;
15. exact tested head merged;
16. post-merge Bootstrap success.

No "CI mostly green" closure.

## 3. Slice-specific minimum proof

### Slice 02

Must prove:
- planning tenant integrity;
- Area hierarchy cycle protection;
- Milestone/WorkPackage/dependency correctness;
- READY gate;
- plan immutability after READY;
- Windows planning tree.

### Slice 03

Must prove:
- CodePolicy WorkOrder allocation;
- exact policy/instruction/checklist/requirement binding;
- config supersession history safety;
- Work dependency cycle protection;
- Project activation gate;
- Windows Work planning.

### Slice 04

Must prove:
- explainable eligibility;
- current People/Team/Certification source checks;
- USER/TEAM/CREW/SUBCONTRACTOR shape;
- honest Phase-5 unavailable source behavior;
- assignment history;
- fail-closed auth projection;
- Windows readiness/assignment.

### Slice 05

Must prove:
- exact-version review;
- reviewer source truth;
- evidence acceptance gate;
- rework history;
- accepted-weight progress;
- explainable health;
- ON_HOLD behavior;
- PM Command Center.

### Slice 06

Must prove:
- current assignment Today filter;
- safe JobBundle;
- revoked assignment immediate disappearance;
- no restricted leakage;
- no fake Warehouse/offline capability;
- Android Arabic RTL/adaptive proof.

## 4. Phase 4 final completion review

After Slice 06 merge, create:

`docs/13-delivery/phase4/11_PHASE4_FINAL_GAP_REVIEW_<date>.md`

It must compare production against:
- owner-entry reality reconstruction;
- six-slice plan;
- Slice 01 final gap review;
- Slice 02–06 contracts;
- Phase 5/6 boundaries.

Phase 4 can be marked COMPLETE only if:
- no required Phase-4 object/action/surface is missing;
- no fake later-domain truth exists;
- Project/Work source ownership is coherent;
- exact current main Bootstrap passes.

## 5. Codex instruction

Use this exact intent when handing execution to Codex:

> GitHub is the source of truth. Do not redesign Phase 4 from chat memory.
>
> Read AGENTS.md, CURRENT_PROGRAM_STATUS, IMPLEMENTATION_ORDER, the Phase 4 docs 00–10, the first-slice contract pack, current main, open PRs and Actions before changing code.
>
> Slice 01 is already verified and merged. Implement the remaining Phase 4 Slices sequentially: Slice 02 -> 03 -> 04 -> 05 -> 06.
>
> For each Slice, implement only the frozen contract for that Slice: forward DB migration where specified, server/domain behavior, authorization/projection, audit/events/idempotency/version protection, shared DTO/client, required Windows/Android surface, contract tests and human render proof.
>
> Keep each Slice in its own branch/PR. Keep the PR Draft until complete. Diagnose CI failures from logs; do not blindly rerun deterministic failures. A Slice closes only when all required gates are green on one exact head, the final gap review finds no remaining Slice-owned work, that exact head is merged, and post-merge Bootstrap passes.
>
> Do not pull later work forward. Phase 5 owns Warehouse/Assets/Materials/Tools source truth. Phase 6 owns technician offline execution, Evidence capture workflow, durable sync/outbox/inbox, process-death recovery and field tracking execution. Do not fabricate those states in Phase 4.
>
> Do not hard-code Mohamed, Ahmed, current staff counts, job titles, membership labels, one global assignment mode, one fixed code format, one final WorkType catalog or one Warehouse assumption.
>
> PostgreSQL remains business source truth. OpenFGA relations are projected with the existing fail-closed consistency contract. Pending grants deny; source revokes deny immediately.
>
> If a genuine repository contradiction would change business semantics, stop and document the contradiction. Normal implementation difficulty is not a reason to redesign the contract.

## 6. Agent non-negotiables

Codex must not:
- edit old migrations;
- merge a red/untested head;
- invent a second Project/Work truth;
- infer permission from descriptive role fields;
- add manual Project progress;
- make ProjectHealth an editable manager opinion;
- mark resource requirements satisfied without authoritative Phase-5 source;
- add offline Start/Complete before Phase 6;
- use local-only state as server truth;
- silently relax exact-version review;
- silently rebind historical WorkOrders to new config revisions.

## 7. Expected end state

When Codex finishes Slice 06 and final Phase-4 review passes:

`PHASE4 = VERIFIED / COMPLETE`

Then planning/execution advances to Phase 5 without reopening Phase 4 unless a real cross-phase contradiction is proven.

`CODEX_HANDOFF = READY_AFTER_THIS_PLANNING_SET_IS_MERGED`

# Phase 5 — Completion Gates and Implementation Handoff

Date: 2026-09-21
Status: **FROZEN**

## 1. Execution sequence

`Slice 01 -> 02 -> 03 -> 04 -> 05 -> 06 -> 07 -> 08 -> 09 -> Final Gap Review`

No production coding should skip the source contract for the current Slice.

## 2. Branch discipline

Each Slice:

- begins from current `main`;
- uses its own branch;
- opens Draft PR after first safe production commit;
- no unrelated refactor;
- no edit of previous Flyway migration;
- no next-Slice domain pull-forward.

## 3. Migration sequence

Planned:

- Slice 01 -> V0026
- Slice 02 -> V0027
- Slice 03 -> V0028
- Slice 04 -> V0029
- Slice 05 -> V0030
- Slice 06 -> V0031
- Slice 07 -> V0032
- Slice 08 -> V0033
- Slice 09 -> no migration by default

If a Slice proves no schema invariant is needed, do not create an empty migration merely to preserve numbering.

If an unforeseen invariant requires additional forward migration, document it in the Slice gap review and keep ordering forward-only.

## 4. Required CI families

Every schema/server Slice:

- empty-PostgreSQL migration contract;
- prior-schema -> new migration path;
- current-source authorization tests;
- idempotency/version collision;
- domain integration contract.

Applicable shared/UI:

- shared client route/header/DTO tests;
- Windows human render;
- Android human render;
- existing Phase 0–4 regressions;
- Bootstrap Phase 0 exact head.

## 5. Phase-wide invariants

Must remain true:

### Asset

- persistent identity;
- append-only movement;
- one current custody;
- no double checkout;
- tag replacement preserves identity;
- calibration/maintenance/incident can block availability;
- retired Asset cannot normal checkout;
- cost visibility restricted.

### Stock

- exact Decimal;
- no accidental negative authoritative quantity;
- movement append-only;
- balance rebuildable;
- reservation cannot over-allocate;
- issue/return/consume conservation;
- adjustment cannot bypass approval/reason.

### Import

- legacy row trace;
- ambiguity cannot silently become production truth;
- opening balance/custody explicit;
- no fake historical movements;
- no source-column label is silently promoted into Asset/Stock semantics.

### Authorization

- current PostgreSQL source guard;
- stale FGA tuple never overrides ended binding;
- Project authority != Warehouse operator;
- custody != Warehouse authority;
- value visibility separate.

### Integration

- Project/Site/Work reused, not duplicated;
- People/Employee reused, not duplicated;
- Approval reused, not duplicated;
- Finance not duplicated;
- Phase-4 readiness consumes Phase-5 resource facts;
- Phase-6 field/offline remains later.

## 6. Representative-data gates

Before Stock cutover:

- UTP/FIBER/ACTIVE/Accessories import rows reviewed;
- UOM mapping approved;
- initial StorageLocation approved;
- ambiguous rows remain blocked;
- legacy column labels with ambiguous semantics remain blocked until explicit mapping/review.

Before Asset production seed:

- representative high-value Asset list reviewed;
- serial/location/custodian captured where known;
- missing values remain null/unknown.

Before calibration rule activation:

- actual calibration-required AssetTypes validated.

No code hard-codes missing seed reality.

## 7. Human usability gates

Warehouse user must be able to:

- find inventory;
- understand where it is;
- understand current custody;
- reserve;
- checkout/issue;
- return/transfer;
- receive;
- inspect;
- count;
- investigate discrepancy;
- request/approve adjustment under authority;
- understand why a resource is blocked.

PM must be able to:

- see resource requirement;
- see availability/shortage;
- see reservation/fulfilment context;
- see Work readiness update.

Android/mobile must never expose restricted value to technician/custodian.

## 8. No-fake-state gates

Prohibited claims unless backed by source:

- available when stock source unavailable;
- reserved without reservation;
- checked out without movement;
- returned without receiver confirmation;
- consumed without issued balance;
- calibrated without record;
- inventory adjusted by editing count;
- received from invoice total only;
- custody inferred from camera/access log.

## 9. Slice closure procedure

Before merge:

1. final gap review;
2. exact PR head captured;
3. Slice-specific workflow PASS;
4. applicable prior regressions PASS;
5. Bootstrap PASS;
6. human proof PASS;
7. Draft -> Ready;
8. merge with expected head SHA.

After merge:

1. verify `main` points to merge commit;
2. post-merge Bootstrap PASS;
3. update Slice status docs if needed.

## 10. Phase 5 final closure

After Slice 09:

create:

`docs/13-delivery/phase5/15_PHASE5_FINAL_GAP_REVIEW_<date>.md`

Review:

- every required Phase-5 object/action/surface;
- no missing integration with Phase 4;
- no Phase-6 truth pulled forward;
- no Finance/Procurement duplication;
- Windows/Android coherence;
- real data import/cutover trace;
- current main Bootstrap.

Only then may status become:

`PHASE5 = VERIFIED / COMPLETE`

## 11. Coding-agent handoff

When implementation begins, the agent must:

- read Phase-5 docs 00–14;
- read current `main`;
- inspect existing V0006 before new migration;
- inspect current OpenFGA model/current-source guards;
- implement only current Slice;
- never guess real seed data;
- prefer fail-closed unavailable state over fabricated green state;
- stop only for a genuine semantic contradiction, not ordinary implementation difficulty.

`PHASE5_PLANNING_CLOSURE = COMPLETE`

`PHASE5_SLICE_PLAN = FROZEN`

`PHASE5_FIRST_PRODUCTION_SLICE = SLICE_01`

`PHASE5_PRODUCTION_CODE_AUTHORIZED_AFTER_PLANNING_PR_MERGE = YES`

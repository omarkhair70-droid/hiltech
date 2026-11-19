# Phase 5 / Slice 08 — Execution Checkpoint

Date: 2026-11-19
Branch: `phase5/slice08-stocktake-adjustment-20261119`
Contract: `12_SLICE08_STOCKTAKE_ADJUSTMENT_CONTRACT_2026-09-21.md`

## Checkpoint 01 — Stocktake snapshot + count history foundation (WIP)

Base main `d8cec754f4c012aca341519a31127382e9ea69b3`; Slice 07 post-merge Bootstrap `44564115421` PASS.

Adds forward-only V0033 stocktake source truth: StocktakeSession, immutable SnapshotLine, append-only CountRecord, InventoryDiscrepancy and InventoryAdjustmentRequest persistence. The first executable vertical intentionally stops before discrepancy reconciliation/adjustment application.

Adds dedicated `canCountAt` authorization using current PostgreSQL organization/binding truth plus OpenFGA `storage_location#can_count` / inherited `warehouse#can_count`; it does not infer adjustment approval from Warehouse operator/manager authority.

First internal flow supports create scoped Stocktake -> start authoritative snapshot -> record count -> append recount history. Snapshot records expected on-hand/reserved/damaged/quarantine quantities plus exact StockBalance/StockItem versions. Count requires canonical unit/current count authority and never writes StockBalance.

PostgreSQL/OpenFGA scenario seeds inventory through the proven Receiving path, captures a 5-unit snapshot, receives one more unit after the snapshot, proves current balance becomes 6 while snapshot remains 5/version-stable, records an idempotent count and a superseding recount, and proves balance remains 6 throughout counting. Unauthorized counter is denied.

Submit/variance reconciliation, canonical Approval integration, ADJUSTMENT movement, API and Windows/Android count surfaces remain later Slice 08 checkpoints. Exact-head CI pending. Do not merge.

### Checkpoint 01 CI repair 01

Exact head `6860e9d0f8dba8083cd83d506a7f75fd8b5c4d30`: multiple server workflows failed at Kotlin compilation because the new public persistence methods `snapshotCandidates` and `insertSnapshot` exposed `StocktakeSnapshotCandidate` while that DTO was declared `internal`. The DTO is now public to match the repository persistence API visibility; no Stocktake schema, snapshot/count semantics, authorization, or test gate changed. A separate Shared HTTP client job also hit external dependency-resolution failures and is not treated as a product regression. Corrected-head verification pending.


## Checkpoint 02 — Submit + deterministic variance/discrepancy reconciliation (WIP)

Checkpoint 01 corrected head `7885aeadf43db7f8779d4f2a6f41daddd57302d1`: Phase 5 Warehouse Setup and Bootstrap PASS. The only failing workflow was the unrelated Phase 4 Assigned Work Android render, whose contract job passed and whose APK build could not resolve multiple Compose/Ktor/Coroutines artifacts from configured public repositories; no Slice 08 source regression was identified.

Adds the next bounded Stocktake vertical without pulling forward adjustment approval/application. Submit requires an ACTIVE exact-version session and a latest physical count for every immutable snapshot line. It uses only the latest recount per line, computes exact variance as counted quantity minus snapshot expected on-hand quantity, and creates one deterministic OPEN InventoryDiscrepancy for each non-zero variance. The discrepancy preserves the exact snapshot balance version, the current balance version at submission, the latest count record, and whether authoritative inventory changed since the snapshot. Submission itself never writes StockBalance and transitions the session only to SUBMITTED.

The PostgreSQL/OpenFGA scenario now proves incomplete submission is denied, the latest recount drives variance, a post-snapshot Receiving movement remains visible as source-changed reconciliation context, the snapshot remains unchanged, a +1 variance is materialized exactly once under idempotent replay, and StockBalance remains 6 throughout submission. Approval integration, adjustment request/application, API, Windows/mobile surfaces and final human proof remain later Slice 08 checkpoints. Exact-head CI pending; do not merge.


### Checkpoint 02 CI repair 01

Exact head `7ab3782b8660785f13d92d57104c7034d4cd9eed`: Phase 5 Warehouse Setup and Bootstrap passed, while three Phase 4 contract workflows reached the shared PostgreSQL/OpenFGA scenario and failed at the new Stocktake discrepancy assertion. The failure was deterministic in meaning but timing-sensitive in selection: first count and recount can share the same fixed `countedAt` in a transaction/test clock, while `latestCount()` ordered by timestamp then random UUID. That could select the superseded first count instead of the recount, producing a false zero variance and no discrepancy. The production query now resolves the terminal count from the append-only `supersedes_count_record_id` chain: the latest count is the record not superseded by another record for that snapshot line. No count history is rewritten, no balance semantics change, and the repair strengthens the frozen recount-history contract. Corrected exact-head CI pending; do not merge.

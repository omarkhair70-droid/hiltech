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

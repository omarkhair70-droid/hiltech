# Phase 5 / Slice 09 — Execution Checkpoint

Date: 2026-11-20
Status: **CHECKPOINT 01 WIP / DO NOT MERGE**

Base main: `96a7b10ff15f5e790b40d1071f4f51da7d98170a` — Slice 08 merge commit.
Post-merge Bootstrap push run `44632228363`: **PASS**.

## Checkpoint 01 — Warehouse Command Center read composition foundation

This is migration-free and creates no dashboard truth table.

The new `GET /v1/warehouses/{warehouseId}/command-center` reuses current Warehouse visibility and composes directly from Phase-5 source truth: StockBalance overview, Asset expected-return custody, active reservations, derived Asset availability blockers, Receiving attention, Stocktake/Adjustment attention, authorized import exceptions, and recent Stock/Asset movements.

Every attention item carries `sourceType`, `sourceId`, and explicit `reasonCodes`; no unexplained red dot exists. Import exceptions are omitted unless the actor holds import-management authority. No Command Center read authorizes a mutation.

There is no frozen reorder threshold/source policy in Phase 5, so low-stock is explicitly `LOW_STOCK_THRESHOLD_NOT_CONFIGURED` rather than guessed. Zero-on-hand and zero-available remain source-derived.

The shared Ktor client exposes the authenticated read. The shared physical Warehouse workspace can load the same Command Center on Windows and Android. Desktop synthetic evidence adds `warehouse-command-center.png`.

Still open: Project/Work shortage/readiness drill, richer source drill-through, Arabic-first mobile home + Quick Scan, same-object scan/search proof, revoked-authority mutation proof, restricted-value proof, dedicated Android Slice 09 evidence, Final Gap Review, and Phase 5 closure.


## Checkpoint 02 — Project / Work resource readiness drill (WIP)

Exact head `27a1902bbcae9273c76baa2ee9210c5d03bd395d`: all eleven inherited workflows PASS after one bounded retry of external dependency-resolution/provider-smoke failures. Phase 5 Warehouse server/shared contract, Windows Command Center render and Android evidence are PASS.

This checkpoint composes Project/Work resource readiness from the existing `WarehouseWorkResourceSource` used by Phase 4 readiness. It does not create a second shortage/readiness table or recalculate resource truth independently. For each open Work Order with a concrete resource request, the selected Warehouse is evaluated with its real local candidates and the canonical fact reason/source/version.

Unsatisfied `ASSET_AVAILABLE` / `STOCK_AVAILABLE` facts are shown as `READY_TO_RESERVE`; shortage, reservation collision, calibration/maintenance/incident and location reasons remain `BLOCKED` with their exact existing reason code. Satisfied reservations/fulfillment are omitted from the attention queue. The read is permission-scoped: detailed Work resource readiness is returned only to an identity that currently holds Warehouse resource-reservation authority.

The Warehouse integration scenario now proves the Command Center Stock overview against the actual StockBalance projection, proves a material requirement reports `STOCK_AVAILABLE` from the Phase-5 source, then proves a competing reservation changes another Work requirement to `STOCK_RESERVED_FOR_OTHER_WORK` with the real StockItem source id. It explicitly asserts no `SOURCE_PENDING_PHASE5` reason remains in these implemented material paths.

The shared Command Center DTO/UI and Windows render expose requirement id, Work/Project/Site context, canonical reason, fact source id/version/as-of and candidate count. Still open: richer direct drill-through controls, Arabic-first mobile home/Quick Scan, scan/search canonical-object proof, revoked-authority mutation proof, restricted-value proof, dedicated Android Slice 09 evidence and final closure.


### Checkpoint 02 CI repair 01

Exact head `6c39cb63b96d8853800eef6c1653b1a6a6d9ec96`: Phase 5 Warehouse server/shared contract and Windows Command Center render PASS; Phase 5 Android evidence and Native OIDC PASS. Phase 4 server contracts and Bootstrap local-platform all failed on the same new assertion in `ReservationOperatorScenario`: expected `STOCK_RESERVED_FOR_OTHER_WORK`, actual canonical Phase-5 fact `STOCK_SHORTAGE`.

The source behavior is correct and unchanged. Synthetic Stock starts at 10; one Work reservation holds 8; the competing Work requirement needs 8 while only 2 remain free. `WarehouseWorkResourceSource.fact(...)` therefore returns `STOCK_SHORTAGE` because free + held-for-this-demand cannot cover the required quantity. The repair changes only the expectation to the canonical reason and preserves its StockItem source id. No readiness, reservation, authorization, projection or Command Center product logic changes. Corrected exact-head CI pending.

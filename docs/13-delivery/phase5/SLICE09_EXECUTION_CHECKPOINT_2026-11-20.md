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


### Checkpoint 02 verification note

Corrected head `7b31ea9ad54480df31d501ae49affcb051a2a1e7`: Phase 5 Warehouse server/shared contract, Windows Command Center render, Android Warehouse evidence, Phase 4 Readiness, Project Site, Planning and WorkOrder policy contracts, Bootstrap, Native OIDC and Phase 3 inherited gates PASS. Phase 4 Review/Progress server contract also PASS. Its desktop Slice05 render failed twice only because the external repository did not resolve `org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.144.6`; the same Compose/desktop stack built and rendered in other exact-head workflows. No source or dependency change is justified by that isolated repository-resolution failure.

## Checkpoint 03 — Arabic-first Quick Scan canonical lookup (WIP)

Quick Scan remains read-only and reuses prior authoritative reads. AssetTag resolution now returns the safe canonical Asset id alongside the already-redacted lookup surface, allowing scan and manual Asset directory search to prove they point to the same Asset. No acquisition cost, currency, tag history or private incident source is added.

Stock lookup adds an optional authenticated directory search over canonical `item_code` and `part_number` only. Supplier barcode is deliberately not interpreted: no authoritative barcode mapping exists in the frozen Phase-5 model, so the UI states that the mapping is unavailable rather than guessing.

The shared Arabic-first Quick Scan surface is mounted into the Warehouse physical workspace on Windows and Android. It supports opaque AssetTag entry, manual Asset fallback and Stock code/part-number search. It exposes canonical ids so operators can verify they are looking at the same object before using existing prior-slice commands.

Server scenarios prove AssetTag and manual Asset search return the same Asset id, Stock item-code and part-number search return the same canonical StockItem(s), and an arbitrary numeric barcode string does not silently resolve as Stock. Shared-client tests prove authenticated opaque AssetTag lookup returns the canonical id and Stock search transports only the explicit search query. Desktop evidence adds `warehouse-quick-scan.png`. Dedicated Android evidence drives the actual shared Quick Scan controls and captures tag resolution, scan/search canonical match and Stock part-number search while asserting the no-barcode-mapping message.

Exact-head CI pending. No mutation engine, camera-specific truth, barcode mapping or new persistence is introduced.


### Checkpoint 03 CI repair 01

Exact head `16b0caeef1d7f3ed85861e731fa65294a2076647`: Warehouse server/shared-client contracts, Windows render, Bootstrap, Native OIDC and all inherited product workflows PASS. Phase 5 Android failed only in the new Quick Scan smoke after AssetTag resolution and manual Asset search both returned the same canonical Asset id. The UI dump shows `Asset result · SYNTH-PASSPORT · 11111111-2222-4333-8444-555555555555`, but the synthetic proof text `Scan/search canonical match · PASS` is below the initial Android viewport. The smoke previously used a non-scrolling `wait_for_text` for that assertion.

The repair uses the existing scroll-aware `focus_text` helper to locate the canonical-match evidence before capture. It changes no Quick Scan lookup, API, authorization, redaction, canonical id, Stock search or barcode behavior. Corrected exact-head CI pending; do not merge.


## Checkpoint 04 — Authorization / restricted-value closure proof (WIP)

Exact head `0354696341ecc3f469529a888108887a8d858ed5`: all twelve inherited workflows PASS, including corrected Android Quick Scan canonical-match evidence, Phase 5 Warehouse server/shared contract, Windows render, Bootstrap and Native OIDC.

This checkpoint adds no new authority or mutation semantics. The production Warehouse physical handoff condition is factored into a shared `WarehousePhysicalHandoffAuthorityGate`; the real operator surface still renders Slices 05/06 fulfillment only when the current reservation queue says the identity can reserve/fulfill, otherwise it exposes a clear denied state.

The authoritative mutation proof already exists in `RoleTeamAuthorizationPostgresOpenFgaContractTest`: after current PostgreSQL Warehouse authority is ended, the test deliberately confirms stale OpenFGA tuples can still exist, then current-source authorization denies the actor and `StockLedgerOperatorScenario.assertTransferDenied` proves an actual Stock transfer command returns `STOCK_UNAVAILABLE`. Asset/service commands are denied in the same revoked-source scenario. Slice 09 therefore reuses the established mutation engine and its fail-closed source guard rather than creating a dashboard permission.

Android closure evidence now renders the same production safe Quick Scan result plus the same production physical-handoff authority gate in revoked state. The smoke scrolls the whole shared surface, requires `Warehouse mutation authority · denied`, and rejects acquisition/candidate/purchase/unit-cost/currency/payroll/invoice-value labels. A server MockMvc wire test independently asserts Command Center alerts retain sourceType/sourceId/reasonCodes while restricted Finance/value keys are absent.

Still open before Final Gap Review: direct Inventory drill controls from Command Center into the existing detailed Asset/Stock/Receiving/Stocktake/Work surfaces, then exact-head re-verification and Phase 5 Final Gap Review. Do not merge or start Phase 6.


### Checkpoint 04 CI repair 01

Exact head `a4d55a9df3fdb16bb4dcb3041ea22464f0d5b905`: Warehouse server/shared-client verification and Windows render PASS. Android-building workflows (Phase 5, Bootstrap foundation, Native OIDC Android shell, Onboarding Android and Assigned Work Android) all failed at the same debug Kotlin compile point before runtime evidence because `WarehouseClosureEvidenceActivity` imported Compose Foundation layout symbols (`Column`, `Arrangement`, `dp`) that the Android application module does not directly depend on. Shared Compose already owns layout dependencies.

The repair removes the debug-only local Foundation layout wrapper and invokes the two production shared composables sequentially inside the existing composable `warehouseOperator` slot. No dependency is added and no authority, Quick Scan, redaction or mutation behavior changes. Corrected exact-head CI pending; do not merge.

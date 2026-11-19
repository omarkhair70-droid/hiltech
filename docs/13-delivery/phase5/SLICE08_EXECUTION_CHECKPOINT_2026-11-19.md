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


## Checkpoint 03 — Adjustment request boundary (WIP)

Exact head `b2bbcf898f3e044d68a4d9f126b94253c248df9c`: all ten inherited workflows PASS, including Phase 5 Warehouse Setup, Bootstrap, Native OIDC, Shared Command Runtime and the Phase 4 contract/human-proof regressions. Checkpoint 02's recount repair is therefore verified on the exact head.

Adds the request side of the privileged inventory-correction boundary without inventing HILTECH approval policy. Warehouse Operator/Manager authority can request an adjustment only through the existing OpenFGA `warehouse#can_request_adjustment` permission plus current PostgreSQL authority binding. Unauthorized actors fail closed. The request requires an exact discrepancy version, a non-empty reason, the canonical snapshot unit and the exact non-zero discrepancy delta; it is idempotent and transitions only the discrepancy to `ADJUSTMENT_REQUESTED`. It does not create an ApprovalRequest, infer an approver, set approval status, apply a StockMovement or mutate StockBalance.

A discrepancy whose authoritative StockBalance changed after the snapshot is explicitly blocked with `STOCKTAKE_RECONCILIATION_REQUIRED`; applying its snapshot variance to the later current balance could duplicate a legitimate post-snapshot movement. The scenario proves this against the existing 5 -> 6 post-snapshot Receiving case, then opens a clean snapshot at 6, counts 5 with no intervening movement, materializes a -1 discrepancy, denies an unauthorized requester, requires a reason, creates exactly one adjustment request under replay and proves StockBalance remains 6.

The V0033 recount trigger is aligned with the application repair: terminal count truth is resolved from the append-only `supersedes_count_record_id` chain rather than timestamp/random-UUID ordering. Approval linkage remains intentionally unimplemented because the frozen Phase-5 authority model names `INVENTORY_ADJUSTMENT_APPROVER` / `can_approve_adjustment`, but no production ApprovalPolicy/authority mapping for inventory adjustment is frozen or seeded. Do not infer `OWNER_FINAL` or any named person. Exact-head CI pending; do not merge.


## Checkpoint 04 — Stocktake HTTP + shared client boundary (WIP)

Exact head `3dae938e609931d04c998d9f654cfa2f729bc97e`: all ten inherited workflows PASS, including Phase 5 Warehouse Setup and its Android evidence, Bootstrap, Native OIDC, Shared Command Runtime and Phase 4 regressions. Checkpoint 03 is therefore verified on the exact head.

Adds the frozen Slice 08 online API through the existing authenticated HILTECH transport: create Stocktake, read one authorized Stocktake with immutable snapshot/count history, start, append count/recount, submit, read variances/adjustment-request status, and request adjustment. Command routes require the existing Idempotency-Key to match the body operationId through `stockContext`; read routes use the authenticated actor and the same current count-scope authorization. Shared Android/Windows contracts preserve exact decimal quantity strings, snapshot/source versions, recount supersession, source-changed reconciliation context and nullable approval/application references without exposing a second source of truth.

The shared Ktor client receives matching typed DTOs and route tests prove authenticated transport, exact paths, command keys, snapshot/count/variance decoding and an adjustment request whose Approval reference remains null. This checkpoint intentionally does not expose `POST /v1/inventory-adjustments/{id}/apply`: final application still requires canonical approval truth plus explicit `INVENTORY_ADJUSTMENT_APPROVER`, and no production inventory-adjustment ApprovalPolicy/authority mapping is frozen. No ApprovalRequest, approver, policy key, ADJUSTMENT movement or StockBalance mutation is inferred. Exact-head CI pending; do not merge.


## Checkpoint 05 — Shared Windows/Android Stocktake operator surface (WIP)

Exact head `f04331caaae9a8ccbf7c61eab2f8297baa132049`: all thirteen inherited workflows PASS, including Phase 5 Warehouse Setup, Shared Command Runtime, Native OIDC, Bootstrap and the Phase 3/4 human-proof regressions. Checkpoint 04 HTTP/shared-client boundary is therefore verified on the exact head.

Adds the Slice 08 human workflow as one shared Compose surface used by the real Warehouse physical-operator shell rather than a second mutation path. An authorized operator can load active StorageLocations, create a subtree count scope, open an existing Stocktake, start the immutable snapshot, enter an online physical count with optional scan/source and note, append a recount without erasing prior history, submit only after every snapshot line has a count, inspect exact expected/count/variance and source versions, and request an adjustment with a reason when reconciliation is safe. The surface shows adjustment request and approval-reference/status fields but exposes no approval or apply button; a missing canonical approval reference is displayed honestly rather than fabricated.

Android already hosts `WarehousePhysicalOperatorSection`; the Stocktake surface is therefore live there through the same authenticated API. Windows now mounts the same physical-operator section into the signed-in shell using the existing authenticated `WarehouseApiClient` and installation identity, so Receiving/Stocktake are not synthetic-only desktop flows. Command generation keeps the same operation id across an unconfirmed retry and advances only after a confirmed command, preserving the server idempotency contract.

Desktop synthetic human proof adds `stocktake-count-history.png` and `stocktake-adjustment-request.png`: the first shows expected quantity plus initial count and superseding recount history; the second shows a -1 discrepancy, exact snapshot/current versions, requested delta/reason and the truthful absence of an Approval reference. Phase 5 CI now requires both captures. Slice 09 Command Center/Quick Scan remains untouched, and final ADJUSTMENT application remains blocked on real canonical approval policy/authority mapping. Android stocktake-specific emulator evidence remains a later Slice 08 checkpoint before closure. Exact-head CI pending; do not merge.


## Checkpoint 06 — Android Stocktake human proof (WIP)

Exact head `b96181f7b4ae6d90632d99d41872130590fed56b`: all thirteen inherited workflows PASS. Phase 5 proves the Checkpoint 05 Warehouse service/shared-client suite, the two new Windows Stocktake renders, Android debug APK compilation and the existing Asset/Handoff/Receiving emulator proofs. Bootstrap's first dependency-review attempt failed because Gradle reported `kotlinx-coroutines-core:1.11.0 FAILED` in the dependency inventory; a targeted retry of that failed job completed PASS with no source change, confirming the failure was transient dependency resolution rather than a product regression.

Adds Stocktake-specific Android human proof through the production shared `StocktakeHumanProofSection`. A DEBUG-only synthetic host drives the real controls through: one physical count with scan/source reference, a superseding recount that retains the first count in visible history, submit producing an exact -1 variance against the immutable expected quantity/version, and an adjustment request with reason. The final Android state explicitly renders `Approval · no approval reference recorded`; no ApprovalRequest, approver, policy or ADJUSTMENT movement is fabricated.

The Phase 5 emulator gate now executes `phase5_android_stocktake_smoke.sh` after the existing Asset/Handoff/Receiving proofs and requires four non-empty screenshots: count, recount history, variance and adjustment request. This remains Slice 08 evidence only; Slice 09 Quick Scan/Command Center is untouched, and final adjustment application remains blocked on canonical Approval policy/authority mapping. Exact-head CI pending; do not merge.


## Final Gap Review — BLOCKED on real approval authority / contract

Exact head `a5a29b3163ac005cc2a0f53142a8a3c200158151`: all 13 inherited workflows PASS after bounded external Android-runner retries; Phase 5 Stocktake Android/Windows human proof is PASS. Final contract-to-code review is recorded in `SLICE08_FINAL_GAP_REVIEW_2026-11-19.md`.

Result: pre-approval Stocktake/variance/adjustment-request vertical is verified, but Slice 08 is **not merge-ready**. Frozen remaining obligations are canonical Approval linkage, explicit `INVENTORY_ADJUSTMENT_APPROVER` revalidation, approved exact-once `ADJUSTMENT` StockMovement, negative-stock denial, applied-movement linkage, discrepancy closure and terminal Stocktake reconciliation. Repository reality explicitly forbids seeding an Adjustment Approver until official Warehouse responsibility/approval mapping is confirmed, and no production Inventory Adjustment ApprovalPolicy/subject-version contract is frozen. The discrepancy `INVESTIGATING / RESOLVED_NO_ADJUSTMENT` and Stocktake `RECONCILING / CLOSED` terminal transition semantics also lack an authorized command/API rule. Do not infer them, do not merge, and do not start Slice 09.


## Checkpoint 07A — Canonical Inventory Adjustment Approval linkage (WIP)

Exact head ff8b3156c9684a52e757eb1384b45ace19aa0526: all thirteen workflows PASS after the bounded Native OIDC Android dependency-resolution retry completed successfully without source change.

Owner clarification is frozen in SLICE08_INVENTORY_ADJUSTMENT_APPROVAL_DECISION_2026-11-19.md: authority is name-independent and configurable as one current Warehouse INVENTORY_ADJUSTMENT_APPROVER USER or TEAM; multiple humans use a TEAM. The canonical policy is INVENTORY_ADJUSTMENT_STANDARD, subject INVENTORY_ADJUSTMENT_REQUEST, explicit SINGLE decision, with no silent requester/approver bypass.

This checkpoint adds subject-aware Approval authority/version sources while unsupported subjects remain fail-closed. Trusted provisioning sets/replaces the privileged Warehouse authority, projects it to OpenFGA and ensures the Approval-owned policy; ordinary delegated Warehouse grants remain unable to grant it. Adjustment request creation now creates/recovers and links a canonical ApprovalRequest with deterministic idempotency, leaves the material subject version unchanged while linking approval metadata, and reads status from canonical Approval truth. The PostgreSQL/OpenFGA scenario proves that an operator who is also the configured approver still receives an explicit PENDING request, explicitly approves it, and StockBalance remains unchanged.

Final ADJUSTMENT movement/application, negative-result denial, applied linkage and terminal reconciliation remain Checkpoint 07B after this exact head is green. Do not merge and do not start Slice 09.


### Checkpoint 07A CI repair 01

Exact head `4b5572fe43ab51fa960aa695290dcf083b1115da`: Approval Stability PASS, Phase 5 Warehouse contract/render PASS, Android Warehouse evidence PASS and all inherited product regressions PASS. Bootstrap alone failed in the full Spring application-context recovery contracts because `JdbcApprovalPersistence` gained a Kotlin defaulted collection constructor and Spring could not choose a production constructor, reporting `No default constructor found`. Empty-database migration, jOOQ generation and server compilation were independently PASS.

Production DI is now explicit for both `JdbcApprovalPersistence` and `FailClosedApprovalSubjectVersionGuard`, while one-argument/no-argument constructors remain available to existing focused tests. The subject-aware authority resolver is also tightened fail-closed: when a domain source claims `INVENTORY_ADJUSTMENT_REQUEST`, a missing/ambiguous current Warehouse adjustment authority returns unavailable and can no longer fall through to an unrelated generic Approval authority with the same key. No policy, subject version, decision or Stocktake semantics are weakened. Corrected exact-head CI pending; do not start Checkpoint 07B until green.


## Checkpoint 07B — Approved Adjustment application / Stock ledger reconciliation (WIP)

Exact head `d485c574d4c681d51e611cb6075b39a3819dee80`: all workflows PASS. The prior Native OIDC provider failure was bounded external dependency resolution; its provider-only retry passed the compile, browser callback and ID-token contract without source change. Checkpoint 07A Approval linkage, full Bootstrap, Approval Stability, Phase 5 Warehouse server/render and Android Stocktake evidence are verified.

This checkpoint implements final application as an append-only `ADJUSTMENT` StockMovement, never a direct StockBalance edit. V0033 now binds each ADJUSTMENT movement uniquely to one InventoryAdjustmentRequest and gives ADJUSTMENT its own database projector. The projector revalidates the exact request/discrepancy/session, canonical APPROVED ApprovalRequest subject/version/policy, immutable delta/unit/location, active Stock/location truth and the exact StockBalance version captured by the discrepancy. Positive adjustments move into the counted location; negative adjustments move out. The balance changes only inside the movement projection trigger. Negative available Stock is rejected, and any post-discrepancy balance-version change forces reconciliation instead of silently applying stale variance.

Inventory discrepancy variance truth is constrained to non-negative expected/counted quantities with `variance = counted - expected`. Discrepancy history and adjustment-request core fields are durable: request approval-linking is the only version-stable metadata update, final apply is the only allowed version-incrementing applied transition, and the approved delta/reason/item/location cannot be rewritten after request.

Application requires current `can_approve_adjustment` authority in addition to canonical Approval status. The command is idempotent: one operation creates one StockMovement, links it to the request, closes the discrepancy, and closes the Stocktake when no unresolved discrepancy remains (otherwise it enters RECONCILING). The PostgreSQL/OpenFGA scenario proves unauthorized apply denial, PENDING Approval cannot apply, explicit approval remains non-mutating, approved -1 application changes authoritative Stock from 6 to 5 through one ADJUSTMENT movement, closes the discrepancy/session and replays without a second movement. HTTP exposes the frozen `POST /v1/inventory-adjustments/{id}/apply` endpoint.

Shared client/UI application controls and final Windows/Android applied-state human proof remain the next Slice 08 checkpoint after this exact head is green. Do not merge and do not start Slice 09.

# Phase 5 / Slice 07 — Execution Checkpoint

Date: 2026-11-19
Branch: `phase5/slice07-receiving-discrepancy-20261119`
Contract: `11_SLICE07_RECEIVING_DISCREPANCY_CONTRACT_2026-09-21.md`

## Checkpoint 01 — Receiving source truth foundation (WIP)

Base main `8edb1143664a05e14afa1737b59e3b67319a74f0`; Slice 06 post-merge Bootstrap `44445622603` PASS.

Adds forward-only V0032 Receiving source truth: header, typed line and append-only discrepancy records. Purchase/PO/source references are references only and cannot create physical lines. Stock RECEIVE is an append-only StockMovement linked one-to-one to a ReceivingLine and is the only path that projects received quantity into StockBalance.

Adds dedicated `canReceiveAt` authorization using current PostgreSQL organization/binding truth plus OpenFGA `storage_location#can_move_in` / inherited `warehouse#can_receive`; it does not reuse transfer authority.

First internal vertical path supports create Receiving -> add explicit Stock line -> record physical receipt -> complete. It validates current destination, canonical unit, explicit Stock classification, versions, idempotency and serialized-stock serial count. STOCK_SERIALIZED remains Stock and does not auto-create Asset merely because serials exist. Damaged/unfit received Stock remains physically on hand but enters damaged quantity. No invoice total or source reference can create a line.

PostgreSQL/OpenFGA scenario covers unauthorized receiver, exact-once quantity, duplicate operation replay, wrong-unit denial, serialized Stock staying Stock, source-only completion denial and inactive destination denial.

Asset creation from Receiving, discrepancy commands/review, API and Windows/Android surfaces remain later Slice 07 checkpoints. Exact-head CI pending. Do not merge.

### Checkpoint 01 CI repair

Exact-head c3d14a0c969b73ac120cf485da33e9dce4d19307 compiled and migrated V0032 successfully, and Warehouse contract passed. The real PostgreSQL/OpenFGA contract exposed one semantic ordering defect: attempting to complete a source-only DRAFT Receiving record returned RECEIVING_VERSION_CONFLICT before proving that no physical lines exist. Completion now checks the exact base version and current authority, then fails RECEIVING_INCOMPLETE for zero/unreceived lines before requiring IN_PROGRESS lifecycle. This preserves the frozen rule that PO/invoice/source references alone cannot become received inventory. No gate weakened; corrected exact-head CI pending.

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

### Checkpoint 01 CI repair 2

Exact-head 8acfed81ae67c3eb8c63012987aef0a037262d35 progressed through V0032 migration and compilation. The real contract then reached the inactive-destination fixture, where the test attempted to set StorageLocation lifecycle to RETIRED without the required active_until timestamp. PostgreSQL correctly rejected the invalid fixture via ck_storage_location_retirement_time. The fixture now records a valid retirement timestamp and still requires Receiving creation to fail closed at the retired destination. Production Receiving logic and database gates are unchanged. Corrected exact-head CI pending.

## Checkpoint 02 — Receiving API + typed discrepancy truth (WIP)

Exact-head 7751d56fea5fa707b6f4d79a6e8ef5971b4e704a: all triggered workflows PASS after one bounded rerun of the Phase 1 Android shell job, whose first attempt timed out while the emulator remained on session bootstrap; desktop/provider OIDC jobs were already PASS and no production change was needed.

Adds Warehouse-scoped Receiving directory/detail reads using current `canReceiveAt` authority, deterministic keyset pagination by updated timestamp/id, and the frozen API surface for create, add Stock line, receive line, record discrepancy and complete. Adds append-only typed discrepancy commands without rewriting expected line truth; PostgreSQL/OpenFGA scenario proves SHORT_QTY records review context while expected quantity remains unchanged and completion becomes RECEIVED_WITH_DISCREPANCY. Shared DTO/client transport covers receiving directory and discrepancy idempotency. Asset receiving and human UI surfaces remain later checkpoints. Exact-head CI pending; do not merge.

### Checkpoint 02 CI repair

On exact head `05579efa8b089bb05a20b1d97dca3646807fb863`, the shared PostgreSQL/OpenFGA contract reached the new SHORT_QTY assertion and failed only because PostgreSQL preserves the declared `numeric(20,6)` scale, returning expected quantity `5.000000` rather than the test literal `5`. The expected source value itself remained unchanged before/after discrepancy recording. The assertion now matches canonical persisted Decimal text; no production code, quantity semantics, migration, authorization or test gate is weakened. Exact corrected-head CI pending.

## Checkpoint 03 — Explicit Asset receiving (WIP)

Checkpoint 02 corrected head `4167b74c721af14146f69253e2c5c95a7f6c09b8`: all 13 workflows PASS after the Decimal assertion repair and one bounded retry of the known transient desktop Skiko dependency lookup. Adds explicit COMPANY_ASSET receiving without any STOCK_SERIALIZED inference. A Receiving asset line carries an explicit active AssetType; physical receipt atomically creates one Asset, marks the same ReceivingLine received, records the existing REGISTER_INITIAL_LOCATION AssetMovement to the Receiving destination, projects current custody, and links the immutable movement back to the exact ReceivingLine. Asset identity/serial/type/condition are validated against line truth; serial evidence alone never changes classification. Optional initial tags are supported only when the explicit AssetType allows that tag and current Warehouse tag authority permits it; no tag type is guessed. PostgreSQL scenario proves Asset creation, ReceivingLine linkage, initial custody, movement provenance, idempotent replay and serialized Stock remaining Stock. API/UI exposure for Asset fields remains next; exact-head CI pending. Do not merge.

### Checkpoint 03 CI repair 01

On exact head `294e21ffdec6dbbadc95c84e8f81a3c4d609a762`, server compilation failed in the new ReceivingLine movement lookup because its mapper passed the Asset ID into `AssetMovementView`, whose established read contract starts with movement ID/type and represents occurred time as ISO text. The lookup now reuses the exact existing `movements()` mapper shape over `SELECT * FROM asset_movement`. No schema, transaction, authorization, receiving policy or test gate changed. Corrected-head verification pending.

### Checkpoint 03 CI repair 02

On exact head `60e49c65fdceb950b672b0c7e2b76e780050b80c`, Flyway failed before tests because the newly inserted PostgreSQL trigger function was committed with a single-dollar PL/pgSQL delimiter (`AS $` / `$;`) instead of the repository's required `$$` delimiter. Corrected only the function delimiters; Receiving schema semantics, Asset linkage, authorization and tests are unchanged. Corrected-head verification pending.

### Checkpoint 03 CI repair 03

Exact-head `3188c3d7f151e58a14c056b9c565e30c6c4b870c` verification showed the prior delimiter repair did not persist: GitHub still stored the new PL/pgSQL function as single-dollar `AS $` / `$;`, so Flyway failed across every workflow that migrated V0032. This repair writes the delimiter from two explicit dollar characters and verifies the stored blob after the ref update. No schema or Receiving behavior changes.

### Checkpoint 03 CI repair 04

The repository write path collapses a literal double-dollar PL/pgSQL delimiter to a single dollar. Replaced only this function's quote delimiter with PostgreSQL's equivalent tagged delimiter `$receiving_asset$`, which avoids the transport ambiguity while preserving the exact function body and behavior. Stored blob is verified after ref update.

### Checkpoint 03 CI repair 05

Exact head `fad35de0b56dd01ac724bb7802796e5d5ba924b0` migrated and compiled, but the shared PostgreSQL/OpenFGA contract failed during explicit Asset receipt with `RECEIVING_CONFLICT`. Root cause: the new Asset movement guard correctly requires the ReceivingLine to already contain the received Asset/quantity/condition/actor/time, while the service appended REGISTER_INITIAL_LOCATION before marking the line. The transaction is reordered without weakening the guard: create Asset identity -> mark the same ReceivingLine received -> append the initial custody movement -> optional tag -> header version touch. All steps remain inside the same idempotent command transaction, so any later failure rolls back the Asset and line mark together. Corrected-head CI pending.

## Checkpoint 04 — Asset Receiving API + shared transport (WIP)

Exact-head `7b426fd5398a4aa57b3711e4549913c985aafe19`: all 13 workflows PASS, including Phase 5 server/shared/render + Android evidence and Bootstrap. Exposes the already-proven explicit COMPANY_ASSET path without changing the existing Stock routes: POST asset line and POST receive-asset-line use the same authenticated/idempotent Receiving context, return the authoritative ReceivingLine plus created Asset/movement/tag receipt, and carry `receivedAssetId` in shared reads. Shared client/DTO tests prove explicit AssetType and identity/serial round-trip; no serial-only classification inference is introduced. Windows/Android operator UI and Receiving human proof remain next. Exact-head CI pending; do not merge.

## Checkpoint 05 — Shared Windows/Android Receiving operator (WIP)

Exact-head `51a2b47bf72bca617a4892a6a706c27d4444e5da`: all 13 workflows PASS, including Phase 5 server/shared/render + Android evidence and Bootstrap; the one desktop Readiness dependency lookup failure was retried on the identical head and passed without source changes. Adds one shared Compose Receiving journey inside the existing online Warehouse physical workspace, so Windows and Android use the same UI and the same server-authoritative commands. Operators can load/paginate Receiving records, create a record with an explicit destination/source reference, add canonical Stock lines or explicit AssetType lines, record Stock or Asset physical receipt, record typed discrepancy without rewriting source truth, review immutable server receipts and complete only after every line has a physical receipt. Stock classification/unit/version are reloaded from the server before receipt; Asset type/serial/tag choices come only from the explicit active catalog; the UI never infers Asset from serials. This is shared UI integration, not yet dedicated Receiving human-proof evidence. Exact-head CI pending; do not merge.

## Checkpoint 06A — Receiving scan/search + shared proof surface (WIP)

Exact-head `dd140bf9b596ba332ea5b2236f92f8964c897f04`: 13/13 workflows PASS. Contract reread found the frozen Mobile surface requires Receiving scan/search while Checkpoint 05 exposed only a loaded-directory picker. Production shared UI now supports free search over loaded receipt/source/document/state values plus an exact UUID scan path that opens the authoritative Receiving detail endpoint; older pages remain explicit. The existing production Receiving detail is exposed through a thin public proof adapter with add-line controls hidden, so Windows/Android evidence can exercise the same receipt/discrepancy/complete controls rather than a test-only copy. No server truth or Receiving policy changes. Exact-head CI pending.

## Checkpoint 06B — Dedicated Receiving human proof (WIP)

Checkpoint 06A exact head `889bcebd63dcac94783f6615f9e8074efbdb8f65`: 13/13 workflows PASS after bounded retries of two unrelated dependency-resolution jobs; Phase 5 shared/server/render/Android evidence was PASS without source repair. Adds dedicated human proof using the exact shared production Receiving controls. Android debug evidence performs exact receipt scan/search, serialized Stock quantity + GOOD condition + two serial capture, server-style receipt state transition, typed SHORT_QTY discrepancy capture, and final RECEIVED_WITH_DISCREPANCY completion. Windows synthetic render shows final PO/source/document context, expected vs received serialized Stock, serial evidence, explicit received Asset link and discrepancy receipt. No real Warehouse mutation occurs in evidence; authoritative server behavior remains covered by the PostgreSQL/OpenFGA contracts. Phase 5 CI now requires both new evidence families. Exact-head CI pending; do not merge.

### Checkpoint 06B Android evidence repair 01

Exact head `9f779f26fbd247bafa87f1fbf5a25d435e9f8825`: Windows Receiving render PASS and 12/13 workflows PASS. Android evidence reached the real shared Receiving detail, proved exact receipt scan/search, selected GOOD condition and populated both serialized Stock serials. The script then failed before callback execution because the soft keyboard remained open after text entry and intercepted the bottom receipt control; UI hierarchy showed the enabled Record Stock receipt control and correct entered values, with no crash or production error. Evidence automation now dismisses the keyboard after scan, serial and discrepancy text entry before tapping action buttons. No production Receiving code, policy or gate changed.

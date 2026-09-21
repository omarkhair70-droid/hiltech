# Phase 5 / Slice 08 — Stocktake / Discrepancy / Adjustment Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0033`

## Goal

Make physical count variance auditable without ever allowing a count screen to overwrite StockBalance directly.

## Persistence

Add:

- `stocktake_session`;
- `stocktake_snapshot_line`;
- `stock_count_record`;
- `inventory_discrepancy`;
- `inventory_adjustment_request`.

Approval decision remains owned by the existing approval system where used.

## Stocktake lifecycle

- DRAFT;
- ACTIVE;
- SUBMITTED;
- RECONCILING;
- CLOSED;
- CANCELLED.

Session defines scope:

- Warehouse;
- StorageLocation subtree;
- selected categories/items;
- snapshot timestamp.

## Snapshot

Snapshot stores expected authoritative quantities/versions for comparison.

It does not freeze or become a parallel inventory ledger.

## Count

CountRecord is append/history preserving:

- stocktake session;
- StockItem/location;
- counted quantity;
- unit;
- countedBy/At;
- optional scan/source;
- notes;
- version.

A corrected recount creates a new record/revision; it does not erase history.

## Variance/discrepancy

Variance = counted - expected snapshot.

Variance creates InventoryDiscrepancy when non-zero beyond exact policy.

Discrepancy state:

- OPEN;
- INVESTIGATING;
- RESOLVED_NO_ADJUSTMENT;
- ADJUSTMENT_REQUESTED;
- CLOSED.

## Adjustment

AdjustmentRequest includes:

- exact discrepancy/session;
- StockItem/location;
- delta;
- reason;
- requestedBy/At;
- approval ref/status;
- version.

Final application requires explicit `INVENTORY_ADJUSTMENT_APPROVER`.

Effect:

- append ADJUSTMENT StockMovement;
- update StockBalance projection;
- close/link discrepancy;
- audit/event.

No direct balance update.

## Separation of duty

Counter/requester may not infer final approval.

Warehouse Manager only approves if separately bound to adjustment-approver authority.

## API

- `POST /v1/stocktakes`
- `GET /v1/stocktakes/{id}`
- `POST /v1/stocktakes/{id}/start`
- `POST /v1/stocktakes/{id}/counts`
- `POST /v1/stocktakes/{id}/submit`
- `GET /v1/stocktakes/{id}/variances`
- `POST /v1/inventory-discrepancies/{id}/request-adjustment`
- `POST /v1/inventory-adjustments/{id}/apply`

Approval action may route through canonical approval endpoint rather than duplicate it.

## Surfaces

Windows:

- count scope/setup;
- expected vs counted;
- variance;
- discrepancy investigation;
- adjustment request/approval status;
- history.

Mobile:

- online count/scan surface;
- location + item;
- quantity;
- recount history.

No controlled offline count lane is required in first Phase-5 production.

## Tests

- count cannot mutate balance;
- snapshot version preserved;
- duplicate count submission idempotent where operation-id based;
- variance deterministic;
- adjustment without approval denied;
- requester without approver binding denied;
- approved adjustment creates exactly one movement;
- negative resulting stock denied unless an explicit future policy exists;
- stale snapshot/changed inventory produces reconciliation context, not silent overwrite;
- audit/history retained.


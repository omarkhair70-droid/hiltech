# Phase 5 / Slice 02 — Stock Master / UOM / Opening Balance / Ledger Core Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0027`

## Goal

Turn reviewed legacy Stock rows into authoritative StockItem master and opening physical quantity while establishing append-only StockMovement + rebuildable StockBalance.

## Source truth

- reviewed InventoryImportRow;
- StorageLocation from Slice 01;
- StockItem master;
- StockMovement append-only ledger;
- StockBalance projection.

## UOM

Each StockItem has one canonical base unit.

DB quantity remains `numeric(20,6)`.

Wire uses exact decimal representation.

No implicit conversion.

Import must explicitly map legacy `PC` or other source values to canonical unit.

First production does not invent arbitrary pack conversion without real receiving evidence.

## StockItem

Required production fields include:

- itemCode;
- normalized name/description;
- category revision;
- brand/manufacturer?;
- partNumber?;
- baseUnitCode;
- classification: STOCK_QUANTITY / STOCK_SERIALIZED;
- serialized;
- lotTracked;
- active;
- restricted valuation metadata ref?;
- source import reference?;
- version.

Hard identity is itemCode, not part number.

## Opening balance

Command:

`InitializeStockOpeningBalance`

Requires:

- reviewed StockItem;
- valid StorageLocation;
- exact canonical quantity;
- cutover date/time;
- source batch/row trace;
- authority;
- idempotency.

Effect:

- append `INITIALIZE_OPENING` StockMovement;
- update StockBalance in same transaction;
- audit/event.

Do not fake RECEIVE when no trustworthy receiving document exists.

## Stock movement core

Movement quantity is positive.

Direction comes from movement type/from/to context.

Slice 02 supports:

- INITIALIZE_OPENING;
- TRANSFER;
- CONTROLLED_CORRECTION only under explicit authority if needed for migration repair.

Balance rows are locked deterministically.

No balance may become negative.

No direct balance edit API exists.

## API

- `GET /v1/stock-items`
- `POST /v1/stock-items`
- `GET /v1/stock-items/{id}`
- `POST /v1/stock-items/{id}/opening-balance`
- `GET /v1/stock-items/{id}/balances`
- `GET /v1/stock-items/{id}/movements`
- `POST /v1/stock-items/{id}/transfer`
- `POST /v1/inventory-imports/{batchId}/rows/{rowId}/approve-stock-item`

## Windows

Inventory:

- item list;
- normalized Brand/Part/UOM;
- balance per location;
- movement history;
- import provenance;
- transfer control;
- opening cutover state.

## Representative proof

Use reviewed UTP/FIBER/ACTIVE rows.

Must demonstrate:

- cable UOM ambiguity cannot pass silently;
- placeholder/duplicate Part Number does not become hard identity;
- ACTIVE description conflict remains visible during import review.

## Tests

- exact decimal preservation;
- no negative balance;
- transfer conservation;
- duplicate operation replay;
- two concurrent transfers do not corrupt balance;
- opening operation idempotent;
- wrong-org location denied;
- direct balance edit impossible;
- rebuild balance from movements equals projection;
- import provenance retained.

## Out of scope

- reservation;
- issue/consume/return;
- receiving;
- financial valuation ledger;
- Asset.


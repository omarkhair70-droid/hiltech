# Phase 5 / Slice 07 — Receiving / Discrepancy / Serialized Intake Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0032`

## Goal

Create trustworthy inbound physical receipt without treating invoice totals as stock lines or pulling Procurement/Finance ownership into Warehouse.

## Persistence

Add:

- `receiving_record`;
- `receiving_line`;
- `receiving_discrepancy` or equivalent typed discrepancy record.

Receiving header:

- id;
- organization;
- sourceType;
- sourceRef?;
- destinationStorageLocation;
- receivedBy;
- receivedAt;
- state;
- deliveryDocumentRef?;
- correlation;
- version.

Source types:

- PO;
- TRANSFER;
- RETURN;
- INITIALIZATION;
- OTHER.

No PO object is required to exist in Phase 5.

## Receiving lines

Line contains:

- expected StockItem/Asset candidate;
- expected quantity?;
- received quantity;
- canonical unit;
- condition;
- serials;
- candidate classification;
- discrepancy state;
- source line ref?;
- version.

## State

Candidate header lifecycle:

- DRAFT;
- IN_PROGRESS;
- RECEIVED;
- RECEIVED_WITH_DISCREPANCY;
- CANCELLED.

Final receipt is online-authoritative.

## Effects

For StockItem:

- append RECEIVE StockMovement;
- update StockBalance.

For new Asset:

- create Asset;
- issue tag where requested;
- create initial RECEIVE/REGISTER location movement;
- update custody projection.

For STOCK_SERIALIZED:

- remain serialized stock, or create Asset only when explicit AssetType/receiving policy says so.

No automatic conversion based only on presence of a serial number.

## Discrepancy

Typed examples:

- SHORT_QTY;
- OVER_QTY;
- WRONG_ITEM;
- DAMAGED_ON_RECEIPT;
- SERIAL_MISMATCH;
- UNEXPECTED_ITEM;
- DOCUMENT_MISMATCH.

Discrepancy does not silently rewrite expected source.

It creates review context and event.

## Purchase/invoice boundary

The reviewed purchase workbook provides supplier/invoice references, not receiving-line physical truth.

Phase 5 may store:

- supplier/source display/reference;
- invoice/PO/source id/ref;
- delivery document ref.

It does not infer received items/quantities from invoice totals.

Finance remains separate.

## API

- `GET /v1/receiving`
- `POST /v1/receiving`
- `GET /v1/receiving/{id}`
- `POST /v1/receiving/{id}/lines`
- `POST /v1/receiving/{id}/receive-line`
- `POST /v1/receiving/{id}/discrepancies`
- `POST /v1/receiving/{id}/complete`

## Surfaces

Windows:

- deliveries/receiving queue;
- expected vs received;
- serial capture;
- discrepancy review;
- supplier/source refs;
- resulting Stock/Asset links.

Mobile:

- receiving scan/search;
- quantity/serial/condition;
- discrepancy capture;
- online final receipt.

## Tests

- duplicate receive operation idempotent;
- received Stock increases exactly once;
- wrong unit denied;
- new Asset registration atomic with receipt;
- serialized row does not auto-create Asset without policy;
- discrepancy preserves expected source;
- inactive destination denied;
- unauthorized receiver denied;
- invoice total alone cannot create receiving lines;
- receiving rebuild/audit trace.

## Out of scope

- supplier login;
- Accounts Payable;
- PO lifecycle implementation;
- payment.


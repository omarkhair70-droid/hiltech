# Phase 5 / Slice 01 — Warehouse / Storage / Authority / Import Staging Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0026`

## Goal

Create trusted physical locations, trusted warehouse authority and a safe legacy-data staging lane before any authoritative Phase-5 stock/custody mutation.

## Existing truth reused

- Organization / Identity / Team / Employee current truth;
- Project / Site / ProjectSite truth;
- existing V0006 `warehouse` and `storage_location`;
- current OpenFGA warehouse/storage types;
- Phase-5 Reality / Data / Decision / Authorization docs.

## Schema ownership

V0026 may harden existing Warehouse/StorageLocation tables and adds:

- `warehouse_authority_binding`;
- `storage_location_authority_binding`;
- `inventory_import_batch`;
- `inventory_import_row`;
- issue/review enums/checks/indexes;
- organization-safe composite FKs where missing;
- StorageLocation hierarchy cycle protection.

Do not edit V0006.

## Authority

PostgreSQL binding is current source.

OpenFGA is projected authorization.

Every command rechecks:

- active identity;
- organization membership;
- USER or TEAM binding currentness;
- Warehouse/Storage relation;
- object version.

Ended binding denies immediately despite stale FGA tuple.

## Warehouse semantics

Warehouse is a facility/operational aggregate.

StorageLocation is the movement/balance location.

Kinds remain:

- MAIN_WAREHOUSE;
- WAREHOUSE;
- PROJECT_STORAGE;
- SITE_STORAGE;
- OTHER_TYPED.

Project/Site temporary storage reuses canonical Project/Site ids.

Hierarchy is acyclic.

Inactive/retired locations cannot receive new physical movements.

## Import staging

Import is non-authoritative.

Batch stores source filename/hash/type/cutover metadata.

Rows preserve:

- source sheet;
- source row;
- raw payload;
- candidate normalized values;
- candidate class;
- issue codes;
- review state;
- eventual approved target reference.

Required issue codes include:

- UNKNOWN_UOM;
- PART_NUMBER_MISSING;
- PART_NUMBER_PLACEHOLDER;
- DUPLICATE_PART_NUMBER;
- DESCRIPTION_CONFLICT;
- BRAND_NORMALIZATION_REQUIRED;
- CATEGORY_UNMAPPED;
- SERIAL_REQUIRED_BUT_MISSING;
- QUANTITY_AMBIGUOUS;
- LEGACY_DATE_SEMANTICS_AMBIGUOUS;
- ITEM_CLASSIFICATION_REQUIRED;
- OPENING_LOCATION_REQUIRED.

No reviewed row creates Stock/Asset truth in Slice 01.

## API

Minimum:

- `GET /v1/warehouses`
- `POST /v1/warehouses`
- `GET /v1/warehouses/{id}`
- `POST /v1/warehouses/{id}/storage-locations`
- `GET /v1/storage-locations/{id}`
- `POST /v1/inventory-imports`
- `POST /v1/inventory-imports/{id}/rows`
- `GET /v1/inventory-imports/{id}`
- `POST /v1/inventory-imports/{id}/rows/{rowId}/review`

File parsing adapter may be desktop/CLI/server implementation detail; authoritative server contract receives source hash + raw/staging rows. Parser output is never production stock by itself.

## Windows surface

Warehouse Setup:

- warehouse list/detail;
- location tree;
- Project/Site temporary location context;
- authority bindings;
- import batches;
- row issue/review table;
- raw-vs-normalized comparison.

Restricted value columns remain hidden unless explicit permission.

## Tests

Must prove:

- duplicate Warehouse code denied;
- StorageLocation cycle denied;
- Site storage requires matching Project/Site org context;
- inactive location cannot become future movement target;
- stale USER binding denied;
- stale TEAM membership denied;
- unrelated org denied;
- import source hash/idempotency;
- ambiguous UOM/part/description stays blocked;
- no import row mutates stock/asset tables;
- value redaction.

## Out of scope

- Stock opening balance;
- Asset registration;
- reservation;
- checkout/issue;
- receiving;
- stocktake;
- hardware access control;
- Phase-6 offline.

`SLICE01_PRODUCTION_CODE_ALLOWED_AFTER_THIS_CONTRACT = YES`

# Phase 5 / Slice 09 — Warehouse Command Center / Mobile Scan / Operational Closure Contract

Status: **FROZEN**
Date: 2026-09-21
Default migration: **NONE**

## Goal

Compose the Phase-5 primitives into one daily Warehouse product and close the phase without creating a second inventory truth.

## No new authoritative dashboard table

Warehouse Home is a read composition/projection.

It consumes:

- StockBalance;
- reservations;
- Asset availability/custody;
- expected returns;
- incidents;
- calibration/maintenance;
- receiving;
- stocktake/discrepancy;
- import staging exceptions;
- Project/Work resource context.

## Windows Command Center

Required cards/queues:

- Inventory overview;
- Issues/checkouts due today;
- Returns due/overdue;
- active reservations;
- Project/Work shortages;
- low/zero stock;
- blocked Assets;
- calibration due/expired;
- maintenance queue;
- damaged/missing incidents;
- receiving queue;
- stocktake/adjustment queue;
- import exceptions;
- recent movements.

Every alert includes source object refs/reasons.

No unexplained red dot.

## Inventory drill

From Command Center:

- Warehouse -> StorageLocation;
- StockItem -> balances/movements/reservations/issued contexts;
- Asset -> passport/custody/availability/movements/service;
- Project/Work -> resource requirement/reservation;
- Receiving -> resulting movements/assets;
- discrepancy -> adjustment/approval.

## Mobile Warehouse experience

Arabic-first / RTL / adaptive.

Home:

- Quick Scan;
- Issue/Checkout Today;
- Returns Due;
- Receive;
- Count;
- Exceptions.

Quick Scan supports:

- AssetTag opaque code;
- Asset manual/search fallback;
- Stock item code/part-number search;
- configured supplier barcode only if an authoritative mapping exists.

Do not invent a Stock barcode mapping table only for demo.

## Mutation reuse

Slice 09 adds no duplicate mutation engine.

Buttons call commands owned by Slices 02–08.

## Hardware boundary

Phone camera scan may be used where implementation supports it.

External scanner/printer/access-control/camera hardware is not required for core completion.

Generated AssetTag identity must still work with manual/search fallback.

## Project readiness proof

Command Center and PM surfaces must show Work requirements that now resolve from real Phase-5 resource source.

No `SOURCE_PENDING_PHASE5` remains for requirement types whose authoritative source is implemented and configured.

If a requirement uses a resource capability still legitimately unavailable, it must remain explainably unavailable, not green.

## Human proof

Windows capture must demonstrate:

- normal inventory;
- reservation/shortage;
- checked-out Asset;
- calibration/incident block;
- receiving discrepancy;
- stocktake adjustment requiring approval.

Android capture must demonstrate:

- scan/search;
- Asset Passport;
- checkout/return or issue/return path under authority;
- revoked authority denied;
- no hidden Finance data.

## Tests

- read projection matches source objects;
- stale projection cannot authorize mutation;
- all alerts link to source;
- restricted value redaction;
- search/scan returns same canonical object;
- revoked Warehouse operator loses mutation immediately;
- Project resource readiness source is Phase 5;
- no duplicate command-center state writes.

## Scope boundary

No Phase-6 technician field lifecycle/offline sync.

No Finance ledger.

No Procurement full PO lifecycle.

No Security access-control dependency.

Slice 09 ends with Phase-5 Final Gap Review.


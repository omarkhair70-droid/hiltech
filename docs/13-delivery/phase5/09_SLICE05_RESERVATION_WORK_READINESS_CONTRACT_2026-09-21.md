# Phase 5 / Slice 05 — Resource Reservation / Work Readiness Binding Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0030`

## Goal

Bind real Phase-5 resource availability/reservation truth into Phase-4 Work requirements without giving Warehouse ownership of Work lifecycle.

## Persistence

Harden existing:

- `asset_reservation`;
- `stock_reservation`.

Add exact Work requirement reference where applicable:

- `work_requirement_instance_id`.

Reservation state:

- REQUESTED;
- ACTIVE;
- RELEASED;
- CANCELLED;
- FULFILLED;
- EXPIRED.

Reservation is not custody/movement.

## Asset reservation

Requires:

- Asset ACTIVE;
- derived availability eligible;
- no conflicting current reservation;
- Project/Site/Work context;
- authority;
- version.

## Stock reservation

Requires:

- canonical quantity/UOM;
- eligible StorageLocation;
- available = onHand - reserved - damaged - quarantine;
- no double allocation;
- Work/Project context.

StockBalance reservedQty is projection from current reservations.

## Work integration

Phase-4 WorkRequirementInstance remains authoritative demand.

Phase 5 provides typed source facts.

Examples:

- ASSET_AVAILABLE;
- ASSET_RESERVED;
- ASSET_CHECKED_OUT;
- ASSET_CALIBRATION_EXPIRED;
- ASSET_MAINTENANCE_BLOCKED;
- ASSET_INCIDENT_BLOCKED;
- STOCK_AVAILABLE;
- STOCK_SHORTAGE;
- STOCK_RESERVED_FOR_OTHER_WORK;
- RESOURCE_LOCATION_NOT_ELIGIBLE.

Resource source includes:

- source object id;
- version;
- asOf;
- quantity where relevant;
- reason codes.

Phase 5 must not update Work readiness fields directly.

It calls/publishes into the existing Work readiness reevaluation path.

## Commands

- `ReserveAsset`
- `ReleaseAssetReservation`
- `ReserveStock`
- `ReleaseStockReservation`
- `ExpireReservation`
- `EvaluateWorkResourceCandidates`

## API

- `GET /v1/work-orders/{id}/resource-candidates`
- `GET /v1/work-orders/{id}/resource-reservations`
- `POST /v1/assets/{id}/reserve`
- `POST /v1/stock-items/{id}/reserve`
- `POST /v1/asset-reservations/{id}/release`
- `POST /v1/stock-reservations/{id}/release`

## Authorization

Project PM/Engineer/Supervisor may request resources through Project context.

Warehouse operator/manager applies operational reservation.

Project authority alone does not grant Warehouse mutation.

## Surfaces

PM:

- Work resource requirement;
- candidates;
- reservation state;
- shortage/block reason;
- Warehouse response.

Warehouse:

- reservation queue;
- Work/Project context;
- conflicts;
- release/expiry.

## Tests

- Asset double reservation collision;
- Stock reservation cannot exceed available;
- two concurrent Stock reservations deterministic;
- stale Work/version context rejected;
- ended reservation stops blocking;
- reservation fulfilment not yet physical movement;
- Phase-4 readiness changes from SOURCE_PENDING_PHASE5 to real reason;
- no direct Work table mutation;
- stale Warehouse authority denied.

## Out of scope

- checkout;
- issue;
- consumption;
- return;
- receiving.


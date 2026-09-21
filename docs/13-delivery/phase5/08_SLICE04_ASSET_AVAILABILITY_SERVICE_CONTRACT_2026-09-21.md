# Phase 5 / Slice 04 — Asset Availability / Incident / Calibration / Maintenance Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0029`

## Goal

Make Asset availability explainable and safe before reservation/checkout.

## New authoritative records

- AssetIncident;
- CalibrationRecord;
- Maintenance/ServiceRecord.

Optional materialized read:

- AssetAvailabilityProjection.

Availability is never an editable status.

## Derived states

Candidate output:

- AVAILABLE;
- RESERVED;
- CHECKED_OUT;
- BLOCKED_INSPECTION;
- BLOCKED_CONDITION;
- BLOCKED_CALIBRATION;
- BLOCKED_MAINTENANCE;
- BLOCKED_INCIDENT;
- RETIRED.

Reasons include source ids/versions.

## Incident

Damage/missing/lost remain explicit incidents.

Minimum state:

- OPEN;
- UNDER_REVIEW;
- RESOLVED;
- CLOSED where policy needs it.

No automatic accusation from camera/access telemetry.

## Calibration

Record:

- asset;
- type;
- provider ref?;
- certificate document ref?;
- result;
- calibratedAt;
- validUntil;
- notes;
- recordedBy/At.

If AssetType requires calibration and no current valid record exists, normal availability/reservation/checkout fails closed.

No generic emergency override in Phase-5 first production.

## Maintenance

Record service lifecycle/history without replacing Asset lifecycle.

Maintenance due/in-progress may block availability according AssetType policy.

## Commands

- `ReportAssetDamage`
- `ReportAssetMissing`
- `ResolveAssetIncident`
- `RecordCalibration`
- `SendAssetForMaintenance`
- `CompleteAssetMaintenance`
- `EvaluateAssetAvailability`

## API

- `GET /v1/assets/{id}/availability`
- `GET /v1/assets/{id}/incidents`
- `POST /v1/assets/{id}/incidents`
- `POST /v1/assets/{id}/calibrations`
- `POST /v1/assets/{id}/maintenance/send`
- `POST /v1/assets/{id}/maintenance/complete`

## Surfaces

Windows:

- blocked Asset queue;
- calibration due/expired;
- maintenance queue;
- incident detail;
- source explanation.

Android safe Asset passport:

- availability;
- why blocked;
- due date/certificate safe ref;
- current incident summary where authorized.

## Tests

- required calibration absent -> blocked;
- expired calibration -> blocked;
- new valid record -> available if no other blocker;
- active incident -> blocked;
- retired -> blocked;
- condition UNFIT -> blocked;
- no single source may silently override another blocker;
- availability rebuild deterministic;
- unauthorized value/security evidence hidden.

## Real-data gate

Before initial AssetType config activation, validate which actual HILTECH tools require calibration and any representative certificate/service evidence available.


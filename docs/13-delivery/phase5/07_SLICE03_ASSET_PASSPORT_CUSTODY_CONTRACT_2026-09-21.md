# Phase 5 / Slice 03 — Asset Passport / Tags / Opening Custody Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0028`

## Goal

Create persistent HILTECH Asset identity, tags and authoritative movement/custody truth before routine checkout operations.

## Source truth

- Asset identity;
- AssetTag history;
- AssetMovement append-only;
- AssetCustodyProjection rebuildable current state.

No currentCustodian/currentLocation editable fields.

## Asset identity

Hard identity:

`assetCode`

Search attributes:

- manufacturer;
- model;
- serial;
- AssetType;
- tag.

No blanket serial uniqueness.

Duplicate normalized serial produces review/warning context.

## AssetType

Configuration controls:

- serialized requirement;
- calibration required;
- maintenance defaults;
- restricted/high-value behavior;
- allowed tag types;
- expected accessory template;
- inspection required on return.

Tool categories are configuration, not hard-coded code enums.

## Tags

Opaque public code only.

Tag replacement:

- creates new tag;
- ends old active-primary status;
- preserves Asset id/history;
- links replacement chain.

At most one current primary tag per policy/purpose.

## Movement/custody transaction

For every custody mutation:

1. lock Asset row;
2. verify baseVersion/lifecycle;
3. validate source/destination;
4. append AssetMovement;
5. update AssetCustodyProjection;
6. increment Asset version;
7. audit/event;
8. commit.

Slice 03 movement types:

- REGISTER_INITIAL_LOCATION;
- INITIALIZE_OPENING_CUSTODY;
- CONTROLLED_CORRECTION.

Routine CHECKOUT/RETURN comes later.

## Commands

- `RegisterAsset`
- `IssueAssetTag`
- `ReplaceAssetTag`
- `InitializeAssetCustody`
- `CorrectAssetMovement` under restricted authority

## API

- `GET /v1/assets`
- `POST /v1/assets`
- `GET /v1/assets/{id}`
- `GET /v1/assets/by-tag/{opaqueCode}`
- `POST /v1/assets/{id}/tags`
- `POST /v1/assets/{id}/replace-tag`
- `POST /v1/assets/{id}/opening-custody`
- `GET /v1/assets/{id}/movements`
- `POST /v1/inventory-imports/{batchId}/rows/{rowId}/approve-asset`

## Surfaces

Windows Asset Passport:

- identity;
- type;
- serial;
- tags;
- condition;
- current custody/location;
- Project/Site/Work context;
- movement history;
- import provenance;
- restricted cost hidden by default.

Android read/scan:

- scan opaque tag;
- Asset safe passport;
- current custody;
- no mutation buttons beyond authorized Slice-03 actions.

Search/manual lookup remains fallback if no scanner/printer hardware exists.

## Tests

- assetCode unique;
- duplicate serial warning but no global DB rejection;
- one active primary tag;
- tag replacement preserves Asset identity;
- one custody projection per Asset;
- two concurrent initialization/correction attempts do not produce two current states;
- stale baseVersion denied;
- projection rebuild equals movement truth;
- stale warehouse authority denied;
- acquisition value redacted.

## Real-data gate

Before activating real Asset import:

- representative high-value tool list;
- current serial/location where known;
- classification reviewed.

Missing seed data must remain absent, not guessed.


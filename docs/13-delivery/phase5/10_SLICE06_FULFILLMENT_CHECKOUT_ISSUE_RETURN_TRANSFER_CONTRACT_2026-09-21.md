# Phase 5 / Slice 06 — Fulfillment / Checkout / Issue / Return / Transfer Contract

Status: **FROZEN**
Date: 2026-09-21
Target migration: `V0031`

## Goal

Turn reservation/availability truth into auditable physical handoff and return without collapsing Asset custody, Stock issue, Project context and Phase-6 field execution into one model.

## Asset commands

- `CheckoutAsset`
- `ReturnAsset`
- `TransferAsset`
- `InspectReturnedAsset`

## Stock commands

- `IssueStock`
- `ReturnUnusedStock`
- `TransferStock`
- `ConsumeStock` authoritative online server command
- controlled correction under explicit authority only.

Phase 6 later supplies field/offline consumption intent; it does not own physical Stock truth.

## Individual custody identity

For first production, individually accountable physical custody uses business identity:

`EMPLOYEE`

not login-only USER.

A no-login Employee may be a valid custodian.

TEAM/CREW may be context for Work/operations but do not replace the accountable individual custodian by default.

SUBCONTRACTOR_ORGANIZATION may be a custodian/recipient only under explicit policy.

Storage custody uses StorageLocation, not a fake employee.

## Asset checkout transaction

1. lock Asset;
2. verify version/lifecycle;
3. verify derived availability;
4. verify reservation if policy requires;
5. resolve current Employee/subcontractor recipient;
6. validate Project/Site/Work context;
7. insert CHECKOUT AssetMovement;
8. update custody projection;
9. fulfil reservation atomically where applicable;
10. increment Asset version;
11. audit/event.

Expected return is recorded where supplied.

## Asset return

Return receipt is online-authoritative.

Return:

- identifies physical Asset;
- verifies current custody;
- moves custody to destination StorageLocation;
- closes prior custodian context;
- creates RETURN movement;
- may create pending inspection gate.

If inspection required, custody is returned but availability remains BLOCKED_INSPECTION until inspection passes.

## Return inspection

One AssetReturnInspection per return movement.

Records:

- condition;
- accessory template/revision;
- accessory results;
- notes;
- damage incident link;
- resulting availability explanation.

Damage/missing accessory may open AssetIncident.

## Asset transfer

Supports authoritative movement between:

- StorageLocations;
- current Employee custody and another Employee where policy allows;
- Employee and StorageLocation;
- Project/Site context changes associated with real custody movement.

No direct current-location edit.

## Stock issue

Issue removes quantity from StorageLocation on-hand and creates issued-but-not-consumed truth.

Recipient context includes accountable Employee or explicitly allowed organization/context plus Project/Site/Work refs.

Reservation fulfilment and ISSUE movement occur atomically.

## Issued stock projection

Materialized/rebuildable projection from movements:

`ISSUE - RETURN - CONSUME +/- controlled correction`

scoped by StockItem + recipient + Project/Site/Work.

This is the source Phase 6 later validates against.

## Stock return

Unused return:

- requires outstanding issued quantity;
- creates RETURN StockMovement;
- increases destination StockBalance;
- reduces issued projection.

## Stock consumption

Authoritative server command:

- requires outstanding issued quantity;
- reduces issued projection;
- creates CONSUME movement;
- does not restore Warehouse balance.

No technician offline workflow is implemented here.

## Transfer

Location-to-location transfer updates both balances atomically.

Issued-custody reassignment must preserve conservation and recipient context.

## API

Asset:

- `POST /v1/assets/{id}/checkout`
- `POST /v1/assets/{id}/return`
- `POST /v1/assets/{id}/transfer`
- `POST /v1/assets/{id}/returns/{movementId}/inspect`

Stock:

- `POST /v1/stock-items/{id}/issue`
- `POST /v1/stock-items/{id}/return-unused`
- `POST /v1/stock-items/{id}/transfer`
- `POST /v1/stock-items/{id}/consume`
- `GET /v1/stock-items/{id}/issued-context`

## Surfaces

Warehouse mobile/desktop:

- scan/search Asset;
- scan/search StockItem;
- recipient Employee selection;
- Project/Site/Work context;
- condition;
- expected return;
- checkout/issue;
- return;
- transfer;
- inspection;
- movement receipt.

No free-text employee recipient when current Employee source exists.

## Tests

- concurrent Asset checkout -> one winner;
- stale Asset version denied;
- no-login Employee custody supported;
- offboarded Employee cannot receive new custody;
- return closes custody;
- inspection gate blocks availability;
- IssueStock cannot exceed balance/reservation;
- issue/consume/return conservation;
- issued projection rebuild;
- duplicate operation replay;
- transfer conservation;
- wrong Project/Site context denied;
- stale Warehouse authority denied.

## Out of scope

- technician offline execution;
- Evidence capture;
- final Finance costing;
- supplier portal.


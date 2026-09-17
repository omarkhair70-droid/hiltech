# Exact Object Specs — Asset, Stock & Warehouse

Status: DOMAIN DATA MODEL v0.1 / NOT SCHEMA-FROZEN

---

# Asset

## Purpose
Canonical identity for an individually tracked physical company/client asset.

## Fields
- id: UUID — R
- assetCode: String — R unique human ID
- assetTypeId: UUID/String — R
- manufacturer: String — O
- model: String — O
- serialNumber: String — O, unique where manufacturer context supports
- ownershipType: COMPANY / CLIENT / RENTED / OTHER — R
- lifecycleState: AssetState — R
- conditionState: AssetCondition — R
- currentWarehouseId: UUID — O
- currentLocationRef: ObjectRef — O
- currentCustodianUserId: UUID — O
- currentProjectId: UUID — O
- currentSiteId: UUID — O
- purchaseDate: LocalDate — O — RESTRICTED
- acquisitionCost: Decimal — O — RESTRICTED
- currency: ISO4217 — O
- warrantyStart/end: LocalDate — O
- calibrationRequired: Boolean — R
- calibrationDueAt: Instant/Date — O
- maintenancePlanRef: UUID — O
- tagId: UUID — O
- lastObservedAt: Instant — O
- lastObservedSource: enum — O
- version: Long — R
- createdAt/by
- retiredAt — O

## Invariants
- one assetCode per asset.
- one active custody at a time.
- currentCustodian/currentLocation are projections from movement/lifecycle, not free-form authoritative edits.
- calibration-required + expired blocks AVAILABLE-for-use where policy.
- retired/disposed asset cannot be checked out.
- acquisitionCost field restricted.

## Offline
Passport cached.
Damage report local-first.
Checkout final authority online by default.

---

# AssetTag

## Fields
- id
- assetId
- type: QR/BARCODE/BLE/UWB/GPS/etc.
- publicOpaqueCode
- providerExternalId — O
- active
- issuedAt
- replacedTagId — O

## Invariants
Tag can change without changing asset identity.
QR code contains opaque identifier only.

---

# AssetMovement

## Purpose
Append-only custody/location movement event.

## Fields
- id
- assetId
- movementType
- fromContextRef — O
- toContextRef — O
- fromCustodianId — O
- toCustodianId — O
- projectId — O
- siteId — O
- occurredAt
- recordedAt
- recordedBy
- sourceOperationId
- conditionAtTransfer — O
- notes — O
- auditRef

## Invariants
Append-only.
No destructive update except controlled correction metadata.

---

# AssetIncident

## Fields
- id
- assetId
- type: DAMAGE/MISSING/LOSS/THEFT_SUSPECTED/etc.
- state
- reportedBy
- reportedAt
- project/site/context
- description
- evidenceRefs
- investigationOwner
- resolution
- resolvedAt

Important:
Telemetry/access/camera correlation cannot automatically set blame.

---

# CalibrationRecord

## Fields
- id
- assetId
- calibrationType
- provider
- certificateDocumentId
- result
- calibratedAt
- validUntil
- notes

---

# Warehouse

## Fields
- id
- code
- name
- facilityId
- address/location ref
- active
- managerUserId — O
- version

---

# StorageLocation

## Fields
- id
- warehouseId
- parentLocationId — O
- code
- name
- type: ZONE/AISLE/RACK/SHELF/BIN
- restrictedAccess
- version

No cyclic hierarchy.

---

# StockItem

## Purpose
Quantity-tracked inventory item.

## Fields
- id
- sku/itemCode
- name
- category
- unitOfMeasure
- serialized: Boolean
- lotTracked: Boolean
- reorderPoint — O
- reorderQuantity — O
- defaultWarehouseId — O
- active
- valuationClassRef — O — RESTRICTED
- version

---

# StockBalance

## Purpose
Derived/materialized quantity by item/location/status.

## Fields
- stockItemId
- storageLocationId
- onHandQty
- reservedQty
- availableQty — C
- damagedQty
- quarantineQty
- updatedAt/version

Not source of movement history.

---

# StockMovement

## Fields
- id
- stockItemId
- quantity
- unit
- movementType
- fromLocationRef — O
- toLocationRef — O
- projectId — O
- siteId — O
- workOrderId — O
- recipientUserId — O
- purchaseReceiptId — O
- reason
- operationId
- occurredAt
- recordedAt/by

## Invariants
Append-first.
No accidental negative authoritative quantity.
Each operation idempotent.

---

# Reservation

## Fields
- id
- resourceType: ASSET/STOCK
- resourceRef
- quantity — if stock
- projectId
- siteId — O
- workOrderId — O
- requestedBy
- reservedForUser/team — O
- start/end
- state
- priority
- version

## Invariants
No double allocation beyond policy.

---

# Stocktake

## Fields
- id
- warehouse/location scope
- type: FULL/CYCLE
- startedAt/by
- expectedSnapshotVersion
- state
- completedAt
- discrepancyCount

Child:
StockCountLine
- item/location
- expectedQty
- countedQty
- variance
- counter
- timestamp

Adjustment is separate approved action.

---

# ReceivingRecord

## Fields
- id
- sourceType: PO/TRANSFER/RETURN/OTHER
- sourceRef
- destinationWarehouse/location
- receivedBy
- receivedAt
- state
- deliveryDocumentRef
- discrepancyRefs

Child lines:
item/asset, expected qty, received qty, condition, serials.

---

# Classification Summary

Asset operational identity: INTERNAL
Acquisition cost: RESTRICTED
Custody: INTERNAL/RESTRICTED depending context
Security-linked location history: RESTRICTED/HIGHLY_RESTRICTED if correlated with access/cameras
Stock quantity: INTERNAL
Supplier-linked cost/value: RESTRICTED

## Next
Reality walkthrough determines actual categories, units, serialized rules, calibration data, and storage hierarchy.

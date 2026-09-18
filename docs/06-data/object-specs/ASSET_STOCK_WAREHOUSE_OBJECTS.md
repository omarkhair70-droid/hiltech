# Exact Object Specs — Asset, Stock & Warehouse

Status: DOMAIN DATA MODEL v0.2 / PRE-FREEZE / CONTRACT PACK CANONICAL

---

# Asset

## Purpose
Canonical identity for an individually tracked physical company/client asset.

## State dimensions

### AssetLifecycleState
- ACTIVE
- RETIREMENT_REQUESTED
- RETIRED

### AssetConditionState
- UNKNOWN
- GOOD
- FAIR
- DAMAGED
- UNFIT

### Custody
Derived from accepted AssetMovement/custody projection:
- STORED
- CHECKED_OUT
- IN_TRANSFER
- UNKNOWN

### Calibration / maintenance
Derived from AssetTypeDefinition + records:
- calibration: NOT_REQUIRED / VALID / DUE / EXPIRED / IN_PROGRESS
- maintenance: NONE / DUE / IN_PROGRESS

### Missing/lost
Modeled as AssetIncident + availability blocker, not destructive identity replacement.

### Availability
Derived read-model result from lifecycle + custody + reservation + condition + calibration + maintenance + incidents.
No editable duplicate availability truth.

## Fields
- id: UUID — R
- assetCode: String — R unique human ID
- assetTypeDefinitionId: UUID — R
- assetTypeRevision: Int — R
- manufacturer: String — O
- model: String — O
- serialNumber: String — O
- ownershipTypeCode: String — R
- lifecycleState: AssetLifecycleState — R
- conditionState: AssetConditionState — R
- purchaseDate: LocalDate — O — RESTRICTED
- acquisitionCost: Decimal — O — RESTRICTED
- currency: ISO4217 — O
- warrantyStart/end: LocalDate — O
- calibrationDueAt: Instant — O — derived/cache optimization
- maintenancePlanRef: UUID — O
- tagId: UUID — O
- lastObservedAt: Instant — O
- lastObservedSource: String — O
- version: Long — R
- createdAt/by
- retiredAt — O

## Invariants
- one Asset identity survives tags/transfers/projects/damage/repair.
- one active authoritative physical custody.
- current custodian/location/project/site are projections, not free-form authoritative edits.
- RETIRED cannot be normally reserved/checked out.
- condition/calibration/maintenance/incident facts can block derived availability.
- acquisitionCost restricted.

## Offline
Passport cached.
Damage/missing report local-first where allowed.
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

## Purpose
Business/facility grouping for warehouse operations when a full warehouse concept is needed.

## Fields
- id: UUID
- organizationId: UUID
- code: String
- name: String
- facilityRef — O
- active: Boolean
- version: Long

Current Maadi warehouse is seed/master data, not schema limit.

---

# StorageLocation

## Purpose
Scalable physical/logical storage identity supporting both warehouses and temporary Project/Site storage.

## kind
- MAIN_WAREHOUSE
- WAREHOUSE
- PROJECT_STORAGE
- SITE_STORAGE
- OTHER_TYPED

## Fields
- id: UUID
- organizationId: UUID
- code
- name
- kind
- parentStorageLocationId — O
- warehouseId — O
- projectId — O
- siteId — O
- temporary: Boolean
- activeFrom/activeUntil — O
- responsibleRelationshipCode — O
- restrictedAccess: Boolean
- address/location ref — O
- notes — O
- version

## Invariants
- no cyclic hierarchy.
- Project/Site storage references valid context.
- retiring location preserves movement/history.
- new movements cannot target invalid retired location.

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

## Canonical implementation bridge

First-slice implementation contracts now exist in:
- `docs/13-delivery/first-slice-contract-pack/12_ASSET_WAREHOUSE_CONTRACT.md`
- `00_CONFIGURATION_POLICY_SCHEMAS.md`
- `03_POSTGRES_FLYWAY_JOOQ.md`
- `05_AUTHORIZATION_POLICY_TESTS.md`

Warehouse reality supplies seed/master data and validates structural coverage; it does not define permanent code enums or cap future scale.

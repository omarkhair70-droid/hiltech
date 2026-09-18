# 12 — Asset / Warehouse / Stock Contract

Status: **CONTRACT CANDIDATE v0.1**
Date: 2026-09-18

## Purpose

Convert Asset / Warehouse / Stock / Custody docs into one implementation-facing contract for the first production slice.

Canonical sources:
- `docs/06-data/object-specs/ASSET_STOCK_WAREHOUSE_OBJECTS.md`
- `docs/06-data/transition-tables/ASSET_AND_WAREHOUSE_TRANSITIONS.md`
- `docs/05-workflows/WAREHOUSE_FLOW.md`
- `00_CONFIGURATION_POLICY_SCHEMAS.md`
- `04_ROOM_OFFLINE_SYNC.md`
- `05_AUTHORIZATION_POLICY_TESTS.md`

---

# 1. Core distinction: Asset vs Stock

## Asset
Individually tracked physical item with its own identity and custody/history.

Examples can include:
- Fluke,
- OTDR,
- fusion splicer,
- laptop,
- drill,
- high-value toolkit,
- future client/company equipment.

## StockItem
Quantity-tracked inventory/master item.

Examples can include:
- cable,
- consumables,
- connectors,
- accessories,
- materials.

Serialized stock may later bridge to Asset registration according to receiving/install policy.

Do not model every physical thing as Asset.
Do not model a high-value individually accountable tool as anonymous quantity stock.

---

# 2. Asset

## Asset fields

- id: UUID
- assetCode: String
- assetTypeDefinitionId: UUID
- assetTypeRevision: Int
- manufacturer: String?
- model: String?
- serialNumber: String?
- ownershipTypeCode: String/config/master data
- lifecycleState: AssetLifecycleState
- conditionState: AssetConditionState
- tagId: UUID?
- purchaseDate: LocalDate? RESTRICTED
- acquisitionCost: Decimal? RESTRICTED
- currencyCode: ISO-4217 String?
- warrantyStart: LocalDate?
- warrantyEnd: LocalDate?
- calibrationDueAt: Instant?
- maintenancePlanRef: UUID?
- lastObservedAt: Instant?
- lastObservedSource: String?
- createdAt: Instant
- createdBy: UUID
- retiredAt: Instant?
- version: Long

Current location/custodian/project/site are **projections**, not arbitrary writable source fields.

## AssetLifecycleState

Candidate semantic states:

- REGISTERED
- AVAILABLE
- RESERVED
- CHECKED_OUT
- IN_USE
- UNDER_INSPECTION
- MAINTENANCE
- CALIBRATION_BLOCKED
- DAMAGED
- MISSING
- RETIRED

Exact transition table may collapse/split some states before final freeze.
The important invariant is that custody/availability and condition/calibration are not blindly conflated.

## AssetConditionState

Candidate:
- UNKNOWN
- GOOD
- FAIR
- DAMAGED
- UNFIT

Condition vocabulary may be configurable/master data if operationally useful, but hard availability rules still live in domain policy.

---

# 3. Asset type configuration

Canonical:
`AssetTypeDefinition` from configuration contract.

Controls:
- serialized requirement,
- calibration required,
- maintenance defaults,
- high-value/restricted handling,
- allowed tag types,
- expected accessory template.

A new tool category must not require code deployment.

Hard invariants remain code/domain.

---

# 4. AssetTag

Fields:

- id: UUID
- assetId: UUID
- tagTypeCode: String
- publicOpaqueCode: String
- providerExternalId: String?
- active: Boolean
- issuedAt: Instant
- replacedTagId: UUID?
- version: Long?

Rules:
- tag identity can change without changing Asset identity.
- public QR/barcode contains opaque identifier, not sensitive asset payload.
- at most one active primary tag per tag-purpose according policy.

---

# 5. Storage model

## StorageLocation

Use one scalable physical/logical location model.

Kinds:
- MAIN_WAREHOUSE
- WAREHOUSE
- PROJECT_STORAGE
- SITE_STORAGE
- OTHER_TYPED

Fields:

- id: UUID
- organizationId: UUID
- code: String
- name: String
- kind: StorageKind
- parentStorageLocationId: UUID?
- warehouseId: UUID?
- projectId: UUID?
- siteId: UUID?
- temporary: Boolean
- activeFrom: Instant?
- activeUntil: Instant?
- responsibleRelationshipCode: String?
- restrictedAccess: Boolean
- addressOrLocationRef: typed ref?
- notes: String?
- version: Long

Rules:
- hierarchy acyclic.
- Project/Site storage requires valid project/site context.
- retirement preserves movement/stock history.
- new movements cannot target invalid/retired location.
- today's Maadi storage is seed data, not schema.

## Warehouse

Warehouse remains a business/facility grouping where needed.

Fields:
- id: UUID
- organizationId: UUID
- code
- name
- facilityRef?
- manager/relationship config?
- active
- version

A temporary site store does not need to become a full Warehouse.

---

# 6. AssetMovement

Append-only physical/custody movement event.

Fields:

- id: UUID
- assetId: UUID
- movementType: AssetMovementType
- fromStorageLocationId: UUID?
- toStorageLocationId: UUID?
- fromCustodianTarget: typed ref?
- toCustodianTarget: typed ref?
- projectId: UUID?
- siteId: UUID?
- workOrderId: UUID?
- occurredAt: Instant
- recordedAt: Instant
- recordedBy: UUID
- sourceOperationId: UUID
- conditionAtTransfer: AssetConditionState?
- expectedReturnAt: Instant?
- reasonCode: String?
- notes: String?
- correctionOfMovementId: UUID?
- auditRef/correlation

## Movement types — candidate

- RECEIVE
- REGISTER_INITIAL_LOCATION
- CHECKOUT
- RETURN
- TRANSFER
- SITE_TRANSFER
- CUSTODY_TRANSFER
- SEND_FOR_MAINTENANCE
- RETURN_FROM_MAINTENANCE
- SEND_FOR_CALIBRATION
- RETURN_FROM_CALIBRATION
- RETIRE
- CONTROLLED_CORRECTION

No destructive history edit.

---

# 7. Asset custody projection

Derived from authoritative accepted movements/lifecycle.

Candidate projection:

- assetId
- currentStorageLocationId?
- currentCustodianTargetType?
- currentCustodianTargetId?
- currentProjectId?
- currentSiteId?
- currentWorkOrderId?
- custodyStartedAt?
- expectedReturnAt?
- sourceMovementId
- assetVersion

Invariant:
one active authoritative physical custody/context.

DB/transaction strategy must make two successful simultaneous Checkouts impossible.

---

# 8. Asset commands

## RegisterAsset
Creates Asset + initial tag/location/context.

## ReserveAsset
Requires:
- active asset.
- eligible availability.
- calibration/maintenance valid.
- no conflicting authoritative reservation beyond policy.
- project/site/work context as configured.

## CheckoutAsset
Online-authoritative by default.

Request candidate:
- operationId
- assetId
- baseVersion
- recipientTargetType/id
- projectId?
- siteId?
- workOrderId?
- destinationStorageLocationId?
- expectedReturnAt?
- conditionAtCheckout
- reason/purpose code?
- clientOccurredAt

Server checks:
- permission.
- current version.
- active custody.
- reservation.
- calibration/maintenance.
- target eligibility/context.
- storage validity.
- idempotency.

Result:
- movementId.
- new asset version.
- authoritative custody projection.

## ReturnAsset
Final receipt online-authoritative.

Requires:
- physical identity.
- current custody.
- destination.
- return condition.
- accessory/inspection requirements according policy.

May transition to:
- AVAILABLE.
- UNDER_INSPECTION.
- DAMAGED.
- CALIBRATION_BLOCKED.
- MAINTENANCE.

## TransferAsset
Moves storage/custody/context under policy.

May require receiver acceptance.

## ReportAssetDamage / Missing
Local-first capture permitted.
Final lifecycle/incident handling server-side.

## RetireAsset / WriteOff
Online + approval/policy.
Never technician-local final action.

---

# 9. Collision contract

Representative:

Two devices/users attempt CheckoutAsset on same version.

Rule:
first authoritative transaction wins.

Second returns:
- VERSION_CONFLICT or RESOURCE_CUSTODY_CONFLICT.
- current safe status.
- current custodian/context if actor may see it.
- current version.
- allowed recovery actions.

Never:
- last-write-wins.
- second active custody.
- silent override.

UI:
`CHECKOUT NOT APPLIED`.

---

# 10. Calibration / maintenance

AssetTypeDefinition determines whether calibration is required.

CalibrationRecord:

- id
- assetId
- calibrationTypeCode
- providerOrganization/contact ref?
- certificateDocumentId?
- result
- calibratedAt
- validUntil
- notes
- recordedAt/by

Rule:
If required calibration is expired/invalid, normal availability/reservation/checkout is blocked according hard policy/configured exception path.

An exception:
- explicit,
- authorized,
- reasoned,
- audited,
- cannot silently flip calibration truth.

---

# 11. Asset incident

Fields:

- id
- assetId
- incidentTypeCode
- state
- reportedBy
- reportedAt
- projectId/siteId/workOrderId?
- description
- evidenceRefs
- investigationOwner?
- resolution?
- resolvedAt?
- version

Types are configurable/master codes where possible.

Telemetry/access/camera correlation may create context, never automatic guilt/responsibility.

---

# 12. StockItem

Fields:

- id: UUID
- itemCode: String
- name: String
- categoryDefinitionId: UUID
- categoryRevision: Int
- unitOfMeasureCode: String
- serialized: Boolean
- lotTracked: Boolean
- active: Boolean
- valuationClassRef: UUID/String? RESTRICTED
- version: Long

Reorder policy/master configuration remains configurable.

---

# 13. StockBalance

Materialized/derived per item + location + status.

Candidate:
- stockItemId
- storageLocationId
- onHandQty: Decimal
- reservedQty: Decimal
- availableQty: Decimal derived
- damagedQty: Decimal
- quarantineQty: Decimal
- version/updatedAt

Not movement source of truth.

Invariant:
no accidental negative authoritative quantity.

---

# 14. StockMovement

Append-oriented stock ledger.

Fields:
- id: UUID
- stockItemId: UUID
- quantity: Decimal signed/typed by movement semantics
- unitCode: String
- movementType
- fromStorageLocationId?
- toStorageLocationId?
- projectId?
- siteId?
- workOrderId?
- recipientTargetType/id?
- purchaseReceiptId?
- reasonCode
- operationId
- occurredAt
- recordedAt/by
- correlation/audit

Candidate types:
- RECEIVE
- RESERVE
- RELEASE_RESERVATION
- ISSUE
- CONSUME
- RETURN
- TRANSFER
- DAMAGE
- QUARANTINE
- ADJUSTMENT

Reservation may be a separate object rather than a stock movement if quantity remains on hand; exact accounting/storage shape to freeze with DB contract.

---

# 15. Reservation

Unified logical reservation object:

- id
- resourceType: ASSET / STOCK
- resourceId
- quantity? for stock
- projectId
- siteId?
- workOrderId?
- requestedBy
- reservedForTargetType/id?
- startAt?
- endAt?
- state
- priorityCode
- version

Rules:
- no double allocation beyond explicit policy.
- reservation does not equal physical custody.
- cancellation/release is explicit.
- expired reservation policy may automate release.

---

# 16. Project / Work integration

WorkOrder may declare required resources through typed requirement instances.

Flow:

Work planned
→ requirement instance
→ reservation
→ checkout/issue
→ field use
→ consumption/return
→ Work/Project actuals

One physical/resource event should flow downstream.

Do not re-enter:
- asset checkout in project.
- stock issue in project.
- material consumption again in finance.

Project/Work consume warehouse truth through events/read models.

---

# 17. Temporary Project/Site stock

Remote work such as project-site material storage uses StorageLocation:

- PROJECT_STORAGE or SITE_STORAGE.
- project/site scoped.
- temporary lifecycle.
- responsible relationship.
- stock movements in/out.
- consumption linked to WorkOrder where applicable.

This is first-class enough for accountability without creating a fake enterprise warehouse for every building/site.

The same model can scale to many project stores later.

---

# 18. Receiving

ReceivingRecord candidate:

- id
- sourceType: PO / TRANSFER / RETURN / INITIALIZATION / OTHER
- sourceRef?
- destinationStorageLocationId
- receivedBy
- receivedAt
- state
- deliveryDocumentRef?
- discrepancyRefs
- version

Lines:
- item/asset ref.
- expected qty.
- received qty.
- condition.
- serials.
- discrepancy.

Receiving can create:
- stock movement.
- asset registration/tagging.
- discrepancy workflow.

---

# 19. Stocktake / adjustment

Stocktake:
- scoped snapshot.
- physical count.
- variance.
- investigation.

Adjustment:
separate command/workflow.

Never directly edit StockBalance number.

Adjustment requires:
- reason.
- authority/ApprovalPolicy as configured.
- exact snapshot/version/context.
- append ledger effect.
- audit.

---

# 20. Offline classification

| Action | Class |
|---|---|
| Asset passport | CR |
| Asset scan/identify | CR/MIXED |
| ReserveAsset | OA |
| CheckoutAsset | OA default |
| Return capture | LF/MIXED; final receipt OA |
| Damage report | LF |
| Mark lost/write-off | OA |
| Stock count | LF |
| Stock issue | OA default |
| Stock consumption at site | LF |
| Stock adjustment | OA |
| Storage/master config edit | OA |
| config activation | OA |

If future controlled offline checkout is required, it needs a separate explicit contract/spike/revisit; do not inherit it from normal field offline semantics.

---

# 21. Authorization / field visibility

Operational users:
- asset identity/state/custody as needed.
- acquisition cost separately restricted.
- security-correlated history highly restricted.

Technician:
- assigned/current-custody context.
- no acquisition cost.
- no unrelated movement history.

Warehouse:
- operational inventory/custody.
- finance value only by separate permission.

PM:
- project-context asset/material readiness/usage.
- not unrestricted warehouse financial data.

Client:
- only client-owned/client-visible subset.

---

# 22. Events

Asset:
- asset.registered
- asset.reserved
- asset.reservation_released
- asset.checked_out
- asset.returned
- asset.transferred
- asset.condition_changed
- asset.damage_reported
- asset.missing_reported
- calibration.due
- calibration.completed
- maintenance.due
- asset.retired

Stock:
- stock.received
- stock.reserved
- stock.reservation_released
- stock.issued
- stock.consumed
- stock.returned
- stock.transferred
- stock.discrepancy_created
- stock.adjustment_applied

Storage:
- storage.created
- storage.retired

Events carry IDs/version/context, not unnecessary sensitive data.

---

# 23. DB ownership

## warehouse/assets module owns

- asset
- asset_tag
- asset_movement
- asset_custody_projection/read model
- asset_incident
- calibration_record
- warehouse
- storage_location
- stock_item
- stock_balance projection
- stock_movement
- reservation
- receiving_record
- stocktake/count
- adjustment workflow objects owned here unless Approval subject record lives in approvals module

No Project/Work table writes directly.

Project/Work refs are foreign/business context; cross-module updates flow through commands/events/read models.

---

# 24. Required tests

Asset:
- unique assetCode.
- tag replacement preserves Asset identity.
- one active custody.
- checkout two-winner collision yields one success.
- stale baseVersion conflict.
- calibration blocked.
- retired asset checkout denied.
- unauthorized checkout denied.
- acquisition cost redaction.
- return condition routes correctly.
- append-only movement.
- idempotent checkout retry.
- temporary site storage transfer.
- custody projection equals movement truth.

Stock:
- no negative authoritative quantity.
- reservation collision.
- issue/consume/return conservation.
- site storage transfer.
- duplicate operation idempotent.
- stocktake variance does not directly edit balance.
- adjustment requires authority/reason.
- unit/category configuration works without deployment.

Offline:
- cached Asset passport stale indicator.
- damage report offline.
- stock consumption offline.
- checkout remains authoritative online.
- stale cached AVAILABLE collision UX.

---

# 25. Open items before final freeze

- exact AssetLifecycleState/Condition enums and transition matrix normalization.
- exact Warehouse vs StorageLocation table split.
- exact custody projection persistence strategy.
- exact reservation table model for Asset vs Stock.
- exact quantity Decimal precision per unit strategy.
- exact serial uniqueness scope.
- exact accessory/return-inspection model.
- exact calibration exception policy.
- exact stock valuation/accounting boundary.
- exact offline return finalization policy.
- exact master-data import/seed format.

Current decision:
**Asset/Warehouse/Stock is structurally contract-ready; remaining work is exact persistence/transition closure and representative data validation, not domain rediscovery.**

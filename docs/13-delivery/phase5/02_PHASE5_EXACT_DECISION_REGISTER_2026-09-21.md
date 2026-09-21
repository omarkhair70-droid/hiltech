# Phase 5 — Exact Decision Register

Date: 2026-09-21  
Status: **DECISION REGISTER v0.1 / CONTRACT CLOSURE IN PROGRESS**

This register converts the old Asset/Warehouse “open items” into explicit frozen or reality-blocked decisions.

No production code is authorized merely by writing this register.

---

# Decision status vocabulary

- **FROZEN** — implementation may rely on it.
- **FROZEN_FIRST_SLICE** — deliberate limited first-production behavior; later expansion requires new contract.
- **STRUCTURE_FROZEN / TRANSITIONS_PENDING** — storage shape is chosen but lifecycle transition details still need closure.
- **REALITY_VALIDATION_PENDING** — cannot be invented from architecture alone.
- **DEFERRED** — intentionally not Phase-5 first implementation.

---

# P5-D01 — Warehouse vs StorageLocation

Status: **FROZEN**

Decision:

- `Warehouse` is an optional operational/facility aggregate.
- `StorageLocation` is the authoritative physical/logical inventory location dimension.
- Warehouse inventory locations point to a Warehouse.
- Project/Site temporary storage uses StorageLocation directly and does not require a Warehouse row.
- inventory commands reference StorageLocation ids, never free-text warehouse names.
- nested StorageLocation hierarchy is supported.
- location retirement preserves history and blocks new movement.

Existing V0006 table split is retained.

The old “exact Warehouse vs StorageLocation split” open item is stale and CLOSED.

---

# P5-D02 — Asset movement / custody source of truth

Status: **FROZEN**

Authoritative source:

`asset_movement` append-only history.

Current custody:

`asset_custody_projection` rebuildable/materialized projection.

No command edits current custodian/location/project/site/work fields directly.

Every accepted custody-changing command runs in one database transaction:

1. lock the Asset row for update;
2. verify lifecycle + baseVersion + current projection;
3. validate permission / reservation / calibration / maintenance / destination;
4. insert append-only AssetMovement with unique sourceOperationId;
5. update/recreate the custody projection;
6. increment Asset version;
7. append audit/domain event;
8. commit.

The Asset row lock is the first-slice serialization point.

Result:

two concurrent successful checkouts of one Asset are impossible.

Optimistic version conflict remains visible to callers; row locking protects the physical invariant.

Correction never deletes/replaces history.

Controlled correction creates a new movement referencing the corrected movement.

---

# P5-D03 — Asset reservation persistence

Status: **FROZEN**

Use typed `asset_reservation`.

Do not create a generic polymorphic resource reservation table.

States:

- REQUESTED;
- ACTIVE;
- RELEASED;
- CANCELLED;
- FULFILLED;
- EXPIRED.

Rules:

- only ACTIVE reservation blocks competing normal allocation;
- reservation does not change physical custody;
- CheckoutAsset may fulfil the relevant reservation in the same authoritative transaction;
- release/cancel/expire are explicit history-preserving transitions;
- no retired/unfit/calibration-blocked Asset may become a normal ACTIVE reservation;
- overlapping conflict rules are enforced transactionally against current reservations.

Time-window scheduling may use start/end, but first production behavior does not invent complex optimization/scoring.

---

# P5-D04 — Stock reservation persistence

Status: **FROZEN**

Use typed `stock_reservation`.

States match Asset reservation:

- REQUESTED;
- ACTIVE;
- RELEASED;
- CANCELLED;
- FULFILLED;
- EXPIRED.

Reservation does **not** create physical StockMovement because quantity remains on hand.

`stock_balance.reserved_qty` is a materialized projection of authoritative active reservations.

ReserveStock transaction:

1. lock item/location StockBalance row;
2. validate item/location/version/available quantity;
3. create or transition StockReservation;
4. rebuild/update reserved quantity;
5. audit/event;
6. commit.

IssueStock may fulfil/reduce a reservation and create the physical StockMovement atomically.

No double allocation beyond policy.

---

# P5-D05 — Stock movement / balance truth

Status: **FROZEN**

Authoritative source:

`stock_movement` append-only ledger.

`stock_balance` is a transactionally maintained rebuildable projection.

V0006 rule remains:

- movement quantity is positive;
- movement type + from/to context determines direction;
- no negative authoritative balance.

For operations touching multiple StorageLocations, rows are locked in deterministic StorageLocation-id order to avoid deadlock and race inconsistencies.

No command edits StockBalance totals directly.

Adjustment is a StockMovement produced only by an authorized adjustment workflow.

---

# P5-D06 — Issued-but-not-consumed stock

Status: **FROZEN CONCEPT / PROJECTION SHAPE TO IMPLEMENT IN PHASE 5**

Physical issue and physical consumption are distinct facts.

Issue removes quantity from a StorageLocation but does not imply it has already been consumed.

Authoritative issued quantity is derived from append-only StockMovement history:

`ISSUE - RETURN - CONSUME +/- approved corrections`

scoped by:

- StockItem;
- recipient;
- Project;
- Site;
- WorkOrder where applicable.

Phase 5 may materialize a `stock_issue_projection` / equivalent read model for performance and Phase-6 integration.

It is not a second editable stock ledger.

This closes an important gap not represented by V0006 `stock_balance` alone.

---

# P5-D07 — Quantity type / precision

Status: **FROZEN**

Keep database quantity capability:

`numeric(20,6)`

Wire/API:

exact Decimal string / decimal type, never binary float.

The real control is UOM policy.

Each StockItem has one canonical base unit.

Movement/reservation/opening quantity must resolve to that unit before authoritative write.

Allowed operational scale is defined by the unit/item policy.

First production import supports explicit human mapping from legacy units into canonical units.

No implicit unit conversion.

Pack/carton/roll purchase conversions are **not** automatically introduced until representative receiving data proves the required conversion model.

This is deliberate because the reviewed legacy Fiber sheet represents a large cable quantity as `PC`.

---

# P5-D08 — Part number / serial uniqueness

Status: **FROZEN**

Hard internal identities:

- StockItem: HILTECH itemCode.
- Asset: HILTECH assetCode.
- AssetTag: opaque tag code.

Part Number and Serial Number are normalized searchable attributes.

No blanket global unique Part Number.

No blanket global unique Asset serial number.

Import/registration performs duplicate detection and returns review/warning context.

A stricter uniqueness rule may be configured for a proven AssetType/manufacturer combination later.

The reviewed files already prove that duplicate/placeholder part numbers exist.

---

# P5-D09 — Asset / Stock classification

Status: **FROZEN PROCESS**

Classification is explicit during master-data creation/import review.

Supported first-phase classes:

- STOCK_QUANTITY;
- STOCK_SERIALIZED;
- COMPANY_ASSET.

Do not infer class only from legacy workbook/category.

Examples from the Active workbook may represent quantity stock, serialized stock or individually accountable equipment depending on ownership/use.

Serialized stock may later create/bridge to Asset identity at receiving/issue/install according to a later explicit rule.

No automatic StockItem -> Asset conversion is allowed without policy.

---

# P5-D10 — Return receipt and inspection

Status: **FROZEN_FIRST_SLICE**

Authoritative ReturnAsset receipt is online and means physical custody has been accepted back by an authorized receiver.

Return transaction:

1. identify Asset;
2. lock/validate current custody + version;
3. insert RETURN AssetMovement to destination StorageLocation;
4. close previous custodian context in custody projection;
5. update custody projection to returned StorageLocation;
6. if AssetType policy requires inspection, derived availability becomes BLOCKED_INSPECTION until inspection exists;
7. create/audit return context.

Return receipt and inspection are separate facts.

`asset_return_inspection` is one record per return movement.

Inspection may record:

- condition;
- typed accessory checklist template/revision;
- accessory results;
- notes;
- linked damage incident.

If no inspection is required, availability can be derived immediately from lifecycle/condition/calibration/maintenance/incidents/reservation.

If inspection is required, physical return closes custody before inspection, but the Asset is not normally available until inspection passes.

---

# P5-D11 — Accessory checklist

Status: **STRUCTURE_FROZEN / REALITY_VALIDATION_PENDING**

Accessory requirements are configuration/templates by AssetType.

Do not hard-code “Fluke accessories”, “OTDR accessories”, etc.

The current AssetReturnInspection JSON/template-reference shape remains valid.

Need representative real high-value tool examples before freezing initial template seed.

---

# P5-D12 — Calibration exception

Status: **FROZEN_FIRST_SLICE**

If calibration is required and invalid/expired:

- normal reservation denied;
- normal checkout denied.

No generic emergency override button in first Phase-5 production.

An exception requires a later demonstrated HILTECH use case plus explicit ApprovalPolicy/reason/audit contract.

Do not silently modify calibration truth.

---

# P5-D13 — Calibration truth

Status: **STRUCTURE_FROZEN / REPRESENTATIVE DATA PENDING**

CalibrationRecord is authoritative history.

Derived status:

- NOT_REQUIRED;
- VALID;
- DUE;
- EXPIRED;
- IN_PROGRESS.

AssetTypeDefinition states whether calibration is required.

Need HILTECH representative tools/certificates to freeze seed configuration and certificate fields.

---

# P5-D14 — Maintenance truth

Status: **STRUCTURE_FROZEN / REPRESENTATIVE DATA PENDING**

Maintenance/service history is separate from Asset lifecycle and condition.

Derived status:

- NONE;
- DUE;
- IN_PROGRESS.

Maintenance/calibration may block normal availability according to AssetType policy.

Need representative actual repair/service workflow before freezing detailed provider/cost fields.

---

# P5-D15 — Asset incident

Status: **FROZEN CONCEPT**

Damage/missing/lost is an explicit AssetIncident.

It does not destroy or rename Asset identity.

First-slice incident minimum:

- Asset;
- type;
- state;
- reportedBy/At;
- Project/Site/Work context;
- description;
- Evidence refs;
- investigation owner optional;
- resolution;
- resolvedAt;
- version.

Camera/access events may be correlated for investigation but never assign guilt automatically.

---

# P5-D16 — Stock valuation/accounting

Status: **FROZEN BOUNDARY**

Phase 5 is authoritative for:

- physical quantity;
- reservation;
- movement;
- custody/context;
- condition/availability.

Phase 5 is **not** authoritative for:

- FIFO;
- weighted-average financial costing;
- GL;
- tax valuation;
- accounting inventory journal.

Legacy Unit Price/Total Price may be retained as import/source metadata or restricted reference when useful.

They do not drive StockBalance.

Receiving may reference PO/invoice/source identifiers without duplicating Finance.

Project/Finance may later consume approved physical usage events.

---

# P5-D17 — Receiving ownership

Status: **FROZEN CONCEPT / LINE SOURCE VALIDATION PENDING**

ReceivingRecord is Phase-5 physical receiving truth.

Header:

- sourceType;
- sourceRef;
- destination StorageLocation;
- receivedBy/At;
- state;
- delivery document;
- discrepancies;
- version.

Lines:

- expected item/asset;
- expected quantity;
- received quantity;
- condition;
- serials;
- discrepancy.

Receiving may create:

- StockMovement RECEIVE;
- Asset registration/tagging;
- discrepancy workflow.

The reviewed sales/purchase report is invoice-level evidence and cannot substitute for receiving lines.

No received quantity is inferred from invoice totals.

---

# P5-D18 — Stocktake / adjustment

Status: **FROZEN**

Stocktake count never directly edits StockBalance.

Flow:

snapshot scope
-> physical count
-> variance
-> discrepancy/investigation
-> adjustment request
-> authority/approval
-> append-only ADJUSTMENT movement
-> balance projection update.

Adjustment requires:

- exact count/snapshot context;
- reason;
- authority;
- idempotency;
- audit.

---

# P5-D19 — Offline warehouse authority

Status: **FROZEN_FIRST_SLICE**

First Phase-5 production:

- checkout final authority: online;
- return final receipt: online;
- reserve: online;
- issue: online;
- adjustment: online;
- storage/master changes: online.

Do not implement a controlled offline warehouse lane without demonstrated business need.

Phase-6 field offline architecture is not inherited automatically by warehouse operations.

Local scan/count capture may be added later as a separate explicit slice when required.

---

# P5-D20 — Master import architecture

Status: **FROZEN**

Direct Excel -> production master/balance writes are prohibited.

Import uses staging.

Required concepts:

## InventoryImportBatch

- id;
- organizationId;
- sourceFileName;
- sourceHash/ref;
- sourceType;
- cutoverDate?;
- createdBy/At;
- state;
- version.

## InventoryImportRow

- id;
- batchId;
- sourceSheet;
- sourceRowNumber;
- rawPayload;
- candidateClass;
- normalized Brand/Manufacturer;
- normalized Part Number;
- normalized description;
- candidate UOM;
- candidate quantity;
- candidate value metadata;
- issueCodes;
- reviewState;
- reviewedBy/At;
- approvedTargetType/id?;
- version.

No ambiguous row becomes authoritative silently.

---

# P5-D21 — Opening truth migration

Status: **FROZEN**

Legacy stock sheets are cutover/opening evidence, not trusted movement history.

Opening stock:

- approved StockItem;
- StorageLocation;
- canonical quantity/UOM;
- cutover timestamp;
- source batch/row trace.

Opening asset:

- Asset identity;
- condition;
- StorageLocation/current custodian where known;
- optional tag;
- cutover timestamp;
- source trace.

Opening truth uses explicit initialization operations/events.

Do not fabricate historical PURCHASE/RECEIVE/ISSUE movements from ambiguous legacy date columns.

---

# P5-D22 — QR / barcode tags

Status: **FROZEN DATA MODEL / HARDWARE VALIDATION PENDING**

AssetTag remains separate from Asset identity.

Tag stores opaque public code, not sensitive payload.

Tag replacement preserves Asset history.

First production must support generated tag identity and scan lookup.

Physical printer/scanner hardware is not assumed.

Manual/search lookup remains a fallback.

Do not block core inventory truth on hardware purchase.

---

# P5-D23 — Warehouse electronic access / cameras

Status: **DEFERRED FROM CORE PHASE-5 TRUTH**

Current HILTECH electronic access-control/camera capability is not established strongly enough to make it a dependency.

Core inventory/custody runs without it.

Future access/camera integrations produce separate facts for investigation/correlation.

They never replace movement/custody records.

---

# P5-D24 — Current responsible people

Status: **FROZEN AUTHORIZATION PRINCIPLE / SEED REALITY PENDING**

No named HILTECH person is hard-coded.

Current reported operating roles inform initial configuration only.

Authorization is relation/role based.

Warehouse operator, manager, PM, technician/custodian, inventory controller and adjustment approver are capabilities/relationships.

Actual first seed assignments must be validated separately.

---

# P5-D25 — Temporary Project/Site storage

Status: **FROZEN**

Phase-4 Project/Site truth is reused.

Temporary storage is a StorageLocation with:

- projectId;
- siteId where applicable;
- temporary=true;
- active dates;
- responsibility/access metadata.

No duplicate Project/Site master inside Warehouse.

The reported remote-site storage reality directly supports this model.

---

# P5-D26 — Phase-4 readiness integration

Status: **FROZEN TARGET**

Phase 5 becomes authoritative source for previously unresolved resource readiness.

Phase-4 WorkRequirementInstance remains Work truth.

Phase 5 answers resource facts such as:

- Asset candidate availability;
- current reservation;
- current custody;
- calibration/maintenance/incident block;
- Stock available quantity at eligible location;
- reservation state.

Phase 5 does not rewrite WorkRequirementInstance manually.

Work readiness reevaluates from Phase-5 authoritative resource source/version.

---

# P5-D27 — Phase-6 stock consumption integration

Status: **FROZEN BOUNDARY**

Phase 5 owns issued quantity / resource custody truth.

Phase 6 may later submit field consumption/return intent.

Authoritative stock consumption validates against current Phase-5 issued balance/context.

Do not implement technician field consumption workflow in Phase 5.

---

# Remaining reality gates before final phase freeze

These do not reopen the domain model.

They are seed/process validation gates:

1. representative high-value Asset/tool list;
2. actual serial examples;
3. calibration certificate/process example;
4. current return/damage/loss example;
5. official warehouse responsibility/approval mapping;
6. physical storage layout/names;
7. representative receiving line-level document;
8. current tag/QR hardware if any.

The Phase-5 slice plan may include explicit validation/provisioning gates for these instead of inventing facts.

---

# Decision-register result

Old contract open items:

- Warehouse vs StorageLocation: **CLOSED**
- custody projection persistence: **CLOSED**
- reservation model: **CLOSED**
- Decimal precision: **CLOSED**
- serial uniqueness: **CLOSED**
- return inspection: **CLOSED first-slice behavior**
- calibration exception: **CLOSED first-slice behavior**
- valuation boundary: **CLOSED**
- offline return finalization: **CLOSED first-slice behavior**
- import/seed format: **CLOSED**

Additional discovered decisions:

- issued-but-unconsumed stock projection: **FROZEN concept**
- import staging: **FROZEN**
- opening truth cutover: **FROZEN**
- receiving invoice-vs-line boundary: **FROZEN**
- electronic access/camera dependency: **DEFERRED**
- Phase-4 readiness integration: **FROZEN target**
- Phase-6 consumption boundary: **FROZEN**

`PHASE5_OLD_OPEN_ITEMS_CLOSED = YES`

`PHASE5_REPRESENTATIVE_ASSET_SEED_VALIDATION_PENDING = YES`

`PHASE5_AUTHORIZATION_MATRIX_PENDING = YES`

`PHASE5_SLICE_PLAN_FROZEN = NO`

`PHASE5_PRODUCTION_CODE_AUTHORIZED = NO`

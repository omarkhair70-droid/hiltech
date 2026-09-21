# Phase 5 — Master Implementation Map

Date: 2026-09-21  
Status: **PHASE PLAN v1.0 / FROZEN FOR CONTRACT AUTHORING**

## 1. Phase objective

Replace Phase-4 `SOURCE_PENDING_PHASE5` resource uncertainty with authoritative HILTECH physical-resource truth.

At Phase-5 completion HILTECH must be able to answer, from auditable source truth:

- what important Assets exist;
- what StockItems exist;
- how much Stock is physically on hand;
- where each Asset / stock balance is;
- who currently has an Asset or issued Stock;
- what is reserved for which Project/Site/Work;
- what is checked out / issued / due back;
- what is damaged/missing/quarantined;
- which Assets are calibration/maintenance blocked;
- what physical movement created current state;
- what was received;
- what was counted;
- why an adjustment occurred.

Phase 5 remains physical-resource truth, not Finance ledger and not Phase-6 field execution.

---

# 2. Inputs already closed

Required planning inputs:

- `00_PHASE5_OWNER_ENTRY_REALITY_RECONSTRUCTION_2026-09-21.md`
- `01_PHASE5_REPRESENTATIVE_DATA_VALIDATION_2026-09-21.md`
- `02_PHASE5_EXACT_DECISION_REGISTER_2026-09-21.md`
- `03_PHASE5_AUTHORIZATION_MATRIX_2026-09-21.md`
- existing `12_ASSET_WAREHOUSE_CONTRACT.md`
- V0006 structural warehouse/assets schema
- Phase 0–4 production source truth

Representative HILTECH stock files have been reviewed.

No production code is authorized until the slice contracts below are frozen.

---

# 3. Phase-wide implementation rules

Every Slice:

1. starts from live `main`;
2. reads existing source truth before schema change;
3. uses forward-only migration only where required;
4. opens Draft PR early;
5. includes PostgreSQL + OpenFGA/current-source proof where authorization matters;
6. includes shared client contract where a UI consumes it;
7. includes Windows/Android human proof appropriate to the slice;
8. preserves Phase-4 Work/Project and Phase-1–3 People ownership;
9. never fabricates later-phase state;
10. closes with exact-head green gates, gap review, merge, post-merge Bootstrap.

No direct edits of:

- Asset current custody;
- StockBalance quantity;
- Work readiness;
- Finance valuation.

Those remain derived/projected from authoritative commands/history.

---

# 4. Slice order

`01 -> 02 -> 03 -> 04 -> 05 -> 06 -> 07 -> 08 -> 09 -> Phase 5 Final Gap Review`

No later slice begins before the prior production slice closes unless a docs-only plan correction is required.

---

# Slice 01 — Warehouse / Storage / Authority / Import Staging

Primary purpose:

establish trusted places, trusted operators and safe legacy-data intake before physical quantity/custody mutation.

Target migration:

`V0026`

Owns:

- Warehouse hardening;
- StorageLocation hierarchy/lifecycle hardening;
- WarehouseAuthorityBinding;
- StorageLocationAuthorityBinding;
- OpenFGA Warehouse permission expansion;
- InventoryImportBatch;
- InventoryImportRow;
- normalized import issue codes/review states;
- source-file/row trace;
- safe Warehouse/Storage APIs;
- Windows Warehouse setup/import-review surface.

No Stock opening balances yet.

No Asset checkout yet.

No inventory quantity mutation beyond staging metadata.

Completion proof:

- current/stale authority;
- Team authority;
- temporary Project/Site location;
- hierarchy cycle denial;
- import row validation;
- legacy UOM/part/description issue surfacing;
- restricted value redaction.

---

# Slice 02 — Stock Master / UOM / Opening Balance / Ledger Core

Target migration:

`V0027`

Owns:

- UnitOfMeasure policy/master;
- StockItem production master hardening;
- item classification = STOCK_QUANTITY / STOCK_SERIALIZED where relevant;
- Brand/Manufacturer + Part Number normalization;
- StockMovement authoritative core;
- StockBalance transactional/rebuildable projection;
- opening Stock balance operation;
- approved import-row -> StockItem/opening balance;
- location-to-location Stock transfer core;
- Stock inventory Windows list/detail/history.

Invariants:

- Decimal exactness;
- no negative Stock;
- no direct balance edit;
- duplicate operation idempotency;
- deterministic multi-location lock order;
- opening truth is initialization, not fake receiving.

No reservation/issue yet.

Completion proof uses real UTP/FIBER/ACTIVE representative rows.

---

# Slice 03 — Asset Passport / Tags / Opening Custody

Target migration:

`V0028`

Owns:

- Asset production master hardening;
- AssetTypeDefinition execution contract;
- AssetTag primary/replacement invariant;
- AssetMovement authoritative core;
- AssetCustodyProjection transaction/rebuild contract;
- RegisterAsset;
- opening Asset location/custody initialization;
- approved import-row -> Asset candidate;
- QR/opaque tag lookup;
- Asset Passport Windows + Android read surface.

Invariants:

- one authoritative custody;
- no direct current-custodian edit;
- Asset identity survives tag replacement;
- row-lock + baseVersion collision behavior;
- no global serial uniqueness assumption.

No routine CheckoutAsset yet except initialization/correction primitives.

Representative high-value Asset seed data should be supplied/validated during this slice, but code must not hard-code specific tool categories.

---

# Slice 04 — Asset Availability / Incident / Calibration / Maintenance

Target migration:

`V0029`

Owns:

- AssetIncident;
- CalibrationRecord;
- Maintenance/service record;
- return/accessory inspection configuration support;
- derived Asset availability read model;
- AssetType calibration/maintenance rules;
- damage/missing reporting;
- send/return calibration/maintenance authoritative records;
- Windows Asset health/service queues;
- safe mobile Asset availability explanation.

Availability derives from:

- lifecycle;
- custody;
- reservation presence;
- condition;
- calibration;
- maintenance;
- incident;
- inspection gate.

No generic calibration override.

No automatic accusation from security/camera context.

---

# Slice 05 — Resource Reservation / Phase-4 Work Readiness Binding

Target migration:

`V0030`

Owns:

- AssetReservation transitions;
- StockReservation transitions;
- reservation expiration/release/fulfilment;
- stock reserved-quantity projection;
- Asset reservation collision protection;
- Project resource request -> Warehouse reservation context;
- Phase-4 Work resource availability adapter;
- reevaluate readiness from Phase-5 source version/reasons;
- PM resource-context surface;
- Warehouse reservation queue.

Phase-4 WorkRequirement remains authoritative Work requirement definition.

Phase 5 supplies resource facts only.

No physical issue/checkout yet.

---

# Slice 06 — Fulfillment / Checkout / Issue / Return / Transfer

Target migration:

`V0031`

Owns:

## Asset

- CheckoutAsset;
- ReturnAsset;
- TransferAsset;
- reservation fulfilment;
- AssetReturnInspection;
- accessory result;
- expected return;
- current custodian/project/site/work;
- collision behavior.

## Stock

- IssueStock;
- ReturnUnusedStock;
- TransferStock;
- authoritative ConsumeStock server command/source;
- issued-but-not-consumed projection;
- reservation fulfilment/release;
- recipient/project/site/work context.

Surfaces:

- Warehouse scan-first checkout/issue/return/transfer;
- recipient selection from current People/Project context;
- Asset/Stock movement receipt/history;
- due-return queue.

Phase-6 technician offline execution is still out of scope.

---

# Slice 07 — Receiving / Discrepancy / Serialized Intake

Target migration:

`V0032`

Owns:

- ReceivingRecord;
- ReceivingLine;
- source types PO / TRANSFER / RETURN / INITIALIZATION / OTHER;
- expected vs received quantity;
- condition;
- serial capture;
- discrepancy record;
- delivery-document reference;
- receiving StockMovement;
- Asset registration from receiving where policy says Asset;
- STOCK_SERIALIZED -> Asset creation/bridge only by explicit policy;
- receiving Windows/mobile scan surface.

No supplier external login.

No Finance posting.

Invoice/PO/source refs remain references to external/later authoritative domains.

---

# Slice 08 — Stocktake / Discrepancy / Adjustment

Target migration:

`V0033`

Owns:

- StocktakeSession;
- scoped snapshot;
- CountRecord;
- variance;
- InventoryDiscrepancy;
- AdjustmentRequest;
- Approval linkage;
- authorized ADJUSTMENT StockMovement;
- count/adjustment audit;
- Windows count/discrepancy/approval surfaces;
- mobile/handheld online count surface if supported by current app.

Rules:

- count never edits balance;
- adjustment never erases movement;
- requester/operator does not gain final approval implicitly;
- final adjustment is online-authoritative.

No uncontrolled offline warehouse lane.

---

# Slice 09 — Warehouse Command Center / Mobile Scan / Operational Closure

Default migration:

**NONE unless final gap review proves an invariant needs schema.**

Purpose:

turn the Phase-5 primitives into one coherent daily warehouse product without inventing new truth.

Owns read models/surfaces:

- Warehouse Home;
- Issues/checkout due today;
- Returns due/overdue;
- Reservations;
- low stock;
- blocked Assets;
- calibration/maintenance due;
- damaged/missing queue;
- receiving queue;
- stocktake/adjustment queue;
- import exceptions;
- recent movement/history;
- quick scan;
- Asset Passport;
- Stock detail;
- Project/Work resource drill-through.

Mobile:

- Quick Scan;
- identify Asset/Stock;
- authorized receive/checkout/return/transfer/count actions already owned by prior slices;
- search/manual fallback;
- RTL/adaptive proof.

No new “god dashboard” state table.

Command Center is projection/read composition.

Slice 09 closes with Phase-5-specific final gap review candidate.

---

# 5. Cross-slice source ownership

## Warehouse module owns

- Warehouse;
- StorageLocation;
- authority bindings;
- import staging;
- StockItem/UOM;
- StockMovement;
- StockBalance projection;
- StockReservation;
- issued-stock projection;
- Receiving;
- Stocktake;
- InventoryDiscrepancy;
- Adjustment workflow records.

## Assets module owns

- Asset;
- AssetType execution;
- AssetTag;
- AssetMovement;
- AssetCustodyProjection;
- AssetReservation;
- AssetIncident;
- CalibrationRecord;
- Maintenance/service record;
- AssetReturnInspection;
- derived Asset availability.

## Projects / Work remain owners of

- Project;
- Site;
- ProjectSite;
- WorkOrder;
- WorkRequirementInstance;
- Work readiness.

Phase 5 references them; it does not mutate their source tables directly.

## People remains owner of

- Person;
- Employee;
- Employment;
- WorkforceAssignment;
- Team;
- current identity/membership truth.

## Approvals remains owner of approval decision truth

Phase-5 adjustment/retirement records may reference Approval subject/outcome; they do not become a second approval engine.

## Finance boundary

Phase 5 emits/serves physical usage facts.

It does not own accounting valuation ledger.

---

# 6. Phase-4 integration path

Before Slice 05:

Phase-4 material/tool readiness remains explainably unresolved.

At Slice 05:

Phase-5 resource adapter supplies typed availability/reservation source truth.

Examples:

- STOCK_AVAILABLE;
- STOCK_SHORTAGE;
- STOCK_RESERVED_FOR_OTHER_WORK;
- ASSET_AVAILABLE;
- ASSET_RESERVED;
- ASSET_CHECKED_OUT;
- ASSET_CALIBRATION_EXPIRED;
- ASSET_MAINTENANCE_BLOCKED;
- ASSET_INCIDENT_BLOCKED;
- RESOURCE_LOCATION_NOT_ELIGIBLE.

Work readiness consumes reasons/source versions.

It does not edit Phase-5 availability.

---

# 7. Phase-6 integration path

Phase 5 must leave a clean contract for Phase 6:

- current issued Stock per recipient/Project/Site/Work;
- current Asset custody;
- resource return expectations;
- server-authoritative ConsumeStock;
- safe Asset/Stock scan lookup.

Phase 6 later owns:

- technician field intent;
- offline capture/queue;
- Evidence capture;
- execution lifecycle;
- sync/reconciliation.

---

# 8. Real-data gates

Not every real-data gap blocks Slice 01.

Required timing:

## Before Slice 01 merge

- confirm Main Warehouse initial code/name/location seed;
- confirm who receives initial Warehouse Manager/Operator bindings, or leave seed empty and provision through authorized admin setup.

## Before Slice 02 cutover/import activation

- approve UOM mapping for representative UTP/FIBER/ACTIVE rows;
- approve initial StorageLocation;
- approve import review of ambiguous rows.

## Before Slice 03 production Asset seed/import

- representative high-value Asset list;
- available serial/tag/current-location data.

## Before Slice 04 configuration activation

- identify which actual AssetTypes require calibration;
- representative calibration/service evidence if available.

Missing seed data must never be replaced with guessed values.

---

# 9. Whole-phase completion gate

Phase 5 is complete only when:

- all 9 Slices VERIFIED / MERGED;
- V0026–V0033 (or final actual forward sequence) migrate cleanly;
- authoritative stock never goes negative under collision tests;
- Asset double checkout has one winner;
- stale authority fails closed;
- reservation collision is deterministic;
- opening import is traceable;
- UOM ambiguity cannot silently pass;
- Asset/Stock current state rebuilds from movement truth;
- Phase-4 Work readiness consumes real Phase-5 resource truth;
- receiving does not fake Finance;
- adjustments are auditable/authorized;
- Windows Warehouse experience is coherent;
- Android/mobile scan experience is coherent where supported;
- no hidden hardware dependency;
- no Phase-6 offline field truth is fabricated;
- post-merge Bootstrap green;
- `PHASE5_FINAL_GAP_REVIEW = PASS`.

---

# 10. Freeze result

`PHASE5_SLICE_COUNT = 9`

`PHASE5_SLICE_ORDER = 01_TO_09`

`PHASE5_WHOLE_PHASE_PLAN = FROZEN_FOR_SLICE_CONTRACTS`

`PHASE5_PRODUCTION_CODE_AUTHORIZED = NOT_YET`

Production authorization begins only after the nine individual Slice contracts + completion-gates handoff are written and reviewed against this map.

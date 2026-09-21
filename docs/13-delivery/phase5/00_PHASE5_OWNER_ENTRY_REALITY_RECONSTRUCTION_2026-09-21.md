# Phase 5 — Owner Entry Reality Reconstruction

Date: 2026-09-21  
Status: **REALITY RECONSTRUCTION v0.1 / NO PRODUCTION CODE AUTHORIZED YET**

## 1. Purpose

Phase 5 owns the physical-resource truth that Phase 4 deliberately left unresolved:

- Assets;
- Warehouse / StorageLocation;
- StockItem quantity;
- reservations;
- custody;
- checkout / return / transfer;
- receiving;
- issue / consumption;
- damage / loss;
- calibration / maintenance availability;
- stocktake / adjustment.

This document reconstructs the current repository truth and the HILTECH operational reality before any Phase-5 production implementation begins.

The purpose is not to rediscover the warehouse domain from zero.

The purpose is to separate:

1. facts already frozen in the repository;
2. HILTECH reality already reported by the owner;
3. old contract items that are now stale;
4. real unresolved decisions that must be closed;
5. physical/process facts that still require representative validation.

No Phase-5 migration, API, command, UI or production service should be added until this reconstruction and the Phase-5 slice plan are closed.

---

# 2. Current program boundary

Phase 0–4 are VERIFIED / COMPLETE.

Current merged `main`:

`1382ee7da10d40c5909310e6ec81871be4c0b0ec`

Phase 4 intentionally projects unresolved Work material/tool requirements as:

`SOURCE_PENDING_PHASE5`

Phase 5 must replace that unresolved integration slot with authoritative resource truth.

Phase 6 remains separate and owns:

- technician StartWork / Block / Resume;
- field Evidence capture;
- SubmitCompletion;
- durable offline command queue / sync;
- process-death recovery for field execution.

Phase 5 must not pull those behaviors forward merely because warehouse/mobile scanning may later interact with them.

---

# 3. Repository reality already present

## 3.1 Existing schema foundation

`V0006__warehouse_assets__custody_stock.sql` already creates:

- `warehouse`;
- `storage_location`;
- `asset`;
- `asset_tag`;
- `asset_movement`;
- `asset_custody_projection`;
- `asset_return_inspection`;
- `stock_item`;
- `stock_balance`;
- `stock_movement`;
- `asset_reservation`;
- `stock_reservation`.

This is an early structural foundation, not proof that Phase-5 business behavior is implemented.

The production modules are currently only module placeholders:

- `server/.../assets/AssetsModule.kt`;
- `server/.../warehouse/WarehouseModule.kt`.

There is no completed Phase-5 command/service/API/UI vertical yet.

## 3.2 Existing contract sources

Canonical current inputs include:

- `docs/13-delivery/first-slice-contract-pack/12_ASSET_WAREHOUSE_CONTRACT.md`;
- `docs/06-data/object-specs/ASSET_STOCK_WAREHOUSE_OBJECTS.md`;
- `docs/06-data/transition-tables/ASSET_AND_WAREHOUSE_TRANSITIONS.md`;
- `docs/05-workflows/WAREHOUSE_FLOW.md`;
- `docs/03-product/role-experiences/WAREHOUSE_EXPERIENCE.md`;
- `docs/10-design/wireflows/WAREHOUSE_SCAN_WIREFLOW.md`;
- `docs/02-people/warehouse.md`.

Current source statuses matter:

- Asset/Warehouse contract: **CONTRACT CANDIDATE v0.2**;
- object model: **PRE-FREEZE**;
- transition table: **NOT FROZEN**;
- warehouse role experience: **NOT BUILD-READY**;
- warehouse flow: **RESEARCHING**.

Therefore V0006 must not be mistaken for a fully frozen Phase-5 implementation contract.

---

# 4. HILTECH reality already reported

The following is owner-reported operating reality and should be treated as a working Reality Register until contradicted by stronger company evidence.

## 4.1 Physical locations

- Main internal warehouse/storage is inside HILTECH's Al-Maadi headquarters.
- Remote projects may create temporary project/site storage.
- A reported example is cable/carton storage inside Alamein project buildings.
- Therefore the system must support:
  - permanent warehouse/facility storage;
  - nested storage locations;
  - temporary Project storage;
  - temporary Site storage.

This supports the existing Warehouse + StorageLocation semantic split.

## 4.2 Asset / stock character

- HILTECH holds tools/equipment whose combined value is very high.
- The number of major tools is relatively limited compared with their value/accountability risk.
- Work reality includes telecom/data-center/rack work, cable pulling, apartment-building projects and factories.
- Typical high-accountability equipment examples already modeled in the product include:
  - Fluke/test equipment;
  - OTDR;
  - fusion splicer;
  - laptops;
  - drills/toolkits.
- Materials/stock examples already represented in existing company data include broad groups such as:
  - Active;
  - Fiber;
  - UTP;
  - Accessories.

The real model therefore must preserve the Asset vs Stock distinction:

- high-value individually accountable equipment -> Asset;
- quantity-tracked consumables/material -> StockItem.

## 4.3 Current operational custody

Reported current operating pattern:

- Ahmed is a major operational coordinator around workers.
- Workers may receive tools/materials and, in some cases, work cash for an assignment.
- They travel to sites, perform work, document work/spending, return items and settle custody.
- Mohamed remains final management/oversight authority.

This does **not** mean “Ahmed” should be hard-coded into Phase 5.

It means Phase 5 must support current responsible-role / authority configuration while allowing the company's flexible roles to evolve.

## 4.4 Current access-control reality

Reported:

- warehouse access is difficult;
- many people may enter;
- at least one physical key is controlled informally by one person.

No reliable evidence currently establishes an installed HILTECH electronic access-control system.

Therefore:

- inventory movement/custody must work independently of future door hardware;
- camera/access events may later correlate with warehouse anomalies;
- access events must never replace checkout/movement truth;
- no Phase-5 command may depend on future badge/biometric/camera hardware.

## 4.5 Current records

Known historical/current evidence includes a mixture of:

- Excel-style inventory/work records;
- paper/manual practice;
- WhatsApp/photos;
- project/work evidence.

A previously reviewed stock Excel contained categories such as Active / Fiber / UTP / Accessories.

The current import reality is not yet authoritative enough to define the final master-data import contract.

Known modeling lesson from that review:

- item identity should not rely only on a free-text description;
- Brand / Manufacturer and Part Number should be available where relevant;
- legacy Excel is migration/input evidence, not the future authoritative stock ledger.

---

# 5. Reality not yet established

The following must **not** be invented.

We do not currently have authoritative evidence for:

- exact warehouse key-holder / official stock-responsible role mapping;
- full physical storage-location hierarchy;
- current item/asset count;
- current asset serial conventions;
- current QR/barcode/tagging usage;
- current asset code naming convention;
- exact checkout paperwork or sign-off;
- whether every tool issue requires Ahmed approval;
- exact return timing policy;
- damaged-return authority/decision flow;
- formal lost/missing process;
- actual calibration practice/certificate handling;
- actual maintenance process/vendor history;
- current receiving document flow;
- exact procurement-receiving handoff;
- current stock valuation source;
- lot/batch usage in real inventory;
- existing electronic warehouse access hardware;
- current camera/NVR warehouse coverage;
- employee badge scanning availability;
- printer/scanner/tag hardware;
- whether multiple formal warehouses exist today;
- whether temporary site storage has named responsible custodians;
- whether project supervisors can collect directly without warehouse operator confirmation.

These are representative-data/process validation questions, not reasons to restart the whole domain model.

---

# 6. Contract truths already strong enough to preserve

The following should be treated as strong current design truth unless Phase-5 validation proves a contradiction.

## 6.1 Asset vs Stock

Asset:

- individually identified;
- movement/custody history;
- condition;
- calibration/maintenance where required;
- persistent identity across transfers/repairs/tag replacement.

StockItem:

- quantity-based;
- unit of measure;
- location balance;
- reservation;
- issue;
- consumption;
- return;
- adjustment.

Do not model every cable/consumable unit as Asset.

Do not model a high-value accountable tool as anonymous stock quantity.

## 6.2 Asset lifecycle dimensions remain separated

Do not collapse:

- identity lifecycle;
- custody/location;
- physical condition;
- reservation;
- calibration;
- maintenance;
- missing/lost incident

into one giant editable status enum.

The current contract's separation is the correct direction.

## 6.3 Current custody is derived from movement

No command should directly edit:

- current custodian;
- current location;
- current Project/Site/Work context.

Those are projections from accepted movement/custody facts.

## 6.4 Stock correction is append-only/auditable

No silent balance edit.

Stocktake discrepancy -> reason / investigation -> authorized adjustment.

## 6.5 Physical access is correlation, not inventory truth

Door/camera events may support later investigation.

They do not prove an Asset movement by themselves.

---

# 7. Stale/open contract item cleanup

The current Asset/Warehouse contract ends with ten “open items”.

They must be reclassified before Phase-5 slice planning.

## 7.1 Warehouse vs StorageLocation table split

Current classification: **LIKELY ALREADY CLOSED / STALE OPEN ITEM**

Reason:

- contract Section 5 calls the semantic split frozen;
- V0006 already has separate `warehouse` and `storage_location`;
- HILTECH reality requires permanent + temporary Project/Site storage.

Remaining validation is not “do we need two tables?”.

Remaining validation is:

- exact location hierarchy;
- when a location requires a Warehouse parent;
- activation/retirement behavior;
- responsibility/access metadata.

## 7.2 Custody projection persistence strategy

Current classification: **PARTIALLY CLOSED / NEEDS TRANSACTION CONTRACT**

V0006 already contains `asset_custody_projection`.

Still must freeze:

- exact command transaction order;
- locking/concurrency;
- movement + projection atomicity;
- stale-version conflict behavior;
- correction/rebuild behavior;
- whether projection is synchronously transactionally updated or rebuild/event projected.

Hard requirement:

two simultaneous successful checkouts of one Asset must be impossible.

## 7.3 Reservation model Asset vs Stock

Current classification: **STRUCTURALLY CLOSED / TRANSITIONS STILL NEED FREEZE**

V0006 already contains:

- `asset_reservation`;
- `stock_reservation`.

Remaining work:

- exact states/transitions;
- collision/overlap rules;
- expiration;
- partial stock reservation;
- fulfil/release semantics;
- reservation -> checkout/issue linkage.

## 7.4 Quantity Decimal precision

Current classification: **SCHEMA CANDIDATE ALREADY EXISTS / UNIT POLICY STILL NEEDS VALIDATION**

V0006 uses:

`numeric(20,6)`

Need representative HILTECH data to validate units such as:

- piece;
- meter;
- roll;
- box/carton;
- possibly kg/other future units.

Question is not merely DB precision; it is unit conversion/allowed precision policy.

## 7.5 Serial uniqueness

Current classification: **CONTRACT DIRECTION CLOSED / DATA VALIDATION REQUIRED**

Current contract correctly avoids a blanket global unique serial constraint.

Need representative data to freeze:

- normalization;
- duplicate warning behavior;
- manufacturer/model scoped duplicate detection;
- AssetType-specific stricter policy where justified.

## 7.6 Accessory / return inspection

Current classification: **REAL OPEN ITEM**

V0006 already has `asset_return_inspection`, but exact accessory/result semantics need closure.

Need to determine:

- which AssetTypes require accessory checklist;
- condition vocabulary;
- missing accessory behavior;
- damage incident linkage;
- whether normal return completes custody before or after inspection;
- availability result after inspection.

## 7.7 Calibration exception policy

Current classification: **FIRST-SLICE DIRECTION ALREADY CLOSED**

Existing contract says:

- required expired/invalid calibration blocks normal issue;
- emergency override is deferred until HILTECH supplies a real use case plus explicit approval/audit contract.

Keep that fail-closed default.

Remaining validation:

- which actual HILTECH tools require calibration;
- certificate source;
- due date source;
- service provider/history.

## 7.8 Stock valuation/accounting boundary

Current classification: **REAL CROSS-PHASE OPEN ITEM**

Phase 5 owns physical quantity/movement/custody truth.

It must not become a second accounting ledger.

Need freeze of:

- what cost metadata Phase 5 may retain;
- what Project costing consumes;
- what later Finance phase owns;
- how receipt/invoice/PO references enter without duplicating Finance.

## 7.9 Offline return finalization

Current classification: **LIKELY CLOSED FOR FIRST PHASE-5 SLICE**

Existing contract says final physical return/custody transfer should remain authoritative online.

Do not inherit Phase-6 field offline semantics into warehouse simply for convenience.

A controlled offline warehouse lane should require separate demonstrated business need.

## 7.10 Master-data import / seed format

Current classification: **REAL OPEN ITEM**

Need representative source files before finalizing:

- StockItem import;
- Asset import;
- StorageLocation seed;
- categories/master data;
- manufacturer/brand/part number;
- unit codes;
- serials;
- opening balances;
- opening custody/current location.

Import must preserve traceability to legacy source and never silently transform ambiguous rows into authoritative truth.

---

# 8. Missing schema/domain objects versus V0006

V0006 is not yet the entire Phase-5 target.

The contract pack references additional domain truth that is not yet represented by complete production persistence/behavior, including:

- AssetIncident;
- CalibrationRecord;
- Maintenance/service history;
- ReceivingRecord / discrepancy;
- stocktake / count session;
- stock discrepancy;
- adjustment request/approval linkage;
- stronger tag primary/history invariant;
- derived Asset availability read model;
- replenishment request integration;
- procurement receiving references.

These require deliberate Phase-5 slice ownership.

Do not add all of them in one migration.

---

# 9. Phase-5 execution principles to freeze

1. PostgreSQL remains authoritative physical truth.
2. Movement/history is append-only.
3. Projections must be rebuildable from authoritative sources.
4. No negative authoritative stock.
5. No double checkout.
6. No reservation beyond available truth except explicit policy.
7. No hidden free-text recipient when a current identity/context exists.
8. Project/Site/Work references reuse Phase-4 truth.
9. People/custodian references reuse Phase-1–3 truth.
10. Phase-5 resource availability must become the source consumed by Phase-4 readiness.
11. Warehouse mobile should be scan-first where scanning exists, but search/manual fallback remains necessary.
12. No physical hardware dependency should block core inventory truth.
13. No supplier/customer external portal is required for first Phase-5 execution.
14. Finance ownership remains separate.
15. Phase-6 field execution/offline remains separate.

---

# 10. Immediate Phase-5 closure work

Before production code:

## A. Representative data validation

Review:

- actual stock Excel/source rows;
- representative high-value tools;
- representative consumables/materials;
- current receiving/issue/return evidence;
- any current serial/certificate examples;
- current storage layout.

## B. Exact decision closure

Freeze:

- custody transaction strategy;
- Asset/Stock reservation transitions;
- unit/quantity precision policy;
- return inspection/accessory behavior;
- stock valuation boundary;
- master-data import;
- opening balance/custody migration;
- receiving ownership;
- stocktake/adjustment flow;
- AssetIncident/calibration/maintenance first-slice ownership.

## C. Authorization matrix

Freeze who may:

- create Asset/Stock master;
- receive;
- reserve;
- issue/checkout;
- accept return;
- transfer;
- report damage/loss;
- inspect return;
- send/return calibration/maintenance;
- count;
- request adjustment;
- approve adjustment;
- retire asset;
- view restricted acquisition/value data.

Use roles/relationships, not hard-coded people.

## D. Whole-phase slice plan

Only after A–C:

freeze the complete Phase-5 slice map before production implementation.

---

# 11. Current readiness assessment

The Phase-5 domain is **not a blank sheet**.

It is structurally mature enough that full rediscovery would be wasteful.

However it is **not yet production-build-ready as a phase**, because:

- representative HILTECH warehouse data is not fully validated;
- transition tables remain explicitly NOT FROZEN;
- several cross-object transaction semantics remain open;
- V0006 is structural groundwork, not behavior closure;
- real warehouse responsibility/process/hardware facts are incomplete.

Therefore:

`PHASE5_DOMAIN_REDISCOVERY_REQUIRED = NO`

`PHASE5_REALITY_VALIDATION_REQUIRED = YES`

`PHASE5_EXACT_CONTRACT_CLOSURE_REQUIRED = YES`

`PHASE5_SLICE_PLAN_FROZEN = NO`

`PHASE5_PRODUCTION_CODE_AUTHORIZED = NO`

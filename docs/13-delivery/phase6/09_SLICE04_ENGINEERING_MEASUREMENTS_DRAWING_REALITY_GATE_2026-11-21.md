# Phase 6 / Slice 04 — Engineering Measurements & Drawing Reality Gate

Date: 2026-11-21  
Status: **SOURCE REALITY BLOCKED / CONTRACT-ONLY / NO INVENTED MEASUREMENT SCHEMA**  
Stacked engineering checkpoint: PR #122 → PR #123 → PR #124.

## 1. Why this checkpoint exists

Slice 04 now has engineering implementations for:

- own issued material read (S04A, merged earlier, default OFF);
- immutable field material usage report;
- warehouse-scoped pending review read;
- append-only reconciliation to the canonical Warehouse `CONSUME` path;
- selected-WorkOrder AssetTag/manual lookup that narrows, rather than expands, canonical Asset authorization.

The remaining S04C scope is **not another generic form**. It is the HILTECH-specific engineering measurement + approved drawing context required for real OTDR/Fluke/other technical work.

This file records why S04C cannot be honestly closed from the repository alone and exactly what evidence unlocks it.

## 2. Current verified implementation state

### Material usage

- PR #121 is merged: immutable `PENDING_REVIEW` field material usage reports, no stock mutation.
- PR #122 is Ready for Review, default OFF:
  - exact-origin Warehouse review queue;
  - unauthorized candidate paging cannot starve later visible reports;
  - exact-head 7/7 workflows PASS.
- PR #123 is Ready for Review, default OFF:
  - V0035 append-only `field_material_usage_reconciliation`;
  - one terminal reconciliation per immutable report;
  - `CONSUME` delegates to existing `StockFulfillmentService.consume`;
  - `REJECT` creates no StockMovement;
  - Work / issued source versions rechecked;
  - reconciled reports leave pending review and pending-quantity calculations;
  - unit + PostgreSQL/OpenFGA canonical consume scenario PASS.

No new field technician Warehouse permission was invented. Production reviewer authority and operator walkthrough remain independent gates.

### AssetTag / QR-manual lookup

PR #124 is Ready for Review, default OFF:

- current assigned user + existing WorkOrder `can_view`;
- reuses `AssetPassportService.lookup`, so no new field Asset permission;
- current custody must reference the exact selected WorkOrder;
- active tag + custody source are rechecked after lookup;
- returned field projection strips location code, employee code and custody timestamp;
- no checkout, custody, tag, stock or Work mutation;
- manual opaque-code entry remains valid without camera/scanner hardware;
- server tests, shared client tests and Android compile with the feature explicitly enabled PASS.

## 3. What already exists for S04C

### Evidence

Phase 6 S03 already supplies durable WorkOrder Evidence capture and private upload/finalize semantics.

`evidence_policy_requirement` currently gives the field runtime:

- requirement key;
- evidence type code;
- stage;
- min/max count;
- offline capture permission;
- allowed content types.

This can safely carry approved photo/file evidence once a real WorkType policy identifies what is required.

### Drawing surface shape exists, authoritative source does not

The assigned JobBundle already includes `safeDocumentRefs` and Android already knows how to render document code/title/revision.

But `FieldAssignedWorkPersistence.safeDocumentRefs()` currently deliberately returns `emptyList()` and states:

> Phase 0-4 has Evidence metadata and document requirement definitions, but no authoritative Project/Work document-revision source. Slice 06 must not fabricate a current drawing/document.

Therefore the UI shape is not evidence that an approved/current drawing source exists.

### Product contract expects typed measurements

The configurable product model describes an EvidencePolicy candidate with a measurement/test schema reference and makes photos / OTDR / Fluke / signatures / checklists configurable requirements rather than universal rules.

That is architectural intent, not a frozen HILTECH measurement schema.

## 4. Additional source search performed

A second source search was performed across:

- the HILTECH repository;
- local HILTECH project folders;
- Desktop project folders;
- Documents / historical Codex HILTECH material;
- Downloads.

The search found historical HILTECH/company material and copper/fiber imagery, but no source package that freezes:

- a real OTDR result format;
- a real Fluke result format;
- measurement names/dimensions/units;
- numeric tolerance or pass/fail rules;
- instrument/calibration provenance fields;
- approved drawing identity/revision lifecycle;
- stale/revoked drawing handling;
- engineering reviewer authority for those results.

Accordingly, this is a confirmed reality gap rather than an unsearched implementation TODO.

## 5. Minimum source packet that unblocks S04C

S04C implementation can resume as soon as the following **representative evidence** is available. It does not require a complete company manual.

### A. One real executed technical test

Provide one real example for every test family that HILTECH actually uses in this first vertical, for example OTDR or Fluke.

For each real test, source evidence must reveal or an engineer must confirm:

- WorkType / task it belongs to;
- measured object: fiber link, copper link, port, device, rack, site point, etc.;
- test/measurement field names;
- canonical unit and dimension;
- precision/rounding;
- acceptance/tolerance semantics;
- whether pass/fail is instrument-generated, engineer-reviewed, or server-calculated;
- repeated test / retest / correction behavior;
- raw machine file / PDF / image / typed-value relationship;
- measured-at timestamp source;
- instrument identity;
- whether calibration must be current;
- required reviewer.

No field should be inferred from another vendor's generic form.

### B. One real drawing revision chain

At minimum:

- one drawing/spec reference used on a WorkOrder;
- two revisions or an explicit current + superseded example;
- source system/file location;
- who marks a revision approved/current;
- who may view/download it;
- whether field offline caching is allowed;
- what happens when a cached revision is replaced or access is revoked.

### C. Authority answer

Name the actual relationship that may accept/reject the technical result for the representative WorkType:

- engineer;
- supervisor;
- PM;
- another source-defined role/relationship.

Do not replace this with a guessed title.

## 6. Implementation contract after source arrival

Once the representative source packet is supplied, the smallest honest implementation is:

1. Freeze one WorkType-specific measurement/test schema revision.
2. Bind that schema revision through the existing Work policy/Evidence context rather than a universal measurement table.
3. Capture typed values and/or machine artifact with:
   - WorkOrder;
   - assignment/source version;
   - schema revision;
   - measured object;
   - unit;
   - instrument/calibration source when required;
   - client occurrence + stable operation ID.
4. Keep offline entry as pending local intent/evidence.
5. On server apply:
   - reauthorize current Work/assignment;
   - validate exact schema revision;
   - validate unit/dimension/precision;
   - validate required instrument/calibration source;
   - preserve raw evidence;
   - apply source-approved tolerance/pass/fail semantics only.
6. Never overwrite a previous result. Correction/retest creates a new revision/supersession link.
7. Drawing context records the exact approved revision used by the test; a newer/revoked revision creates a stale/conflict state, not an implicit overwrite.
8. Feed only accepted authoritative technical results into later SubmitCompletion / handover paths.

## 7. Required proof before S04 can close

- real WorkType fixture from HILTECH source;
- engineer-reviewed field/unit/tolerance mapping;
- PostgreSQL source/version/concurrency tests;
- OpenFGA current assignment + reviewer authority tests;
- offline process-death/replay tests;
- wrong WorkOrder/object/schema/unit/revision rejection;
- instrument calibration block where source requires it;
- drawing stale/revoked revision behavior;
- Android native capture/review evidence;
- supervisor/engineer walkthrough;
- exact-head CI;
- independent gap review.

## 8. Slice 04 closure decision

**Engineering that can be sourced today has been pushed through material usage/reconciliation and WorkOrder-scoped AssetTag lookup.**

S04C structured measurement and authoritative drawing revision code is intentionally **not created** because the required HILTECH source facts do not exist in the inspected sources.

Do not:

- invent OTDR/Fluke field names;
- invent units/tolerances;
- invent a generic PASS/FAIL formula;
- treat arbitrary PDF/image upload as a structured measurement;
- fabricate a drawing revision source;
- grant an engineer/reviewer role by title alone;
- mark Slice 04 complete;
- advance to S05 as if the S04 source gate were satisfied.

### Current state

`P6_S04_MATERIAL_ENGINEERING = IMPLEMENTED_DEFAULT_OFF_PENDING_REVIEW`  
`P6_S04_ASSETTAG_ENGINEERING = IMPLEMENTED_DEFAULT_OFF_PENDING_REVIEW`  
`P6_S04C_MEASUREMENT_SOURCE = NOT_AVAILABLE`  
`P6_S04C_DRAWING_SOURCE = NOT_AVAILABLE`  
`P6_S04C_IMPLEMENTATION = BLOCKED_BY_REALITY`  
`P6_S04_COMPLETE = NO`

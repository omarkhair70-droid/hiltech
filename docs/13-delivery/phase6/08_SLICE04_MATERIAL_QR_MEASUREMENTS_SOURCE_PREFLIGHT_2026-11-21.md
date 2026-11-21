# Phase 6 / Slice 04 — Material Usage, Tag Lookup, Measurements, Drawings: Source Preflight

Date: 2026-11-21
Status: **CONTRACT-ONLY / NOT FROZEN / NO S04 PRODUCTION CODE**
Source main: `13c49c8b07060bd5495f6dcb8f219efe18ffb5c5`
Dependency: Phase 6 S03 PR [#115](https://github.com/omarkhair70-droid/hiltech/pull/115) must pass its merge gate before S04 code is authored against the merged base.
Owner/operator evidence: [#116](https://github.com/omarkhair70-droid/hiltech/issues/116)
S04 open decisions: [#117](https://github.com/omarkhair70-droid/hiltech/issues/117)

## Why this slice exists

Technicians need to record which **already-issued** materials they used, consult safe asset tags and approved drawings, and record WorkType-specific technical measurements. None of those screens may mint stock, invent custody, change a StockBalance, or become a shadow source of truth. This is the next bounded field layer over canonical Phase 4 Work and Phase 5 Warehouse.

## Reused source / source authority map (verified from main)

| Capability | Existing contract | Authority and limitation |
| --- | --- | --- |
| Assigned Work | `FieldAssignedWorkService.jobBundle`, `GET /v1/work-orders/{id}/job-bundle` | Current technician assignment, WorkOrder version, policy binding and safe requirements. Resource requirement metadata here does **not** prove issued quantity. |
| Stock base unit / truth | `StockLedgerPersistence`, `WarehouseWorkResourceSource`, Phase 5 Slice 02 Stock Master/UOM/Ledger | One canonical `baseUnitCode` per item, positive exact-decimal quantity with up to 6 fractional digits, no implicit reel/box/PC conversions; append-only movement and rebuildable balance. |
| Stock issue / consume / return | `StockFulfillmentApi.kt`, `StockFulfillmentService.kt` | `POST /v1/stock-items/{id}/issue`, `/consume`, `/return-unused`; `GET /v1/stock-items/{id}/issued-context?workOrderId=...`. Each existing mutation is warehouse-authorized, idempotent, version-locked. **Do not call it as a technician by assuming warehouse permission.** |
| Reservations / readiness | `WarehouseWorkResourceSource.kt` + current Work source | Reservation and physically-issued facts may satisfy resource readiness, with location/project/site and stock/asset restrictions. This is not proof that the technician has consumed the material. |
| Asset tag / safe passport | `AssetPassportApi.kt`, Phase 5 Slice 03 Asset Passport | `GET /v1/assets/by-tag/{opaqueCode}` uses an opaque tag, and Asset IDs survive tag replacement. Existing endpoint's **current authorization may be warehouse-oriented**; technician visibility must be established independently. Manual search fallback required. |
| Documents | `FieldAssignedWorkService` safe document refs and source revision | Read-only document identity/revision is known; no authorized local drawing edit/overwrite contract is established. |
| Technical measurement | **Not found as a canonical S04 WorkType-specific source in inspected contracts** | No invented universal OTDR/Fluke form, thresholds, calibrated instrument identity or pass/fail math. Requires approved WorkType and field engineer source. |

## Slice boundary and safe delivery sequence

### S04A — Source-bound read-only field view (first implementation candidate after S03 merge)

1. On opening an **online-current** assigned JobBundle, show the existing Work resource requirements and their current state/reason/version, with honest `SOURCE_PENDING`/unavailable/unknown labels where issued material details have not been authorized.
2. Design a **new, scoped server read projection only if needed**, restricted to this WorkOrder's current assigned actor and permitted issued context: `stockItemId`, `requirementId`, `issuedQuantity`, `remainingIssuedQuantity`, canonical `unitCode`, exact `issuedVersion`, source movement reference and source-as-of. The existing `GET issued-context` is warehouse-authorized; do not expose it directly as a field endpoint without a separate capability/visibility proof.
3. Reuse existing tag lookup only with evidence of a current field-readable permission. Render a safe, redacted Asset identity and replacement-state notice; a QR scan is **lookup**, never checkout/transfer/consume. Provide manual opaque-code entry fallback. Do not mistake an SKU/barcode for a serialized AssetTag.
4. Show approved safe drawing/document reference and revision as **read-only**, with stale or withdrawn notice on refresh. Never alter original drawing bits or mark a local copy authoritative.

**S04A is read-only. No stock DB mutation, new permission by default, Room queue, QR-write path, or camera/location requirement.**

### S04B — Durable material usage intent and canonical reconciliation (separately frozen)

1. Once the source can answer `issued` and `remaining` with authorized exact versions, record an immutable, actor/device-scoped **usage intent** with operation UUID, WorkOrder and assignment source versions, requirement, item, canonical decimal quantity/unit and timestamp.
2. On reconnect, identity and source authority must be revalidated. The existing warehouse `Consume` flow is *not* automatically a field technician API. Freeze whether field reporting proposes a supervisor/storekeeper reconciliation or a purpose-built policy-authorized server command. No permission escalation by reusing warehouse actors.
3. Only a successful, idempotent, locked authoritative server transaction records real usage/consumption. Pending/rejected intents stay **pending** or require review; they never reduce a local fake StockBalance.
4. Conflicts: wrong WorkOrder/actor/device, revoked or reassigned WorkOrder, stale `workVersion`, stale `issuedVersion`, wrong UOM, amount exceeding issued-remaining, concurrent return/consume, superseded reservation, replay of same operation UUID, unexpected source policy change.
5. For stock trace, keep the original issuance, return, consumption and originating storage-location chain; verify movement/issued projection conservation under concurrent calls.
6. Room migration only after the source contract is frozen, with process-kill and cross-identity non-leak tests.

### S04C — Engineering measurements and drawing context (reality gate)

Do not code a generic numeric form. Engineering owner must provide representative site/WorkType details:
- Measurement name, dimension, canonical unit, min/max or tolerance semantics, precision/rounding, calibration/instrument provenance and responsible reviewer;
- Whether input is manual, machine-generated (e.g. OTDR/Fluke), photo/file-backed, or typed structured evidence;
- Measured-at versus device clock, test repetition, retest/correction and supersession rules;
- Approved drawing ref/revision, who may access it offline and how revoked/stale revisions are surfaced.

A field measurement can be **captured as a source-bound intent** only after this schema is frozen. It must not claim `PASS` or update WorkOrder readiness merely from local input without canonical server validation.

## Hard invariants

- Current assignment + WorkOrder/Policy and server FGA remain source of truth; stale `Today` cache is display-only, never authorization to write or expose restricted stock/asset information.
- Exact decimal string / canonical base unit. No silently converted pack lengths, cable reels or inferred units.
- No negative stock, fabricated movement, synthetic issue/consumption, or direct StockBalance edit.
- Opaque AssetTag only; wrong, revoked or replaced tags never cause mutation.
- Missing scanner hardware and no-camera sites must have permitted manual/read-only routes; never introduce mandatory camera/location permissions.
- No financial values in field read projections unless independently authorized.
- Source loss, role revocation, reassignment and policy changes fail closed; preserve legitimate local pending evidence for supervised recovery.
- No generic measurement PASS/FAIL calculation or typed form Evidence without an approved WorkType schema.

## Tests / proof needed before calling S04 done

| Gate | Minimum evidence |
| --- | --- |
| Authority | PostgreSQL/OpenFGA current assigned actor can see only allowed WorkOrder material/tag context; wrong actor, org, warehouse, WorkOrder, revoked assignment denied. |
| Conservation | Exact canonical quantity, positive amounts, no negative issued-remaining, duplicate idempotent replay and two-client race; warehouse ledger and issued projections reconcile. |
| Offline | Room replay/process death, no cross-user data exposure, revoked identity pause, stale version conflicts, network failure retry without extra consumption. |
| Asset tag | Unknown/replaced/foreign tag, manual fallback, safe redaction and no unauthorized custody writes. |
| Drawings | Revision change, expired document/read permission, no implicit overwrite and no stale-cache authorization. |
| Engineering | Real WorkType measurement fixture and engineer-approved thresholds/units/instrument requirements before any numeric PASS/FAIL. |
| Delivery | Exact-head checks, dedicated native Android/Windows proof, independent diff and explicit technician/supervisor acceptance. |

## Explicit no-go / next approval

**No S04 production code, migration, stock mutation endpoint, invented FGA permission, issuance reversal, or automatic material consumption in this contract PR.**

Before implementation, close [#115](https://github.com/omarkhair70-droid/hiltech/pull/115) per its stated merge gates; then rebase a bounded S04A implementation PR on the merged `main`. Real site WorkType data, stock usage authority and drawing permissions remain open in [#117](https://github.com/omarkhair70-droid/hiltech/issues/117).

`P6_S04_SOURCE_PREFLIGHT = DOCUMENTED`
`P6_S04_CONTRACT_FREEZE = PENDING_OWNER_REVIEW`
`P6_S04_IMPLEMENTATION = NOT_STARTED`
`P6_S04_PRODUCTION = NOT_AUTHORIZED`

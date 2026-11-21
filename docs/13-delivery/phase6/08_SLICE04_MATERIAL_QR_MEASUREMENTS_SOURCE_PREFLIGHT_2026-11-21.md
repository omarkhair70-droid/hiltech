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

## Concrete S04A source and identity proof (review checkpoint — corrected)

The previous draft incorrectly said that no verified identity-to-employee mapping was found. **The canonical mapping already exists and is explicitly implemented.** It must be reused, not reconstructed from names, emails, employee codes or phone numbers.

### Authoritative source chain (verified from main)

- `database/migrations/V0015__people_employee_employment__core.sql` defines `user_identity.person_id → person.id ← employee.person_id`. `employee` has a **unique `(organization_id, person_id)`** constraint, so within one organization the Person resolves to at most one Employee. This does **not** imply that every identity has an Employee.
- `PeoplePersistence.loadOwnEmployee(identityId, organizationId, at)` already implements exactly that Person link and checks `user_identity.status = ACTIVE` plus a current `organization_membership`. `WorkforceAssignmentPersistence.ownAssignment` independently uses the same Person/Employee link for an actor's own assignment. Reuse these existing semantics; do not expose HR data or use People admin directory permissions as a shortcut.
- `WorkAssignmentAuthorization.currentAssignmentRows` checks that the current actor participates in the **actual WorkOrder assignment** (USER/TEAM/CREW/SUBCONTRACTOR_ORGANIZATION), including current user and organization membership. `FieldAssignedWorkService.requireCurrentAssignedRead` combines this source check with the WorkOrder-scoped `can_view` authorization.
- `StockFulfillmentPersistence.issued(item, work)` reads canonical `stock_issued_balance`, whose key includes `stock_item_id`, `employee_id` and `requirement_id` and whose row additionally binds `organization_id`/`project_id`/`site_id`/`work_order_id`. Migration `V0031__physical_fulfillment__slice06.sql` enforces nonnegative `outstanding_qty numeric(20,6)` and connects the requirement to this WorkOrder. The row exposes **outstanding**, not total historical issued quantity or consumption.
- `StockFulfillmentService.issued` is a separate **warehouse-authorized** API with origin-location checks. Field read must not reuse that endpoint as if `can_view` meant `canReserveResources`/`canTransferStock`.

### Scoped Field read algorithm to freeze before code

1. On a new **online** request with a WorkOrder ID, verify authenticated identity, current active assignment for that WorkOrder **and** WorkOrder `can_view` through existing Field assignment/authorization ports; load WorkOrder's current organization/project/site source. Any failed gate returns the existing not-visible response, not stock rows.
2. Resolve exactly one `employee.id` in the same organization by `user_identity.person_id = employee.person_id`, using current active membership and identity status from `loadOwnEmployee` semantics. Require an **ACTIVE Employee and current eligible employment**, if production fulfillment requires it; do not treat an external subcontractor organization membership as an employee custody identity. Absent or ineligible Employee means **no authorized personal issued-stock projection**; never fall back to the crew, project or warehouse directory.
3. Read **only** `stock_issued_balance` rows constrained to `organization_id`, `work_order_id`, the WorkOrder's project/site and `employee_id`. Verify each `requirement_id` belongs to that WorkOrder and its current MATERIAL resource request. Join the canonical StockItem **base UOM** and expose `outstandingQuantity` as an exact decimal string, source `version` and `asOf`; do not fabricate `issuedQuantity`, `usedQuantity` or physical warehouse balances.
4. Return a per-request safe projection, not an employee directory and not a stock movement audit trail. No HR details, item costs, source bins, payroll identifiers, other technicians' balances or stock mutation buttons.
5. If authorization changes or the actor's employment is revoked/reassigned, deny next online read and never treat cached WorkOrder/Room content as write authority. Treat `no rows` separately from `not permitted`; don't infer that `outstanding=0` proves nothing was ever issued.

### Negative and concurrency matrix required before S04A implementation

| Scenario | Required result |
| --- | --- |
| Same active assigned actor, matching Employee/person/org and own issued row | Only that Employee's outstanding quantity/version/base UOM shown |
| Another Employee on same WorkOrder or a member of the same crew | Other Employee's issued rows **not visible** |
| TEAM/CREW membership without matching personal Employee | Do not infer custody from team/crew or return another person's stock |
| SUBCONTRACTOR_ORGANIZATION assignment without employee link | No personal stock rows; fail closed |
| Unassigned/revoked WorkOrder, denied `can_view`, inactive membership or user | No existence or stock disclosure |
| Employee OFFBOARDING/FORMER, ended employment (per active fulfillment source policy) | No new authorized personal issued-stock read |
| Same Person in separate organizations | Resolve only the WorkOrder organization; never cross org |
| Inactive/changed stock item or source version | Show source state as authoritative, never invent a consumed quantity |
| Warehouse returns/consumes concurrently with read | Display a versioned snapshot only; no local stock mutation or fabricated consistency |
| Repeated requests or malformed WorkOrder ID | Read-only, no ledger mutation; invalid/foreign ID fails closed |

This is an architectural **source proof**, not an approved permission expansion or tested S04 endpoint. New implementation and PostgreSQL/OpenFGA negative tests require S03 merge and explicit S04A source contract acceptance.

## Slice boundary and safe delivery sequence

### Reuse check — do not rebuild screens that already exist

`shared/core/src/commonMain/kotlin/com/hiltech/shared/core/AssignedWorkSection.kt` **already renders** the assigned JobBundle `resourceRequirements` with integration reason codes and `safeDocumentRefs` with document revision. These are existing safe Work projection surfaces, not S04 functionality to implement again. `FieldEvidenceCaptureControls` already distinguishes local files from server READY separately.

Therefore the first *genuinely new* S04A engineering unit is not another generic "Materials" card. It is the independently authorized **field-issued-stock read projection** (or an explicit source-unavailable state if identity-to-employee custody binding remains unresolved), plus a safe asset-tag lookup in the assigned Work context only after checking the field actor's tag visibility. Avoid copying the warehouse query/authorization blindly.

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

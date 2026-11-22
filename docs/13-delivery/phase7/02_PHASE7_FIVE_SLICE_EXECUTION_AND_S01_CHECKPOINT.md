# Phase 7 — Procurement implementation map, revised after Phase 6 integration

Date: 2026-11-22
Baseline: main at 2947618203fd6b3e8d0ce937f5cee8d41e6e9405
Status: **FIVE-SLICE EXECUTION MAP / RELEASE RULES OPEN / NO LIVE PURCHASE AUTHORIZED**

This revises the earlier seven-slice proposal in PR #139 without deleting any business capability.
Each vertical slice owns its required UX, permissions, audit and tests.

| Slice | Boundary | Exit criteria |
| --- | --- | --- |
| S01 | PurchaseRequirement and stock-before-buy | Versioned, permission-safe request, authoritative stock snapshot, race/staleness handling, Windows/Android role behavior |
| S02 | Supplier RFQ + quotes + comparison | Evidence, confidential supplier quotes, revision history, explainable human sourcing choice |
| S03 | Approval + PO issue, version and cancellation | Canonical ApprovalEngine, current quote/spec/amount binding, immutable issued PO |
| S04 | PO delivery + canonical Warehouse receipt | Delivery line references, partial/rejected/over-delivery, no second Stock ledger |
| S05 | Three-way-match evidence + Finance handoff | PO vs actual receipt vs invoice, discrepancies, no payable/payment posting |

## Mandatory Phase 7 Final Gap Review

After S05, exercise one complete role-authorized journey:
Work/Project demand -> Warehouse stock check -> supplier sourcing/comparison ->
version-bound approval -> PO -> partial warehouse receipt/discrepancy ->
invoice evidence -> Finance handoff. Include Windows/Android, audited exception
handling, offline classification, backups/rollback and purchaser/approver/
warehouse acceptance on authorized devices.

There is no separate late "UX completion" slice. Every slice owns its full required role experience.

## Non-negotiable domain invariants

- An internal PurchaseRequirement is NOT a PurchaseOrder, StockReservation,
  StockMovement, SupplierInvoice, Payable or payment authorization.
- Reuse Work/Project, Warehouse balances/reservations, ApprovalEngine,
  Documents/Evidence, idempotent command runtime and audit. No duplicates.
- The existing canRequestResources permission is only preliminary *demand*
  authority, never authority to issue or approve a PO.
- Finance owns authoritative payables and payments; external supplier login is later.
- No invented HILTECH procurement policy, threshold, supplier terms or PO numbering.
- Offline procurement and PO release commands are not authoritative by default.
- Preserve exact-head tests, source-current authority and real-work acceptance gates.

## S01 initial engineering checkpoint — intentionally bounded

The source checkpoint supports an **internal DRAFT** material StockItem
request sourced from an authorized WorkOrder, plus an append-only requester
withdrawal (V0040) which preserves the draft evidence. It does not submit for sourcing,
approve, purchase, reserve, move goods, email suppliers or post Finance data.

Canonical ReservationPersistence reads current reserved stock and available balance.
The server checks work and stock versions, organization and per-location permissions.
The immutable, timestamped snapshot is advisory only and must be rechecked before
any future sourcing/approval/PO decision. Unknown or hidden storage results in
WAREHOUSE_REVIEW_REQUIRED, never leaked balances or an invented zero amount.

Endpoints:
- POST /v1/work-orders/{workOrderId}/purchase-requirements
- GET /v1/work-orders/{workOrderId}/purchase-requirements
- GET /v1/purchase-requirements/{id}
- GET /v1/purchase-requirements/{id}/review-preview
- POST /v1/purchase-requirements/{id}/withdraw

The review-preview endpoint is a side-effect-free, permission-scoped snapshot:
it rechecks current WorkOrder/StockItem versions and stock-unit activation,
refreshes canonical Warehouse free capacity subject to location visibility,
and warns of up to 20 other non-withdrawn DRAFTs of the **same exact work
order and stock item** (with a truncation flag when there are more).
Stale/withdrawn sources do NOT return any current quantitative stock claim.
This is advisory only: no purchase approval, duplicate hard-block, supplier
selection, stock reservation, stock movement or persistent state mutation.
The shared KMP client exposes the identical GET contract to Android/Windows.
The single shared Compose operator surface
`shared/core/.../procurement/PurchaseRequirementOperatorSection.kt` is now
routed through both signed-in Android (selected field WorkOrder) and Windows
(project WorkOrder picker). Its stock picker reads canonical Work resource
catalog and current StockItem versions; the WorkOrder version comes from the
canonical Work API. It supports save/list/review/withdraw without arbitrary
UUID entry, offline writes, stock movements or PO issue.

There are TWO independent OFF-by-default gates:
- Server: `hiltech.procurement.requirements.enabled=false`.
- Android build UI: `hiltech.procurementRequirementsEnabled=false` unless
  explicitly set at build time.
- Windows UI: absent/false `HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED`
  environment variable. No production enablement from this PR.

The existing HILTECH Shell does not mount this operator in signed-out or
offline read-only mode. Read/list actions require server permission. The
server's canWithdraw boolean is only UX guidance; every withdrawal is
authorized again server-side. UI integration does NOT equal operator
acceptance; no actual company policy or purchaser handoff is live.

All default OFF: hiltech.procurement.requirements.enabled=false.
POST requires authentication, matching Idempotency-Key, current project demand
authority, expected WorkOrder/StockItem versions, valid canonical unit and quantity.

The five engineering slices are not five mandatory user-facing steps.
An ordinary purchase must be low-friction; policy-gated approvals and exception
routes are handled through the canonical shared engine only when warranted.
See 03_PROCUREMENT_FLEXIBILITY_AND_LIFECYCLE_DELTA.md for a grounded
confirmed/correction/missing review of the owner-requested flexibility.

This checkpoint DOES NOT complete S01. The current shared KMP
`shared/core/.../procurement/PurchaseRequirementApiClient.kt` implements the
authenticated, online-only Android/Windows transport for current WorkOrder
DRAFT create/list/detail/withdraw; it is NOT yet a routed operator UI.
The server canWithdraw boolean is derived from the original requester/current
state and remains advisory; server authority is always rechecked.

Later S01 work: additional demand sources (asset/service/office), purchasing
review decisions and transitions, duplicate detection, role-specific action inbox,
wired native Windows/Android journeys, real request and approval/stock examples.

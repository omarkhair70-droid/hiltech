# Phase 7 / Slice 04 — PO-linked canonical Warehouse receiving

Date: 2026-11-22
Status: **initial guarded engineering implementation; NOT S04 closure or an approved company rollout**
Dependency: S03 PR #153 (Draft) -> this stacked S04 branch.

## Boundaries implemented in this checkpoint

- V0050 adds an immutable, company-scoped provenance link from the **existing** `receiving_record` to an actual `procurement_purchase_order`, preserving canonical Warehouse/StockMovement as the ONLY physical ledger.
- A new opt-in server adapter `ProcurementPoReceivingGuard` validates a PO UUID as source (not an arbitrary text label), the PO's current non-cancelled lifecycle, matching company and effective storage destination, and the exact ordered StockItem/unit.
- Under the enabled guard, the existing `ReceivingService` checks the PO at record creation, line creation, physical stock receipt and completion. PO-linked records cannot receive asset lines for a stock-only PO.
- Each physical stock receipt holds the PO row lock and sums already posted canonical `receiving_line.received_quantity` across ALL linked delivery records. A concurrent second receipt cannot exceed ordered quantity; over-delivery is rejected before StockMovement posting, so the warehouse must log a discrepancy instead.
- PO cancellation now refuses orders with already posted physically received stock. The check shares the parent PO lock with receipt posting.
- Other Warehouse receiving source types retain their existing code. The new guarded PO pathway is default-OFF; legacy Phase-5 manual PO text references remain ungoverned until migration/release policy is agreed.

## Safety limits / remaining for Slice 04

This is NOT complete fulfillment. S04 still needs an operator-facing PO intake picker, verified delivery-document Evidence, explicit partial/complete/rejected status, shortage/excess/damage discrepancy UX, proper access-revocation and cross-org integration tests on real PostgreSQL, Android and Windows acceptance, exact-head CI, and reconciliation of legacy manual `source_type=PO` references without corrupting history. No supplier has been sent an order by this code. The S03 dispatch and commercial replacement policies remain pending.

This adapter is gated by `hiltech.procurement.po-receiving.enabled=false` by default. No synthetic user, vendor, approval, PO, receipt or payment is provisioned by this change. Until an independently authorized company pilot, do NOT enable this flag.

## Next dependency

S05 must use the canonical PO V1 plus current effective state, **actually posted** receipts and trusted supplier invoice Evidence for explainable three-way-match/handoff to Finance. It must NOT create a payable, settle a liability, post a payment or infer that a supplier invoice was accepted simply because a quantity matched.

## Integration extension — V0054–V0057 (verification pending)

The initial S04 checkpoint above has since expanded through the stacked Phase 7 integration PR #158.

- V0054/56: physical rejected-line event and discrepancy resolution, with exact full-line quantity enforcement. Rejected goods never post as usable StockMovement; separate partial deliveries use separate canonical Receiving records.
- **V0057 (new)**: private, SHA-256-verified `PO_RECEIVING_DELIVERY_NOTE` Evidence bound by both ID and organization to exactly one `procurement_po_receiving_link` record. Existing Evidence storage validates MIME, content hash, security scan and READY state. Quarantined/rejected upload attempts can be retried; only one READY note per receipt is allowed.
- The canonical PostgreSQL Receiving transition itself rejects closing a governed PO receipt without a READY note; `ReceivingService.complete` enforces the same rule. A manually typed `deliveryDocumentRef` is explicitly NOT binary proof.
- Warehouse users upload through the shared KMP API and native Windows file picker or Android Storage Access Framework. Only operators with current `canReceiveAt` authorization for the active company destination can reserve, finalize or read private delivery-note Evidence. Feature flag remains OFF.
- S05 three-way match is blocked if any linked receiving is still open or lacks a READY delivery note. A match source fingerprint includes receipt versions and actual verified note ID so evidence changes require re-review.

**Remaining before S04 acceptance**: exact-head PostgreSQL/native test pass for these new changes; an actual on-device multi-person PO receipt with redacted document, quarantined/retry, role revocation and cross-org checks; signed company policy; staging reconciliation and safe merge. None of the new Evidence workflow has been activated for real procurement.

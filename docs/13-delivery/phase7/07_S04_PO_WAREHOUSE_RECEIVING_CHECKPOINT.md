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

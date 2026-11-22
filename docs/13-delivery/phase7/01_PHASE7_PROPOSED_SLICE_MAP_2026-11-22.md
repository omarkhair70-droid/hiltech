# Phase 7 — proposed Procurement implementation map

Date: 2026-11-22
Status: **PROPOSED / SOURCE-INFORMED / NOT FROZEN**
Prerequisite: `00_PHASE7_SOURCE_AUDIT_AND_REALITY_GATE_2026-11-22.md`

This is the smallest coherent internal purchasing sequence derived from existing HILTECH source contracts. **Seven proposed vertical slices**, subject to the Phase-7 contract/reality freeze. No fabricated production approvals, vendor terms or new finance/warehouse truth.

## S01 — Purchase Requirement + stock-before-buy

**Job:** a Project/Work/Warehouse/office actor requests a defined material, asset or service, with business context; a purchaser sees source-backed current availability and an explicit next action.

- Business source: sourceType/sourceRef/source version, Project/Work/Site where applicable, requester, organization, classification, justification, need-by, quantity + unit + technical specification.
- Capture and validate a **versioned StockAvailabilityAssessment** referring to a real Warehouse source read and timestamp; include a freshness/staleness marker. It must not imply that requested units are reserved.
- Duplicate/overlap candidate detection is an advisory signal; it must not silently merge independent requests.
- States proposed: DRAFT -> SUBMITTED -> VALIDATION -> NEEDS_INFO / READY_FOR_SOURCING / REJECTED. Exact names are **not frozen**.
- Server owns current membership/authority, source integrity, version checks and command idempotency; no offline-authoritative submit.
- Windows: request inbox and stock-before-buy decision. Android: low-friction request or read-only current status only if justified by field-device/operator proof.
- Acceptance scenarios: same stock requested simultaneously by two projects; reserved stock unavailable to a new request; unknown StockItem; withdrawn WorkOrder; mismatched unit; source organization revoked; duplicate operationId replay; stale stock assessment; empty supplier data.

**S01 excludes** supplier quote, PO, pricing threshold, receiving and Finance writes. Successful submission is not purchase authorization.

## S02 — Supplier sourcing / RFQ

**Job:** purchaser records which supplier is invited, for which exact request and specification revision.

- Reuse existing supplier organization identity when legitimately available, with internal contact visibility/permission boundaries.
- Preserve immutable RFQ request/version, invited suppliers, terms requested and evidence/document references.
- Supplier has no login in this phase; email/phone/out-of-system RFQ provenance may be recorded without claiming electronic delivery.
- Acceptance: reissue after specification change; redacted supplier contacts for insufficient roles; duplicate invitation; currency/quantity mismatch; closed or cancelled demand.

## S03 — Supplier quote capture and comparison

**Job:** purchaser records actual quotes and compares them without losing source evidence.

- Quote version, supplier, requested line matching, currency, tax/discount/total, validity, delivery and warranty terms, document reference and technical compliance.
- Issued/received quote versions are immutable; superseding creates a new version.
- Comparison presents explainable dimensions (pricing, time, compliance, known exceptions), not a hidden rank.
- Acceptance: stale/expired quote; two currencies without approved conversion; mismatched tax treatment; changed line or supplier; confidential competitor quote isolation.

## S04 — Approval + Purchase Order issue/versioning

**Job:** authorized purchaser submits exact sourcing decision for approval and issues an immutable supplier-facing PO only after the canonical approval route is satisfied.

- Subject version includes selected supplier, quote version, lines/quantity, money/currency, delivery destination and scope.
- Reuse Phase-2 ApprovalEngine and current-source organization/role checks; do not hard-code "Mohamed approves > X" without verified policy.
- PO may be DRAFT/INTERNAL_READY/ISSUED etc; exact transitions and order numbering require G2 freeze.
- Amendments create new PO versions and require policy re-evaluation when material.
- Acceptance: quote changed after approval; approval revoked; second submit replay; concurrent approval; unauthorized issuer; issued PO remains immutable; cancel/reissue trace.

## S05 — Delivery and Warehouse receiving linkage

**Job:** purchaser sees real order fulfillment based on canonical receiving and discrepancy truth.

- Link PO line/version to the **existing** Warehouse Receiving sourceLineRef/sourceRef without writing a second stock movement.
- Warehouse receiver records physical quantity/condition, serial/asset identity and discrepancies through the canonical receiving service.
- Procurement tracks partial/late/over delivery and purchasing action; never increments balance from its own table.
- Acceptance: split deliveries; direct-to-site destination under Warehouse control; damaged/incorrect serial; over-receipt; repeated receiving event; cancellation while a delivery is in flight.

## S06 — Three-way match evidence and Finance-ready handoff

**Job:** purchaser/Finance can see PO version vs received quantity vs supplier invoice evidence, with visible variance and disputed facts.

- Match snapshot must bind exact PO/receipt/invoice references and versions; redacted commercial documents remain access-controlled.
- Proposed states: MATCHED / MISSING_RECEIPT / PRICE_MISMATCH / QUANTITY_MISMATCH / TAX_OR_TERMS / DISPUTED.
- Finance owns authoritative SupplierInvoice/Payable and all later payment/reconciliation. A Phase-7 handoff may provide inputs, not post payables without the Phase-8 contract.
- Acceptance: invoice before delivery, partial receipt, amendment after invoice, duplicate supplier invoice number, amount/currency mismatch, rejected goods, missing document.

## S07 — Internal procurement operations and exceptions

**Job:** a purchaser can run daily purchasing without jumping through disconnected modules.

- Windows source-backed actionable queues: new request / missing info / quotes due / comparison / pending approval / POs awaiting supplier / late receipt / discrepancy / match issues.
- PM/warehouse/approver mobile experiences are role/context-specific, no redundant general ERP UI.
- Analytics are explainable measures with source drill-through and freshness (supplier timeliness, open commitments, exceptions), not an AI guess.
- End-to-end human scenario: Work request -> stock proof -> supplier comparison -> version-bound approval -> PO -> partial receipt -> discrepancy -> invoice match -> Finance handoff.
- Final gap review, audit/privacy verification, offline classification, staged smoke and representative HILTECH purchaser/receiver/approver acceptance.

## Shared engineering completion gate for every slice

1. Freeze only the slice's exact object/state/permission and current-source contracts; keep the proposed map revisable by evidence.
2. DB migration with safe constraints, audit and idempotency; server domain/service/API with current-source auth.
3. Shared KMP transport + Windows/Android role behavior where relevant. Verify human flow, not just controllers.
4. PostgreSQL/OpenFGA semantic and concurrency tests; immutable version proof, missing/revoked access and existing Warehouse/Approval integration.
5. Feature default OFF until owner-approved rollout. Exact-head CI proof, review, merge and post-merge Bootstrap, one slice at a time.

### What would block real code?

A normal missing supplier template or project-specific business term should become **configurable**. A missing authorization rule for **issuing a real PO**, a missing actual organization/supplier identity, or a contradictory ownership model must not be invented. S01 may progress on contract and tests when these later-slice facts are missing, but must not be labelled production-operable until the required real-world proof exists.

### Next step

Run a focused S01 contract audit against the exact current main/StockLedger/Reservations sources, and obtain one **redacted** request+stock-check example from HILTECH. Freeze S01 versions, visibility and current-availability semantics before migrating schema. Do not merge the still-open Phase-6 stacked PRs or claim Phase-6 production readiness as a side effect of this planning branch.

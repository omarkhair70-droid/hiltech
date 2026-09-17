# Master Workflow — Procurement

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Turn a legitimate HILTECH requirement into an approved order, verified receipt, matched commercial document, and finance-ready obligation without losing project/stock context.

---

# 1. Requirement Creation

Sources:
- Project/work requirement
- Warehouse low stock
- Asset replacement/repair
- Office/facility need
- Approved ad-hoc request

Requirement includes:
- requester
- context/project/site
- item/service specification
- quantity
- required date
- urgency
- technical requirements
- existing stock check
- suggested suppliers where relevant

Event:
- purchase.requirement_created

---

# 2. Requirement Validation

Checks:
- duplicate request?
- already in stock?
- reserved stock available?
- correct technical specification?
- budget/project context?
- requested date feasible?
- service vs stock vs asset classification?

Outcome:
VALID / NEEDS_INFO / REJECTED / READY_FOR_SOURCING

---

# 3. Sourcing / Supplier Quote

Procurement requests/records quote(s).

Preserve:
- supplier
- version
- validity date
- currency
- tax/commercial terms
- delivery
- warranty
- technical compliance
- attachment/source

Never overwrite historical quote versions.

Events:
- rfq.sent_to_supplier
- supplier.quote_received

---

# 4. Comparison

Compare facts, not opaque score only:
- price
- delivery
- technical compliance
- warranty
- historical delivery performance
- quality/rejection history
- payment terms

Recommendation can be recorded with human rationale.

---

# 5. Internal Approval

Policy decides who must approve based on:
- amount
- project
- budget variance
- urgency
- supplier risk
- exception to preferred supplier
- unusual terms

Management by exception applies.

Events:
- purchase.approval_requested
- purchase.approved
- purchase.rejected
- purchase.change_requested

---

# 6. Purchase Order

Approved requirement creates PO.

PO contains:
- supplier
- delivery destination
- items/services
- quantities
- agreed pricing
- terms
- required date
- project/cost allocation
- attachments/specs

PO versions/amendments remain traceable.

Events:
- po.created
- po.issued
- po.amended

---

# 7. Supplier Confirmation

Supplier acknowledges:
- order
- quantities
- expected delivery
- exceptions/backorder

Event:
- po.confirmed_by_supplier

---

# 8. Delivery

Delivery can target:
- HILTECH warehouse
- office
- project site
- other authorized location

Receiver records:
- quantity
- condition
- serials where applicable
- delivery note
- discrepancy
- rejection/partial receipt

Events:
- delivery.arrived
- goods.partially_received
- goods.received
- goods.rejected

---

# 9. Warehouse / Asset Creation

Accepted items transition to:
- stock receipt
- asset registration/tagging
- direct project receipt
according to classification.

Procurement does not independently maintain duplicate inventory truth.

---

# 10. Invoice / Matching

Match:
PO
vs Receipt
vs Supplier Invoice

Potential outcomes:
MATCHED
QUANTITY_MISMATCH
PRICE_MISMATCH
TAX/TERM_MISMATCH
MISSING_RECEIPT
PARTIAL

Events:
- supplier.invoice_received
- invoice.match_completed
- invoice.mismatch_created

---

# 11. Finance Handoff

Matched/approved obligation becomes eligible for finance payment workflow.

Procurement can see payment status according to permission but does not execute finance payment unless role/policy allows.

Event:
- payable.ready

---

# 12. Returns / Claims

If item rejected/damaged/wrong:
- return
- replacement
- credit note
- supplier claim

Link all actions to PO/receipt/invoice.

---

# 13. Supplier History

Preserve measurable history:
- quote response time
- on-time delivery
- quantity accuracy
- quality rejection
- price history
- invoice mismatch
- warranty/return experience

Do not convert to hidden AI ranking without explainable facts.

---

# Invariants
- No PO without an approved path except explicitly defined emergency procurement.
- Received quantity cannot exceed authorized handling without discrepancy flow.
- Supplier invoice cannot silently alter PO terms.
- Commercial history is versioned.
- Inventory receipt and finance obligation derive from the same procurement truth.

---

# Major Objects
- Purchase Requirement
- Supplier
- Supplier Quote
- Quote Version
- Comparison
- Approval
- Purchase Order
- PO Version
- Delivery
- Receipt
- Receiving Discrepancy
- Supplier Invoice
- Match Result
- Return/Claim
- Credit Note reference

DISCOVERED, not final.

---

# Completion gate
Requires:
- actual HILTECH purchasing examples,
- supplier/quote documents,
- approval thresholds,
- warehouse receiving reality,
- finance matching/payment reality,
- emergency procurement policy,
- state/event/permission freeze,
- UI/desktop/mobile surfaces,
- integration/file/module placement,
- tests for partial delivery, price mismatch, cancellations, and amendments.

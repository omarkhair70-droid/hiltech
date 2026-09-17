# Procurement — Persona & Work Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Core responsibility
Turn approved company/project requirements into the correct purchase from the correct supplier at the correct commercial/technical terms, then connect delivery and invoice back to the project/warehouse/finance chain.

## HILTECH should remove
- Purchase requests buried in chats.
- Repeated re-entry of item/project information.
- Unclear approval state.
- Ordering without visibility into stock/reservations.
- Supplier quote comparison in disconnected spreadsheets.
- Finance asking whether delivery happened.
- Warehouse receiving goods without purchase context.

## Primary home
### Procurement Queue
- New purchase requests.
- Requests blocked by missing specification.
- Requests awaiting quote.
- Quote comparisons.
- Requests awaiting approval.
- Approved / PO pending.
- Supplier confirmations.
- Deliveries expected today/this week.
- Late orders.
- Invoice mismatches.
- Low-stock replenishment proposals.

## Requirement sources
- Project work plan.
- Warehouse low-stock threshold.
- Asset replacement/repair.
- Maintenance requirement.
- Office/facility requirement.
- Approved ad-hoc request.

## Core flow
Need -> Purchase Request -> requirement/spec validation -> supplier sourcing/quote(s) -> comparison -> approval policy -> Purchase Order -> supplier confirmation -> delivery -> warehouse/site receiving -> invoice match -> finance payment workflow -> supplier history updated.

## Controls
- No purchase without request/context except explicitly authorized emergency policy.
- Approval thresholds should be policy-driven, not hard-coded to one person.
- PO history must be immutable/auditable.
- Delivery quantity/condition must be confirmed by receiver.
- Invoice differences require explicit resolution.
- Supplier performance becomes measurable over time.

## Potential supplier score data
Do not create an opaque single score initially. Preserve measurable facts:
- on-time delivery
- accepted/rejected quantities
- defects
- price history
- response time
- invoice mismatch rate
- return history

## Mobile
- review urgent request
- approve within permission
- supplier contact/context
- confirm delivery status
- view PO/quote
- attach field requirement

## Desktop
- sourcing
- quote comparison
- PO creation
- catalog/history
- supplier analysis
- bulk line items
- delivery planning
- invoice matching context

## Events
- purchase.requested
- purchase.spec_confirmed
- supplier.quote.received
- purchase.approval_requested
- purchase.approved
- purchase.rejected
- po.issued
- supplier.confirmed
- delivery.expected
- goods.received
- invoice.matched
- invoice.mismatch_detected

## Questions to validate
- Who currently buys?
- Is procurement a dedicated role or split between people?
- Typical approval thresholds.
- Whether supplier quotes arrive by email/WhatsApp/paper.
- Repeated suppliers.
- Emergency-purchase behavior.
- Whether goods are delivered to HQ, warehouse, or sites.
- Relationship between BOQ pricing and procurement.

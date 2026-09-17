# Supplier / Subcontractor — Experience Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Important distinction
Supplier and subcontractor are external parties but not the same product role.

### Supplier
Provides goods/materials/equipment/services against commercial orders.

### Subcontractor
Executes assigned work or delivers a defined work package and may interact with sites, evidence, schedules, materials, and acceptance.

The final model may separate them internally while keeping both inside the same HILTECH identity/organization model.

# Supplier

## Supplier-visible scope
- purchase orders
- line items/specifications
- confirmation
- expected delivery
- delivery evidence
- invoice submission/status where allowed
- return/rejection context
- communication linked to order

## Supplier should NOT see
- unrelated project details
- HILTECH internal margins
- competitor quotes
- restricted finance
- other suppliers

## Supplier events
- supplier.invited
- po.received
- po.confirmed
- delivery.updated
- delivery.completed
- supplier.invoice.submitted
- return_or_rejection.created

# Subcontractor

## Subcontractor-visible scope
- assigned project/work package only
- site/context
- schedule
- required scope/evidence
- documents/drawings needed for execution
- assigned material/equipment when applicable
- issues/RFIs
- completion/inspection status
- commercial milestones only where authorized

## Main flow
Assigned work package -> accept/clarify -> plan resources -> execute -> submit evidence -> HILTECH review -> rework if needed -> accepted completion -> commercial milestone.

## Critical rule
Subcontractor work must produce the same structured project truth as internal work where feasible. HILTECH should not lose operational visibility just because execution is external.

## Events
- subcontract.work_assigned
- subcontract.accepted
- subcontract.issue_created
- subcontract.evidence_submitted
- subcontract.work_reviewed
- subcontract.rework_requested
- subcontract.work_accepted

## Questions to validate
- How often HILTECH uses subcontractors.
- Whether subcontractors currently receive company materials/tools.
- Who supervises external crews.
- Existing payment/milestone process.
- Which suppliers/subcontractors are strategic recurring partners.

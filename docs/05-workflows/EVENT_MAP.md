# HILTECH Cross-Domain Event Map

Status: DISCOVERED / GROWING
Purpose: maintain one registry of meaningful business events discovered during workflow design.

This is not yet a message-broker schema. It is the product/domain event vocabulary from which technical events can later be designed.

## Naming
Use:
domain.subject.action
where practical.

## Opportunity / Sales
- opportunity.created
- opportunity.qualified
- opportunity.disqualified
- tender.received
- rfq.received
- site_visit.scheduled
- discovery.completed
- boq.created
- costing.completed
- quote.drafted
- quote.approval_requested
- quote.approved
- quote.rejected
- quote.submitted
- quote.revised
- opportunity.won
- opportunity.lost
- contract.received
- client_po.received

## Project / Work
- project.created_from_award
- project.baseline_created
- project.plan.updated
- milestone.created
- milestone.progressed
- work_package.created
- work_order.created
- resource.requirement.created
- work.started
- work.completed
- work.reviewed
- work.accepted
- rework.requested
- field.issue.created
- technical_issue.created
- evidence.captured
- test.recorded
- asset.installed
- client.update.published
- change.identified
- change.assessed
- variation.approval_requested
- variation.client_approved
- variation.client_rejected
- project.baseline.changed
- handover.started
- handover.package_generated
- handover.ready
- project.delivery_completed
- project.closed

## People / HR
- candidate.created
- interview.scheduled
- candidate.accepted
- candidate.rejected
- hire.approved
- identity.created
- employee.invited
- account.activated
- employee.activated
- employee.role_changed
- employee.team_changed
- employee.manager_changed
- attendance.recorded
- overtime.requested
- overtime.approved
- leave.requested
- leave.approved
- leave.rejected
- certification.added
- certification.expiring
- employee.offboarding_started
- employee.offboarded
- access.revoked

## Expenses / Employee Finance
- expense.submitted
- expense.approved
- expense.rejected
- advance.requested
- advance.issued
- advance.settled

## Warehouse / Assets
- goods.receiving_started
- goods.received
- receiving.discrepancy_created
- asset.registered
- asset.reserved
- asset.checked_out
- custody.started
- transfer.initiated
- transfer.accepted
- transfer.completed
- stock.issued
- stock.consumed
- stock.returned
- asset.returned
- asset.damage_reported
- asset.loss_reported
- asset.maintenance_due
- asset.calibration_due
- stocktake.started
- stock.discrepancy_created
- stock.adjustment_requested
- stock.adjustment_approved
- asset.retired
- asset.disposed

## Procurement
- purchase.requirement_created
- rfq.sent_to_supplier
- supplier.quote_received
- purchase.approval_requested
- purchase.approved
- purchase.rejected
- po.created
- po.issued
- po.amended
- po.confirmed_by_supplier
- delivery.arrived
- goods.partially_received
- goods.rejected
- supplier.invoice_received
- invoice.match_completed
- invoice.mismatch_created
- payable.ready

## Approval
- approval.requested
- approval.assigned
- approval.approved
- approval.rejected
- approval.change_requested
- approval.delegated
- approval.expired
- approval.superseded
- approval.completed

## Payroll
- payroll.approval_requested
- payroll.approved
- payroll.rejected
- payroll.superseded
- payroll.payment_started
- payroll.employee_paid
- payroll.payment_failed
- payroll.reconciled

## Client / Service
- client.invited
- client.approval_requested
- client.approved
- client.issue_reported
- maintenance.contract_created
- service.started
- support.ticket_created
- warranty.started
- warranty.issue_created
- warranty.issue_resolved

## Rules
1. An event represents something that happened, not a command.
2. Events are immutable facts.
3. Sensitive event payloads must follow permission/privacy rules.
4. Product event vocabulary does not force a distributed event architecture.
5. Event names will be normalized and versioned before technical freeze.

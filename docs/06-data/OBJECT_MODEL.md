# HILTECH Object Model

Status: DISCOVERED / GROWING
Purpose: define the canonical business objects discovered through Human Maps and Master Workflows before database/schema design.

This is not yet a SQL schema.

## Identity & Organizations
- Person
- User Identity
- Organization
- Organization Membership
- Contact
- Role
- Team
- Manager Relationship
- Permission Assignment
- Delegation

## People / Employment
- Candidate
- Application
- Interview
- Offer
- Employee
- Employment
- Certification
- Training Record
- Attendance Record
- Timesheet
- Leave Request
- Expense
- Advance
- Compensation Reference
- Offboarding Case

## Sales / Commercial
- Lead / Opportunity
- Tender / RFQ
- Site Visit
- Scope
- BOQ
- Costing
- Quote
- Quote Version
- Commercial Approval
- Contract
- Client Purchase Order

## Project Delivery
- Project
- Site
- Area / Room / Zone
- Milestone
- Work Package
- Work Order
- Task
- Requirement
- Evidence
- Test
- Issue
- Change / Variation
- Snag
- Handover Package
- Warranty
- Maintenance Contract
- Support Ticket

## Assets / Warehouse
- Inventory Item
- Trackable Asset
- Serialized Stock Unit
- Consumable Stock Item
- Warehouse
- Storage Location / Zone / Bin
- Reservation
- Custody
- Movement
- Issue / Checkout
- Return
- Transfer
- Consumption
- Stock Count
- Stock Adjustment
- Maintenance Record
- Calibration Record
- Asset Incident
- Asset Tag

## Procurement / Supplier
- Supplier
- Purchase Requirement
- Supplier RFQ
- Supplier Quote
- Supplier Quote Version
- Quote Comparison
- Purchase Approval
- Purchase Order
- Purchase Order Version
- Delivery
- Receipt
- Receiving Discrepancy
- Supplier Invoice
- Match Result
- Return / Claim
- Credit Note Reference

## Finance / Payroll
- Payroll Period
- Payroll Run
- Payroll Run Version
- Payroll Employee Line
- Compensation Component
- Payroll Adjustment
- Payroll Exception
- Payment Batch
- Payment Instruction
- Payment Result
- Reconciliation
- Payslip
- Correction Run
- Invoice
- Payment
- Receivable
- Payable

## Shared System Objects
- Approval Request
- Approval Policy
- Approval Step
- Approval Decision
- Notification
- Message
- Activity Event
- Audit Event
- Document
- Document Version
- Attachment
- Comment / Note
- Integration Connection
- Device
- Session

## Relationship Principles

### Organization first
Users, employees, clients, suppliers, and subcontractors should be modelled through explicit organization relationships rather than isolated account types.

### Identity != Employee
A user can exist without being an employee. External clients/suppliers/subcontractors also use HILTECH identities.

### Project is not a container for copies
Project should reference canonical client, site, asset, commercial, and document objects rather than duplicate their truth.

### Asset identity persists
An asset remains the same object while moving across employee/site/warehouse/project contexts.

### Version important commercial objects
Quotes, POs, payroll runs, approvals, and other sensitive records require explicit version semantics.

### Documents live in context
A document/attachment should link to the object(s) it proves or belongs to, not exist only as an orphan file in a folder.

### History is first-class
Critical lifecycle changes must be represented by events/movements/versions rather than only mutable current-state fields.

## Next work
For every object:
- owner/domain
- identifier
- required fields
- sensitive fields
- relationships
- lifecycle states
- commands
- emitted events
- invariants
- retention rules
- offline policy
- audit level

No object is FROZEN yet.

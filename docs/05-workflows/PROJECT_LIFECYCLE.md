# Master Workflow — Project Lifecycle

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Model the complete HILTECH lifecycle from first commercial opportunity through delivery, handover, warranty/maintenance, and expansion.

This workflow is the first company-wide spine. It connects Sales/Tenders, Management, Project Management, Engineering, Field Teams, Warehouse, Procurement, Finance, Client, Supplier/Subcontractor, Documents, Security, and future Managed Services.

---

# 1. Opportunity Intake

## Sources
- website/RFQ
- referral
- existing client
- direct outreach
- partner/subcontract request
- formal tender

## Created objects
- Organization/Client
- Contact(s)
- Opportunity
- Source
- Required Sites
- Initial Scope
- Tender/RFQ documents
- Deadline
- Owner/internal responsible user

## Events
- opportunity.created
- tender.received
- rfq.received

## Exit condition
Opportunity is qualified, rejected, or put on hold.

---

# 2. Qualification & Discovery

## Questions
- Is this work inside HILTECH capability?
- Is direct contracting/subcontracting context known?
- Required disciplines?
- Site visit required?
- Client decision process?
- Deadline?
- Commercial risk?
- Required vendor approvals/certifications?

## Possible work
- meeting
- site visit
- technical survey
- drawing review
- clarification questions

## Outputs
- qualified scope
- assumptions
- exclusions
- site information
- technical requirements
- delivery constraints

## Events
- opportunity.qualified
- site_visit.scheduled
- discovery.completed
- opportunity.disqualified

---

# 3. Solution / BOQ / Costing

## Participants
Sales/Tenders + Technical/Engineering + Finance/Commercial + Management when needed.

## Build
- solution
- BOQ
- quantities
- labor estimate
- equipment/material estimate
- subcontract requirements
- timeline estimate
- commercial assumptions
- internal cost
- proposed sell price
- target margin
- payment terms

## Product rule
Commercially sensitive values must be permission-scoped. Client-visible price and internal cost are different facts.

## Events
- boq.created
- costing.completed
- quote.drafted

---

# 4. Internal Commercial Approval

Policy-driven approval based on:
- value
- margin
- risk
- payment terms
- unusual commitments
- strategic client
- required capital/material exposure

Management by exception:
routine work should not require Mohamed for every decision if policy allows delegation.

## Events
- quote.approval_requested
- quote.approved
- quote.rejected
- quote.change_requested

---

# 5. Submission / Negotiation / Award

## Actions
- tender submission
- quote submission
- clarification
- revision/versioning
- negotiation
- BAFO/final offer where relevant
- award/PO/contract receipt

## Required history
Every submitted commercial version must remain traceable.

## Outcomes
WON / LOST / ON_HOLD / CANCELLED

## Events
- quote.submitted
- quote.revised
- opportunity.won
- opportunity.lost
- contract.received
- client_po.received

---

# 6. Contract-to-Project Conversion

This must NOT be manual re-entry.

Winning work seeds the delivery project using commercial truth.

## Carries forward
- client organization
- contacts
- sites
- agreed scope
- BOQ
- approved price/terms with permissions
- exclusions
- documents
- promised dates
- payment milestones
- special obligations
- warranty/maintenance terms

## Creates
- Project
- Project ID
- project team placeholders
- site records
- milestones
- baseline scope
- baseline budget
- contract document set

## Events
- project.created_from_award
- project.baseline_created
- delivery.handoff.started

---

# 7. Internal Project Kickoff

## Participants
Owner/management where needed
Project Manager
Engineering
Warehouse
Procurement
Finance
Field leadership
Sales handoff owner

## Confirm
- scope
- risks
- sites
- dependencies
- team
- schedule
- materials
- equipment
- procurement
- subcontractors
- client contacts
- access/security constraints
- documents/drawings
- invoicing milestones

## Exit
Project is READY_FOR_PLANNING / ACTIVE according to final state model.

---

# 8. Planning / Work Breakdown

Project -> Sites -> Milestones -> Work Packages -> Work Orders -> Tasks

## Each planned work item can declare
- required people/skills
- required materials
- required equipment
- drawings/specs
- planned date/time
- dependency
- evidence requirements
- acceptance criteria
- client access requirement

## Derived workflows
- warehouse reservations
- procurement requests
- subcontract assignments
- site access requests
- field schedules

## Events
- project.plan.updated
- milestone.created
- work_package.created
- work_order.created
- resource.requirement.created

---

# 9. Resource Readiness

## People
- assigned
- available
- qualified/certified
- access approved

## Materials
- in stock
- reserved
- purchase required
- delivery expected

## Equipment
- available
- reserved
- calibrated/fit-for-use
- checked out

## Documents
- latest approved drawing/spec available

## Rule
A work order may have a computed readiness state rather than relying on PM memory.

Example:
READY / BLOCKED_MATERIAL / BLOCKED_ACCESS / BLOCKED_DRAWING / BLOCKED_EQUIPMENT / BLOCKED_PEOPLE

---

# 10. Site Execution

## Field loop
Check context -> start work -> perform -> capture evidence -> record materials -> record asset installation/movement -> report issue/change -> complete -> supervisor review.

## Offline-first
Site execution must tolerate poor/no connectivity within defined policy.

## Evidence examples
- photos
- serials
- measurements
- OTDR/Fluke results
- installed asset
- material quantities
- notes
- client/site signoff where required

## Events
- work.started
- evidence.captured
- material.consumed
- asset.installed
- field.issue.created
- work.completed

---

# 11. Supervision / Quality / Engineering Review

## Review
- evidence completeness
- technical compliance
- quantity
- rework need
- test pass/fail
- drawing/spec adherence

## Outcomes
ACCEPTED / REWORK_REQUIRED / TECHNICAL_HOLD / CLIENT_INPUT_REQUIRED

## Events
- work.reviewed
- rework.requested
- technical_issue.created
- work.accepted

---

# 12. Issue / Change / Variation Flow

Potential sources:
- hidden site condition
- client request
- drawing change
- unavailable material
- design conflict
- access delay
- quality issue

## Rule
Never silently change commercial or project baseline.

Create structured Change/Variation.

## Flow
Issue -> assess technical impact -> assess schedule impact -> assess cost impact -> internal approval -> client approval where commercial/scope change -> baseline/change order update -> execution.

## Events
- change.identified
- change.assessed
- variation.approval_requested
- variation.client_approved
- variation.client_rejected
- project.baseline_changed

---

# 13. Progress & Client Visibility

Progress should derive from accepted work, not manual percentage typing where possible.

Client sees only approved client-visible facts.

## Client-visible
- milestone state
- agreed progress
- selected evidence
- documents
- approvals required
- issues requiring client action
- delivery dates where allowed

## Events
- milestone.progressed
- client.update.published
- client.approval_requested

---

# 14. Billing Milestones / Revenue

Depending on contract:
- advance
- milestone invoice
- quantity/measurement certificate
- partial completion
- final invoice
- retention

## Trigger
Commercial milestone becomes eligible only when contractual evidence/approval conditions are satisfied.

## Flow
eligible -> finance review -> invoice issued -> client receives -> payment tracked -> received/reconciled.

## Events
- billing.milestone.eligible
- invoice.drafted
- invoice.issued
- payment.received
- payment.reconciled

Exact accounting treatment requires finance validation.

---

# 15. Handover Preparation

## Required package may include
- as-built drawings
- asset register
- test reports
- certificates
- manuals
- warranties
- photos
- acceptance records
- punch list / snag closure
- training records if applicable

## Product rule
Handover should be generated from structured project truth as much as possible, not rebuilt manually at the end.

## Events
- handover.started
- handover.package.generated
- snag.created
- snag.closed
- handover.ready

---

# 16. Client Acceptance / Project Close

## Conditions
- contractual scope accepted
- required documentation delivered
- open snags handled according to policy
- final commercial state known
- company assets returned
- unused materials reconciled
- subcontract completion accepted
- project records locked/versioned appropriately

## Events
- client.accepted
- project.delivery_completed
- project.closed

Closure does NOT delete project operational history.

---

# 17. Warranty / Defects Liability

Project remains live after delivery.

## Track
- warranty periods
- warranty-covered assets/work
- defects/issues
- response obligations
- manufacturer warranty
- subcontract/supplier responsibility

## Events
- warranty.started
- warranty.issue.created
- warranty.issue.resolved
- warranty.expiring

---

# 18. Maintenance / Managed Service Conversion

If applicable:
Delivered project/site/assets -> maintenance contract or managed-service relationship.

## Possible recurring operations
- preventive visits
- support tickets
- SLA
- asset maintenance
- calibration
- monitoring
- recurring billing
- upgrades

## Events
- maintenance.contract.created
- service.started
- preventive_visit.due
- support.ticket.created

---

# 19. Expansion / Repeat Business

A completed project should improve future selling.

Examples:
- new branch/site
- upgrade
- replacement
- maintenance renewal
- managed service
- additional infrastructure

The opportunity links back to client/site/assets/history rather than starting from zero.

---

# Cross-Domain Guarantees

## One truth
No duplicate client/project/asset truth per department.

## No dead handoff
Every phase passes structured data to the next.

## Auditability
Commercial, approval, asset, and financial changes require history.

## Permission boundaries
Internal cost, salaries, sensitive security data, and unrelated client data stay restricted.

## Event-driven continuity
Important state changes emit events that other domains can react to.

## Mobile/Desktop continuity
Same project truth; device-appropriate interaction.

---

# Major Objects Revealed by This Workflow

- Organization
- User
- Contact
- Opportunity
- Tender/RFQ
- Site Visit
- Scope
- BOQ
- Quote
- Quote Version
- Approval
- Contract
- Client PO
- Project
- Site
- Milestone
- Work Package
- Work Order
- Task
- Requirement
- Material
- Asset
- Reservation
- Purchase Request
- Purchase Order
- Supplier
- Subcontractor
- Evidence
- Test
- Issue
- Change / Variation
- Invoice
- Payment
- Handover Package
- Snag
- Warranty
- Maintenance Contract
- Support Ticket

This object list is DISCOVERED, not final.

---

# Major Permission Boundaries Revealed

- internal cost vs client price
- project financial visibility
- salary/personnel data
- supplier quote visibility
- client-visible evidence
- technical vs commercial approvals
- project-close authority
- write-off authority
- security/site-access data

---

# Research / Reality Questions Before Freeze

1. Real HILTECH project award paths: direct contract, subcontract, PO, tender.
2. Typical project sizes/durations.
3. Current BOQ/costing process.
4. Who approves pricing and discounts.
5. How project managers receive won jobs today.
6. How sites and milestones are actually structured.
7. Current field evidence and reporting.
8. Existing project progress method.
9. Typical client approval points.
10. How invoicing milestones are determined.
11. Current handover package and checklist.
12. Warranty/maintenance reality.
13. How tools/materials are reconciled at project end.
14. How subcontractor work is accepted.
15. Which portions are contractual/legal and cannot be automated without explicit human approval.

---

# Current completion status

RESEARCHING.

This workflow must not be marked COMPLETE until:
- validated against real HILTECH projects,
- object/state/event models are frozen,
- permissions are defined,
- UI surfaces are mapped,
- technical architecture is assigned,
- implementation structure is build-ready,
- tests and operational requirements are defined.

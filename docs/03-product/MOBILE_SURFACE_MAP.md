# HILTECH Mobile Surface Map

Status: EXPERIENCE MODEL v0.1 / NOT VISUAL DESIGN / NOT BUILD-READY

## Purpose
Define the complete mobile product surface before final navigation styling, component design, or implementation.

Mobile HILTECH is optimized for:
- immediate action,
- approvals,
- field work,
- capture,
- scan,
- status,
- communication,
- self-service,
- critical exceptions.

It is NOT desktop squeezed into a phone.

---

# 1. Shared Mobile Shell

## Global capabilities
- Account / identity
- Role/context awareness
- Home
- Work / action queue
- Search
- Inbox
- Deep links
- Scan
- Profile / self-service
- Offline/sync state
- Notifications
- Contextual actions

Exact bottom-navigation structure is not frozen.

---

# 2. Home — Role Aware

## Mohamed
- Needs You
- Company Pulse
- Project risks
- Financial exceptions
- Client escalations
- Critical warehouse/security
- executive digest

## Ahmed
- Finance actions
- Payroll state
- Failed payments
- Receivables/payables
- Invoice mismatches
- Expense/advance queue

## PM
- My Projects
- Blockers
- Client actions
- Resource readiness
- milestones at risk

## Engineer
- Engineering Today
- Technical reviews
- Tests due
- Drawing revisions
- Technical issues

## Supervisor
- My Site Today
- Crew
- Planned work
- Resource issues
- Evidence review

## Technician
- Today
- Next job
- job bundle
- assigned assets/tools

## Warehouse
- Quick Scan
- Issue today
- Returns due
- Deliveries
- Low stock
- overdue assets

## Procurement
- urgent requirements
- supplier/PO issues
- delivery exceptions
- approval status

## HR
- onboarding blockers
- leave/actions
- documents/certifications
- offboarding exceptions

## Sales
- today's meetings/site visits
- follow-ups
- tender deadlines
- quote approvals

## Client
- My HILTECH
- projects
- actions
- support
- documents

## Supplier
- RFQ/PO/delivery/invoice actions

## Subcontractor
- assigned work
- evidence/rework
- schedule/site updates

---

# 3. Work / Action Queue

One cross-domain action surface filtered by role.

Possible item types:
- approval
- work order
- technical review
- evidence review
- client action
- payroll exception
- invoice mismatch
- warehouse return
- procurement action
- onboarding task
- support ticket

Each item includes:
- action type
- object
- urgency
- owner / waiting-on
- due/SLA
- next valid actions

---

# 4. Search

Mobile search targets:
- project
- site
- employee
- client
- supplier
- asset tag/serial
- PO
- invoice
- work order
- document

Support:
- exact ID
- text
- recent items
- role-aware results

No giant command palette required on mobile initially; contextual quick actions can emerge from result/detail.

---

# 5. Inbox

Durable attention:
- Action Required
- Critical
- Mentions
- Requests
- Status
- External actions
- Digests

Notification opens exact Inbox/object context.

---

# 6. Scan

Global/contextual scan supports:
- Asset QR
- stock/item barcode
- employee/user identification where policy allows
- work/site QR where adopted
- delivery/receipt identifiers where applicable

Scan results route to authorized object action.

Examples:
Technician scans asset -> View / use/report.
Warehouse scans asset -> Checkout/return/transfer.
Client scans maintained asset -> View/support if allowed.

---

# 7. Project Mobile Surface

- Overview
- Health
- Progress
- Sites
- Milestones
- Blockers
- Work
- Team
- Materials/equipment readiness
- Issues/changes
- Documents
- Client actions
- Activity
- Handover
- Finance summary only if permission allows

Not all tabs necessarily visible at once; final IA research determines presentation.

---

# 8. Site Mobile Surface

- Site identity/location/access context
- Today's work
- people/crew
- areas/rooms/zones
- assets
- issues
- documents/drawings
- recent activity
- client/site contacts

---

# 9. Work Order / Job Mobile Surface

- identity/status
- project/site
- instructions
- drawing/spec
- people
- required tools/materials
- readiness/blocker
- Start / Pause / Block / Complete
- evidence checklist
- scan
- measurements/tests
- notes
- activity
- sync state

This is one of HILTECH's most critical mobile surfaces.

---

# 10. Asset Mobile Surface

- Asset Passport
- status
- location/context
- custodian
- current project/site
- condition
- calibration/maintenance
- documents
- movement history
- related issues

Role-specific actions:
- checkout
- return
- transfer
- report damage
- reserve
- support ticket

---

# 11. Warehouse Mobile Modes

## Scan / Identify
## Receive
## Checkout
## Return
## Transfer
## Count
## Inspect
## Damage / Missing
## Delivery

Mobile warehouse should prioritize operational flows over browsing tables.

---

# 12. Approval Mobile Surface

- subject
- version
- value/impact
- requester
- context
- approval flow
- history/comments
- supporting evidence
- Approve
- Reject
- Request Change

Critical action may require re-auth.

---

# 13. Payroll Mobile

## Ahmed
- run status
- exceptions
- approval state
- failed payment
- employee line detail
- reconciliation action

## Mohamed
- approval decision only at required authority depth

## Employee
- own payslip
- payment state
- payroll notification

Dense payroll editing remains desktop-oriented.

---

# 14. Client Mobile

- My Projects
- Project detail
- Actions
- Approvals
- Documents
- Site/asset context
- Support
- Maintenance
- Invoice view where allowed
- New request

---

# 15. Employee Self-Service

- own profile
- payslips
- leave
- expense
- advances
- assigned assets/tools
- certifications/training
- onboarding
- notifications/settings

---

# 16. Security / Camera Mobile

Restricted:
- cameras list
- event context
- live stream if integration/policy allows
- access event
- warehouse security context
- deep link from critical alert

No general employee access.

---

# 17. Offline / Sync UX

Persistent concept, not hidden setting.

Need visible states:
- current
- cached
- offline
- pending upload
- syncing
- failed
- conflict/review required

Never label server-authoritative action complete before confirmation.

---

# 18. Mobile Error Families

- no connectivity
- stale cache
- permission denied/revoked
- object changed
- required evidence missing
- scan unknown
- integration unavailable
- upload failed
- conflict
- re-auth required
- feature unavailable for role

---

# 19. Mobile Design Research Still Required

- final navigation count
- scan placement
- contextual action placement
- Arabic/English behavior
- one-handed field ergonomics
- glove/dirty-hand realities if relevant
- camera-prohibited site mode
- tablet/foldable pane behavior
- motion and haptic grammar

Current state: EXPERIENCE MODEL v0.1.

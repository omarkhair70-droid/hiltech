# HILTECH Canonical Feature Catalog

Status: DISCOVERED / GROWING / NOT FROZEN

## Purpose
Give every capability a stable identity before implementation so features cannot disappear between chat, design, architecture, code, tests, and release.

## Status vocabulary
DISCOVERED -> RESEARCHING -> PRODUCT_DEFINED -> DOMAIN_DEFINED -> EXPERIENCE_DEFINED -> TECH_DEFINED -> BUILD_READY -> IMPLEMENTED -> INTEGRATED -> VERIFIED -> OPERABLE -> RELEASED -> COMPLETE

See `docs/13-delivery/DEFINITION_OF_COMPLETE.md`.

---

# CORE — Identity / System

- CORE-ID-001 Account activation
- CORE-ID-002 Sign in
- CORE-ID-003 Sign out / session termination
- CORE-ID-004 Multi-device session management
- CORE-ID-005 MFA / passkey path
- CORE-ID-006 Organization membership
- CORE-ID-007 Role assignment
- CORE-ID-008 Team assignment
- CORE-ID-009 Project-scoped access
- CORE-ID-010 External organization access
- CORE-ID-011 Delegation
- CORE-ID-012 Re-authentication for critical action
- CORE-ID-013 Access revocation
- CORE-ID-014 Device/session trust
- CORE-ID-015 User/identity audit

# CORE — Navigation / Search / Activity

- CORE-NAV-001 Role-aware Home
- CORE-NAV-002 Adaptive mobile navigation
- CORE-NAV-003 Desktop navigation
- CORE-NAV-004 Global search
- CORE-NAV-005 Command palette
- CORE-NAV-006 Recent context
- CORE-NAV-007 Deep links
- CORE-ACT-001 Company/activity timeline
- CORE-ACT-002 Object activity history
- CORE-ACT-003 Audit history viewer
- CORE-ACT-004 Mentions/contextual comments

# CTRL — Executive Control

- CTRL-001 Company Pulse
- CTRL-002 Needs You queue
- CTRL-003 Executive approvals
- CTRL-004 Project portfolio health
- CTRL-005 Finance pulse
- CTRL-006 Client/commercial risk
- CTRL-007 Asset/warehouse exceptions
- CTRL-008 Security exceptions
- CTRL-009 Daily executive digest
- CTRL-010 Weekly executive digest
- CTRL-011 Executive global search
- CTRL-012 Delegated authority view

# SALES — CRM / Tenders

- SALES-001 Lead capture
- SALES-002 Opportunity qualification
- SALES-003 Client/contact context
- SALES-004 Site visit/discovery
- SALES-005 Tender/RFQ registry
- SALES-006 Tender calendar/deadlines
- SALES-007 Scope/assumption capture
- SALES-008 BOQ preparation context
- SALES-009 Costing context
- SALES-010 Quote creation
- SALES-011 Quote versioning
- SALES-012 Internal quote approval
- SALES-013 Tender submission record
- SALES-014 Negotiation/revision
- SALES-015 Won/lost/on-hold outcome
- SALES-016 Contract/PO capture
- SALES-017 Won-to-project conversion
- SALES-018 Existing-client expansion opportunity
- SALES-019 Pipeline/reporting

# WORK — Projects / Delivery

- WORK-001 Project creation from award
- WORK-002 Project baseline
- WORK-003 Project overview
- WORK-004 Site model
- WORK-005 Area/room/zone model
- WORK-006 Milestones
- WORK-007 Work packages
- WORK-008 Work orders
- WORK-009 Tasks
- WORK-010 Dependencies
- WORK-011 Schedule/planning
- WORK-012 Team assignment
- WORK-013 Resource requirements
- WORK-014 Readiness computation
- WORK-015 Blockers
- WORK-016 Project progress
- WORK-017 Accepted-work progress derivation
- WORK-018 Project health/risk
- WORK-019 Daily site report
- WORK-020 Client-visible project update
- WORK-021 Issue management
- WORK-022 Change/variation
- WORK-023 Variation approval
- WORK-024 Rework
- WORK-025 Snag/punch list
- WORK-026 Handover preparation
- WORK-027 Handover package
- WORK-028 Client acceptance
- WORK-029 Project closure
- WORK-030 Warranty
- WORK-031 Maintenance conversion

# FIELD — Field Execution

- FIELD-001 Today jobs
- FIELD-002 Job detail
- FIELD-003 Job start
- FIELD-004 Job pause/block
- FIELD-005 Job completion
- FIELD-006 Offline job bundle
- FIELD-007 Drawing/instruction access
- FIELD-008 Camera evidence
- FIELD-009 QR/barcode scan
- FIELD-010 Measurement capture
- FIELD-011 Test capture
- FIELD-012 Voice/text note
- FIELD-013 Material usage
- FIELD-014 Asset/tool custody context
- FIELD-015 Problem/blocker report
- FIELD-016 Supervisor review
- FIELD-017 Rework request
- FIELD-018 Evidence completeness check
- FIELD-019 Sync state/retry
- FIELD-020 Site contact/access context

# ENG — Engineering

- ENG-001 Approved drawings/specifications
- ENG-002 Revision awareness
- ENG-003 Technical issue
- ENG-004 Technical review
- ENG-005 Test registry
- ENG-006 OTDR evidence
- ENG-007 Fluke evidence
- ENG-008 Measurement history
- ENG-009 Asset/link technical history
- ENG-010 Technical acceptance
- ENG-011 Technical rework
- ENG-012 Handover technical artifacts
- ENG-013 Calibration validity check

# PEOPLE — HR / Workforce

- PEOPLE-001 Candidate record
- PEOPLE-002 Interview/selection
- PEOPLE-003 Hire approval
- PEOPLE-004 Employee record
- PEOPLE-005 Employment lifecycle
- PEOPLE-006 Org structure
- PEOPLE-007 Role/team/manager change
- PEOPLE-008 Employee documents
- PEOPLE-009 Certification/training
- PEOPLE-010 Certification expiry
- PEOPLE-011 Attendance record
- PEOPLE-012 Timesheet/work time
- PEOPLE-013 Overtime request/approval
- PEOPLE-014 Leave request/approval
- PEOPLE-015 Expense request
- PEOPLE-016 Advance request/settlement
- PEOPLE-017 Employee self-service
- PEOPLE-018 Offboarding case
- PEOPLE-019 Access clearance
- PEOPLE-020 Asset clearance
- PEOPLE-021 Former employee retention/history

# ONB — New Hire

- ONB-001 Invite
- ONB-002 Account activation
- ONB-003 Profile completion
- ONB-004 Document upload
- ONB-005 Bank/payroll information
- ONB-006 Policy acknowledgement
- ONB-007 Safety onboarding
- ONB-008 Manager/team introduction
- ONB-009 First-day plan
- ONB-010 Training requirements
- ONB-011 Asset/PPE assignment
- ONB-012 Onboarding blocker
- ONB-013 Onboarding completion

# WH — Warehouse / Stock

- WH-001 Warehouse/location model
- WH-002 Item classification
- WH-003 Goods receiving
- WH-004 Receiving discrepancy
- WH-005 Inventory list
- WH-006 Stock availability
- WH-007 Reservation
- WH-008 Stock issue
- WH-009 Stock consumption
- WH-010 Stock return
- WH-011 Transfer
- WH-012 Low-stock rule
- WH-013 Replenishment request
- WH-014 Stocktake
- WH-015 Cycle count
- WH-016 Stock discrepancy
- WH-017 Adjustment request
- WH-018 Adjustment approval
- WH-019 Multi-storage-location support
- WH-020 Project stock context
- WH-021 Tomorrow issue preparation

# ASSET — Trackable Assets

- ASSET-001 Asset registration
- ASSET-002 Asset ID/tag
- ASSET-003 QR/barcode identity
- ASSET-004 Asset Passport
- ASSET-005 Serial/model data
- ASSET-006 Current location/context
- ASSET-007 Current custodian
- ASSET-008 Reservation
- ASSET-009 Checkout
- ASSET-010 Return
- ASSET-011 Transfer
- ASSET-012 Condition inspection
- ASSET-013 Damage incident
- ASSET-014 Missing/loss incident
- ASSET-015 Repair
- ASSET-016 Maintenance
- ASSET-017 Calibration
- ASSET-018 Calibration due block
- ASSET-019 Warranty
- ASSET-020 Movement history
- ASSET-021 Retirement/disposal
- ASSET-022 Optional location telemetry/tag integration

# PROC — Procurement

- PROC-001 Purchase requirement
- PROC-002 Requirement validation
- PROC-003 Stock-before-buy check
- PROC-004 Supplier RFQ
- PROC-005 Supplier quote capture
- PROC-006 Quote versioning
- PROC-007 Quote comparison
- PROC-008 Purchase approval
- PROC-009 Purchase Order
- PROC-010 PO version/amendment
- PROC-011 Supplier confirmation
- PROC-012 Delivery tracking
- PROC-013 Partial delivery
- PROC-014 Rejection/return
- PROC-015 Receipt
- PROC-016 Invoice match
- PROC-017 Mismatch workflow
- PROC-018 Claim/credit note context
- PROC-019 Supplier factual history
- PROC-020 Emergency procurement path

# FIN — Finance

- FIN-001 Receivable
- FIN-002 Payable
- FIN-003 Client invoice
- FIN-004 Supplier invoice
- FIN-005 Invoice lifecycle
- FIN-006 Payment preparation
- FIN-007 Payment approval
- FIN-008 Payment execution/handoff
- FIN-009 Payment result
- FIN-010 Reconciliation
- FIN-011 Partial payment
- FIN-012 Failed/returned payment
- FIN-013 Project cost view
- FIN-014 Project commercial summary
- FIN-015 Expense reimbursement
- FIN-016 Advance settlement
- FIN-017 Finance exceptions
- FIN-018 Audit/history
- FIN-019 Export/reporting
- FIN-020 Bank integration adapter

# PAY — Payroll

- PAY-001 Payroll period
- PAY-002 Eligibility snapshot
- PAY-003 Payroll input collection
- PAY-004 Compensation components
- PAY-005 Overtime input
- PAY-006 Deduction
- PAY-007 Bonus/allowance
- PAY-008 Expense reimbursement input
- PAY-009 Advance deduction
- PAY-010 Payroll calculation
- PAY-011 Payroll exceptions
- PAY-012 Draft review
- PAY-013 Manual controlled adjustment
- PAY-014 Payroll versioning
- PAY-015 Payroll approval
- PAY-016 Payment batch
- PAY-017 Employee payment result
- PAY-018 Partial payroll payment
- PAY-019 Reconciliation
- PAY-020 Payslip
- PAY-021 Payslip notification
- PAY-022 Correction/reversal run
- PAY-023 Final/offboarding pay context

# CLIENT — Client Portal/Experience

- CLIENT-001 Client organization
- CLIENT-002 Client users/roles
- CLIENT-003 My HILTECH home
- CLIENT-004 Client project view
- CLIENT-005 Client site view
- CLIENT-006 Client progress
- CLIENT-007 Client documents
- CLIENT-008 Client-visible evidence
- CLIENT-009 Client approval
- CLIENT-010 Variation approval
- CLIENT-011 Handover acceptance
- CLIENT-012 Support issue
- CLIENT-013 Ticket status
- CLIENT-014 Maintenance/service
- CLIENT-015 Client invoice view
- CLIENT-016 New work/RFQ
- CLIENT-017 Client notification preferences

# SUPP — Supplier

- SUPP-001 Supplier organization
- SUPP-002 Supplier users
- SUPP-003 Supplier RFQ inbox
- SUPP-004 Quote response
- SUPP-005 PO view
- SUPP-006 PO confirmation
- SUPP-007 Amendment
- SUPP-008 Delivery status
- SUPP-009 Invoice submission
- SUPP-010 Return/rejection context
- SUPP-011 Supplier notification

# SUBC — Subcontractor

- SUBC-001 Subcontractor organization
- SUBC-002 Assigned work packages
- SUBC-003 Scope/document access
- SUBC-004 Site/schedule
- SUBC-005 Evidence submission
- SUBC-006 Issue/RFI
- SUBC-007 Work completion
- SUBC-008 Rework
- SUBC-009 Acceptance
- SUBC-010 Material/tool custody if applicable
- SUBC-011 Commercial milestone visibility

# SEC — Security / Facilities

- SEC-001 Office/security home
- SEC-002 Camera registry
- SEC-003 Camera live view integration
- SEC-004 Camera event deep link
- SEC-005 Access-control event
- SEC-006 Door/zone model
- SEC-007 Visitor record
- SEC-008 Temporary visitor access
- SEC-009 Warehouse access correlation
- SEC-010 Critical security alert
- SEC-011 Security audit access
- SEC-012 Restricted security permission model

# DOC — Documents / Evidence

- DOC-001 Document object
- DOC-002 Document versioning
- DOC-003 Attachment
- DOC-004 Object-context linking
- DOC-005 Latest-approved revision
- DOC-006 Document preview
- DOC-007 Download/cache
- DOC-008 Client-visible sharing
- DOC-009 Acknowledgement
- DOC-010 Handover document generation/index
- DOC-011 Evidence retention
- DOC-012 Search metadata

# APR — Approval Engine

- APR-001 Approval request
- APR-002 Policy evaluation
- APR-003 Sequential approval
- APR-004 Parallel approval
- APR-005 Approve
- APR-006 Reject
- APR-007 Request change
- APR-008 Delegation
- APR-009 Expiry
- APR-010 Escalation
- APR-011 Version binding
- APR-012 Re-authentication
- APR-013 Approval audit

# COMM — Inbox / Notifications / Communication

- COMM-001 HILTECH Inbox
- COMM-002 Action Required item
- COMM-003 Critical alert
- COMM-004 Status update
- COMM-005 Digest
- COMM-006 Push notification
- COMM-007 Email notification
- COMM-008 SMS notification when justified
- COMM-009 Desktop notification
- COMM-010 Deep link
- COMM-011 Read/acted state
- COMM-012 Notification preferences
- COMM-013 Quiet hours
- COMM-014 Deduplication/aggregation
- COMM-015 Contextual comments/mentions

# AUTO — Automation / AI

- AUTO-001 Workflow orchestration
- AUTO-002 Derived readiness state
- AUTO-003 Reminder/escalation
- AUTO-004 Low-stock automation
- AUTO-005 Calibration automation
- AUTO-006 Project risk detection
- AUTO-007 Handover completeness
- AUTO-008 Payroll exception detection
- AUTO-009 Invoice matching automation
- AUTO-010 Offboarding orchestration
- AUTO-011 Executive digest
- AUTO-012 AI authorized search
- AUTO-013 AI company summary
- AUTO-014 AI explain delay/exception
- AUTO-015 AI draft summary
- AUTO-016 Automation audit/kill switch

# OFF — Offline / Sync

- OFF-001 Local database
- OFF-002 Offline job bundle
- OFF-003 Cached drawings/documents
- OFF-004 Offline evidence queue
- OFF-005 Sync engine
- OFF-006 Retry/backoff
- OFF-007 Visible sync state
- OFF-008 Conflict detection
- OFF-009 Conflict resolution
- OFF-010 Server-authoritative action guard
- OFF-011 Stale data indicator
- OFF-012 Device recovery/re-provision

# INT — Integrations

- INT-001 Banking adapter
- INT-002 NVR/VMS adapter
- INT-003 ONVIF camera integration path
- INT-004 Access-control adapter
- INT-005 QR/barcode
- INT-006 Push provider
- INT-007 Email provider
- INT-008 SMS provider
- INT-009 GPS/asset telemetry adapter
- INT-010 IoT adapter
- INT-011 Test-equipment import where possible
- INT-012 Public website RFQ
- INT-013 External accounting/export if required

# OPS — System Operations

- OPS-001 Observability
- OPS-002 Audit logs
- OPS-003 Backup/recovery
- OPS-004 Feature flags
- OPS-005 Integration health
- OPS-006 Background job health
- OPS-007 Sync diagnostics
- OPS-008 Notification diagnostics
- OPS-009 Support/admin tools
- OPS-010 Data retention controls

---

## Rules

1. IDs are stable once referenced by implementation/tests.
2. Renaming feature title does not casually change ID.
3. New features must enter this catalog before implementation.
4. Every BUILD_READY feature must link to:
   - role(s)
   - object(s)
   - workflow
   - permissions
   - experience surface
   - technical owner/module
   - tests
5. A feature can be intentionally deferred but cannot be silently forgotten.

## Current state
All entries are DISCOVERED unless another document explicitly advances their status.

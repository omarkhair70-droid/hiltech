# HILTECH Object Specification Index

Status: ACTIVE INDEX / GROWING

## Purpose
Track which discovered business objects have exact field-level specifications and which still need deeper work.

---

# Specified — First Pass

## Project / Delivery
- Project
- Site
- Area/Room/Zone
- Milestone
- WorkPackage
- WorkOrder
- Task
- Blocker
- Change/Variation

File:
`object-specs/PROJECT_SITE_WORK_OBJECTS.md`

## Asset / Warehouse
- Asset
- AssetTag
- AssetMovement
- AssetIncident
- CalibrationRecord
- Warehouse
- StorageLocation
- StockItem
- StockBalance
- StockMovement
- Reservation
- Stocktake
- ReceivingRecord

File:
`object-specs/ASSET_STOCK_WAREHOUSE_OBJECTS.md`

## People / Payroll
- Person
- Employee
- Employment
- EmployeeDocument
- Certification
- AttendanceRecord
- Timesheet
- LeaveRequest
- Expense
- Advance
- CompensationReference
- PayrollPeriod
- PayrollRun
- PayrollEmployeeLine
- Payslip
- OffboardingCase

File:
`object-specs/PEOPLE_PAYROLL_OBJECTS.md`

## Procurement / Finance
- PurchaseRequirement
- SupplierQuote
- PurchaseOrder
- Delivery
- ReceivingDiscrepancy
- SupplierInvoice
- MatchResult
- ClientInvoice
- Receivable
- Payable
- PaymentInstruction
- PaymentAttempt
- Allocation
- Reconciliation
- BankTransaction

File:
`object-specs/PROCUREMENT_FINANCE_OBJECTS.md`

## Support / Documents / Security
- SupportTicket
- SLAInstance
- MaintenanceVisit
- Finding
- Document
- DocumentVersion
- ObjectDocumentLink
- Evidence
- HandoverPackage
- Facility
- SecurityZone
- Camera
- Door/AccessPoint
- AccessEntitlement
- AccessEvent
- SecurityIncident
- IntegrationConnection

File:
`object-specs/SUPPORT_DOCUMENT_SECURITY_OBJECTS.md`

---

# Still Need Exact Specs

## Identity / Organization
- UserIdentity
- Device
- Session
- Organization
- OrganizationMembership
- Contact
- Role
- Team
- Delegation

## Sales
- Opportunity
- Tender/RFQ
- SiteVisit
- Scope
- BOQ
- Costing
- Quote/QuoteVersion
- Contract
- ClientPO

## Approval
- ApprovalRequest
- ApprovalPolicy
- ApprovalStep
- ApprovalDecision
- ApprovalDelegation/Escalation

## Notifications / Inbox
- InboxItem
- Notification
- DeliveryAttempt
- Preference
- Digest

## Automation
- AutomationDefinition
- AutomationRun
- Rule
- AIRequest/Audit if adopted

## Managed Service / NOC
- ServiceContract
- CoverageRule
- SLADefinition
- PreventiveSchedule
- ManagedService
- MonitoringProfile
- MonitoredNode/Edge
- Incident linkage

## Audit
- AuditEvent

## Sync
- DeviceOperation
- SyncCursor
- Conflict
- UploadSession

---

# Completion Rule

An object is not DATA-SPEC COMPLETE until it has:
- purpose,
- owner module,
- ID strategy,
- fields/types,
- required/optional rules,
- classification,
- relationships,
- state/lifecycle,
- commands,
- events,
- invariants,
- audit requirements,
- retention,
- offline policy,
- permission rules,
- schema/API representation.

Current objects are still first-pass specs, not schema-frozen.

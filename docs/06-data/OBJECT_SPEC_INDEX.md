# HILTECH Object Specification Index

Status: STRONG FIRST PASS / NOT SCHEMA-FROZEN

## Purpose
Track which discovered business objects have exact field-level specifications and which still need deeper work.

---

# Specified — First Pass

## Identity / Organization
- UserIdentity
- Device
- Session
- Organization
- OrganizationMembership
- Contact
- RoleDefinition
- Team
- TeamMembership
- Delegation

File:
`object-specs/IDENTITY_ORGANIZATION_OBJECTS.md`

## Sales / Commercial
- Opportunity
- Tender/RFQ
- SiteVisit
- Scope
- BOQ
- Costing
- Quote
- Contract
- ClientPurchaseOrder
- CommercialApprovalContext

File:
`object-specs/SALES_COMMERCIAL_OBJECTS.md`

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

## Approval / Inbox / Notification / Sync / Audit
- ApprovalRequest
- ApprovalStep
- ApprovalAssignment
- ApprovalDecision
- InboxItem
- Notification
- NotificationDeliveryAttempt
- NotificationPreference
- DeviceOperation
- SyncCursor
- SyncConflict
- UploadSession
- AuditEvent

File:
`object-specs/APPROVAL_NOTIFICATION_SYNC_OBJECTS.md`

## Maintenance / Managed Service / NOC
- ServiceContract
- CoverageRule
- SLADefinition
- PreventiveSchedule
- MaintenanceChecklistTemplate
- ManagedService
- MonitoringProfile
- MonitoredNode
- MonitoredEdge
- MonitoringSignal concept
- MonitoringIncidentLink
- ServiceHealthSummary

File:
`object-specs/SERVICE_NOC_OBJECTS.md`

---

# Still Need Deeper Exact Specs / Decisions

These areas are discovered but not yet fully exact enough for schema freeze:

## Approval Policy Internals
- ApprovalPolicy
- approval condition language
- escalation policy
- delegation policy

## Automation
- AutomationDefinition
- AutomationRun
- Rule
- AIRequest/Audit if adopted

## Client-specific service objects
Some may remain projections over Organization/Projects/Support rather than separate entities.

## Search / Read Projections
Read-model catalog exists conceptually but exact schemas remain.

## High-volume monitoring telemetry
Storage strategy intentionally not frozen in OLTP model.

---

# Cross-cutting Models Now Defined

- `DATA_CLASSIFICATION_AND_RETENTION.md`
- `COMMAND_CATALOG.md`
- `DATA_MODEL_FREEZE_GAPS.md`
- transition tables for high-risk domains
- `OBJECT_ACTION_PERMISSION_MATRIX.md`
- `FIELD_LEVEL_ACCESS_MATRIX.md`
- `OFFLINE_CLASSIFICATION_MATRIX.md`
- API / error / ID-versioning / read-model conventions

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

Current state:
Strong domain-data first pass. Not schema-frozen until reality validation + technical decisions.

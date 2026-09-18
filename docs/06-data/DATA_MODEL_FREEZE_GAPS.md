# HILTECH Data Model Freeze Gaps

Status: ACTIVE TRACKER

## What is now covered

First-pass exact specs exist for:
- identity/devices/sessions.
- organizations/memberships/roles/teams/delegation.
- people/employment/HR.
- sales/tenders/commercial.
- projects/sites/work/changes.
- assets/warehouse/stock.
- procurement.
- finance/payments.
- payroll.
- approvals.
- notifications/inbox.
- sync/upload/audit.
- support/maintenance.
- documents/evidence/handover.
- security/facilities.
- managed service/NOC.

Also exists:
- transition tables.
- command catalog.
- field-level access matrix.
- data classification.
- offline classification.

---

# Blocking Before Data Freeze

## Reality
- actual field names/document requirements from HILTECH.
- payroll/accounting/legal fields.
- project/site hierarchy.
- warehouse units/categories.
- supplier/client commercial fields.
- existing IDs/codes/naming conventions.

## Ownership
- Asset vs Warehouse custody final boundary.
- Clients module scope.
- Partners module need.
- Attendance ownership/source.
- Finance project-cost ownership.

## Legal / Retention
- employee/HR retention.
- financial retention.
- access/camera retention.
- client confidentiality.
- tax/e-invoice fields.

## Technical
Still open:
- UUID creation strategy / exact ID representation.
- exact server/local schema split.
- exact serialization schemas.
- Flyway baseline/versioning and migration conventions after exact schemas.
- final jOOQ generated-schema/codegen/package conventions.
- exact object-storage metadata/table schema.
- exact Ktor/API transport contracts after SPIKE-15.
- production object-storage provider.

Already decided/proven:
- PostgreSQL authoritative transactional store — ADR-004.
- jOOQ persistence access layer — ADR-005.
- Room3/SQLite local DB — ADR-006.
- OpenFGA object/action authorization — ADR-009.
- offline command persistence/replay semantics — ADR-011.
- S3-compatible binary evidence protocol — ADR-010.

## Performance
- high-volume telemetry separated from OLTP.
- audit/event volume strategy.
- exact PostgreSQL indexes/read projections after real query shapes.
- portfolio/read projection schemas.

Search architecture itself is decided PostgreSQL/read-model first via ADR-018; a dedicated search engine is not baseline.

---

# Output Required Before Freeze

1. DATA_DICTIONARY.md with every implementation field.
2. Exact DB schema/migrations plan.
3. API request/response schemas.
4. Per-object transition/action tests.
5. Permission policy tests.
6. Retention/classification mapping.
7. Local/offline schema.
8. Read-model catalog.
9. External ID mappings.
10. Migration/import plan from current spreadsheets/tools if needed.

Current state:
Strong DOMAIN DATA MODEL, not schema-frozen.

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
- UUID creation strategy.
- server/local schema split.
- exact serialization.
- jOOQ/Flyway decision.
- OpenFGA model.
- sync command persistence.
- object storage metadata.

## Performance
- high-volume telemetry separated from OLTP.
- audit/event volume strategy.
- search/index strategy.
- portfolio/read projections.

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

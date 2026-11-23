# Phase 11 C3 positive proof and operator maintenance agenda — 2026-11-23

Draft owner branch: phase11/handover-service-20261122, PR #167. Not merged, deployed or enabled.

## Verified-vs-unverified C3 behavior
Earlier disposable PostgreSQL checks rejected a forged internal technical completion on IN_PROGRESS work and updates to the immutable review ledger. The new positive fixture creates a synthetic required COMPLETE source checklist and advances the fixture WorkOrder to ACCEPTED with a new version, then appends an internal technical completion referencing exact current source version and READY visit Evidence. It verifies the resulting event and appends an explicit RECHECK_REQUESTED event. All fixture rows roll back. No company or client acceptance is implied, and the new exact-head CI still must run.

## Manager-only maintenance planning agenda (read model; no extra tables)
- GET /v1/projects/{projectId}/maintenance-visits/agenda?from=UTC-ISO&to=UTC-ISO checks authenticated Project manager permission and an increasing explicit half-open UTC time window.
- Reads existing service_maintenance_visit_plan DRAFT records by indexed project and planned time, actual WorkOrder title/status/version, and latest internal technical review event. Does not create or amend a maintenance schedule.
- The returned list is ordered by planned time and immutable visit ID; at most 200 rows are shown. The server reads 201 rows and exposes truncated=true if more records exist, so the operator narrows the time window rather than trusting a partial list.
- visitWindowState is purely temporal: FUTURE_DRAFT_VISIT or DRAFT_VISIT_WINDOW_REACHED. This is NOT a missed contractual visit, SLA breach or proof work occurred.
- Every item stays originalPlanState=DRAFT, coverageState=UNVERIFIED, contractScheduleApproved=false, even if its linked WorkOrder has been accepted or its internal technical review is completed.
- KMP typed MaintenanceAgendaApiClient and standalone native Compose MaintenancePlanningAgendaSection expose a date-range query and opening actual visit detail; not mounted in integrated Windows or Android yet.
- No recurrence frequency, contract-approved date, client contact, field dispatch, billing, renewal, warranty or other company policy is invented.

## Integration and CI
- Shares hiltech.phase11.maintenance.enabled default OFF. Technical review additionally needs hiltech.phase11.maintenance.review.enabled default OFF.
- Phase11 workflow compiles additional MaintenanceAgendaPolicyTest and native MockEngine request test, Desktop Kotlin and Android debug APK; runs eight pending migrations and positive/negative source proof on disposable PostgreSQL.
- DevOps owns conflict-free Flyway numbering, combined role/tenant/revocation, real shell routing, startup and staging. Phase owner retains Draft PR #167.

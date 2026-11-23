# Phase 11 maintenance finding → existing Support Ticket

## Why
HILTECH's draft workflow `docs/05-workflows/MAINTENANCE_MANAGED_SERVICE_LIFECYCLE.md` and `CLIENT_SUPPORT_SERVICE_LIFECYCLE.md` says a field finding may need corrective support. That must connect the existing Maintenance Visit/READY Work Evidence to the canonical Support Ticket — not create another inventory, technician, contract or Client portal system.

## Delivered
- One append-only follow-up link per `service_maintenance_finding` in the same project and HILTECH organization, with a separately created `service_support_ticket`, real Manager identity, required reason, operation ID, versioned Audit. The original finding and original ticket remain immutable.
- DB composite foreign keys prevent link crossing Project/organization, source trigger locks the canonical ticket and refuses links to internally RESOLVED/CLOSED cases. An existing link cannot be overwritten; a repeated operation replays idempotently. No client communications or SLA/coverage changes.
- Manager-only `GET /v1/projects/{projectId}/maintenance-visits/{visitId}/ticket-followups` (batch list), `POST .../ticket-followups/{findingId}` (link existing).
- Typed KMP API `MaintenanceTicketFollowupApiClient`, additional fields/action in existing `MaintenanceVisitsSection`. Only eligible existing ticket choices from SupportDesk are offered, not hardcoded IDs.
- `MaintenanceTicketFollowupPolicyTest`, a Ktor MockEngine endpoint/idempotency test and PostgreSQL synthetic valid-link, forbidden duplicate, forbidden update tests, all transaction-rolled-back.

## SQL integration
Append `database/pending/phase11/11C2_maintenance_ticket_followups.sql` AFTER `11C_maintenance_visits.sql`; it also depends on `11B3_support_case_workflow.sql` for existing ticket state. DevOps assigns a unique Flyway version across parallel phases. No current source DB or project is mutated in this PR.

## Explicit limits
No automatic ticket creation, field dispatch, replacement part reservation, supplier PO, billing, external notification, SLA promise, warranty entitlement, visit completion or renewal. Source WorkOrder/READY Evidence, client approval and contract terms keep their existing owners. Only Project Managers have these P11 commands until company-approved role matrix says otherwise.

## Validation/merge gate
Do not merge/enable before latest exact-head GitHub workflow verifies Kotlin server, PostgreSQL trigger/SQL fixture and Android/Desktop native client assembly, and UX/DevOps integrate authenticated screens. Entire Phase11 remains draft/feature flags OFF in this branch.


## Source integrity repair (2026-11-23)
- A field finding is allowed only while its canonical WorkOrder is genuinely IN_PROGRESS, BLOCKED, SUBMITTED_FOR_REVIEW, REWORK_REQUIRED, ACCEPTED or CLOSED. This is checked in the Spring service **and** by the P11C insert trigger even if the API is bypassed.
- The source WorkOrder row is locked while the finding insert is committed, protecting against racing field lifecycle changes.
- READY Evidence must be WorkOrder-scoped and captured on or after the internal visit draft was created. An older image or a READY image superseded by a new READY revision is not eligible; the server's evidence count/choices and PostgreSQL trigger share this condition.
- The dedicated disposable PostgreSQL negative test now creates one historical READY source photo and rejects attempting to use it for a new visit; positive same-visit field evidence remains accepted, with no claim that the visit itself is completed.
- The server reads canonical Work and checklist/Evidence metadata in repeatable-read source snapshots so source status and counters are not mixed across in-flight field updates.
- Case resolution uses an accepted original WorkOrder row lock and attempts after REOPENED demand fresh source link and new READY evidence. Triage after internal case close is also guarded in SQL.


## Technician Android capability — guarded opt-in
The Phase 11 field-finding read/write API now reuses the authoritative WorkAssignmentSourceAuthorityPort and WorkAssignmentAuthorizationPort from Phase4/6. It does not create a TECHNICIAN role or bypass real OpenFGA.

- HILTECH Project Managers retain current management permissions: draft visit/work choices, view all visits, record finding.
- When **both** `hiltech.phase11.maintenance.enabled=true` and the separate `hiltech.phase11.maintenance.assigned-findings.enabled=true` are explicitly authorized in staging, an active assigned WorkOrder participant may list/read only visits whose underlying WorkOrder they can `can_view`.
- Recording a finding requires current active assignment AND the existing WorkOrder relation `can_submit_completion`; the server checks again inside the transaction after idempotency precheck and source Work row lock. Revoked membership and missing OpenFGA fail closed.
- The source-status response's `canRecordInternalFinding` now combines Work lifecycle **and current actor authority**; a view-only field user gets false and no record button.
- Native `MaintenanceUiState.canDraftVisit` defaults false. Only the host's manager-authorized project UI may set it true, preventing draft creation controls on a field-only visit view. The existing Field Evidence/WorkOrder lifecycle remains authoritative for capture, checklist, test and acceptance.
- The assigned findings toggle remains OFF by default and requires a HILTECH authority decision on mapping the existing `can_submit_completion` permission to the internal P11 finding action. No role/policy approvals are inferred. Negative policy tests verify disabled, revoked and OpenFGA-denied states.
- **Integration gate:** Verify an actual assigned technician account on Android (live assignment, `can_view`, `can_submit_completion`), then revoke assignment/device and confirm both new write and historical replay are denied. Test PM, technician and unrelated role/organization across Windows/Android, before enabling either flag.

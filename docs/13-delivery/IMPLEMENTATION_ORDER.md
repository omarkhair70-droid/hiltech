# HILTECH Production Implementation Order

Status: PROPOSED v0.1 / TO BE FROZEN AFTER REALITY + SPIKES

## Core Rule

Do NOT build:
Database completely -> then Backend completely -> then UI completely.

After the shared foundation is bootstrapped, HILTECH is built in complete vertical slices.

Every slice includes as applicable:
- DB migration/schema,
- backend domain/application logic,
- API command/query,
- authorization,
- audit/event,
- client/local data,
- mobile/desktop UI,
- offline behavior,
- tests,
- observability.

This keeps the architecture honest and prevents discovering broken contracts months later.

---

# PHASE 0 — Repository / Engineering Foundation

Build once:

- Gradle multi-project / final monorepo roots.
- version catalog / build conventions.
- Android app shell.
- Windows desktop app shell.
- server application.
- PostgreSQL local/dev.
- Flyway baseline.
- object storage local/dev.
- identity/auth services for dev.
- CI.
- lint/static checks.
- test infrastructure.
- structured logging / trace IDs.
- environment/secrets conventions.

Output:
all major runtimes build and test in CI.

---

# PHASE 1 — Identity, Organization, Permissions Foundation

Build:
- authentication integration.
- UserIdentity.
- Device / Session.
- HILTECH organization.
- employee/external organization membership.
- Role/Team foundations.
- authorization engine/model.
- re-auth path.
- access revocation.
- audit baseline.

Client:
- sign in.
- bootstrap session.
- permission-safe shell.
- Me / Sessions minimal.

Why first:
every later object/action depends on identity and authority.

---

# PHASE 2 — Shared Product Infrastructure

Build:
- documents/evidence metadata foundation.
- object storage upload/finalize.
- audit.
- activity events.
- approval engine foundation.
- Inbox / Work Queue foundation.
- notification abstraction.
- error model.
- API conventions.
- IDs/versioning.
- read-model/query foundation.

These are shared capabilities, not business modules pretending to be features.

---

# PHASE 3 — People / Internal Workforce Core

Build:
- employee.
- employment.
- team/manager.
- certifications.
- basic self-service.
- onboarding.
- role/team change.
- offboarding skeleton.

Do NOT wait for full payroll here.

Why:
projects, assignments, assets, warehouse and permissions need real people.

---

# PHASE 4 — Projects / Sites / Work Core

Build:
- Project.
- Site/Area.
- Milestone.
- WorkPackage.
- WorkOrder.
- Task.
- assignment.
- blockers.
- readiness framework.
- project activity/progress.

Desktop:
PM core.

Mobile:
basic assigned work.

This creates the operational spine.

---

# PHASE 5 — Assets / Warehouse

Build:
- Asset Passport.
- tags/QR.
- warehouses/storage.
- stock items.
- reservation.
- checkout/return/transfer.
- receiving.
- custody/movement ledger.
- damage/loss.
- calibration.
- stocktake/adjustment.

Connect readiness to Project/Work.

This is internal priority.

---

# PHASE 6 — Field Execution + Offline / Sync + Engineering Evidence

Build:
- Today.
- Job bundle.
- local DB.
- local command queue.
- background sync.
- evidence capture/upload.
- QR scan.
- material consumption.
- blockers.
- work completion.
- conflict recovery.
- drawings/revisions.
- test/measurement.
- supervisor/engineering acceptance/rework.

This phase proves the full mobile/desktop/shared-state thesis.

---

# PHASE 7 — Procurement

Build:
- Purchase Requirement.
- stock-before-buy.
- supplier RFQ/quote.
- comparison.
- purchase approval.
- PO/versioning.
- delivery/receipt.
- discrepancy.
- three-way match handoff.

Supplier external login can remain later; internal procurement works first.

---

# PHASE 8 — Finance Operations

Build internal finance first:
- expenses.
- employee Advance / سلفة.
- Financial Imprest / عهدة مالية.
- payables.
- receivables.
- client/supplier invoices.
- payment preparation.
- approval.
- payment result model.
- reconciliation.
- project finance read models.

Bank execution adapter only after actual bank path is validated.

---

# PHASE 9 — Payroll

Build:
- payroll period.
- compensation/input snapshot.
- overtime/deductions/bonuses.
- expense/advance inputs.
- draft calculation.
- exception review.
- controlled adjustment.
- versioning.
- Mohamed approval.
- payment batch.
- employee-level results.
- reconciliation.
- payslip.
- correction run.
- offboarding final pay.

Payroll is later than People because it consumes validated workforce/finance foundations.

---

# PHASE 10 — Sales / Tenders / Commercial

Build:
- leads/opportunities.
- tenders/RFQ.
- discovery/site visit.
- Scope/BOQ.
- Costing.
- Quote/versioning.
- approval.
- contract/client PO.
- Won -> Project conversion.

Internal Sales/Tender surface first.

---

# PHASE 11 — Handover / Warranty / Support / Maintenance

Build:
- document completeness.
- handover package/version.
- warranty.
- support ticket.
- SLA.
- maintenance contract.
- preventive schedule.
- visits/checklists/findings.
- renewal.

This converts delivery into continuing operations.

---

# PHASE 12 — Security / Facilities Integrations

Only after real hardware inventory.

Build:
- facility/zone registry.
- CCTV/NVR adapter.
- access-control adapter.
- camera/access health.
- restricted event views.
- security incidents.
- warehouse correlation.

Remote physical actions remain separately gated.

---

# PHASE 13 — Executive Control / Company Brain

Now aggregate mature internal truth:

- Mohamed Home.
- Needs You.
- Company Pulse.
- portfolio risk.
- finance pulse.
- warehouse/asset exceptions.
- security exceptions.
- daily/weekly digest.
- global search/command improvements.
- automation refinements.

Some basic owner approvals exist earlier; the rich company-brain experience matures here because it finally has real data.

---

# PHASE 14 — External Supplier / Subcontractor Surfaces

Internal records already exist.

Add restricted external experiences only when internal workflows are stable:
- supplier RFQ/PO/invoice.
- subcontract work/evidence.

---

# PHASE 15 — External Client Experience — LAST

Per Mohamed's priority:

Build Client Portal / My HILTECH only after internal operating system is stable.

Uses already mature internal data:
- projects/progress.
- actions/approvals.
- documents/handover.
- support/maintenance.
- invoices.
- new work/RFQ.

Important:
client business objects exist much earlier.
Only the external client login/surface is intentionally last.

---

# PHASE 16 — Managed Service / NOC Expansion

Conditional on real commercial roadmap:

- monitoring profiles.
- topology.
- telemetry storage.
- incidents.
- SLA/availability.
- managed-service dashboards.

Do not force this into initial company-operations build if not yet commercially required.

---

# Per-Slice Coding Order

Inside any vertical slice:

1. Freeze the object/command/read-model contract.
2. Write migration/schema + constraints.
3. Write domain/application tests first/alongside.
4. Implement server command/query.
5. Implement authorization/audit/events.
6. Implement API contract.
7. Implement shared client data/repository/local cache.
8. Implement mobile/desktop UI needed for slice.
9. Implement offline/sync if classified.
10. Integration tests.
11. UI/flow tests.
12. Observability.
13. Staging smoke.
14. Mark slice VERIFIED/OPERABLE only after real scenario passes.

So the answer to “Database or Backend first?” is:

**Foundation first; then DB + Backend + Client together per vertical slice.**

Never build the whole database in isolation first.
Never build the whole backend before seeing real client behavior.

---

# First Production Proof Candidate

After foundation, one strong end-to-end proof:

PM Desktop
-> creates/assigns Work Order
-> Warehouse reserves/checks out Asset
-> Technician Android receives Job
-> goes offline
-> scans/captures evidence/completes
-> reconnects/syncs
-> Supervisor accepts
-> PM Desktop updates
-> audit/activity exists

If this works correctly, the hardest HILTECH architecture assumptions are proven together.

---

# Client Priority Rule

Internal OS:
FIRST.

External Client UI:
LAST.

Do not spend implementation time polishing Client Portal while Ahmed/warehouse/field/company core is incomplete.

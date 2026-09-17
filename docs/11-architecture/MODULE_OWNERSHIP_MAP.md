# HILTECH Module Ownership Map

Status: ARCHITECTURE MODEL v0.1 / NOT FROZEN

## Purpose
Assign authoritative ownership of business truth before schema/code creation.

One fact should have one authoritative owner.

Other modules may reference, query, subscribe, or project that fact, but must not silently become second owners.

---

# identity

Owns:
- link between HILTECH user and external authentication subject.
- session/security metadata owned by HILTECH side.
- device registrations relevant to HILTECH.
- account activation state where not owned purely by IdP.

Does NOT own:
- employee role in company.
- client organization relationship.
- project assignment.

Depends on:
- organizations for membership context.
- external IdP adapter.

---

# organizations

Owns:
- organizations.
- HILTECH organization.
- client organizations.
- supplier/subcontractor organizations.
- memberships/relationship type.
- contacts where organization-level.

Does NOT own:
- employment lifecycle.
- supplier PO.
- client project.

Provides organizational scope to authorization.

---

# people

Owns:
- employee.
- employment.
- org/team/manager relationship for HILTECH workforce.
- HR documents metadata/reference.
- certification/training.
- attendance/time source where defined.
- leave.
- employee lifecycle.
- onboarding/offboarding business state.

Does NOT own:
- payroll calculation.
- asset movement.
- project work completion.

Emits:
- employee.activated
- employee.role_changed
- employee.offboarding_started
- employee.offboarded
- certification.expiring

---

# sales

Owns:
- lead/opportunity.
- tender/RFQ.
- discovery/site visit.
- sales scope/assumptions.
- quote and quote versions.
- commercial sales approval request context.
- opportunity outcome.

Does NOT own:
- delivered project.
- client invoice.
- internal procurement.

On win:
creates/requests project baseline through projects module.

---

# projects

Owns:
- project identity.
- project baseline.
- project sites/context.
- milestones.
- project lifecycle.
- client project relationship.
- high-level project health/progress projection.

Does NOT own:
- individual field execution detail if work module owns it.
- inventory.
- payroll.
- invoice ledger.

---

# work

Owns:
- work package.
- work order.
- task.
- assignment.
- readiness state/model.
- field execution lifecycle.
- blockers tied to work.
- accepted/rework state.

Does NOT own:
- engineering test canonical record if engineering owns it.
- material stock movement.
- employee identity.

Consumes:
people availability/eligibility,
warehouse/assets readiness,
engineering requirements,
project context.

---

# engineering

Owns:
- technical review.
- technical issue/RFI where engineering-scoped.
- test record.
- technical measurements.
- drawing/spec approval/revision relationship where HILTECH owns revision state.
- technical acceptance/rework decision.
- technical handover artifacts.

Does NOT own:
- binary file storage.
- generic project lifecycle.

---

# assets

Owns:
- trackable asset identity.
- model/serial/tag.
- lifecycle/condition.
- maintenance.
- calibration.
- warranty.
- asset incident.

Custody/movement boundary with warehouse must be frozen by spike/design.
Preferred direction:
Assets owns asset identity/lifecycle.
Warehouse owns physical stock/custody movement while asset module exposes current projection.

---

# warehouse

Owns:
- warehouse/storage locations.
- stock items/quantity.
- reservations.
- stock movement.
- asset custody movement.
- receiving operational record.
- stocktake/adjustments.
- issue/return/transfer.

Does NOT own:
- supplier PO.
- asset technical maintenance details.
- project costing calculation.

---

# procurement

Owns:
- purchase requirement.
- supplier RFQ.
- supplier quote/version.
- comparison.
- purchase order/version.
- delivery expectation.
- commercial procurement workflow.
- supplier invoice matching context before finance obligation.

Does NOT own:
- actual inventory movement after receiving.
- authoritative payable/payment.

---

# finance

Owns:
- receivable.
- payable.
- invoice financial lifecycle.
- payment instruction/business payment record.
- payment result/reconciliation.
- project financial projection/ledger where designed.
- employee expense reimbursement financial state.
- advance financial settlement.

Does NOT own:
- payroll calculation detail.
- procurement PO.
- HR employment facts.

---

# payroll

Owns:
- payroll period/run/version.
- payroll employee line snapshot.
- compensation components used for run.
- payroll exceptions.
- payment batch preparation linkage.
- payslip.
- correction run.

Consumes:
People eligibility and HR facts.
Finance/banking executes/reconciles payment through explicit boundary.

Important:
Payroll computes obligation.
Finance/banking owns external money movement/reconciliation.

---

# clients

Owns:
- client-facing configuration/preferences.
- client role mapping beyond general organization membership where necessary.
- client support/service relationship surface.
- client-visible projection policy where centralized.

Does NOT duplicate project.
Client project view references projects module.

---

# partners

May unify supplier/subcontractor external-user experience and metadata.

Supplier commercial truth remains procurement.
Subcontract work truth remains work/projects.

This module may be split or removed if organizations + domain modules cover all needs.

Status: QUESTIONABLE boundary.

---

# approvals

Owns:
- approval request.
- policy evaluation result.
- approval steps.
- decisions.
- delegation/expiry.
- version binding metadata.

Does NOT own subject business object.

Subject module decides what approval means after approval completion.

---

# documents

Owns:
- document identity.
- document version.
- attachment metadata.
- storage relationship.
- classification.
- object links.
- sharing/acknowledgement metadata where generic.

Does NOT own business meaning such as "test passed".

---

# inbox

Owns:
- durable user action/inbox item.
- read/acted/suppressed state.
- contextual assignment.

Does NOT own business event or notification delivery.

---

# notifications

Owns:
- notification policy evaluation.
- channel dispatch state.
- delivery attempts/status.
- user notification preferences.

Business event is input.
Notification is output.

---

# security

Owns HILTECH business representation of:
- security zones/doors/cameras registry.
- security incident/context.
- visitor/access workflow if implemented.

External controller/NVR remains physical authority.

---

# automation

Owns:
- automation definition/registry.
- deterministic orchestration not naturally owned by one domain.
- automation execution audit.
- later AI assistant policies.

Do not move normal domain rules here.

---

# integrations

Owns:
- adapter implementations.
- external connection configuration.
- integration health.
- external correlation metadata.

Does not own business truth.

---

# audit

Owns:
- immutable audit record infrastructure.
- audit query/access controls.

Domains produce auditable actions/events.

---

# Cross-Module Boundary Examples

## Asset checkout
Work creates requirement.
Warehouse validates/reserves/checks out.
Assets reflects lifecycle/custody projection.
Projects gets resource readiness.
Audit records action.

## Payroll
People supplies employee eligibility.
Payroll calculates exact run/version.
Approvals authorizes.
Finance prepares/executes bank path.
Notifications publishes payslip availability.
Audit records sensitive decisions.

## Procurement
Project/warehouse creates requirement.
Procurement sources/orders.
Warehouse receives.
Procurement matches supplier invoice context.
Finance creates payable/payment.
Project cost projection consumes approved usage/financial events.

## Client variation
Projects/work detects change.
Sales/commercial or projects builds commercial impact depending final policy.
Approvals handles internal authority.
Client user decision captured.
Projects updates baseline/version.
Finance receives commercial impact.

---

# Freeze Gate

Before server bootstrap:
- unresolved ownership conflicts must be zero for first implementation slice.
- every table has an owning module.
- every cross-module write has explicit command/event path.
- circular module dependencies prohibited.

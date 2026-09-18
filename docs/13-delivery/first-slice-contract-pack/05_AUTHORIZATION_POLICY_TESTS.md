# 05 — Authorization / Policy / Test Matrix

Status: **CONTRACT CANDIDATE v0.1**
Date: 2026-09-18

## Locked architecture

Cross-system relationship consistency:
`15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`

- Keycloak authenticates subject/session — ADR-008.
- HILTECH resolves product identity/context.
- OpenFGA answers relationship/object-action authorization — ADR-009.
- application/domain policy enforces lifecycle, thresholds, field redaction, re-auth/MFA, exact-version and other obligations.
- UI visibility is never enforcement.
- offline replay re-authenticates/re-authorizes.
- deny by default.
- current role/title is not hard-coded authorization truth.
- normal company authority is configurable through relationships/policies.

---

# 1. Authorization decision contract

Every critical server command evaluates in this order:

1. authenticated subject/session.
2. identity/device not revoked.
3. organization/context membership.
4. OpenFGA relationship/action check.
5. domain object exists and is visible under policy.
6. application obligations:
   - object lifecycle,
   - exact/base version,
   - WorkType/Review/Approval policy,
   - re-auth/MFA,
   - reason/evidence,
   - online-authoritative requirement.
7. command invariants.
8. authoritative transaction + audit.

A successful OpenFGA relation does not bypass domain/state policy.
A role label does not itself grant permission.

---

# 2. First-slice relationship vocabulary — candidate

Exact model file still to be generated, but first-slice semantics require relationships equivalent to:

## organization
- member
- admin/config_admin
- owner/executive as configured relationship, not hard-coded user

## project
- project_manager
- engineer
- supervisor
- technician/member
- warehouse_context
- client_member where external visibility exists

## work_order
- assignee_user
- assignee_team/crew
- reviewer
- project-derived viewer
- creator where useful
- client_reviewer only if configured

## warehouse / storage
- warehouse_operator
- warehouse_manager
- storage_responsible
- project/site context viewer

## asset
- current_custodian
- project_context
- warehouse_context

## configuration
- config_viewer
- config_editor
- config_activator
- config_admin

## delegation
- scoped temporary relationship/permission grant with validity enforced by application policy.

Names may change; semantic separation must not.

---

# 3. Configuration Center actions

Candidate action family:

- config.view
- config.create_draft
- config.edit_draft
- config.validate
- config.activate
- config.supersede
- config.retire
- config.view_history
- config.export
- config.seed_import

Rules:
- editing draft is less privileged than activation.
- activation/supersession may require re-auth/approval by configuration family.
- specific user names are seed/policy data.
- config changes are always audited.
- invalid config cannot be activated even by an authorized admin.

---

# 4. First-slice business actions

| Action | Relationship/policy | Domain obligation | Online | Audit |
|---|---|---|---|---|
| CreateWorkOrder | project create authority + config policy | valid project/site + ACTIVE WorkType/policies | YES | YES |
| AssignWork | project assignment authority | AssignmentPolicy + eligible target + version | YES | YES |
| StartWork | assigned executor | current assignment + readiness + version | LF replay | YES |
| BlockWork | assigned executor/supervisor policy | valid state + reason when configured | LF replay | YES |
| ResumeWork | assigned executor/supervisor policy | blockers cleared/allowed | LF replay | YES |
| SubmitWorkCompletion | assigned executor | bound EvidencePolicy satisfied + version | LF replay | YES |
| AcceptWork | ReviewPolicy-selected reviewer | exact submitted version + obligations | YES | YES |
| RequestRework | ReviewPolicy-selected reviewer | reason/evidence obligations | YES | YES |
| CancelWork | configured cancel authority | valid state + reason/policy | YES | YES |
| ReserveAsset | project/warehouse policy | available/eligible asset | YES | YES |
| CheckoutAsset | warehouse/custody authority | exact version + single custody + calibration/policy | YES default | YES |
| ReturnAsset | warehouse/receipt authority | identity + condition + custody | YES final | YES |
| ReportAssetDamage | custodian/assigned/warehouse relation | asset context | LF possible | YES |
| ActivateConfiguration | config_activator + policy | valid draft + dependencies + version | YES | YES |

---

# 5. Field-level projection contract

Object authorization and field projection are separate.

## Technician job bundle

Allowed candidate fields:
- assigned WorkOrder identity/state/version.
- safe Project/Site identity.
- job-needed location/access/contact subset.
- instructions/drawings.
- readiness/evidence requirements.
- assigned/relevant asset/material refs.
- own/crew assignment context.

Excluded by default:
- internal budget/cost/margin.
- acquisition cost.
- unrelated employee records.
- unrestricted site security notes.
- finance/client commercial fields.

## Warehouse

Can see operational asset/stock/custody context.
Acquisition/financial values require separate policy.
Security-correlated history remains restricted.

## PM

Can see assigned/context project operational truth.
Finance-sensitive fields remain separately permissioned.

## External/client

Explicit organization/project relationship only.
Object existence itself may need hiding rather than PERMISSION_DENIED disclosure.

---

# 6. Error semantics

Authorization/policy result must distinguish safely:

- UNAUTHENTICATED
- REAUTH_REQUIRED
- PERMISSION_DENIED
- OBJECT_NOT_VISIBLE
- REJECTED_STATE
- REJECTED_VALIDATION
- VERSION_CONFLICT
- POLICY_CHANGED / POLICY_REQUIREMENT_NOT_MET where safe/useful
- DEVICE_REVOKED / SESSION_REVOKED where safe for native client

External callers should not receive object existence leakage.

---

# 7. Offline authorization rule

Permission at capture time is not a durable authorization grant.

On replay server re-checks:
- identity/session/device.
- organization membership.
- current project/work relationship.
- delegation validity.
- config/policy obligations.
- current object state/version.

If rejected:
- local work/evidence remains safe.
- command does not become false-success.
- UI receives a typed recoverable/terminal result.
- support/admin can diagnose from audit/correlation without leaking private payload unnecessarily.

---

# 8. Delegation

Contract:
- delegator.
- delegate.
- scope type/ref.
- action set.
- validFrom/validUntil.
- state.
- reason.
- approval ref if required.
- audit.

Invariants:
- cannot expand authority delegator does not possess.
- expires automatically by time/policy.
- critical action may disallow delegation.
- historical decision records actual actor + delegation context.

---

# 9. Re-auth / high-risk obligations

Candidate first-slice re-auth candidates:
- critical configuration activation.
- privileged permission/delegation change.
- exceptional asset custody override/write-off.
- other actions only when policy requires.

Normal technician Start/Submit should not require repeated re-auth by default.

Exact re-auth policy is configurable where safe, but server support is a hard capability.

---

# 10. Required allow/deny tests

For every critical allow test add a negative counterpart.

Must include:

- PM on assigned project vs PM on unrelated project.
- technician assigned WorkOrder vs unrelated technician.
- team/crew assignment.
- reviewer selected by ReviewPolicy vs non-reviewer.
- warehouse operator in scope vs outside scope.
- current custodian vs unrelated employee.
- external client member vs internal-only object.
- revoked user.
- revoked device/session.
- expired delegation.
- policy changed before action.
- configuration editor cannot activate without activation authority.
- config activator cannot activate invalid config.
- field redaction tests for technician/PM/warehouse/client.
- object-hidden test for unauthorized external actor.
- offline permission revoked before replay.
- offboarded user with queued command.
- WorkOrder reassigned while offline.
- stale review exact-version rejection.
- cross-organization/cross-project reference rejection.

---

# 11. OpenFGA model deliverable

Before final freeze generate:

- first-slice authorization model file.
- tuples/relationship fixtures.
- allow/deny test fixtures.
- application obligation mapping beside each OpenFGA action.
- external organization boundaries.
- configuration-center relations/actions.
- delegation integration strategy.

Do not put financial thresholds, lifecycle rules, evidence completeness or exact-version checks inside OpenFGA when they belong to application/domain policy.

---

# 12. Freeze outcome

Current:
**authorization architecture and first-slice semantic contract are strong enough to generate the exact OpenFGA/application-policy model.**

Still open:
- exact relation/action names.
- exact config activation re-auth/approval policy.
- device trust granularity.
- object-hidden policy by API audience.
- final field-projection DTOs.

These are freeze-closure items, not reasons to redesign identity/authorization.


---

## Projection consistency

Relationship-changing business commands do not dual-write PostgreSQL + OpenFGA blindly.

Contract:
- PostgreSQL source relationship + authorization projection intent/outbox commit together.
- OpenFGA projection uses pinned authorization model ID.
- pending grants fail closed until FGA APPLIED.
- pending revokes deny immediately even if stale FGA tuple still exists.
- stale projector revisions cannot re-grant superseded authority.
- security-sensitive immediate post-write checks may use HIGHER_CONSISTENCY.
- projection failures remain visible/repairable.

Required authorization tests include:
- FGA unavailable during grant.
- FGA unavailable during revoke.
- stale tuple after offboarding.
- stale projector grant after newer revoke.
- offline queued command after relationship revoke.

# 13 — OpenFGA First-Slice Model Candidate

Status: **CONTRACT CANDIDATE v0.2 / CONSISTENCY MODEL LINKED**
Date: 2026-09-18

## Purpose

Define the first-slice relationship authorization model that will be converted into the production OpenFGA model + tuple fixtures.

Canonical boundary:
- OpenFGA answers relationship/object-action authorization.
- HILTECH server remains final enforcement point.
- application/domain policy handles lifecycle, exact version, evidence completeness, thresholds, re-auth/MFA, field redaction and other obligations.

Canonical ADR:
`docs/11-architecture/ADR/ADR-009-OPENFGA-AUTHORIZATION.md`

Cross-system projection consistency:
`15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`

Production rules:
- PostgreSQL owns business relationship truth.
- OpenFGA is the relationship authorization projection.
- every Check/Write pins authorizationModelId.
- grant/revoke synchronization fails closed.
- relationship mutation intent is transactionally recorded with business state.
- selective HIGHER_CONSISTENCY is used after security-sensitive tuple changes, not globally.

---

# 1. Subject model

Primary subject:
`user:<userIdentityId>`

Possible related subject sets:
- team members
- organization members
- project members
- delegated relationships

Do not use display names or employee titles as tuple identity.

---

# 2. organization type

Relations candidate:

- member
- admin
- config_viewer
- config_editor
- config_activator
- owner_or_executive
- finance_member
- warehouse_member

Permissions candidate:

- view_org = member or admin
- view_config = config_viewer or config_editor or config_activator or admin
- edit_config_draft = config_editor or admin
- activate_config = config_activator or admin

Application obligations still apply:
- valid config draft
- baseVersion
- re-auth/approval when policy requires
- audit

Current specific people are tuples/seed data, not model code.

---

# 3. project type

Relations candidate:

- organization
- project_manager
- engineer
- supervisor
- technician
- warehouse_context
- client_organization
- explicit_viewer

Permissions candidate:

- view = project_manager or engineer or supervisor or technician or explicit_viewer
- create_work = project_manager or supervisor where configured relationship permits
- assign_work = project_manager or supervisor
- review_project_work = project_manager or engineer or supervisor
- view_resource_context = project_manager or engineer or supervisor or technician or warehouse_context

Important:
These are relationship candidates, not the whole business rule.

Application policy still checks:
- Project lifecycle state.
- AssignmentPolicy/ReviewPolicy.
- whether user relationship is valid for selected WorkType.
- field-level redaction.

---

# 4. site type

Relations:

- project
- explicit_member
- site_supervisor
- site_technician

Permission inheritance candidate:
- view from project viewer/member relation or explicit site relation.
- access_field_context only for users with current work/site context.

Application policy filters:
- security-sensitive notes.
- access instructions.
- contacts.
- location/tracking details.

---

# 5. work_order type

Relations:

- project
- assigned_user
- assigned_team
- assigned_subcontractor
- reviewer
- creator
- explicit_viewer

Permissions candidate:

- view
- start
- block
- resume
- submit_completion
- review
- request_rework
- cancel
- view_evidence

Possible semantics:

- assigned_user grants start/block/resume/submit subject to application eligibility.
- reviewer grants review/request_rework subject to bound ReviewPolicy and exact submitted version.
- project relation may grant PM/supervisor view/assign/cancel through project permissions.

Do not encode:
- readiness completeness
- evidence completeness
- current lifecycle
- baseVersion
- instruction revision
inside FGA.

---

# 6. warehouse type

Relations:

- organization
- operator
- manager
- viewer

Permissions:
- view_inventory
- receive
- checkout
- return
- transfer
- count
- request_adjustment

Adjustment approval should normally bind ApprovalPolicy/application authority rather than a broad warehouse relation alone.

---

# 7. storage_location type

Relations:

- warehouse
- project
- site
- responsible
- operator
- viewer

Permission candidate:
- view
- move_in
- move_out
- count

For Project/Site storage:
project/site relationship can grant contextual visibility without granting unrestricted warehouse administration.

---

# 8. asset type

Relations:

- warehouse
- storage_location
- current_custodian
- project
- site
- work_order
- explicit_viewer

Permissions candidate:

- view
- reserve
- checkout
- return
- transfer
- report_damage
- report_missing
- view_history

Relationship rule examples:
- warehouse operator may checkout.
- current custodian may return/report damage.
- assigned project/work users may view operational passport subset.
- finance/acquisition-cost visibility is separate application field permission.

Do not encode current lifecycle/calibration/version in FGA.

---

# 9. stock_item / stock_location context

Relations:
- warehouse
- storage_location
- project_context
- operator
- viewer

Permissions:
- view_quantity
- reserve
- issue
- consume
- return
- count

Application policy enforces:
- quantity.
- reservation.
- negative-stock prevention.
- units.
- adjustment approval.

---

# 10. evidence type

Relations:
- work_order
- creator
- reviewer
- client_visible_to_org

Permissions:
- view
- reserve_upload
- finalize
- download
- review

Application policy enforces:
- evidence requirement.
- file/type/size.
- classification.
- checksum/finalization.
- retention.
- client visibility stage.

---

# 11. configuration_revision type

Relations:
- organization
- editor
- activator
- viewer

Permissions:
- view
- edit_draft
- validate
- activate
- supersede
- retire
- view_history

Rules:
- organization config roles may derive these permissions.
- family-specific restrictions can be application policy or extra relations if needed.
- activation remains online-authoritative.

---

# 12. team type

Relations:
- organization
- manager
- member

Permissions:
- view
- manage_membership
- assign_as_work_target where application policy permits

OpenFGA can model team membership inheritance.
Application policy still checks qualification/certification/AssignmentPolicy.

---

# 13. delegation

Delegation is time-bounded and scope-bounded.

Candidate approach:
- represent the delegated relation/permission through tuples while active.
- application owns validFrom/validUntil/state/approval/reason.
- tuple is written when delegation becomes active.
- tuple is removed/revoked when delegation expires/revokes.
- audit stores delegator/delegate/scope/action context.

Never allow delegation tuple to expand beyond delegator's real authority.

Critical actions may reject delegated authority even when relation exists.

---

# 14. Action → FGA relation → application obligation map

| Action | FGA question | Application obligations |
|---|---|---|
| CreateWorkOrder | can user create_work on Project | Project state; ACTIVE WorkType/policies; input validation |
| AssignWork | can user assign_work on Project/Work | AssignmentPolicy; assignee eligibility; baseVersion |
| StartWork | can user start WorkOrder | assignment current; readiness; lifecycle; version |
| BlockWork | can user block WorkOrder | lifecycle; reason policy; version |
| ResumeWork | can user resume WorkOrder | blocker resolution; lifecycle; version |
| SubmitCompletion | can user submit WorkOrder | EvidencePolicy; tests/material declarations; version |
| AcceptWork | can user review WorkOrder | bound ReviewPolicy; exact submitted version; evidence/technical obligations |
| CancelWork | can user cancel WorkOrder | lifecycle; cancel policy; reason; version |
| ReserveAsset/ReserveStock for Project | can user request_resource on Project + can user view/request relevant Warehouse resource context | availability; reservation collision; quantity/calibration; target Project/Site/Work |
| CheckoutAsset | can user checkout Asset/Warehouse | asset state; version; custody; reservation; calibration; recipient/context |
| ReturnAsset | can user return Asset | current custody; condition/inspection; destination; version |
| ConsumeStock | can user consume Stock/Work context | issued/available quantity; unit; Work context |
| ActivateConfig | can user activate ConfigRevision | valid draft; dependencies; version; re-auth/approval if required |
| ViewEvidence | can user view Evidence | field/classification/client-visibility filtering |
| DownloadEvidence | can user download Evidence | classification; signed URL policy; retention |

---

## Resource request composition rule

Resource reservation is contextual.

A Project PM/Engineer/Supervisor can request a resource for Project X even before the selected Asset is related to Project X.

Therefore authorization composes checks:
1. actor can_request_resource on Project X,
2. actor has sufficient resource/warehouse visibility or request path for the selected item,
3. application policy validates availability/reservation/calibration/quantity and creates the Project reservation.

Do not require a pre-existing Asset→Project tuple merely to request the Asset.

# 15. Field-level obligations outside OpenFGA

Examples:

Project:
- internal budget/cost/margin.
- sell value.
- payment terms.

Site:
- security notes.
- access instructions.
- contacts/location subset.

Asset:
- acquisition cost.
- security-correlated movement/location.

Employee:
- personal/bank/compensation.

Evidence:
- restricted/client visibility.

These are filtered in server read models/DTO projection.

Do not return sensitive field then hide only in UI.

---

# 16. External organization boundary

Client/Supplier/Subcontractor access must derive from explicit organization relationships.

Rules:
- no broad HILTECH membership inheritance to external users.
- project/work/evidence relations are explicit.
- unauthorized external object may return OBJECT_NOT_VISIBLE.
- search cannot leak object existence.
- external organization membership alone does not grant all objects for that organization without the relevant relation/policy.

---

# 17. Offline behavior

FGA decision is re-evaluated on replay.

Capture-time permission is not a tokenized permanent grant.

If relation changed while offline:
- queued command may fail PERMISSION_DENIED / OBJECT_NOT_VISIBLE.
- local evidence/intent remains preserved.
- server state is not overwritten.
- client gets typed recovery path.

---

# 18. Configuration model growth

Do not encode every company job title as a relation.

Prefer:
- structural relations.
- Team/Organization membership.
- Project/Work assignment.
- configured selectors.
- typed application obligations.

Reason:
HILTECH can grow/change titles/teams without FGA model rewrite.

Revisit model only when a new relationship semantic cannot be expressed by existing structure.

---

# 19. Required FGA fixtures/tests

Organization:
- member allow.
- outsider deny.
- config editor vs activator separation.

Project:
- PM assigned allow.
- PM unrelated deny.
- technician project/work context.
- external client scoped relation.

Work:
- assigned user allow.
- unrelated technician deny.
- team assignment allow.
- reviewer allow.
- non-reviewer deny.

Warehouse/Asset:
- warehouse operator allow.
- unrelated employee deny.
- current custodian return/report.
- PM project context view but not acquisition cost.
- checkout FGA allow + domain calibration deny test.

Configuration:
- editor can edit draft.
- editor cannot activate if not activator.
- activator can activate valid config.
- activator still cannot activate invalid config due application policy.

Delegation:
- active scoped delegation allow.
- expired/revoked deny.
- critical action application policy can disallow delegated authority.

Offline:
- relation revoked after capture -> replay deny.

External:
- client allowed client-visible object.
- unrelated external org object hidden.

---

# 20. Production artifact after freeze

Generate:
- OpenFGA model file.
- model version/migration record.
- tuple seed fixture for local/dev.
- authorization integration tests.
- application-obligation map in server code.
- tuple lifecycle handlers for Project/Work/Team/Delegation changes.
- diagnostics for denied checks with safe correlation only.

---

# Open items before final freeze

- exact relation/permission names in FGA DSL.
- exact project-derived inheritance expressions.
- team subject-set syntax/usage.
- exact configuration-family scoping.
- tuple lifecycle transaction/outbox strategy.
- caching strategy, if any.
- object-hidden policy by API audience.

Current:
**relationship semantics are concrete enough to generate the exact OpenFGA model in the final freeze pass.**

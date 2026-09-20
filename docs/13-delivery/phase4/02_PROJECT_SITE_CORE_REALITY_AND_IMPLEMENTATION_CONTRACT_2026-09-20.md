# Phase 4 / Slice 01 — Project + Site / ProjectSite Core — Reality + Implementation Contract Closure

Date: 2026-09-20  
Status: **PASS / REALITY CLOSED / IMPLEMENTATION CONTRACT FROZEN / SLICE 01 PRODUCTION CODE AUTHORIZED**

## Purpose

Close the last Slice 01 decisions before production implementation.

This closure is intentionally narrower than the Phase 4 owner-entry reconstruction and the first-slice pre-code pack. It does not rediscover Project/Site/Work architecture. It reconciles the frozen Project/Site contract with the production repository that now exists after Phase 1–3, then fixes the exact Slice 01 boundary, data ownership, commands, authorization, UI proof and regression gate.

Canonical phase inputs:
- `docs/13-delivery/phase4/00_PHASE4_OWNER_ENTRY_REALITY_RECONSTRUCTION_2026-09-20.md`
- `docs/13-delivery/phase4/01_PHASE4_SCOPE_AND_SLICE_PLAN_2026-09-20.md`
- `docs/05-workflows/PROJECT_LIFECYCLE.md`
- `docs/06-data/object-specs/PROJECT_SITE_WORK_OBJECTS.md`
- `docs/06-data/transition-tables/PROJECT_AND_WORK_TRANSITIONS.md`
- `docs/03-product/role-experiences/PROJECT_MANAGER_EXPERIENCE.md`
- `docs/10-design/wireflows/PROJECT_CONTROL_WIREFLOW.md`
- `docs/13-delivery/first-slice-contract-pack/00_CONFIGURATION_POLICY_SCHEMAS.md`
- `docs/13-delivery/first-slice-contract-pack/02_API_AND_READ_MODELS.md`
- `docs/13-delivery/first-slice-contract-pack/05_AUTHORIZATION_POLICY_TESTS.md`
- `docs/13-delivery/first-slice-contract-pack/11_PROJECT_SITE_WORK_CONTRACT.md`
- `docs/13-delivery/first-slice-contract-pack/15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`
- `docs/13-delivery/first-slice-contract-pack/16_DATABASE_DDL_CONSTRAINT_CONTRACT.md`
- `docs/13-delivery/first-slice-contract-pack/openfga/first-slice-model.fga`
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
- Phase 3 Employee / Employment / WorkforceAssignment production schema and contracts.

Repository note:
the handoff name `09_DB_CONSTRAINT_INDEX_GENERATED_CODE_CONTRACT.md` is not a current repository file. The canonical physical constraint contract is `16_DATABASE_DDL_CONSTRAINT_CONTRACT.md`. No substitute document is invented.

---

# 1. Entry gate and inherited Approval stability

The cross-cutting Approval cursor instability is closed before this contract.

Exact tested Approval head:
`241e83cbfb7587f80758653f48e2ad363fe1b653`

Exact-head evidence:
- Approval Stability `35511034523` — PASS, including deterministic non-canonical Base64url alias rejection and 12 repeated Approval contract runs.
- Bootstrap Phase 0 `35511034711` — PASS.
- Native OIDC `35511034539` — PASS.

PR #54 merged as:
`b9eee0ee5af61ffb960b3b4baa142b2896a9ac59`

Post-merge Bootstrap:
`35511320336` — PASS.

Therefore Slice 01 is not inheriting a knowingly flaky Approval foundation.

---

# 2. Slice 01 mission and hard boundary

Slice 01 owns only the durable delivery identity layer:

`Project -> ProjectSite -> Site`

with:
- Project creation/source provenance;
- Project human code;
- current Project responsibility plus immutable history;
- durable Site master;
- ProjectSite association;
- Slice-01-safe Project lifecycle;
- authorization projection;
- audit/activity/idempotency/version protection;
- Windows My Projects + Project header/detail;
- safe Project/Site read models.

Slice 01 does **not** own:
- Area planning UI or hierarchy behavior;
- Milestones;
- WorkPackages;
- WorkOrders;
- Work assignment/readiness;
- Warehouse/material/tool truth;
- full field/offline execution;
- accepted-work progress;
- Project health calculation;
- finance/commercial claim truth.

Those remain in their frozen later slices/phases.

---

# 3. Existing bootstrap schema is scaffolding, not immutable business truth

`V0004__projects_sites__core.sql` already created `project`, `site`, `project_site` and `area` during repository bootstrap.

That migration is immutable history and must **not** be edited.

The production server `projects` module is still only a module marker; Project business behavior has not been implemented. Therefore Slice 01 may make a controlled forward-only schema amendment in a new migration.

Current schema observations that require closure now:

1. `project.project_manager_id -> user_identity` predates Phase 3 People truth.
2. Phase 3 now proves that an Employee can be real business workforce truth without a login.
3. Project responsibility must also support Team responsibility and preserve reassignment history.
4. A single mutable `project_manager_id` cannot represent those requirements without dual truth/history loss.
5. `source_opportunity_id`, `contract_id`, and `client_po_id` were preallocated before Phase 10 owns those objects and currently have no authoritative foreign-object lifecycle.

These are controlled Slice 01 refinements, not a Phase reset.

---

# 4. Project source semantics — frozen

## SourceType

Slice 01 supports:

- `INTERNAL`
- `IMPORT`

Future Phase 10 adds the authoritative `AWARD` conversion path when Award/Contract/PO truth actually exists.

Slice 01 must **not** create fake Opportunity, Award, Contract or Client PO records merely to satisfy a foreign key or demo.

## Persisted Slice 01 source fields

Add to Project through a forward migration:

- `source_type varchar(16) NOT NULL`
- `source_external_reference varchar(255) NULL`

Rules:
- `INTERNAL` -> `source_external_reference IS NULL`.
- `IMPORT` -> a nonblank external/provenance reference is required.
- `source_type` is immutable after creation except controlled data correction.
- imported provenance is not interpreted as a commercial Award.
- current preallocated `source_opportunity_id`, `contract_id`, `client_po_id` stay NULL in Slice 01 and are explicitly non-authoritative.
- Phase 10 must reconcile those future typed relationships through explicit change control; it does not rewrite Project identity/history.

This keeps current Project creation real while preserving a clean future `CreateProjectFromAward` path.

## Create commands

Slice 01 exposes one production creation command:

`CreateProject`

The request explicitly chooses `INTERNAL` or `IMPORT`.

Phase 10 may later introduce `CreateProjectFromAward` using the same Project aggregate.

---

# 5. Project human code — frozen

Project UUID remains identity.

`projectCode` is allocated by the active `CodePolicy` for target `PROJECT`.

Rules:
- default sequence scope is ORGANIZATION.
- allocation uses existing `code_sequence` transactionally.
- no HILTECH prefix/year/padding is compiled into Project code.
- explicit human code is accepted only when the active CodePolicy has `manualOverrideAllowed=true`.
- code collision is a typed conflict; the server never silently renames a historical code.
- policy revision used for allocation is recorded in audit/config revision refs.
- missing/invalid active Project CodePolicy fails safely; it does not fall back to an invented format.

---

# 6. Project responsibility / PM relation — controlled amendment and frozen contract

## Business truth

Project responsibility is a Phase 4 business relationship, not a login field and not a job-title inference.

Create a new authoritative history table:

`project_responsibility`

Minimum fields:
- `id uuid PK`
- `project_id uuid NOT NULL`
- `responsibility_key varchar(64) NOT NULL`
- `principal_type varchar(16) NOT NULL` — `EMPLOYEE | TEAM`
- `principal_employee_id uuid NULL`
- `principal_team_id uuid NULL`
- `state varchar(16) NOT NULL` — `ACTIVE | ENDED`
- `effective_from timestamptz NOT NULL`
- `effective_to timestamptz NULL`
- `assigned_by_user_id uuid NOT NULL`
- `source_operation_id uuid NOT NULL UNIQUE`
- `reason text NULL`
- `created_at timestamptz NOT NULL`
- `version bigint NOT NULL DEFAULT 1`

Slice 01 responsibility key:
- `PROJECT_MANAGER`

Rules:
- exactly one principal column is present according to `principal_type`.
- Employee/Team must belong to the same HILTECH organization as the Project.
- one current ACTIVE row per `project_id + responsibility_key`.
- changing responsibility ends the old row and inserts a new row; no historical overwrite.
- Employee responsibility remains legitimate business truth even if that Employee has no login yet.
- role label / `WorkforceAssignment.role_code` never grants Project authority by itself.
- Team responsibility reuses the existing Phase 1 Team object; no duplicate crew/team master is introduced.
- temporary absence of a current responsibility is allowed in Slice 01 pre-active states.
- ACTIVE policy is not implemented in Slice 01 because Slice 01 intentionally stops before READY/ACTIVE. The later activation gate must require a resolvable authority unless the active Project policy explicitly permits another mode.

## Existing `project_manager_id`

`project.project_manager_id` must stop being authoritative.

Because no Phase 4 production Project behavior has yet used that column, Slice 01 implementation should remove it with the new forward migration rather than maintain two Project-manager truths.

Do not edit V0004.

## Identity/OpenFGA projection

For `PROJECT_MANAGER`:
- TEAM principal -> project `project_manager` relation to `team:<id>#member`.
- EMPLOYEE principal -> resolve a current linked `user_identity` through Person/Employee identity linkage and current organization access.
- Employee without usable linked identity remains valid business responsibility but has no interactive OpenFGA grant.
- commands that require an interactive Project authority must return a typed unresolved-authority failure rather than silently granting by employee name/title.

Relationship change uses the already-frozen authorization consistency contract:

PostgreSQL responsibility row
-> authorization projection intent/outbox in the same DB transaction
-> fail-closed pending grant/revoke
-> pinned-model OpenFGA application
-> audit/activity.

No direct DB + OpenFGA dual write.

---

# 7. Slice 01 Project lifecycle boundary — frozen

The full Project lifecycle remains:

`DRAFT -> KICKOFF -> PLANNING -> READY -> ACTIVE -> ...`

Slice 01 implements only:

- creation -> `DRAFT`
- `DRAFT -> StartKickoff -> KICKOFF`
- `KICKOFF -> CompleteKickoff -> PLANNING`

Slice 01 intentionally does **not** implement:
- `MarkProjectReady`
- `ActivateProject`
- hold/resume
- delivery review/handover/delivered/close.

Reason:
`MarkProjectReady` requires a real baseline plan. Slice 02 owns Areas/Milestones/WorkPackages/baseline-safe planning. Allowing READY/ACTIVE before that would either weaken the frozen lifecycle preconditions or fake planning truth.

This is a boundary decision, not removal of later lifecycle states from the database enum/check.

---

# 8. Site — frozen Slice 01 contract

Site is durable physical/client location truth and survives Project closure.

Authoritative Slice 01 fields refine the V0004 shape with explicit HILTECH tenancy:
- id
- organizationId — owning HILTECH organization
- clientOrganizationId
- siteCode
- name
- addressText?
- latitude?
- longitude?
- timezone?
- status ACTIVE/INACTIVE
- createdAt/by
- updatedAt
- version.

Rules:
- unique `organizationId + clientOrganizationId + siteCode`.
- Site HILTECH tenancy is explicit; clientOrganizationId alone is not an internal tenant boundary.
- Site may be reused by multiple Projects for the same client.
- Site must not be recreated merely because a new Project starts.
- address/geolocation are RESTRICTED fields and projected only to authorized users.
- clientOrganizationId is not casually editable.
- Site status is not ProjectSite lifecycle.

Slice 01 does not implement Area behavior beyond preserving the already-existing schema boundary for Slice 02.

---

# 9. ProjectSite — frozen Slice 01 contract

ProjectSite is the Project-specific association to a durable Site.

Fields remain:
- id
- projectId
- siteId
- projectSiteCode?
- lifecycleState
- accessInstructions?
- projectSpecificNotes?
- activeFrom?
- activeUntil?
- version.

Rules:
- unique `projectId + siteId`.
- Project and Site must belong to the same client context; mismatched client association is rejected.
- initial Slice 01 association state is `PLANNED`.
- closing/removing Project context never deletes Site.
- access instructions/project notes are permission-filtered.
- duplicate attach is idempotent only when it is the same semantic operation; otherwise typed conflict.
- association history is preserved by lifecycle/audit; no hard delete business path.

Slice 01 does not progress ProjectSite into an execution lifecycle that would pretend Project READY/ACTIVE already exists. Later Slice 02/3 work activates those transitions with their real preconditions.

---

# 10. Commands and API routes — frozen for Slice 01

## Project

- `POST /v1/projects` — CreateProject.
- `GET /v1/projects` — My Projects / authorized list.
- `GET /v1/projects/{projectId}` — Project header/detail.
- `PUT /v1/projects/{projectId}/details` — metadata only, exact baseVersion.
- `POST /v1/projects/{projectId}/change-manager` — change `PROJECT_MANAGER` responsibility.
- `POST /v1/projects/{projectId}/start-kickoff`.
- `POST /v1/projects/{projectId}/complete-kickoff`.

Routes for READY/ACTIVE and later states stay reserved by the wider contract but are not implemented in Slice 01.

## Site

- `POST /v1/sites`.
- `GET /v1/sites/{siteId}`.
- `PUT /v1/sites/{siteId}/details`.

## ProjectSite

- `POST /v1/projects/{projectId}/sites` — attach existing Site or create+attach through an explicit typed request mode.
- `GET /v1/projects/{projectId}/sites`.
- `GET /v1/project-sites/{projectSiteId}`.

No generic lifecycle PATCH.

## Common command contract

Retry-sensitive commands use:
- `operationId`
- `Idempotency-Key = operationId`
- `baseVersion` where target already exists
- `clientOccurredAt`
- correlation/trace headers
- current authenticated actor.

The existing shared command/idempotency runtime is reused.

---

# 11. Exact Slice 01 DTO/read-model boundary

## CreateProjectRequest

- operationId
- sourceType: INTERNAL | IMPORT
- sourceExternalReference?
- explicitProjectCode? only when CodePolicy allows
- name
- clientOrganizationId
- initialResponsibility?:
  - principalType EMPLOYEE | TEAM
  - principalId
- startDatePlanned?
- endDatePlanned?
- clientOccurredAt.

Commercial values are not part of the normal Slice 01 creation UI.

## ProjectCommandResult

- projectId
- projectCode
- lifecycleState
- version
- baselineVersion
- currentResponsibility summary
- appliedAt
- correlationId
- duplicateReplay.

## MyProjectListItem

- projectId
- projectCode
- name
- client safe id/label
- lifecycleState
- currentResponsibility safe summary
- planned start/end
- baselineVersion
- siteCount
- updatedAt
- version.

Do **not** fake:
- ProjectHealth
- accepted progress
- Work readiness
- material/equipment readiness
- Waiting On signals

before their owning slices exist.

## ProjectHeader

- identity/code/name
- client safe summary
- sourceType + safe provenance summary
- lifecycleState
- current responsibility + resolution state
- planned dates
- baselineVersion
- Site/ProjectSite summaries
- created/updated/version metadata permitted to the caller.

## Responsibility resolution state

Expose:
- `RESOLVED_USER`
- `TEAM`
- `BUSINESS_ONLY_NO_LOGIN`
- `UNASSIGNED`

This is descriptive state, not a permission shortcut.

## Site / ProjectSite reads

Return safe identity/status/context first.
Restricted address/geolocation/access instructions/notes are field-projected separately.

---

# 12. Authorization — frozen Slice 01 policy

Authentication and organization membership come from Phase 1.

Project read/action authority uses:
- explicit Phase-4-owned Project administration authority for organization-scoped creation/bootstrap administration;
- Project OpenFGA relationships for existing Project operational access;
- current Project responsibility projection;
- application lifecycle/version/field obligations.

Rules:
- no `if roleCode == PM`.
- no employee-name checks.
- no UI-only enforcement.
- out-of-scope/cross-organization objects fail according the existing OBJECT_NOT_VISIBLE/PERMISSION_DENIED audience contract.
- Site access is Project/context derived or explicit where the OpenFGA model allows it.
- a pending new Project relationship does not grant until authorization projection is APPLIED.
- a revoked/ended responsibility denies immediately even while a stale tuple exists.

CreateProject/CreateSite administration in Slice 01 uses an explicit organization-scoped Project authority binding owned by the Projects module. It must not infer authority from membershipType, roleLabel, WorkforceAssignment.roleCode or a named person.

The binding uses USER / TEAM principals, is effective-dated/audited, projects to OpenFGA through the existing authorization outbox, and derives the organization-level `can_manage_projects` action. ProjectSite attachment on an existing Project additionally requires current Project operational authority.

This is a controlled contract refinement recorded below; it does not reopen Phase 1 or make Project authority a generic organization-membership permission.

---

# 13. Audit / Activity / idempotency / concurrency

Every Slice 01 mutation:
- runs through the shared authenticated command runtime;
- is idempotent by operationId where retry-sensitive;
- checks baseVersion for existing aggregates;
- writes immutable audit evidence;
- emits safe domain/activity events after authoritative commit;
- does not put sensitive Site notes/address into generic event payloads.

Minimum events:
- `project.created`
- `project.responsibility_changed`
- `project.kickoff_started`
- `project.kickoff_completed`
- `site.created`
- `site.updated`
- `project_site.attached`
- `project.updated` where metadata change is valid.

Minimum audit must preserve:
actor, target, prior/new version/state references, operation/correlation, reason where applicable, config revision refs used for code allocation.

---

# 14. Typed errors required by Slice 01

At minimum:

- `PROJECT_SOURCE_INVALID`
- `PROJECT_CODE_POLICY_NOT_CONFIGURED`
- `PROJECT_CODE_CONFLICT`
- `PROJECT_RESPONSIBILITY_INVALID`
- `PROJECT_RESPONSIBILITY_UNRESOLVED`
- `PROJECT_SITE_ALREADY_ATTACHED`
- `SITE_CLIENT_MISMATCH`
- `REJECTED_STATE`
- `REJECTED_VALIDATION`
- `VERSION_CONFLICT`
- `OBJECT_NOT_VISIBLE`
- `PERMISSION_DENIED`

Shared unauthenticated/revoked/idempotency/integration error semantics remain inherited rather than redefined.

---

# 15. Windows human-flow contract

Slice 01 production UI is intentionally smaller than the existing future Project Command Center prototype.

## My Projects

Must show only real Slice 01 truth:
- code/name/client
- lifecycle
- current responsibility
- planned dates where present
- Site count
- unresolved/unassigned responsibility state when relevant.

Must include:
- empty state
- loading/error/retry state
- permission-safe absence
- Arabic RTL
- adaptive desktop sizing.

## Project header/detail

Must let an authorized user:
1. create/open Project;
2. see exact source/lifecycle/version;
3. see/change Project responsibility when authorized;
4. create or reuse Site;
5. attach Site as ProjectSite;
6. inspect safe Site/ProjectSite context;
7. Start Kickoff;
8. Complete Kickoff into PLANNING;
9. observe stale-version conflict without silent overwrite.

The screen must not render synthetic health/progress/material/work pipeline as if Phase 4 Slice 03–5 or Phase 5 were already operational.

The existing prototype remains visual/reference research, not permission to fake later-domain data.

---

# 16. Database implementation gate

Implementation starts with a new forward migration after V0020.

Expected Slice 01 migration responsibilities:
- source_type/source_external_reference;
- authoritative project_responsibility history;
- remove non-authoritative project_manager_id;
- explicit HILTECH organization ownership on Site and ProjectSite;
- indexes/checks/uniqueness for responsibility/source semantics;
- composite organization integrity keys that reject cross-organization responsibility and ProjectSite links.

Do not modify V0001–V0020.

jOOQ regeneration/drift checks must remain Green.

The existing Site/ProjectSite tables should be reused unless implementation uncovers a real contradiction. Do not recreate them under new names.

---

# 17. Required automated proof

## Database

Prove:
- Project code unique in organization.
- active Project CodePolicy allocation is transactional/idempotent.
- source-type constraints.
- one current responsibility per Project/key.
- Employee/Team organization consistency.
- responsibility history survives reassignment.
- duplicate ProjectSite rejected.
- Site/client mismatch rejected through service/domain + relevant DB integrity.
- optimistic stale update rejected.
- old V0001–V0020 migrations still apply cleanly; new migration applies on top.

## Authorization

Allow/deny counterparts:
- org admin create vs ordinary unrelated member.
- current Project responsibility can view required Project context vs unrelated user.
- Team responsibility grants current Team members only.
- Employee with no login remains business responsibility but cannot magically act.
- ended responsibility denies immediately.
- cross-org Project/Site reference hidden/rejected.
- pending grant fail-closed.
- pending revoke fail-closed.

## Server/API

Prove:
- CreateProject INTERNAL.
- CreateProject IMPORT with provenance.
- malformed source rejected.
- duplicate operation replay returns same semantic result.
- code collision typed.
- DRAFT -> KICKOFF -> PLANNING only.
- READY/ACTIVE Slice 01 endpoint absent/not implemented.
- create Site / reuse Site / attach ProjectSite.
- safe field projection.
- exact version conflict.
- audit/activity emitted once under replay.

## Shared/Desktop

Prove:
- typed DTO compatibility.
- My Projects list.
- Project header/detail.
- create/open/attach lifecycle flow.
- Arabic RTL render.
- stale conflict render.
- no later-slice fake Project metrics.

---

# 18. Regression / exact-head gate

A Slice 01 implementation PR is not mergeable merely because Project tests pass.

Required exact-head gates:
- Bootstrap Phase 0.
- Phase 1 Native OIDC / authorization regression.
- relevant Phase 2 shared command/activity/audit/authorization projection regressions.
- relevant Phase 3 People/WorkforceAssignment regression because Project responsibility consumes Employee/Team truth.
- dedicated Phase 4 Slice 01 Project/Site contract workflow.
- Windows human-render proof.

Any inherited flaky test is diagnosed and fixed, not bypassed by repeated merge attempts.

After merge:
- post-merge Bootstrap on `main` must PASS before Slice 02 contract work starts.

---

# 19. Explicitly deferred

To Slice 02:
- Areas as usable planning hierarchy;
- Milestones;
- WorkPackages;
- baseline plan required for READY;
- READY/ACTIVE transition gate.

To Slice 03:
- WorkOrder / WorkType / policy binding / instruction/checklist/requirements.

To Slice 04:
- readiness evaluation / assignment / Waiting On.

To Slice 05:
- review/rework / accepted progress / health / command center exception signals.

To Slice 06:
- Android assigned-work read.

To Phase 5:
- warehouse/material/equipment authoritative truth.

To Phase 6:
- offline Start/Block/Evidence/Submit/sync engine.

To Phase 10:
- Opportunity/Tender/Award/Contract/Client PO authoritative conversion and `CreateProjectFromAward`.

---

# 20. Reality decision

No new owner question is required to implement Slice 01.

The remaining mutable business choices are either:
- already configuration/policy;
- intentionally deferred to their owning slice;
- or safely represented as explicit source/responsibility state without inventing company behavior.

No structural HILTECH contradiction was found.

The one production-schema refinement is expected and justified by verified Phase 3 People truth: Project responsibility must consume Employee/Team business identity rather than keep a mutable login-only PM column.

---

# Closure

`SLICE_01_REALITY_CLOSURE = PASS`

`SLICE_01_IMPLEMENTATION_CONTRACT = FROZEN`

`PROJECT_SOURCE_SEMANTICS = INTERNAL_OR_IMPORT_NOW__AWARD_PHASE10`

`PROJECT_RESPONSIBILITY_TRUTH = EFFECTIVE_DATED_EMPLOYEE_OR_TEAM_HISTORY`

`SLICE_01_LIFECYCLE_BOUNDARY = DRAFT_TO_KICKOFF_TO_PLANNING`

`PHASE_5_BOUNDARY = PRESERVED`

`PHASE_6_BOUNDARY = PRESERVED`

`SLICE_01_PRODUCTION_CODE_AUTHORIZED = YES`

`NEXT = IMPLEMENT_PHASE4_SLICE01_PROJECT_SITE_CORE_VERTICAL`


---

# Controlled Amendment 01 — explicit Project administration source truth

Date: 2026-09-20  
Status: **ACCEPTED BEFORE PRODUCTION CODE**

Implementation inspection after contract merge found one cross-cutting ambiguity:

- the OpenFGA model contains a generic `organization#admin` relation;
- verified Phase 1 production source truth intentionally projects only structural organization membership, Team membership and Team manager relations;
- no authoritative PostgreSQL source currently exists for a generic organization-admin grant;
- `membership_type` / `role_label` are explicitly forbidden as permission truth.

Therefore Slice 01 must not pretend that a generic organization-admin tuple has a production source.

The implementation contract is refined as follows:

Create `project_authority_binding` as Projects-owned organization-scoped authority truth.

Minimum fields:
- id
- organization_id
- authority_key = `PROJECT_ADMIN`
- principal_type = `USER | TEAM`
- principal_user_id?
- principal_team_id?
- effective_from
- effective_to?
- active
- created_by_user_id
- created_at
- version.

Rules:
- exactly one principal field according to type;
- USER must have current active identity + organization membership to be effective;
- TEAM must belong to the organization and be active;
- no job-title or membership-label inference;
- multiple current Project admins are allowed;
- grants/revokes are source-truth guarded and projected with the existing fail-closed authorization outbox.

OpenFGA organization model adds:
- `project_admin: [user, team#member]`
- `can_manage_projects: project_admin or admin`

The legacy generic `admin` relation remains model-compatible, but Slice 01 does not invent a source for it.

This amendment preserves the previously frozen business intent — explicit authorized Project administration — while making its production source authoritative and testable.

`CONTROLLED_AMENDMENT_01 = PASS`

`PROJECT_ADMIN_AUTHORITY_SOURCE = PROJECT_AUTHORITY_BINDING`


---

# Controlled Amendment 02 — explicit HILTECH tenancy for Site / ProjectSite

Date: 2026-09-20  
Status: **ACCEPTED BEFORE SITE PRODUCTION CODE**

Implementation inspection found a security/data-integrity gap in the pre-code V0004 Site scaffold:

- Site has `client_organization_id`, but no owning HILTECH `organization_id`;
- `client_organization_id` answers whose physical/client Site it is, not which HILTECH tenant owns the internal record;
- an unattached reusable Site therefore cannot be safely scoped to one HILTECH organization;
- ProjectSite's two independent foreign keys do not by themselves prove that Project and Site belong to the same HILTECH tenant.

Slice 01 refines the physical contract:

`site` adds:
- `organization_id uuid NOT NULL -> organization`;
- uniqueness becomes `(organization_id, client_organization_id, site_code)`;
- `UNIQUE (id, organization_id)` supports composite integrity.

`project_site` adds:
- `organization_id uuid NOT NULL`;
- composite FK `(project_id, organization_id) -> project(id, organization_id)`;
- composite FK `(site_id, organization_id) -> site(id, organization_id)`.

The existing `project_id` / `site_id` identities and unique `(project_id, site_id)` relation remain.

This does not make Site equal Project. Site stays durable/reusable. It adds the missing internal tenant boundary required for safe Site creation, reuse and authorization.

`CONTROLLED_AMENDMENT_02 = PASS`

`SITE_TENANCY = EXPLICIT_HILTECH_ORGANIZATION`

`PROJECT_SITE_CROSS_TENANT_LINK = DB_REJECTED`

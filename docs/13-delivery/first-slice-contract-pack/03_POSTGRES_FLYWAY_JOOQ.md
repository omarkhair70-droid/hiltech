# 03 — PostgreSQL / Flyway / jOOQ Contract

Status: **CONTRACT CANDIDATE v0.2 / TABLE SHAPES DEFINED**

## Locked Technical Direction

- PostgreSQL authoritative OLTP store — ADR-004.
- jOOQ SQL/persistence layer — ADR-005.
- optimistic version checks + DB constraints for critical state.
- operationId/idempotency for retry-sensitive commands.
- append-oriented custody/stock/audit history.
- Flyway migration strategy to be frozen with exact schema.

---

# Required First-Slice Table Families

Exact table names/columns are still pre-freeze, but ownership families are now explicit.

Candidate ownership families:

## configuration / policy
- work_type_definition
- assignment_policy
- readiness_policy
- readiness_policy_requirement
- evidence_policy
- evidence_policy_requirement
- review_policy
- review_policy_step
- field_tracking_policy
- asset_type_definition
- stock_item_category_definition
- notification/escalation policy tables as first-slice scope requires
- typed template tables or versioned configuration document representation
- configuration revision/audit/dependency metadata where not embedded per aggregate

Rules:
- config revisions are versioned and auditable,
- ACTIVE revisions are not materially mutated in-place,
- historical WorkOrders bind required config revisions,
- no arbitrary executable policy scripts.



## identity / organizations
- user_identity
- organization
- organization_membership
- team / team_membership only if first-slice reality requires them
- delegation only if first-slice authority requires it

## projects
- project
- site
- area only if verified needed

## work
- work_order
- work_policy_binding or equivalent immutable revision-reference structure
- work_assignment / assignment target
- blocker
- work_review/rework history if not event-only
- work requirement realization/satisfaction records
- evidence requirement satisfaction/projection where useful

## assets / warehouse
- asset
- asset_tag
- asset_movement
- warehouse
- storage_location as verified
- reservation
- stock tables only if first pilot needs quantity stock

## documents/evidence
- evidence metadata
- upload session / finalize state as selected
- object-evidence link

## platform
- idempotency/operation result
- audit
- durable module event publication infrastructure
- any read projections required by first-slice UI

---

# Per-Table Freeze Sheet

For every table:

| Property | Freeze value |
|---|---|
| owning server module | TBD |
| table name | TBD |
| primary key strategy | TBD |
| business/human code | TBD |
| optimistic version | TBD |
| lifecycle/status fields | TBD |
| created/updated audit columns | TBD |
| foreign keys | TBD |
| unique constraints | TBD |
| check constraints | TBD |
| indexes from real queries | TBD |
| retention/deletion rule | TBD |
| sensitive columns | TBD |
| migration owner | TBD |
| jOOQ package/codegen mapping | TBD |

---

# Mandatory Constraint Tests

Before freeze, database tests must prove at minimum:

- configuration code/revision uniqueness.
- no invalid effective interval.
- no cyclic configuration supersession.
- no overlapping ACTIVE/effective revision for a code/scope where exclusivity is required.
- WorkOrder historical policy binding survives later config supersession.
- retired config cannot be selected for new WorkOrder creation/assignment.
- storage-location hierarchy has no cycle.

- stale WorkOrder transition cannot update current version.
- same idempotency operation cannot repeat business side effect.
- asset cannot have two active authoritative custodies.
- checkout collision has one winner.
- movement history is not destroyed by return/correction.
- stock cannot go negative if stock is in first-slice scope.
- evidence READY cannot be set without verified object.
- accepted work references an exact submitted authoritative version/state.
- invalid cross-project/site references are rejected.
- external/tenant relationship integrity is preserved.

---

# Flyway Freeze

Must decide:
- baseline version,
- file naming,
- module migration ownership,
- global ordering,
- rollback/recovery operational policy,
- test database migration from empty,
- forward migration from previous supported release.

Do not generate production migrations until data dictionary and table constraints are approved.

---

# jOOQ Freeze

Must decide:
- codegen package names,
- generated source location,
- schema selection,
- forced types where required,
- enum mapping strategy,
- CI generation/verification,
- whether generated code is committed or CI-generated.

No repository-global generic ORM abstraction is introduced over jOOQ by default.


---

# Exact first-slice table shape candidates

Conventions:
- snake_case physical names.
- UUID primary keys.
- version bigint not null on mutable aggregates.
- timestamptz for Instants.
- money/quantities use numeric/decimal, never float.
- foreign keys never substitute for authorization.
- cross-module mutation is forbidden; modules communicate through commands/events/read models.

These are candidate production shapes, not generated migrations yet.

---

# configuration module

## config_revision

Candidate base metadata:

- id uuid primary key
- organization_id uuid not null
- family varchar not null
- code varchar not null
- name varchar not null
- description text null
- lifecycle_state varchar not null
- revision_number integer not null
- effective_from timestamptz null
- effective_to timestamptz null
- supersedes_id uuid null
- change_reason text null
- created_at timestamptz not null
- created_by uuid not null
- activated_at timestamptz null
- activated_by uuid null
- version bigint not null

Unique:
- organization_id, family, code, revision_number.

Checks:
- revision_number > 0.
- effective_to > effective_from when both present.

Indexes:
- organization_id + family + code + lifecycle_state.
- effective window lookup.
- supersedes_id.

Typed families remain structured, not arbitrary free-form policy blobs.

Candidate detail tables:
- work_type_definition
- assignment_policy
- assignment_policy_target_type
- readiness_policy
- readiness_policy_requirement
- evidence_policy
- evidence_policy_requirement
- review_policy
- review_policy_step
- field_tracking_policy
- asset_type_definition
- stock_item_category_definition
- template_definition

Each typed row links to config_revision or carries equivalent revision metadata.

---

# projects module

## project

- id uuid primary key
- organization_id uuid not null
- project_code varchar not null
- name varchar not null
- client_organization_id uuid not null
- source_opportunity_id uuid null
- contract_id uuid null
- client_po_id uuid null
- lifecycle_state varchar not null
- project_manager_id uuid null
- start_date_planned date null
- end_date_planned date null
- start_date_actual timestamptz null
- end_date_actual timestamptz null
- baseline_version integer not null
- currency_code char(3) null
- internal_budget_amount numeric null
- sell_value_amount numeric null
- payment_terms_ref varchar/uuid null
- created_at timestamptz not null
- created_by uuid not null
- updated_at timestamptz not null
- version bigint not null

Unique:
- organization_id, project_code.

Indexes:
- organization_id + lifecycle_state.
- project_manager_id + lifecycle_state.
- client_organization_id + lifecycle_state.

## site

- id uuid primary key
- client_organization_id uuid not null
- site_code varchar not null
- name varchar not null
- address_text text null
- latitude numeric null
- longitude numeric null
- timezone varchar null
- status varchar not null
- created_at timestamptz not null
- created_by uuid not null
- updated_at timestamptz not null
- version bigint not null

Unique:
- client_organization_id, site_code.

Indexes:
- client_organization_id + status.

## project_site

- id uuid primary key
- project_id uuid not null
- site_id uuid not null
- project_site_code varchar null
- lifecycle_state varchar not null
- access_instructions text null
- project_specific_notes text null
- active_from timestamptz null
- active_until timestamptz null
- version bigint not null

Unique candidate:
- project_id, site_id.

Indexes:
- project_id + lifecycle_state.
- site_id + lifecycle_state.

## project_site_contact_link

- project_site_id uuid not null
- contact_id uuid not null
- relationship_type varchar null

Primary key:
- project_site_id + contact_id + relationship_type where representation permits.

## area

- id uuid primary key
- site_id uuid not null
- parent_area_id uuid null
- type_code varchar not null
- code varchar not null
- name varchar not null
- sequence integer null
- restricted_access boolean not null default false
- version bigint not null

Unique:
- site_id, code.

Cycle prevention requires application + DB-safe validation strategy.

---

# work module

## work_order

- id uuid primary key
- work_order_code varchar not null
- project_id uuid not null
- site_id uuid not null
- project_site_id uuid null
- area_id uuid null
- work_package_id uuid null
- title varchar not null
- description text null
- lifecycle_state varchar not null
- readiness_state varchar not null
- planned_start timestamptz null
- planned_end timestamptz null
- actual_start timestamptz null
- submitted_at timestamptz null
- accepted_at timestamptz null
- closed_at timestamptz null
- priority_code varchar not null
- instruction_revision bigint not null
- instruction_ref uuid/varchar null
- created_at timestamptz not null
- created_by uuid not null
- updated_at timestamptz not null
- version bigint not null

Unique:
- exact human-code scope still to freeze.

Indexes:
- project_id + lifecycle_state.
- site_id + lifecycle_state.
- lifecycle_state + planned_start.
- readiness_state.
- submitted_at for submitted-review queue.

## work_policy_binding

One row per WorkOrder binding snapshot:

- work_order_id uuid primary key
- work_type_definition_id uuid not null
- work_type_revision integer not null
- assignment_policy_id uuid not null
- assignment_policy_revision integer not null
- readiness_policy_id uuid not null
- readiness_policy_revision integer not null
- evidence_policy_id uuid not null
- evidence_policy_revision integer not null
- review_policy_id uuid not null
- review_policy_revision integer not null
- tracking_policy_id uuid null
- tracking_policy_revision integer null
- checklist_template_id uuid null
- checklist_template_revision integer null
- instruction_template_id uuid null
- instruction_template_revision integer null
- binding_created_at timestamptz not null

If controlled rebind is allowed later, preserve prior binding history rather than overwrite in-place.

## work_assignment

- id uuid primary key
- work_order_id uuid not null
- target_type varchar not null
- target_id uuid not null
- lead boolean not null default false
- assigned_at timestamptz not null
- assigned_by uuid not null
- valid_from timestamptz not null
- valid_until timestamptz null
- state varchar not null
- source_operation_id uuid not null
- version bigint not null

Indexes:
- work_order_id + state.
- target_type + target_id + state.

No authoritative assigned-user arrays.

## work_blocker

- id uuid primary key
- work_order_id uuid not null
- blocker_type_code varchar not null
- severity_code varchar null
- description text not null
- created_by uuid not null
- owner_target_type varchar null
- owner_target_id uuid null
- state varchar not null
- resolved_at timestamptz null
- resolution text null
- client_visible boolean not null default false
- version bigint not null

## work_review_decision

- id uuid primary key
- work_order_id uuid not null
- submitted_work_version bigint not null
- review_policy_id uuid not null
- review_policy_revision integer not null
- review_step_id uuid/varchar null
- reviewer_user_id uuid not null
- decision varchar not null
- reason text null
- decided_at timestamptz not null
- delegation_id uuid null
- correlation_id varchar not null

Append/audit oriented.

## work_requirement_instance

Candidate unified typed table:

- id uuid primary key
- work_order_id uuid not null
- requirement_family varchar not null
- requirement_key varchar not null
- source_config_id uuid null
- source_config_revision integer null
- required boolean not null
- satisfaction_state varchar not null
- satisfied_by_ref varchar/uuid null
- waived boolean not null default false
- waiver_ref uuid null
- updated_at timestamptz not null
- version bigint not null

Exact split into separate readiness/evidence/material/asset tables remains a query/integrity decision.

---

# warehouse/assets module

## asset

- id uuid primary key
- organization_id uuid not null
- asset_code varchar not null
- asset_type_definition_id uuid not null
- asset_type_revision integer not null
- manufacturer varchar null
- model varchar null
- serial_number varchar null
- ownership_type_code varchar not null
- lifecycle_state varchar not null
- condition_state varchar not null
- tag_id uuid null
- purchase_date date null
- acquisition_cost numeric null
- currency_code char(3) null
- warranty_start date null
- warranty_end date null
- calibration_due_at timestamptz null
- maintenance_plan_ref uuid null
- last_observed_at timestamptz null
- last_observed_source varchar null
- created_at timestamptz not null
- created_by uuid not null
- retired_at timestamptz null
- version bigint not null

Unique:
- organization_id, asset_code.

Serial uniqueness scope remains a freeze item.

## asset_tag

- id uuid primary key
- asset_id uuid not null
- tag_type_code varchar not null
- public_opaque_code varchar not null
- provider_external_id varchar null
- active boolean not null
- issued_at timestamptz not null
- replaced_tag_id uuid null

Unique:
- public_opaque_code.

## warehouse

- id uuid primary key
- organization_id uuid not null
- code varchar not null
- name varchar not null
- facility_ref varchar/uuid null
- active boolean not null
- version bigint not null

## storage_location

- id uuid primary key
- organization_id uuid not null
- code varchar not null
- name varchar not null
- kind varchar not null
- parent_storage_location_id uuid null
- warehouse_id uuid null
- project_id uuid null
- site_id uuid null
- temporary boolean not null
- active_from timestamptz null
- active_until timestamptz null
- responsible_relationship_code varchar null
- restricted_access boolean not null
- address_or_location_ref varchar/uuid null
- notes text null
- version bigint not null

Checks:
- active_until > active_from when both present.
- kind/context consistency.

Cycle prevention required.

## asset_movement

- id uuid primary key
- asset_id uuid not null
- movement_type varchar not null
- from_storage_location_id uuid null
- to_storage_location_id uuid null
- from_custodian_target_type varchar null
- from_custodian_target_id uuid null
- to_custodian_target_type varchar null
- to_custodian_target_id uuid null
- project_id uuid null
- site_id uuid null
- work_order_id uuid null
- occurred_at timestamptz not null
- recorded_at timestamptz not null
- recorded_by uuid not null
- source_operation_id uuid not null
- condition_at_transfer varchar null
- expected_return_at timestamptz null
- reason_code varchar null
- notes text null
- correction_of_movement_id uuid null
- correlation_id varchar not null

## asset_custody_projection

- asset_id uuid primary key
- current_storage_location_id uuid null
- current_custodian_target_type varchar null
- current_custodian_target_id uuid null
- current_project_id uuid null
- current_site_id uuid null
- current_work_order_id uuid null
- custody_started_at timestamptz null
- expected_return_at timestamptz null
- source_movement_id uuid not null
- asset_version bigint not null
- updated_at timestamptz not null

Movement + projection update are one authoritative command transaction.

## stock_item

- id uuid primary key
- organization_id uuid not null
- item_code varchar not null
- name varchar not null
- category_definition_id uuid not null
- category_revision integer not null
- unit_of_measure_code varchar not null
- serialized boolean not null
- lot_tracked boolean not null
- active boolean not null
- valuation_class_ref varchar/uuid null
- version bigint not null

## stock_balance

- stock_item_id uuid not null
- storage_location_id uuid not null
- on_hand_qty numeric not null
- reserved_qty numeric not null
- damaged_qty numeric not null
- quarantine_qty numeric not null
- version bigint not null
- updated_at timestamptz not null

Primary key:
- stock_item_id + storage_location_id.

Available quantity is derived.

## stock_movement

- id uuid primary key
- stock_item_id uuid not null
- quantity numeric not null
- unit_code varchar not null
- movement_type varchar not null
- from_storage_location_id uuid null
- to_storage_location_id uuid null
- project_id uuid null
- site_id uuid null
- work_order_id uuid null
- recipient_target_type varchar null
- recipient_target_id uuid null
- purchase_receipt_id uuid null
- reason_code varchar not null
- operation_id uuid not null
- occurred_at timestamptz not null
- recorded_at timestamptz not null
- recorded_by uuid not null
- correlation_id varchar not null

## reservation

- id uuid primary key
- resource_type varchar not null
- resource_id uuid not null
- quantity numeric null
- project_id uuid not null
- site_id uuid null
- work_order_id uuid null
- requested_by uuid not null
- reserved_for_target_type varchar null
- reserved_for_target_id uuid null
- start_at timestamptz null
- end_at timestamptz null
- state varchar not null
- priority_code varchar null
- version bigint not null

---

# evidence/documents module

## evidence

- id uuid primary key
- organization_id uuid not null
- target_type varchar not null
- target_id uuid not null
- work_order_id uuid null
- evidence_requirement_key varchar null
- evidence_policy_id uuid null
- evidence_policy_revision integer null
- evidence_type_code varchar not null
- content_type varchar not null
- original_file_name varchar null
- size_bytes bigint not null
- sha256 char(64) not null
- captured_at timestamptz not null
- client_occurred_at timestamptz null
- captured_by_user_id uuid not null
- source_device_id varchar/uuid null
- instruction_revision bigint null
- work_order_version_at_capture bigint null
- storage_state varchar not null
- object_key_ref varchar null
- finalized_at timestamptz null
- classification_code varchar not null
- client_visibility_mode varchar not null
- supersedes_evidence_id uuid null
- created_at timestamptz not null
- version bigint not null

## evidence_upload_session

- id uuid primary key
- evidence_id uuid not null
- expected_sha256 char(64) not null
- expected_size_bytes bigint not null
- expires_at timestamptz not null
- state varchar not null
- created_at timestamptz not null
- finalized_at timestamptz null
- operation_id uuid not null
- version bigint not null

Binary remains outside PostgreSQL.

---

# platform tables

## idempotent_operation

- operation_id uuid primary key
- actor_user_id uuid not null
- command_type varchar not null
- target_type varchar null
- target_id uuid null
- request_fingerprint varchar null
- state varchar not null
- result_code varchar null
- result_payload jsonb/result_ref null
- created_at timestamptz not null
- completed_at timestamptz null
- correlation_id varchar not null

Retention must avoid unnecessary sensitive payload retention.

## audit_event

Append-oriented audit candidate:
- id uuid
- actor/context
- action
- target
- previous/new state refs or safe diff
- occurred_at
- correlation/trace
- reason/delegation/config revision refs as required

Exact physical partitioning remains an audit/operations freeze item.

---

# Optimistic update pattern

Candidate jOOQ mutation:

UPDATE aggregate
SET ..., version = version + 1
WHERE id = :id AND version = :baseVersion

Zero rows means:
- read safe current state/version.
- return VERSION_CONFLICT/domain conflict.
- never silently retry with new baseVersion.

---

# Transaction boundaries

One business command transaction may include:
- aggregate mutation.
- append movement/decision row.
- projection update.
- idempotent operation completion.
- audit/event registration.

No cross-module table write.

Cross-module consequences use durable events/read models.

---

# Index review required before freeze

Use real first-slice query shapes for:
- Technician Today.
- PM Project Command Center.
- review queue.
- active assignments.
- asset tag lookup.
- custody/location.
- warehouse scan/checkout.
- stock balance/location.
- active configuration by code.
- configuration history/impact.
- pending evidence/upload diagnostics.

Do not index every field speculatively.

---

# Flyway candidate convention

Before bootstrap freeze:
- module-owned migration directories.
- globally deterministic ordering.
- empty-database migration test.
- upgrade-from-supported-version migration test.
- no assumption that destructive DB rollback is safe.
- release rollback must remain compatible with forward schema strategy.

Exact filename convention/baseline number remain final-freeze choices.

---

# jOOQ candidate convention

- generated from authoritative PostgreSQL schema.
- generated code isolated from domain/application layers.
- repository/adapter layer uses jOOQ generated types.
- domain objects do not become jOOQ records.
- CI verifies schema/codegen consistency.

Still to decide:
- generated code committed vs CI-generated.
- exact packages.
- forced-type/enum mappings.

Current:
**table/constraint/ownership shapes are concrete enough for migration skeleton design; final DDL waits on narrow freeze items.**

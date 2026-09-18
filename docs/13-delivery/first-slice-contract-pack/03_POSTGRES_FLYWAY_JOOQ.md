# 03 — PostgreSQL / Flyway / jOOQ Contract

Status: **CONTRACT CANDIDATE v0.1 / CONFIGURATION-INTEGRATED**

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

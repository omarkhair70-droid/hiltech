# 25 — Bootstrap Change Control: Initial Flyway Dependency Order

Date: 2026-09-19  
Status: **ACCEPTED / POST-FREEZE BOOTSTRAP CORRECTION**  
Scope: first-slice migration ordering only.

## Trigger

Mechanical Repository Bootstrap generation exposed a dependency-order contradiction in the frozen migration stream.

The prior order placed platform/configuration before identity/organization, while the frozen DDL contract requires references such as:

- `config_revision.scope_organization_id -> organization.id`
- `config_revision.created_by -> user_identity.id`
- `config_revision.activated_by -> user_identity.id`
- platform actor references to HILTECH product identity

The Flyway contract also requires a referenced table to exist before a later migration adds its foreign key.

## Decision

The initial first-slice stream is corrected to:

1. V0001 identity_organization
2. V0002 platform foundations
3. V0003 configuration
4. V0004 projects_sites
5. V0005 work
6. V0006 warehouse_assets
7. V0007 evidence_documents
8. V0008 authorization_projection
9. V0009 first_slice_indexes_projections

## Boundary

This correction does **not** change:
- domain objects or lifecycle semantics,
- API semantics,
- authorization semantics,
- table/module ownership,
- V0004–V0009 relative order,
- first-slice product scope.

It only makes the already-frozen FK dependencies mechanically satisfiable from an empty PostgreSQL database.

## Verification gate

Bootstrap Verification must prove:
- PostgreSQL 18.6 migrates from empty through V0009,
- declared cross-module FKs resolve,
- Flyway history/checksums are clean,
- jOOQ generation runs after migration,
- constraint tests execute against the migrated schema.

If SQL generation exposes another missing semantic decision, generation stops and records another focused Bootstrap change control rather than inventing a production rule.

## Canonical updates

This record updates only the initial migration-order sections of:
- `docs/12-stack/FINAL_STACK.md`
- `docs/13-delivery/first-slice-contract-pack/03_POSTGRES_FLYWAY_JOOQ.md`

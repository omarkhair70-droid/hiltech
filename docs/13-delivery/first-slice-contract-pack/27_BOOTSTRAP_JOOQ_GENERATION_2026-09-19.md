# 27 — Bootstrap jOOQ Generation Gate

Date: 2026-09-19
Status: **IMPLEMENTED / CI VERIFICATION PENDING**

## Purpose

Close the post-Freeze database-generation obligation without introducing new product semantics.

Frozen source:
- `03_POSTGRES_FLYWAY_JOOQ.md`
- `16_DATABASE_DDL_CONSTRAINT_CONTRACT.md`
- `FINAL_STACK.md`

## Implemented

The server build now exposes explicit tasks:

- `:server:application:generateJooq`
- `:server:application:verifyJooqGeneration`

Generation is intentionally **not** an implicit dependency of every server compile.

Reason:
normal developer/server compilation must not require a live PostgreSQL database merely because jOOQ code generation exists.

The Bootstrap database CI job instead performs the deterministic sequence:

1. migrate empty PostgreSQL through `V0001..V0009`,
2. verify database constraints,
3. generate jOOQ from that migrated schema,
4. assert canonical first-slice tables exist in generated `Tables.kt`,
5. compile the server with the generated Kotlin sources on its source path.

## Frozen generation values

Generator:
`org.jooq.codegen.KotlinGenerator`

Output:
`server/build/generated-src/jooq/main`

Package:
`com.hiltech.server.generated.jooq`

Generated sources:
**not committed**.

Schema:
`public`

Excluded technical table:
`flyway_schema_history`

## No semantic expansion

This change does not add business tables or revise state models.
It mechanically consumes the already-generated and contract-tested first-slice schema.

CI PASS is required before this gate can be marked closed.

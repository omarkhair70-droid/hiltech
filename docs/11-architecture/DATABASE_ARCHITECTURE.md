# HILTECH Database Architecture

Status: ARCHITECTURE MODEL v0.1 / POSTGRESQL LEADING

## Goal
Preserve authoritative transactional truth, history, auditability, and performance without turning PostgreSQL into a shared unstructured dump.

---

# 1. Primary Database

PostgreSQL 18.x candidate.

One primary database at launch does NOT mean no domain ownership.

Each table/object has one owning backend module.

---

# 2. Ownership

Examples:

People owns:
- employee
- employment
- leave
- certification

Warehouse owns:
- stock
- reservation
- inventory movement

Assets owns:
- asset identity
- lifecycle
- custody/movement or explicit shared boundary with Warehouse

Payroll owns:
- payroll run/version/lines/payslips

Approvals owns:
- approval requests/steps/decisions

No module writes another module's tables directly.

---

# 3. IDs

Candidate:
UUIDv7 or equivalent sortable globally unique identifiers for domain objects.

Reasons:
- offline/local creation possibilities where appropriate,
- distributed future,
- non-sequential public IDs.

Human-facing identifiers can be separate:
- PRJ-2026-00124
- HT-A-000184
- PO-2026-0081

Never expose DB sequence semantics as business identity requirement.

---

# 4. Optimistic Versioning

Mutable authoritative objects need version/revision marker.

Commands include base version where conflict-sensitive.

Examples:
- Work Order.
- Asset custody-sensitive state.
- Quote version.
- Payroll run version.
- PO version.
- Approval subject.

---

# 5. Money

Never use floating point.

Need:
- decimal/numeric amount,
- currency,
- explicit rounding policy,
- immutable transaction history where appropriate.

Egyptian tax/accounting rules require accountant/legal validation.

---

# 6. Ledgers / Movement Tables

Use append-first history for:
- asset movement,
- stock movement,
- financial/payment events where applicable,
- approval decisions,
- audit.

Current state can be materialized/cached but must remain explainable from history.

---

# 7. Soft Delete vs Lifecycle

Do not use generic soft-delete for every domain object.

Prefer domain lifecycle:
RETIRED
CANCELLED
CLOSED
FORMER

Hard delete reserved for:
- local drafts,
- privacy/legal requirements,
- accidental non-business data,
according to policy.

---

# 8. Documents

DB stores:
- document identity,
- version,
- metadata,
- object links,
- hash/checksum,
- storage key,
- classification,
- retention.

Binary content stays object storage.

---

# 9. JSONB

Allowed for:
- integration payload snapshots with limits,
- sparse provider metadata,
- low-query extensible details.

Not excuse to avoid modeling important domain fields.

---

# 10. Search

Start with PostgreSQL:
- indexes,
- FTS/trigram as justified,
- exact IDs.

Dedicated search engine only if volume/use case proves need.

AI/semantic search later may use separate indexed representation.

---

# 11. Reporting

Do not let executive dashboards run pathological transactional joins.

Options later:
- optimized views,
- materialized views,
- read projections,
- reporting schema.

Start simple, measure.

---

# 12. Audit

Audit records separate from domain events where necessary.

Need:
- retention,
- access control,
- tamper-resistance strategy,
- high-risk event preservation.

---

# 13. Migrations

Flyway candidate.

Rules:
- production schema only through reviewed migrations.
- migrations committed with code.
- backwards-compatible deployment strategy where needed.
- migration tests from previous supported schema.
- backups before destructive operations.

---

# 14. Backup / Recovery

Must define before production:
- automated backups.
- point-in-time recovery.
- encryption.
- restore test cadence.
- RPO/RTO targets.
- offsite/provider failure considerations.

---

# 15. Data Classification

Database fields mapped:
PUBLIC / INTERNAL / RESTRICTED / HIGHLY_RESTRICTED.

Sensitive:
- IDs,
- salary,
- bank details,
- security/access,
- credentials/tokens.

Encryption/masking/access policy follows classification.

---

# 16. Multi-Tenancy / Organization Scope

HILTECH is one company platform with external organizations.

Do not blindly build generic SaaS tenant isolation if not needed.

But every external-facing object relationship must have organization scope and authorization.

Future commercial SaaS multi-tenancy is separate decision.

---

# 17. Testing

- migration tests,
- constraint tests,
- transaction/concurrency tests,
- ledger invariants,
- version conflict,
- authorization query leakage,
- backup/restore drill.

## Completion gate
Requires actual schemas per frozen module, index strategy, migration baseline, backup plan, retention policy, and performance tests.

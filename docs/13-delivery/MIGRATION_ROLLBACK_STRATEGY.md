# HILTECH Migration & Rollback Strategy

Status: PRE-CODE DELIVERY SPEC v0.1

## Core Rule

Database rollback is not the default incident response.

For stateful systems, prefer:
- safe forward fix,
- compatible schema evolution,
- restore only when required.

---

# Database Migration Rules

- versioned migrations only.
- reviewed in PR.
- tested from previous supported schema.
- no app auto-DDL in production.
- destructive changes separated into stages.
- backups before high-risk migration.
- measure lock/rewriting impact.

---

# Expand / Migrate / Contract Pattern

For breaking field/schema change:

## 1. Expand
Add new compatible structure.

## 2. Migrate
Backfill / dual-read-write where needed.

## 3. Switch
New code uses new structure.

## 4. Observe
Confirm no older client/path relies on old structure.

## 5. Contract
Remove old structure in later release.

---

# Failed Deployment

If app deploy fails but migration is backward compatible:
rollback app artifact.

If migration introduced incompatible state:
use planned forward/recovery procedure.

Avoid emergency manual schema edits.

---

# Data Correction

Business correction is not DB rollback.

Examples:
- wrong payment allocation.
- incorrect asset custody.
- payroll correction.

Use explicit business correction/reversal workflow.

Preserve audit history.

---

# Client Local DB

Room migrations:
- test every supported upgrade.
- preserve pending offline operations.
- if cache can be rebuilt, distinguish cache from unsynced user work.

Never delete local DB to fix migration if it can contain unsynced evidence/commands.

---

# Object Storage

Files are versioned/immutable where business requires.

Deleting DB row must not accidentally orphan/erase required retained evidence.

---

# Backup / Restore

Before production define:
- PostgreSQL PITR.
- backup frequency.
- object storage durability/backup.
- identity configuration backup.
- authz model backup.
- restoration runbook.
- periodic restore test.

---

# Release Rollback Package

Each high-risk release records:
- previous artifact.
- migration list.
- compatibility.
- rollback steps.
- forward fix steps.
- owner.
- verification steps.

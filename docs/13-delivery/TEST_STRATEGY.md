# HILTECH Test Strategy

Status: PRE-CODE DELIVERY SPEC v0.1

## Goal

HILTECH will run company operations, money, physical custody, permissions, and offline field work.

Testing must prove:
- business correctness,
- authorization correctness,
- offline safety,
- concurrency safety,
- integration safety,
- migration safety,
- operability.

A green UI demo is not sufficient.

---

# 1. Test Pyramid / Layers

## Domain Unit Tests
For:
- state transitions,
- invariants,
- payroll calculations,
- stock/asset movement rules,
- approval policies,
- finance allocations,
- SLA calculations.

Fast and deterministic.

## Application / Command Tests
For every business command:
- permission,
- validation,
- state,
- version,
- idempotency,
- emitted event/audit,
- expected errors.

## Architecture Tests
Verify:
- module boundaries,
- no forbidden dependencies,
- no repository access across modules,
- no cycles.

Spring Modulith candidate.

## Database Integration Tests
Run against real PostgreSQL.

Test:
- migrations,
- constraints,
- transactions,
- concurrency,
- optimistic locking,
- indexes where critical.

## API Contract Tests
Request/response/error/version behavior.

## Client Repository Tests
Local DB + network convergence.

## Sync Tests
Critical:
- airplane mode,
- restart,
- retry,
- duplicate replay,
- stale baseVersion,
- dependency ordering,
- binary upload interruption,
- device revocation.

## UI State Tests
Loading / Empty / Loaded / Offline / Stale / Conflict / Error / Permission.

## Compose UI Tests
Representative actions and accessibility.

## End-to-End Tests
Cross-device / cross-role flows.

## Integration Adapter Tests
Bank/NVR/access/email/SMS/object storage providers.

Use:
- sandbox,
- simulator,
- contract fixture,
- controlled integration environment.

---

# 2. Critical Domain Suites

## Identity / Permissions
- revoked user cannot act.
- role change takes effect.
- object-level scope.
- field-level data leakage.
- external org isolation.
- re-auth requirement.
- delegation expiry.

## Work
- stale assignment.
- cancelled work offline.
- evidence required.
- rework history.
- duplicate completion.

## Assets / Warehouse
- double checkout blocked.
- custody history.
- damaged/repair flow.
- calibration expiry.
- stock cannot accidentally go negative.
- adjustment does not erase history.

## Finance
- payment idempotency.
- UNKNOWN outcome.
- partial payment.
- allocation limits.
- returned payment.
- reconciliation.
- invoice immutability.

## Payroll
- exact version approval.
- corrections.
- partial employee payment result.
- advance deduction trace.
- imprest settlement independence.
- totals / rounding.

## Procurement
- PO versioning.
- receipt discrepancy.
- three-way match.
- partial receipt.

## Documents
- unauthorized access.
- version supersede.
- checksum.
- interrupted upload.
- handover completeness.

---

# 3. Offline Test Matrix

Every LF feature gets tests for:

1. online normal.
2. go offline before action.
3. action offline.
4. app process killed.
5. device restarted where relevant.
6. reconnect.
7. server changed object meanwhile.
8. duplicate retry.
9. upload interrupted.
10. permission revoked while offline.

Expected:
no silent loss, no false success, no duplicate business action.

---

# 4. Concurrency Tests

At minimum:
- two warehouse users checkout same asset.
- two PMs modify same work assignment.
- approval while subject version changes.
- two finance users allocate same payment.
- stock issue contention.
- duplicate external result callback.

---

# 5. Security Tests

- auth bypass attempts.
- ID enumeration.
- object visibility leaks.
- field-level leaks.
- document signed URL expiry.
- client/supplier org isolation.
- privilege escalation.
- stale session/device revocation.
- notification sensitive preview.
- logs do not contain secrets.

---

# 6. Accessibility / RTL

Representative tests:
- Arabic RTL.
- English LTR.
- mixed IDs/numbers.
- large text.
- keyboard desktop.
- visible focus.
- reduced motion.
- TalkBack / Windows accessibility review.

---

# 7. Performance

Representative performance budgets to define after spikes.

Test:
- 10k+ row desktop data view.
- project portfolio.
- payroll grid.
- warehouse inventory.
- sync backlog.
- evidence upload.
- API latency.
- DB query plans.

Do not invent final numeric budgets before realistic hardware/data.

---

# 8. Migration Tests

Every DB migration:
- clean install.
- upgrade from previous supported schema.
- data preserved.
- rollback/forward-recovery path documented.
- large table impact checked.

Client local DB:
- Room migration tests.
- queued offline operations preserved where possible.

---

# 9. Release Smoke Tests

Before staging/prod:
- login.
- permissions.
- representative query.
- representative command.
- object storage.
- sync.
- notification.
- DB migration.
- health endpoints.
- audit/event.

---

# 10. Vertical Slice Definition of Verified

A slice is VERIFIED only when:
- unit/domain tests pass.
- DB/integration tests pass.
- API contract passes.
- permissions pass.
- UI states pass.
- offline tests pass if applicable.
- observability present.
- staging scenario passes.

---

# 11. Test Data

Use:
- synthetic HILTECH-shaped fixtures.
- redacted/authorized examples only.
- no production secrets/IDs in fixtures.

Create realistic fixtures:
- company org.
- Mohamed/Ahmed/PM/Technician/Warehouse.
- projects/sites.
- assets/stock.
- payroll.
- supplier/client orgs.

---

# 12. Non-Negotiable Test Principle

Never mark a financial, custody, approval, or security workflow complete because the happy path works.

# ADR-004 — Authoritative Relational Database

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH authoritative state includes:
- projects/work,
- employee/finance/payroll,
- asset custody,
- stock,
- procurement,
- approvals,
- invoices/payments,
- append-oriented movement/audit history.

The database must protect impossible financial/physical states even under concurrent requests.

## Decision

Use **PostgreSQL** as the authoritative transactional relational database.

Critical state transitions must combine:
- database constraints,
- transactions,
- expected-version / compare-and-update semantics where collision-sensitive,
- unique operationId/idempotency constraints,
- append-oriented ledger/history for physical and financial movement.

Application validation does not replace database constraints.

## Evidence

SPIKE-11 — GitHub Actions run 35296301835.

On real PostgreSQL, the spike proved:

### Concurrent asset checkout
Two requests targeted the same AVAILABLE asset/version.

Result:
- exactly one APPLIED,
- exactly one CONFLICT,
- one authoritative custodian,
- one movement,
- version incremented once.

### Idempotent retry
Same operationId was submitted again.

Result:
- ALREADY_APPLIED semantics,
- no second movement,
- no second version increment.

### Stale version
Old expectedVersion was rejected without ledger mutation.

### Concurrent stock issue
Two requests each tried to issue 7 from quantity 10.

Result:
- one APPLIED,
- one CONFLICT,
- final quantity 3,
- stock never negative,
- one movement,
- version incremented once.

## Consequences

- PostgreSQL invariants are part of domain safety, not only persistence.
- money/custody/stock operations need explicit transaction boundaries.
- business corrections use explicit reversal/correction flows, not hidden row rewriting.
- schema/migrations become high-value reviewed artifacts.

## jOOQ

The spike used jOOQ successfully as the SQL/transaction access layer.

jOOQ remains the leading persistence-access candidate, but its final generated-schema/codegen/repository conventions are not frozen by this ADR.

## Revisit Triggers

Revisit only if a verified production requirement cannot be met safely/operably by PostgreSQL, not for speculative scale concerns.

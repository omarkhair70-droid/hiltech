# SPIKE-11 — PostgreSQL + jOOQ Ledger / Concurrency

Status: RUNNING

## Exact tested line

- PostgreSQL 18.6
- jOOQ 3.21.8
- pgJDBC 42.7.13
- Kotlin 2.4.20
- Java 21

## HILTECH scenarios

### Asset checkout collision

Two users attempt to check out the same high-value asset at the same time using the same expected asset version.

Required:
- exactly one succeeds,
- exactly one conflicts,
- one authoritative custodian,
- one movement ledger row,
- asset version increments once.

### Idempotent retry

A client repeats the same checkout command using the same operationId.

Required:
- business action applies once,
- retry returns already-applied,
- ledger row remains one.

### Stale version

A command uses an old expected version.

Required:
- conflict,
- no second movement.

### Stock collision

Two users each try to issue 7 units while stock is only 10.

Required:
- only one succeeds,
- quantity never becomes negative,
- final quantity = 3,
- one stock movement,
- version increments once.

## Pass criteria

All tests run against real PostgreSQL 18.6 in GitHub Actions.

ACCEPT only if database constraints + transactional compare-and-update logic prevent impossible physical states under concurrent requests.

## Production status

Disposable concurrency evidence.
Not final production schema or repository implementation.

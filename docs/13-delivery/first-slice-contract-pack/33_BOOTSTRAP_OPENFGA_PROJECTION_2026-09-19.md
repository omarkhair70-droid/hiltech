# 33 — Bootstrap OpenFGA Authorization Projection

Date: 2026-09-19
Status: **PASS / BOOTSTRAP VERIFIED**

## Purpose

Materialize the frozen authorization consistency contract:

PostgreSQL business truth
→ same-transaction projection intent/outbox
→ local fail-closed guard
→ pinned-model OpenFGA projection/check
→ durable retry / stale-revision protection.

Canonical sources:
- `05_AUTHORIZATION_POLICY_TESTS.md`,
- `15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`,
- frozen `openfga/first-slice-model.fga`,
- accepted OpenFGA and end-to-end spikes.

## Production boundary

Implemented on:
`bootstrap/phase0-20260919`

Primary commits:
- `0d78b9fdb139a4be2243f0bab9f4afb265202b54` — pinned OpenFGA projection boundary,
- `a0c0401902babaa71b0dd48911d9e94c3e184083` — environment binding correction,
- `0ecafc6972501c03b7dbb8cef82249e5c1c0080c` — real PostgreSQL fail-closed projection verification.

## Pinned OpenFGA contract

Environment-backed configuration:
- `HILTECH_FGA_ENABLED`,
- `HILTECH_FGA_API_URL`,
- `HILTECH_FGA_STORE_ID`,
- `HILTECH_FGA_MODEL_ID`,
- `HILTECH_FGA_REQUEST_TIMEOUT_MS`,
- `HILTECH_FGA_PROJECTOR_POLL_DELAY_MS`,
- `HILTECH_FGA_CLAIM_LEASE_SECONDS`.

When enabled, API URL, store ID and authorization model ID are mandatory.

Every:
- Check,
- tuple write,
- tuple delete

carries the explicit configured authorization model ID.

No production path asks OpenFGA for an implicit latest model.

## Fail-closed decision adapter

`FailClosedAuthorizationAdapter` evaluates local projection guards before the OpenFGA check.

Callers provide:
- the tuple/action being checked,
- relationship tuples whose unresolved projection state must guard that action.

Behavior:
- unresolved/failed PRESENT projection → deny,
- unresolved/failed ABSENT projection → deny immediately,
- APPLIED projection → normal OpenFGA decision,
- OpenFGA transport/non-success/malformed check → deny.

This preserves the contract that a stale FGA allow cannot override a locally-known unresolved revoke.

Domain/state/field/re-auth policy remains a later application gate and is not moved into OpenFGA.

## Idempotent projection writes

Projection applies:
- PRESENT with OpenFGA duplicate-write ignore semantics,
- ABSENT with missing-delete ignore semantics.

The projector therefore converges safely when the same durable outbox item is retried after an ambiguous network/process failure.

## PostgreSQL intent / outbox

`JdbcAuthorizationProjectionIntentWriter`:
- requires OpenFGA to be enabled,
- participates in an existing authoritative transaction using mandatory transaction propagation,
- upserts `authorization_relation_projection`,
- writes `authorization_projection_outbox`,
- pins the configured authorization model ID,
- resets a newer desired relation revision to PENDING.

Business modules remain owners of source relationships.
Security/platform owns projection/outbox state.

## Projector behavior

`JdbcAuthorizationProjectionStore` and `AuthorizationProjectionProcessor` implement:
- `FOR UPDATE ... SKIP LOCKED` claim,
- claim lease recovery,
- latest source-version comparison,
- stale outbox suppression,
- pinned model mismatch failure,
- APPLIED only after OpenFGA success,
- retryable versus permanent failure handling,
- compare-by-source-version updates so an old in-flight apply cannot mark a newer relationship revision APPLIED,
- scheduled bounded batch processing.

Frozen retry schedule is represented:
1s → 2s → 5s → 10s → 30s → 1m → 2m → 5m cap

with stable bounded ±20% jitter derived from the relation key/retry number so retry eligibility remains deterministic across server restarts without a new schema column.

## Real PostgreSQL verification

Run `35408965431` (#25) is the canonical verification run.

It proved on PostgreSQL 18.6:

1. PRESENT projection intent + outbox commits.
2. Pending grant is denied by the local guard.
3. Projector safely claims the outbox.
4. Marking projection APPLIED releases the guard to normal FGA evaluation.
5. New ABSENT source revision commits.
6. Pending revoke denies immediately.
7. Revoke projection is claimed/applied.
8. Final row is `ABSENT / APPLIED`.
9. Both outbox revisions are completed.
10. authorization model ID remains pinned.

The same run also proved:
- Shared tests PASS,
- Room schema PASS,
- Android debug build PASS,
- Desktop compile PASS,
- Server tests PASS,
- PostgreSQL migrations/constraints PASS,
- jOOQ generation/verification PASS,
- server compile against generated jOOQ PASS.

## Security properties retained

- PostgreSQL remains business relationship truth.
- OpenFGA remains authorization projection/decision engine.
- no JWT role/title shortcut was introduced.
- no positive authorization cache was introduced.
- pending grants do not optimistically allow.
- pending revokes deny locally before tuple cleanup.
- stale projector revisions do not become authoritative.
- tuple identifiers remain opaque technical identifiers.

## Next bootstrap gate

Evidence/object-storage boundary:
- reserve,
- single signed PUT,
- expected SHA-256 / size,
- authoritative finalize,
- private download,
- OCI Object Storage/KMS production adapter boundary,
- local/dev provider contract.

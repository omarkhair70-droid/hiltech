# 15 — Authorization Projection Consistency Contract

Status: **CONTRACT CANDIDATE v0.1**
Date: 2026-09-18

## Purpose

Define how authoritative HILTECH business relationships in PostgreSQL and their OpenFGA authorization projection remain safe when they cannot participate in one cross-system transaction.

Core rule:

**PostgreSQL owns business relationship truth. OpenFGA is the relationship-authorization projection. Cross-system inconsistency must fail closed.**

This contract prevents:
- stale FGA grant surviving a business revoke,
- new grant becoming effective before projection is ready,
- out-of-order projector work re-adding an old relationship,
- deployment accidentally switching to an unapproved OpenFGA model,
- cached FGA results ignoring a security-sensitive recent tuple change.

---

# 1. Source of truth

PostgreSQL owns authoritative business facts such as:
- OrganizationMembership state.
- TeamMembership state.
- Project assignment.
- WorkAssignment.
- reviewer assignment/policy resolution inputs.
- Warehouse/Storage responsibility.
- Asset custody.
- Delegation lifecycle.
- configuration administration assignment.

OpenFGA stores the derived relationship tuples required to answer relationship authorization efficiently.

OpenFGA tuples must not become the only recoverable record of a HILTECH business relationship.

---

# 2. Pinned authorization model

Production HILTECH server configuration contains:

- openFgaStoreId
- openFgaAuthorizationModelId

Every OpenFGA:
- Check,
- ListObjects/ListUsers where used,
- Write/Delete tuple request

must specify the pinned authorization model ID.

Do not use "latest model" implicitly in production.

Authorization model changes are immutable model-version changes and use an explicit migration/rollout.

---

# 3. Model rollout contract

Authorization models are immutable.

Candidate rollout:

1. create new model version.
2. validate model/tests in CI.
3. prepare tuple compatibility/migration if relation semantics changed.
4. run shadow/dual checks where risk justifies it.
5. deploy app/config referencing the new explicit model ID.
6. monitor authorization denials/allow discrepancies.
7. retire prior model use only after rollout acceptance.

Never overwrite an existing production model in place.

---

# 4. PostgreSQL projection state

Introduce an application-owned projection record.

## authorization_relation_projection

Candidate fields:

- relation_key varchar/typed canonical key primary key
- subject_type varchar not null
- subject_id uuid/string not null
- relation varchar not null
- object_type varchar not null
- object_id uuid/string not null
- desired_state varchar not null — PRESENT / ABSENT
- source_type varchar not null
- source_id uuid/string not null
- source_version bigint not null
- projection_state varchar not null — PENDING / APPLYING / APPLIED / FAILED
- authorization_model_id varchar not null
- last_attempt_at timestamptz null
- applied_at timestamptz null
- last_error_code varchar null
- retry_count integer not null
- created_at timestamptz not null
- updated_at timestamptz not null

Unique semantic relation key:
subject + relation + object under the selected OpenFGA model semantics.

This record describes the latest desired projection state.

---

# 5. Same-transaction outbox

Any authoritative business transaction that changes a projected relationship does both in the **same PostgreSQL transaction**:

1. updates the business relationship state.
2. upserts the latest `authorization_relation_projection` desired state/revision.
3. writes a durable outbox/event record identifying the projection key/revision.
4. writes audit/domain event as required.
5. commits.

If the PostgreSQL transaction rolls back, no relationship projection intent survives.

There is no direct "DB committed but we forgot to remember FGA update" path.

---

# 6. Projector behavior

The authorization projector:

1. claims pending outbox/projection work.
2. loads the **latest** projection row.
3. ignores stale event revision older than current source_version.
4. verifies configured/pinned authorization_model_id.
5. groups compatible tuple writes/deletes where safe.
6. invokes OpenFGA Write with explicit model ID.
7. marks projection APPLIED only after OpenFGA success.
8. retries retryable failure with bounded/backoff policy.
9. leaves FAILED/PENDING visible to operations on permanent/exhausted failure.

Never replay an old "grant" after a newer revoke.

The projection row, not outbox arrival order alone, decides desired current state.

---

# 7. Fail-closed grant rule

When PostgreSQL changes relationship from absent → granted:

- business state may commit immediately.
- projection state becomes PENDING/PRESENT.
- the new authority is **not considered fully effective** for authorization-sensitive action until FGA projection is APPLIED.

Therefore:
- before APPLIED, authorization resolver denies/returns AUTHORIZATION_SYNC_PENDING where exposing that state is safe.
- normal UI can show "Access pending" to an authorized administrator if useful.
- no temporary optimistic grant.

This prevents access from being granted merely because DB truth changed before FGA can enforce it.

---

# 8. Fail-closed revoke rule

When PostgreSQL revokes/removes a relationship:

- business truth changes immediately.
- projection desired state becomes ABSENT/PENDING.
- server must deny the revoked authority **immediately**, even if old FGA tuple/cache could still allow.

Candidate guard:

Before accepting a relationship-sensitive command/query, AuthorizationAdapter checks a small indexed local projection/security guard for an unresolved ABSENT/revoke state relevant to the exact relation.

If a pending revoke exists:
- deny locally without trusting stale FGA allow.

After FGA delete is APPLIED:
- the guard can be cleared/compacted according retention/diagnostics policy.

This is mandatory for:
- membership revoke/offboarding,
- Work reassignment/removal,
- reviewer removal,
- Delegation expiry/revoke,
- config-admin/activator revoke,
- other security-critical removal.

---

# 9. Pending grant/revoke guard semantics

Candidate states:

- PRESENT + APPLIED → normal FGA decision.
- PRESENT + PENDING/APPLYING → deny new grant until applied.
- ABSENT + PENDING/APPLYING/FAILED → deny immediately.
- ABSENT + APPLIED → FGA has no tuple; normal deny/inherited relations still evaluated.
- projection FAILED → fail closed for the changed relation and surface operational exception.

A failed projection must never be silently treated as successful access synchronization.

---

# 10. OpenFGA atomic tuple batch

When one logical HILTECH relationship change requires multiple FGA tuple writes/deletes:

- use one OpenFGA Write request where within provider/request limits,
- include writes + deletes together so the FGA-side tuple mutation is atomic,
- pin authorization model ID.

Examples:
- replace one direct assignment tuple with another,
- promote relationship where old relation must be deleted and new one added,
- apply a small delegation relation set.

This atomicity applies **inside OpenFGA only**.
It does not make PostgreSQL + OpenFGA one transaction.

Large migrations use explicit batching/migration controls rather than assuming one atomic global write.

---

# 11. Query consistency policy

Default stable authorization checks:
- MINIMIZE_LATENCY/default consistency is acceptable where no recent security-sensitive relationship mutation exists.

Use HIGHER_CONSISTENCY selectively when:
- immediately checking after a successful tuple write/delete whose effect must be visible now,
- completing a critical grant/revoke workflow,
- validating a security-sensitive configuration/relationship change,
- recovery tooling verifies synchronization.

Do not use HIGHER_CONSISTENCY on every request by default.

The local fail-closed guard remains necessary because query consistency cannot create atomicity with PostgreSQL.

---

# 12. Relationship mutation completion

A user-facing command that changes authority can have two completion levels:

## BUSINESS_COMMITTED_AUTH_PENDING
PostgreSQL business state committed, FGA projection not yet APPLIED.

## AUTHORIZATION_APPLIED
Projection APPLIED and, where critical, higher-consistency verification succeeded.

For security-critical admin operations, UI should expose pending/failure rather than claim fully synchronized success.

Domain workflows may choose to wait synchronously for AUTHORIZATION_APPLIED when low latency and correctness require immediate use.

---

# 13. Synchronous fast path

For high-value UX where new authority must be available immediately:

1. PostgreSQL transaction commits relationship + projection intent.
2. same request path invokes projector/apply attempt.
3. OpenFGA Write succeeds.
4. optional HIGHER_CONSISTENCY verification.
5. projection marked APPLIED.
6. response reports AUTHORIZATION_APPLIED.

If FGA is unavailable:
- business relationship remains committed with projection pending,
- grant remains fail-closed,
- revoke remains locally denied,
- background projector retries.

Do not roll back real business state by inventing a distributed transaction.

---

# 14. Idempotency

Projection application must be idempotent.

Key:
- relation_key + desired_state + source_version/model id.

Repeated projector attempt:
- must converge to desired tuple state,
- must not create duplicate business records,
- may safely handle "tuple already present/absent" according OpenFGA API semantics.

Out-of-order older revision is discarded/skipped.

---

# 15. Delegation expiry

Delegation has authoritative validFrom/validUntil/state in PostgreSQL.

At request time:
- application checks current delegation validity even if tuple remains temporarily in FGA.

Expiry therefore denies immediately.

Projector removes expired tuple asynchronously/deterministically.

This prevents clock-based delegation expiry from depending solely on projector timing.

---

# 16. Offboarding / membership revoke

Offboarding/revoke path:

1. authoritative membership/employee state changes.
2. sessions/device access revoked according identity contract.
3. projection desired relations become ABSENT.
4. local fail-closed guard denies immediately.
5. tuple deletes project to FGA.
6. queued offline commands re-authenticate/re-authorize and fail if authority is gone.
7. local evidence/intent remains controlled for support/recovery.

Offboarding must not wait for eventual FGA tuple cleanup to become effective.

---

# 17. Tuple data rules

OpenFGA tuple identifiers use opaque HILTECH IDs.

Do not put:
- person names,
- emails,
- phone numbers,
- national IDs,
- salary/bank data,
- client secrets

into tuple user/object identifiers.

Tuple identity is authorization structure, not human-readable directory data.

---

# 18. Observability

Expose safe metrics:

- authorization_projection_pending_count
- authorization_projection_failed_count
- oldest_pending_age
- apply_latency
- FGA write failures
- higher-consistency verification failures
- model ID currently pinned

Trace:
business command correlationId
→ outbox/projection key
→ FGA write/check
→ applied state.

Do not log sensitive tuple payload beyond opaque IDs/allowed safe dimensions.

---

# 19. Operations / recovery

Admin/support tooling needs:

- list pending/failed authorization projections.
- retry one projection.
- retry all retryable.
- inspect source business object/ref.
- verify tuple under pinned model.
- compare desired vs projected state.
- safe reconciliation job.

Reconciliation can periodically detect:
- desired PRESENT missing in FGA,
- desired ABSENT unexpectedly present,
- stale tuple from retired model migration.

Repair remains audited.

---

# 20. Model migration safety

When model relation semantics change:

- do not silently switch model IDs.
- migration plan specifies source model, target model, tuple transformation, test fixtures, rollout.
- application config pins exactly one production model per rollout cohort/environment.
- shadow checks may compare models before cutover.
- old model/tuple cleanup happens after acceptance.

---

# 21. Tests required before first-slice freeze

- grant relation commits DB but FGA unavailable → access denied until projection applies.
- revoke relation commits DB but FGA unavailable → access denied immediately.
- stale projector grant event after newer revoke → skipped.
- duplicate projector delivery → idempotent.
- FGA atomic write replaces tuple set correctly.
- wrong/unpinned model ID rejected by configuration/test.
- immediate post-write HIGHER_CONSISTENCY check sees intended tuple state.
- default-consistency cache cannot bypass local pending-revoke guard.
- expired Delegation denied before tuple cleanup.
- offboarded user denied with stale tuple still present.
- queued offline Work command denied after assignment revoke.
- projection failure visible in ops metrics/queue.
- recovery reconciliation repairs drift.
- model-version rollout tests both old/new model fixtures.

---

# 22. Persistence ownership

Platform/security integration owns:
- authorization_relation_projection
- authorization outbox/projection processing state
- model deployment record/config
- reconciliation diagnostics

Business modules own the source relationships.

OpenFGA remains an external projection/decision engine, not a cross-domain business database.

---

# Current decision

**ACCEPT AS CONTRACT CANDIDATE v0.1**

The first-slice authorization system now has an explicit cross-system consistency model:

PostgreSQL truth
→ transactional projection intent/outbox
→ fail-closed guard
→ OpenFGA pinned-model projection
→ selective higher-consistency verification
→ reconciliation/observability.

This closes the generic "how do Postgres and OpenFGA stay safe?" architecture gap.

Remaining authorization work:
- exact production table DDL/indexes.
- production OpenFGA deployment/provider operational details.

Closed:
- OpenFGA DSL/fixtures validated.
- projector retry/backoff constants.
- 2-second synchronous fast-path budget.
- no first-slice application allow-cache.


---

# 23. Retry / fast-path / cache constants

## Synchronous authority-change fast path

After PostgreSQL commit:
- attempt immediate OpenFGA projection within a 2-second server budget.
- if APPLIED inside budget, return AUTHORIZATION_APPLIED.
- if not, return BUSINESS_COMMITTED_AUTH_PENDING and let durable projector continue.
- a pending grant stays denied.
- a pending revoke stays locally denied.

The business request does not hold a distributed transaction open.

## Projector retry schedule

- 1s
- 2s
- 5s
- 10s
- 30s
- 1m
- 2m
- 5m cap with +/-20% jitter thereafter

Permanent/model/validation error:
- mark FAILED.
- fail closed.
- surface operations exception.
- do not infinite-loop a non-retryable write.

## Authorization decision cache

First production slice:
- no application-level positive/allow decision cache in front of OpenFGA for critical actions.
- server may reuse connection pools/transport and OpenFGA may use its own supported internals.
- deny/fail-closed guards remain local PostgreSQL truth.

A future application-level authorization cache requires:
- bounded TTL,
- relation/model-aware key,
- invalidation on projection change,
- proof that revoke/offboarding cannot be bypassed.

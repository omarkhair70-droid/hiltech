# Phase 2 / Slice 03 — Activity Events Foundation

Date: 2026-09-19  
Status: **VERIFIED / READY FOR MERGE**

## Why this is the next Phase 2 slice

Canonical Phase 2 order includes:

1. documents/evidence metadata,
2. object upload/finalize flow,
3. audit,
4. activity events,
5. approval engine foundation,
6. Inbox / Work Queue foundation,
7. notification abstraction,
8. shared read/query infrastructure.

Repository reality now says:
- Evidence metadata/upload/finalize is VERIFIED and merged in Slice 02.
- Audit baseline is already VERIFIED from Phase 1 Slice 05 and is actively reused by Slice 02.
- Activity events therefore become the next unresolved shared capability.
- Approval Engine is **not** pulled forward: its canonical product/policy documents remain `NOT AUTHORITY-FROZEN` and explicitly require HILTECH approval-line/threshold reality before authority freeze.

This slice does not skip Approval. It closes the preceding shared event/activity foundation without inventing approval authority.

## Canonical sources

Mandatory:
- `AGENTS.md`
- `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- `docs/13-delivery/IMPLEMENTATION_ORDER.md`
- `docs/03-product/FEATURE_CATALOG.md` — CORE-ACT-001/002/003 boundary
- `docs/03-product/PRODUCT_MAP.md`
- `docs/05-workflows/EVENT_MAP.md`
- `docs/06-data/object-specs/APPROVAL_NOTIFICATION_SYNC_OBJECTS.md`
- `docs/11-architecture/MODULE_OWNERSHIP_MAP.md`
- `docs/11-architecture/MODULE_DEPENDENCY_GRAPH.md`
- `docs/11-architecture/READ_MODEL_ARCHITECTURE.md`
- `docs/11-architecture/ADR/ADR-003-SPRING-MODULITH.md`
- `docs/13-delivery/spike-results/SPIKE_10_SPRING_MODULITH.md`
- `docs/13-delivery/first-slice-contract-pack/02_API_AND_READ_MODELS.md`
- `docs/13-delivery/first-slice-contract-pack/05_AUTHORIZATION_POLICY_TESTS.md`
- `docs/13-delivery/first-slice-contract-pack/openfga/first-slice-model.fga`
- Phase 1 Audit closure
- Phase 2 Slice 01 command/runtime closure
- Phase 2 Slice 02 Evidence lifecycle closure

## Product boundary

Activity is a user-facing operational history/read projection.

It answers:
- what meaningful thing happened,
- to which authorized business context,
- when,
- from which authoritative source object/event,
- with a safe typed summary suitable for a timeline.

Activity is **not**:
- business authority,
- an audit log replacement,
- a command queue,
- a notification,
- an Inbox work item,
- an approval request,
- a chat/comments system,
- an integration event bus,
- a generic JSON dump of domain events.

## Audit vs Activity

The existing `audit_event` remains append-only compliance/security evidence.

Activity has different semantics:
- optimized for authorized operational reading,
- can expose a safe human-meaningful timeline,
- may intentionally omit low-value technical/security events,
- may aggregate or project business facts later,
- must never be used to reconstruct authoritative domain state.

A single authoritative action may legitimately create:
- an audit record for compliance/security,
- an Activity record for operational history,
without either becoming the other's source of truth.

Do not build Activity by querying/parsing `audit_event`.

## Initial production scope

### Context
Only:
`WORK_ORDER`

Initial read surface:
`GET /v1/work-orders/{workOrderId}/activity`

Authorization:
- current source WorkOrder must exist,
- caller must satisfy current `work_order.can_view`,
- permission is evaluated at read time,
- no client role/title/display string is authority,
- removed/reassigned users must lose visibility when current OpenFGA/source truth says so.

Unsupported activity scopes remain fail-closed until their owner domain phase supplies authoritative visibility relations.

### Initial event source
Only authoritative Evidence lifecycle facts already owned by Slice 02.

Initial Activity facts are produced for material Evidence terminal transitions:
- `READY`,
- `QUARANTINED`,
- `REJECTED`.

Do not create Activity for:
- signed URL issuance,
- metadata reads,
- downloads,
- retries that do not change authoritative Evidence state,
- exact duplicate command replay,
- transient missing-object finalize attempts that remain retryable/non-terminal.

The technical internal event type may be an implementation detail. Product Activity exposes stable typed activity codes rather than leaking Kotlin class names.

## Event publication contract

Use the accepted Spring Modulith modular-monolith boundary.

Rules:
1. the Evidence state transaction remains authoritative;
2. a material state transition publishes one immutable internal application event with a stable event ID;
3. Activity projection happens through an explicit module boundary;
4. asynchronous projection uses the accepted durable Spring Modulith publication registry where applicable;
5. failed projection remains observable/recoverable;
6. controlled resubmission must not create duplicate Activity rows;
7. no Kafka/RabbitMQ/external broker is introduced for this slice;
8. Activity projection failure must not rewrite or roll back already-committed Evidence truth unless the chosen listener semantics execute in the same transaction by explicit design.

The implementation must prove its chosen transaction/publication behavior, not rely on assumptions.

## Persistence contract

Introduce a dedicated Activity persistence boundary owned by the Activity/Inbox-facing shared capability, not by Audit and not by Documents.

Minimum authoritative projection fields:
- `id` UUID,
- `source_event_id` UUID — stable dedupe identity,
- `activity_type` typed code,
- `context_type` — initially WORK_ORDER,
- `context_id` UUID,
- `source_type` — initially EVIDENCE,
- `source_id` UUID,
- `source_version` when available,
- `actor_user_id` nullable,
- `occurred_at`,
- `recorded_at`,
- `safe_summary` JSONB or equivalent allow-listed typed metadata,
- `correlation_id` nullable,
- `classification_code`,
- schema/version field if required for durable compatibility.

Required constraints:
- unique `source_event_id`,
- context/source IDs non-null for this scope,
- allow-listed context/source/activity codes,
- no signed URLs,
- no auth tokens,
- no file bytes,
- no object-storage key,
- no raw file name by default,
- no SHA/hash unless a later explicit UI requirement justifies exposure,
- no unrestricted arbitrary domain payload.

Migration naming/number follows current Flyway order from main; do not renumber prior migrations.

## Safe Activity envelope

Initial response item must be sufficient for timeline UI without becoming a generic domain-object mirror.

Candidate shared DTO:
- `activityId`
- `activityType`
- `contextType`
- `contextId`
- `sourceType`
- `sourceId`
- `occurredAt`
- `safeSummary`
- `classificationCode`
- `correlationId?`

`safeSummary` is server-authored and allow-listed per activity type.

Initial Evidence summary may contain only safe state/context hints such as:
- Evidence type code,
- resulting storage state,
- requirement key where it is not sensitive.

It must not contain upload/download targets or secret provider metadata.

## Read/pagination contract

Use the frozen first-slice list/read envelope:
- `items`,
- `nextCursor`,
- `asOf`,
- `correlationId`.

Ordering:
- newest material Activity first,
- stable deterministic tie-breaker,
- cursor opaque/tamper-protected using the existing cursor convention,
- clients do not decode cursors.

No Redis cursor session is required.

## Authorization and classification

WorkOrder Activity visibility derives from current WorkOrder authority.

Minimum:
- current WorkOrder source guard,
- OpenFGA `work_order.can_view`,
- cross-organization access fails closed,
- activity payload is field-filtered before return,
- Activity existence must not leak hidden source objects.

Classification:
- initial activity cannot be less restrictive than its source/context policy permits,
- HIGHLY_RESTRICTED source metadata must never be broadened by the Activity projection,
- the projection should prefer safe codes over copied business payload.

## Idempotency / duplicate safety

Activity rows are projections of immutable facts.

Required:
- exact duplicate Evidence finalize replay does not create a second source event,
- listener retry/redelivery does not create a second Activity row,
- crash after domain commit but before projection completion remains recoverable,
- projection dedupe is enforced in PostgreSQL, not only in memory.

## Shared client boundary

Add a thin typed common client usable by Android and Windows:
- fetch WorkOrder activity page,
- pass opaque cursor,
- authenticated through existing shared HTTP runtime,
- no platform-specific duplicate DTO contract.

This slice does not require a polished timeline UI. A minimal shared-client contract is required; rich screen work belongs to the consuming product slice/design pass.

## Observability

Carry:
- traceparent,
- correlation ID,
- source event ID/activity ID where safe.

Telemetry may record:
- activity type,
- projection outcome,
- retry/recovery status,
- latency.

Telemetry must not record:
- raw Evidence bytes,
- signed URLs,
- tokens,
- unrestricted safeSummary payload.

## Explicit non-goals

Do not implement in Slice 03:
- ApprovalRequest/steps/decisions,
- HILTECH Inbox state,
- push/email/SMS/desktop delivery,
- notification preferences/quiet hours,
- mentions/comments/chat,
- company-wide activity across every domain,
- external event broker,
- event sourcing,
- domain-state reconstruction from Activity,
- new hard-coded people/authority rules,
- finance/payroll/warehouse-specific event policy,
- production cutover/provider changes.

## Required evidence for VERIFIED

1. Flyway migration creates dedicated Activity persistence with required constraints.
2. Real PostgreSQL contract persists a material Activity projection.
3. Production Evidence terminal transition publishes the initial source event.
4. READY produces exactly one WorkOrder Activity item.
5. QUARANTINED produces exactly one WorkOrder Activity item.
6. REJECTED produces exactly one WorkOrder Activity item.
7. transient/non-terminal finalize failure produces no Activity item.
8. exact duplicate command replay produces no duplicate Activity item.
9. listener redelivery/recovery produces no duplicate Activity item.
10. failed projection can be detected and recovered using the accepted Spring Modulith publication mechanism.
11. authorized `work_order.can_view` actor can read Activity.
12. unauthorized/cross-scope actor is denied/hidden according to existing visibility policy.
13. current authorization is re-evaluated after assignment/relationship change.
14. Activity response contains no signed URLs, tokens, storage keys or raw binary/hash payload.
15. Activity and Audit are proven separate persistence/query boundaries.
16. cursor pagination is deterministic and opaque with stable no-dup/no-skip behavior across a fixed `asOf`.
17. shared Android/Windows client contract compiles/tests against the same DTOs.
18. Spring Modulith module verification remains PASS.
19. Phase 1 OIDC regressions remain PASS.
20. Phase 2 Slice 01 shared command/runtime regressions remain PASS.
21. Phase 2 Slice 02 Evidence lifecycle/PostgreSQL/OpenFGA/S3 regressions remain PASS.
22. Bootstrap database/foundation/supply-chain/Terraform/evidence-storage/local-platform gates remain PASS where applicable.

## Verification closure evidence — 2026-09-19

Canonical implementation code head:
`e1244da23a0889bab33b249bf4ef96488aef1699`

Canonical verification-closure documentation head:
`1ddf37da97eb626101777f0b1ae2833bfa8baa89`

Closure regression evidence:
- Phase 2 — Shared Command Runtime run `35437488895` — **PASS**.
- Bootstrap Phase 0 run `35437488932` — **PASS**, including local-platform, database/jOOQ, foundation/Spring Modulith verification, evidence-storage, dependency-review, supply-chain and Terraform.
- Phase 1 — Native OIDC Production Smoke run `35437489102` — **PASS**, including Desktop shell render, provider/browser smoke and Android shell/private-callback render.

The Bootstrap local-platform gate proves:
- PostgreSQL Activity persistence,
- READY / QUARANTINED / REJECTED projection,
- source-event dedupe,
- current WorkOrder authorization re-evaluation,
- safe Activity payload,
- Audit/Activity separation,
- fixed-`asOf` opaque cursor behavior,
- existing Evidence lifecycle regressions,
- Spring Modulith durable failed-publication detection and controlled resubmission without duplicate Activity.

All 22 Required Evidence gates are satisfied.

---

## Contract-check conclusion

**IMPLEMENTATION VERIFIED.**

No frozen architecture contradiction was found.

The contract deliberately keeps the slice narrow:
- one context: WorkOrder,
- one existing authoritative source family: Evidence terminal state,
- one permission model: current WorkOrder visibility,
- one durable projection boundary: Activity,
- no Approval/Inbox/Notification authority.

Approval Engine remains the later canonical Phase 2 capability and keeps its own reality/freeze gate.

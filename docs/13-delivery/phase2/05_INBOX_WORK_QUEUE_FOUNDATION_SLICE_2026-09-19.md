# Phase 2 / Slice 05 — Inbox / Work Queue Foundation

Date: 2026-09-19  
Status: **VERIFIED / READY TO MERGE**

## Reality basis

Canonical reality gate:

`docs/13-delivery/phase2/05_INBOX_WORK_QUEUE_REALITY_CLOSURE_2026-09-19.md`

Frozen minimal thesis:
- source domains remain authoritative;
- Work Queue is a current-action read surface;
- Inbox is durable attention + user read state;
- read/unread is presentation state only;
- notifications are separate and out of scope;
- Approval is the first real producer;
- unsupported source types fail closed.

## Product boundary

Inbox / Work Queue is a **shared attention projection**, not a generic workflow engine.

It is not:
- a generic Task object;
- a BPM engine;
- a new assignment authority;
- a notification delivery system;
- a comments/chat/mentions system;
- a substitute for source-domain state;
- a place to copy sensitive subject payloads;
- a global organization feed.

## Initial source

The only required production producer in this slice is verified Approval.

Projection rules:

### ApprovalRequested

Create or update one logical actionable item:
- source type: `APPROVAL_REQUEST`;
- source id: ApprovalRequest ID;
- source version: exact request/source version carried by the event;
- action key: `DECIDE_APPROVAL`;
- organization;
- target principal snapshot from the current Approval assignment;
- state: `OPEN`;
- safe semantic label/reason only;
- correlation/source event identity.

### Approval terminal result

Any of:
- ApprovalApproved;
- ApprovalRejected;
- ApprovalChangeRequested;
- ApprovalSuperseded

resolves the corresponding actionable item:
- state -> `RESOLVED`;
- resolvedAt recorded;
- source version advanced where available;
- no generic "acted" command is needed.

Approval remains the authoritative state.

## Persistence

### inbox_item

Minimum:
- id;
- organization_id;
- producer_key;
- source_type;
- source_id;
- source_version;
- action_key;
- attention_class;
- target_principal_type USER/TEAM;
- target_principal_id;
- state OPEN/RESOLVED;
- safe_title_code;
- safe_summary nullable;
- source_event_id;
- created_at;
- updated_at;
- resolved_at nullable;
- correlation_id nullable;
- version.

Initial `attention_class`:
- `ACTION_REQUIRED`.

Do not activate speculative STATUS/CRITICAL/TIME_SENSITIVE policy in this slice.

Required constraints:
- one logical row per `producer_key`;
- source event replay cannot duplicate an effect;
- target principal shape is valid;
- OPEN cannot have resolved_at;
- RESOLVED must have resolved_at;
- version/source_version positive;
- safe summary bounded.

### inbox_user_state

Per-user presentation state:
- inbox_item_id;
- user_identity_id;
- read_at nullable;
- updated_at;
- version.

Semantics:
- absence/read_at null = unread;
- read_at set = read;
- mark read/unread is idempotent;
- user state cannot resolve or mutate the source item.

This separate row is required because TEAM-targeted attention may be visible to multiple current members without duplicating source truth.

## Producer identity

Each producer must supply a stable logical `producer_key`.

For Approval:
`approval:<approvalRequestId>:decide`

The key identifies the logical action projection.

It is not a secret and does not authorize anything.

## Work Queue semantics

Work Queue returns the subset of Inbox items that are:
1. `OPEN`;
2. `ACTION_REQUIRED`;
3. targeted to the actor or a team the actor currently belongs to;
4. currently visible/actionable according to the source adapter;
5. organization-safe.

Default ordering:
- oldest waiting first: `created_at ASC, id ASC`.

Reason:
the minimal foundation has no verified cross-domain urgency score; waiting time is deterministic and does not invent business priority.

Future producers may add explicit due/priority policy only through later contract change.

## Inbox semantics

Inbox returns durable attention visible to the actor.

Minimal read supports:
- OPEN;
- RESOLVED;
- read/unread state.

Default ordering:
- newest first: `updated_at DESC, id DESC`.

No retention/purge policy is invented in this slice.

No user dismiss/snooze action is implemented.

## Current-source revalidation

A projection row is never enough to authorize action.

Define a source actionability/visibility boundary such as:

`InboxSourceAccessPort`

Given:
- actor identity;
- source type/id;
- action key;
- target principal snapshot;

it returns current visibility/actionability.

Unsupported source type:
- fail closed.

### Approval adapter

For `APPROVAL_REQUEST / DECIDE_APPROVAL`, re-use verified Approval rules:
- request still PENDING;
- assignment still ASSIGNED;
- actor still matches assignment;
- current authority binding still matches the assignment principal;
- active organization/team membership;
- OpenFGA `approval_request.can_decide`.

If any check fails:
- item does not appear in Work Queue;
- direct Inbox item read is hidden if its safe metadata would reveal unauthorized context;
- projection data itself never grants source action.

## Authority changes

Slice 04 deliberately does not silently reassign an Approval when owner/final authority changes.

Slice 05 preserves that behavior.

If current authority changes:
- the stale old authority item becomes non-actionable/hidden through current-source revalidation;
- Slice 05 does not invent a new assignee;
- a future explicit Approval recovery/reassignment contract may create/update the logical queue item.

## Read/unread commands

Allowed:
- mark Inbox item read;
- mark Inbox item unread.

Required:
- authenticated user;
- current item visibility;
- idempotent result;
- Audit only if existing audit policy classifies this presentation mutation as required; do not spam business Audit by default.

Not allowed:
- generic complete;
- generic approve;
- generic reject;
- generic reassign;
- dismiss mandatory action;
- mutate source state.

Source actions happen through source APIs, e.g. Approval decision endpoint.

## API

Minimal server API:

### Work Queue
`GET /v1/work-queue`

Query:
- cursor optional;
- limit 1..100.

Returns safe actionable summaries and deep-link/source references.

### Inbox
`GET /v1/inbox`

Query:
- cursor optional;
- limit 1..100;
- state optional `OPEN|RESOLVED`;
- read optional `READ|UNREAD`.

### One Inbox item
`GET /v1/inbox/{inboxItemId}`

Current visibility required.

### Read state
`POST /v1/inbox/{inboxItemId}/read`
`POST /v1/inbox/{inboxItemId}/unread`

Use operation ID / `Idempotency-Key` through the shared command runtime.

Do not expose:
- create Inbox item endpoint;
- generic complete endpoint;
- generic assignment endpoint.

## DTO safety

A queue/inbox item may return:
- item ID;
- item/action type;
- source type/id;
- safe title code;
- safe bounded summary;
- state;
- read state;
- created/updated/resolved timestamps;
- source deep-link hint where safe.

Do not return:
- arbitrary subject body;
- invoice/payroll amounts merely because source has them;
- evidence URLs;
- tokens;
- object-storage keys;
- raw filenames;
- provider IDs/secrets;
- hidden requester/subject details.

The client follows the authorized source reference to fetch full detail from the owning domain.

## Pagination

No Redis.

Opaque, HMAC-protected cursor.

Cursor must bind:
- actor identity;
- query/surface;
- fixed `asOf`;
- filter state/read values;
- exact boundary timestamp using full `Instant` precision;
- deterministic ID tie-break.

Work Queue and Inbox may use different sort direction, but each must prove no duplicate/skip.

Cursor from another actor/filter/surface is invalid.

## Shared client

One common Android/Windows contract for:
- list Work Queue;
- list Inbox;
- fetch Inbox item;
- mark read;
- mark unread.

No platform-specific duplicate DTO set.

No polished UI required in this slice.

"Needs You" home cards can consume Work Queue later without creating another truth model.

## Events / Spring Modulith

Projection listener consumes Approval events through accepted Spring Modulith durable publication semantics.

Required:
- redelivery is idempotent;
- failed projection is persisted;
- accepted resubmission/recovery succeeds;
- recovery does not duplicate Inbox rows or resolution effect.

Slice 05 does not publish push/email/SMS events.

It may publish internal semantic projection events only if a concrete consumer requires them; do not create event noise for symmetry.

## Activity / Audit separation

Inbox is not Activity.

Activity:
- historical object context.

Inbox:
- user attention/action projection.

Audit:
- security/business transition evidence.

Do not write Work Queue rows into WorkOrder Activity merely because they are visible to a user.

Do not treat read/unread as authoritative business audit.

## Offline

Final actionability is online-authoritative.

Client may cache safe Inbox/Work Queue summaries for navigation/reference.

A cached item must not allow an offline high-risk source action.

Approval decision behavior remains as contracted in Slice 04.

No new offline mutation queue is required except idempotent read/unread if the shared runtime already supports it safely; implementation may keep read state online-only for this foundation.

## Explicit non-goals

Do not implement:
- Notification abstraction;
- push;
- email;
- SMS;
- desktop notifications;
- notification preferences;
- quiet hours;
- digest engine;
- escalation timers;
- SLA engine;
- priority scoring;
- manually created tasks;
- comments;
- mentions;
- chat;
- snooze/dismiss;
- bulk actions;
- arbitrary reassignment;
- requester status fan-out without subject visibility adapter;
- company-wide activity feed;
- external client/supplier Inbox;
- Kafka/RabbitMQ.

## Required evidence for VERIFIED

1. Flyway migration creates Inbox-owned tables/constraints.
2. jOOQ generation/compile covers the new tables.
3. ApprovalRequested creates exactly one OPEN actionable item.
4. Approval event redelivery does not duplicate the logical item.
5. APPROVE resolves the item.
6. REJECT resolves the item.
7. REQUEST_CHANGE resolves the item.
8. SUPERSEDED resolves the item.
9. Work Queue shows the item to the current authorized Approval assignee.
10. unauthorized/cross-org actor cannot read the item.
11. current authority is re-evaluated; stale authority cannot see/action through queue projection.
12. TEAM-targeted item respects current team membership without duplicating source truth.
13. unsupported source type fails closed.
14. read/unread is per-user and idempotent.
15. read/unread does not change source/OPEN-RESOLVED state.
16. no generic complete/create/reassign HTTP endpoint exists.
17. Work Queue deterministic oldest-first cursor has no duplicate/skip.
18. Inbox deterministic newest-first cursor has no duplicate/skip.
19. cursors preserve full timestamp precision and reject tampering/wrong actor/filter/surface.
20. safe payload contains no sensitive subject body/tokens/storage/provider secrets.
21. shared Android/Windows client compiles/tests from one DTO set.
22. Spring Modulith failed projection publication can be recovered without duplicate effect.
23. Inbox/Work Queue remains separate from Activity/Audit business truth.
24. Slice 04 Approval regressions PASS.
25. Phase 1 OIDC regressions PASS.
26. Slice 01 Shared Runtime regressions PASS.
27. Slice 02 Evidence regressions PASS.
28. Slice 03 Activity/Modulith regressions PASS.
29. Bootstrap foundation/database/supply-chain/Terraform/local-platform/evidence-storage gates PASS.

## Closure rule

Do not mark VERIFIED from unit tests alone.

Exact implementation head must pass:
- real PostgreSQL;
- OpenFGA/current-source authorization;
- Spring Modulith durability/recovery;
- shared client;
- inherited regression workflows.

Only then:
- update canonical status;
- mark PR ready;
- merge exact tested closure head;
- run post-merge main Bootstrap.

## Verification closure — 2026-09-19

Canonical tested code head:

`fa8d0660f49c6de0dc4bc1285976f6a06a4610cb`

Exact-head verification:
- Bootstrap Phase 0 run `35467408783` — **PASS**.
- Phase 2 — Shared Command Runtime run `35467408781` — **PASS**.
- Phase 1 — Native OIDC Production Smoke run `35467408788` — **PASS**.

Verified evidence includes:
- Flyway V0013 Inbox/read-state persistence plus jOOQ generation/compile;
- ApprovalRequested -> exactly one OPEN ACTION_REQUIRED projection;
- APPROVE / REJECT / REQUEST_CHANGE / SUPERSEDED -> RESOLVED;
- exact event redelivery as a projection no-op and delayed requested-event replay unable to reopen terminal source truth;
- real PostgreSQL + OpenFGA current-source authorization, including cross-organization denial, current authority replacement and TEAM-membership loss hiding stale projected work;
- per-user idempotent read/unread that does not mutate Approval or Inbox OPEN/RESOLVED business truth;
- deterministic oldest-first Work Queue and newest-first Inbox cursor pagination without duplicate/skip;
- full `Instant` precision plus HMAC-protected, canonical URL-safe Base64 cursor decoding that rejects textual tampering, wrong actor/filter/surface and non-canonical encodings;
- one shared Android/Windows Inbox/Work Queue DTO/client contract;
- production `ApprovalInboxProjectionListener` failed-publication persistence and accepted Spring Modulith resubmission/recovery with no duplicate effect;
- Activity, Audit and Inbox remaining separate truth/presentation concerns;
- inherited Slice 04 Approval, Slice 03 Activity/Modulith, Slice 02 Evidence, Slice 01 runtime, database, supply-chain, Terraform, local-platform and OIDC regression gates all PASS.

No generic Task object, generic complete/create/reassign endpoint, notification delivery, push/email/SMS, quiet-hours policy, priority/SLA engine, comments/chat/mentions, Kafka/RabbitMQ, or named-person authority was added.

## Contract conclusion

**VERIFIED / READY TO MERGE.**

The slice creates a shared, source-linked attention/action projection without turning HILTECH into a generic task manager or notification spam system.

Next after merge verification: **Notification Abstraction reality/contract closure**.

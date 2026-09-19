# Phase 2 / Slice 05 — Inbox / Work Queue Reality Closure

Date: 2026-09-19  
Status: **MINIMAL FOUNDATION REALITY CLOSED / IMPLEMENTATION MAY BE CONTRACTED**

## Why this closure exists

Phase 2 requires an Inbox / Work Queue foundation after Approval.

The purpose of this reality gate is to prevent HILTECH from inventing a generic task-management product, notification spam system, or rigid bureaucracy that does not match the company.

This closure uses already-recorded HILTECH evidence and product research. No new company answer is required for the deliberately small first foundation.

## Canonical evidence

### RE-006 — owner coordination load

`docs/01-reality/REALITY_EVIDENCE_REGISTER.md`

Recorded operating meaning:
- substantial routine follow-up and cross-person/site coordination currently reaches the owner;
- HILTECH OS should reduce status chasing;
- exceptions and decisions that genuinely require authority should become visible;
- management should work by exception, not by exposing every operational event.

Product consequence:
- "Needs You" is a role/context-aware view of authoritative pending actions;
- it must not become a hard-coded Mohamed queue.

### Human map

`docs/02-people/HUMAN_MAP.md`

Recorded product intent:
- owner: receive decisions rather than hunt through the system;
- technician: open HILTECH and immediately know what to do today;
- warehouse: know what requires action;
- finance/admin, PM, engineering, HR, procurement and other roles each have different actionable contexts.

The common requirement is not one shared business workflow. It is one shared **attention/action surface** over many authoritative domains.

### Mobile / desktop surface maps

`docs/03-product/MOBILE_SURFACE_MAP.md`  
`docs/03-product/DESKTOP_SURFACE_MAP.md`

Already separated:
- Work / Action Queue;
- Inbox;
- Notifications.

Mobile Work / Action Queue is explicitly cross-domain and role-filtered.

Inbox is durable attention.

Notifications are an attention/delivery mechanism and are a separate later capability.

### Notification research

`docs/03-product/NOTIFICATION_MODEL.md`  
`docs/03-product/EVENT_NOTIFICATION_MATRIX.md`

Frozen useful principles for this slice:
- notifications are not system of record;
- business event does not automatically mean notification;
- owner receives exceptions, not raw operational stream;
- stale attention should resolve when the underlying action is completed elsewhere;
- deep links must return to the authoritative object/action;
- sensitive values should not be duplicated into attention payloads.

The exact push/email/SMS/quiet-hours/provider policy is explicitly **not frozen** and remains outside Slice 05.

### Existing object research

`docs/06-data/object-specs/APPROVAL_NOTIFICATION_SYNC_OBJECTS.md`

The previous research already proposed `InboxItem` as durable attention linked to a source object and stated:
- no sensitive business payload is required when the source object can be fetched securely;
- an Inbox item can resolve when the underlying action completes elsewhere.

That research is directional, not a frozen technical schema. Slice 05 may refine it where current verified foundations require stronger authorization/current-truth behavior.

## Reality conclusions

### 1. Do not create a generic Task business object

There is no HILTECH evidence that every action should be converted into a manually managed generic task.

A Work Queue item is a **projection/reference to authoritative work owned by another domain**.

Examples later may include:
- Approval decision;
- Work Order action;
- evidence review;
- warehouse return;
- invoice mismatch;
- support action.

The source domain remains authoritative.

### 2. Work Queue and Inbox are related but not identical

Minimal product meaning:

**Work Queue**
- "What can/should I act on now?"
- only currently actionable items;
- current authorization and current assignment/authority are re-evaluated;
- completing an item means executing the source-domain command, not clicking generic "done".

**Inbox**
- durable attention around source facts/actions;
- can track user read/unread presentation state;
- may contain actionable or status attention as later producers mature;
- read/unread never changes business truth.

For the minimal foundation, Work Queue can be implemented as the actionable subset/read model of the same durable Inbox projection rather than inventing a second business truth.

### 3. Notifications remain separate

Slice 05 does not send:
- push;
- email;
- SMS;
- desktop notification.

Notification abstraction is the next separate Phase 2 capability after Inbox / Work Queue.

### 4. Initial production producer is Approval

Approval is already authoritative and verified.

The first real producer is therefore:
- `ApprovalRequested` -> one actionable attention item for the assigned approval authority;
- `ApprovalApproved` / `ApprovalRejected` / `ApprovalChangeRequested` / `ApprovalSuperseded` -> resolve the actionable item.

This proves the shared foundation without inventing unfinished Payroll, Finance, Procurement, Warehouse or Work semantics.

### 5. Current truth beats projection snapshots

An Inbox/Work Queue row may store a principal/source snapshot for deterministic projection.

But read/action visibility must re-check current truth.

For Approval items:
- current assignment;
- current authority binding;
- organization membership;
- OpenFGA permission;
must still allow the actor.

A stale queue row must never authorize a source action.

### 6. No requester/status fan-out yet without visibility proof

Approval requester-status Inbox items are useful conceptually, but subject visibility differs by domain and later source adapters do not yet all exist.

Therefore the minimal slice does **not** create broad requester/status attention that could leak hidden subject context.

Unsupported producer/read contexts fail closed.

### 7. Read state is presentation state only

A user may mark an Inbox item read/unread.

That must not:
- approve;
- reject;
- complete;
- cancel;
- reassign;
- dismiss a mandatory action;
- alter the authoritative source.

### 8. No invented priority/SLAs

The product research mentions urgency, due dates and SLA.

HILTECH does not yet have one verified company-wide priority scoring or escalation cadence.

Therefore the foundation may carry producer-supplied nullable due/urgency metadata later, but Slice 05 must not invent:
- numeric priority score;
- owner escalation timer;
- arbitrary due date;
- automatic reassignment;
- notification cadence.

### 9. Flexible company reality is preserved

HILTECH historically operated flexibly and sometimes informally.

The shared foundation standardizes:
- durable ownership/reference;
- visibility;
- resolution;
- idempotency;
- read state;
- pagination;
- audit/recovery.

It does **not** force every human interaction through a rigid queue.

## Minimum implementation authorization

The following minimal foundation is sufficiently grounded to implement without further company questions:

- durable source-linked Inbox item projection;
- actionable Work Queue view over open items;
- per-user read/unread state;
- current authorization/actionability revalidation;
- Approval as the first producer;
- deterministic pagination;
- shared Android/Windows client;
- Spring Modulith durable projection/recovery;
- safe metadata only.

## Deferred reality

Not required to start this slice:
- manually created personal tasks;
- mentions/comments/chat;
- snooze/dismiss policy;
- priority scoring;
- SLA/escalation cadence;
- external client/supplier inbox policy;
- notification channels/preferences/quiet hours;
- bulk actions;
- cross-domain due-date semantics;
- dashboard visual design.

These must be contracted only when their real producer/domain or Notification slice reaches implementation.

## Reality conclusion

**MINIMAL FOUNDATION REALITY CLOSED.**

HILTECH needs one low-friction, role-aware answer to "what needs my attention/action now" without creating a second source of business truth.

The minimal foundation may proceed using verified Approval events as its first real producer.

# Phase 2 / Slice 06 — Notification Abstraction Reality Closure

Date: 2026-09-20
Status: **MINIMAL FOUNDATION REALITY CLOSED / IMPLEMENTATION MAY BE CONTRACTED**

## Why this closure exists

Phase 2 requires a Notification Abstraction after the verified Inbox / Work Queue foundation.

The purpose of this reality gate is to prevent HILTECH from turning every business event into a push, inventing provider/channel policy, leaking sensitive data on a lock screen, or creating a second source of business truth.

This closure uses already-recorded HILTECH evidence and product/architecture research. No new company answer is required for the deliberately small provider-neutral foundation.

## Canonical evidence

- `docs/03-product/NOTIFICATION_MODEL.md`
- `docs/03-product/EVENT_NOTIFICATION_MATRIX.md`
- `docs/05-workflows/AUTOMATION_MAP.md`
- `docs/05-workflows/AUTOMATION_REGISTRY.md`
- `docs/06-data/object-specs/APPROVAL_NOTIFICATION_SYNC_OBJECTS.md`
- `docs/11-architecture/MODULE_OWNERSHIP_MAP.md`
- `docs/11-architecture/MODULE_DEPENDENCY_GRAPH.md`
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
- `docs/13-delivery/phase2/05_INBOX_WORK_QUEUE_FOUNDATION_SLICE_2026-09-19.md`

## Existing HILTECH reality that matters

### Owner / management by exception

HILTECH OS should reduce routine chasing around the owner rather than forward every operational event to him.

Notification consequence:
- exceptions and genuinely time-sensitive decisions may interrupt;
- routine state changes should remain silent or appear in normal product surfaces;
- owner notification policy cannot be "notify owner about everything".

### Low-friction internal operation

HILTECH is flexible and field-heavy.

Notification consequence:
- a notification should return the user to the relevant authorized object/action;
- it must not create another workflow to maintain;
- acknowledgement of a notification is not a business transition.

### Arabic-first / privacy

Internal usage is Arabic-first and HILTECH contains employee, payroll, finance, client and evidence data.

Notification consequence:
- previews must be safe for lock-screen/external OS surfaces;
- sensitive subject values must not be copied into a delivery payload;
- full detail is fetched after authenticated deep link.

### No validated production notification provider

Current repository reality does not establish:
- FCM/APNs/another push provider as a frozen production choice;
- SMTP/email provider;
- SMS provider/Sender ID;
- desktop notification distribution policy;
- user device-token lifecycle;
- company quiet-hour policy;
- digest cadence;
- escalation timers;
- mandatory-vs-optional preference categories.

Do not invent any of these to make the foundation look complete.

## Reality conclusions

### 1. Notification is output, never business truth

A Notification is a delivery/attention artifact derived from an already-authoritative source event.

It cannot:
- approve/reject;
- complete work;
- alter assignment;
- mutate finance/payroll/warehouse/project truth;
- grant authorization;
- replace Inbox/Work Queue.

The source domain remains authoritative.

### 2. Business event does not imply notification

The policy layer must be able to decide:
- deliver;
- suppress;
- defer to a later domain policy.

Actor self-confirmation, routine progress and raw operational events should not automatically interrupt users.

### 3. Inbox and Notification stay separate

Inbox is durable attention/read state.

Notification is best-effort attention delivery.

Reading/dismissing an OS notification does not resolve the source action or Inbox item.

### 4. Initial real producer is verified Approval

Approval is the only required producer for the minimal Slice 06 foundation.

Initial semantic trigger:
- `ApprovalRequested` for a directly assigned **USER** approver.

Initial class:
- `ACTION_REQUIRED`.

Deep link:
- the authoritative Approval request/action.

Terminal Approval results do not require a new notification in this minimal slice.

### 5. TEAM fan-out is deliberately deferred

A TEAM-targeted Approval already appears through current-source-authorized Work Queue semantics.

Automatically pushing every current team member could create duplicate/spam behavior and no company-wide team fan-out policy is verified.

Therefore the first foundation does not expand TEAM assignment into user delivery recipients.

### 6. Provider-neutral channel abstraction only

The foundation may define stable channel types such as:
- PUSH;
- EMAIL;
- SMS;
- DESKTOP.

But no channel is considered production-enabled merely because the enum/port exists.

Real provider activation requires a later provider/operations decision.

### 7. Safe preview is frozen

External delivery surfaces receive only bounded safe metadata:
- semantic template/title code;
- generic safe preview where required;
- source/deep-link reference that reveals no restricted subject body;
- correlation/dedup identity.

No salary/payment/invoice values, bank/national-ID data, evidence/file URLs or storage keys, access tokens, provider secrets, raw filenames, or arbitrary user-entered subject text.

### 8. Current truth is revalidated before delivery

Before an actionable delivery attempt:
- source still exists and is actionable;
- current assignment/authority still matches;
- organization/user access still valid;
- current authorization remains allowed.

For the initial Approval producer, reuse verified Approval current-authority/OpenFGA checks.

If stale:
- suppress the delivery;
- do not reassign;
- do not mutate Approval.

### 9. Deduplication is mandatory

The same semantic trigger/redelivery must not create duplicate delivery intent.

A stable producer/dedup key is required.

Spring Modulith event redelivery must be safe.

### 10. Delivery attempts are append-only evidence, not success truth

When a provider adapter exists, record attempts/results independently.

Provider accepted is not the same as:
- delivered to device;
- read by person;
- acted on source.

Do not fake delivery/read receipts.

### 11. No generic Send Notification API

Business modules publish semantic facts/events.

They must not call a generic arbitrary-text send endpoint.

Notification policy owns whether/how a source event becomes delivery output.

### 12. Preferences / quiet hours / digest / escalation are deferred

The first foundation does not invent:
- user preference UI;
- opt-out categories;
- quiet hours;
- critical override policy;
- digest windows;
- retry cadence;
- SLA escalation;
- fallback channel sequencing.

### 13. No device-token/provider credential model yet

Without a selected production push provider, Slice 06 must not create fake FCM/APNs/token contracts.

Provider-specific device registration belongs to the adapter activation that actually needs it.

## Minimum implementation authorization

The following provider-neutral foundation is sufficiently grounded:

- dedicated Notification module boundary;
- durable Notification intent/policy-decision persistence;
- append-only delivery-attempt persistence;
- stable semantic producer/dedup key;
- ApprovalRequested(USER assignee) as first real producer;
- current-source/current-authority revalidation before dispatch;
- safe preview/template metadata only;
- provider-neutral delivery port;
- recording/test transport proving dispatch semantics without activating a production provider;
- Spring Modulith durable consumption/recovery;
- observability/correlation;
- no generic public send endpoint.

## Explicitly deferred

Not required to implement the minimal foundation:
- FCM or another push provider;
- email provider;
- SMS provider/Sender ID;
- Windows notification provider;
- device-token registration;
- user preferences;
- quiet hours;
- digest engine;
- escalation timers;
- TEAM fan-out;
- requester/result fan-out;
- external client/supplier notifications;
- notification center UI;
- analytics/marketing messaging.

## Reality conclusion

**MINIMAL FOUNDATION REALITY CLOSED.**

HILTECH needs a safe provider-neutral notification boundary now so later channels can be attached without allowing delivery concerns to leak into domain modules.

Implementation may proceed only against a Slice 06 contract that preserves the deferred boundaries above.

# Phase 2 / Slice 06 — Notification Abstraction Foundation

Date: 2026-09-20
Status: **IMPLEMENTATION AUTHORIZED / CONTRACT FROZEN FOR MINIMAL FOUNDATION**

## Reality basis

Canonical reality closure:

`docs/13-delivery/phase2/06_NOTIFICATION_ABSTRACTION_REALITY_CLOSURE_2026-09-20.md`

Frozen thesis:
- Notification is output, not business truth;
- business events do not automatically become notifications;
- Inbox and Notification stay separate;
- ApprovalRequested with a direct USER assignee is the first producer;
- TEAM fan-out is deferred;
- no production push/email/SMS/desktop provider is activated;
- preferences, quiet hours, digest and escalation are deferred;
- safe preview metadata only;
- current source/authority is revalidated before actionable dispatch.

## Product / module boundary

The `notifications` module owns:
- notification policy evaluation;
- durable notification intent;
- channel dispatch state;
- delivery attempts;
- provider-neutral delivery port.

It does not own:
- Approval state;
- Inbox/Work Queue state;
- Activity;
- Audit;
- employee/project/finance/warehouse state;
- provider credentials;
- device-token lifecycle in this slice.

Business event is input.
Notification delivery is output.

## Initial producer

Consume verified `ApprovalRequested`.

Eligible only when:
- Approval assignment principal type is `USER`;
- a concrete assigned user exists;
- source remains PENDING/current;
- current assignment/authority still matches;
- current organization membership and OpenFGA/actionability allow the user.

TEAM assignment:
- no external delivery intent in this slice;
- Work Queue remains the attention mechanism;
- no automatic fan-out.

## Notification intent persistence

Create `notification_intent`.

Minimum fields:
- id;
- organization_id;
- producer_key;
- source_event_id;
- source_type;
- source_id;
- source_version;
- recipient_user_identity_id;
- notification_class;
- template_code;
- safe_preview nullable;
- deep_link_type;
- deep_link_id;
- policy_state;
- suppression_reason nullable;
- created_at;
- updated_at;
- dispatched_at nullable;
- correlation_id nullable;
- version.

Initial class:
- `ACTION_REQUIRED`.

Initial policy states:
- `PENDING`;
- `SUPPRESSED`;
- `DISPATCHED`.

Required constraints:
- `producer_key` unique;
- source/version positive where applicable;
- safe preview bounded;
- SUPPRESSED requires suppression_reason;
- DISPATCHED requires dispatched_at;
- recipient belongs to same organization context at creation;
- no sensitive subject payload.

Approval producer key:

`approval:<approvalRequestId>:requested`

Slice 04 has one assignment per request in this minimal foundation, so request identity is sufficient for one semantic requested-notification intent. The key makes event redelivery idempotent.

## Delivery attempt persistence

Create `notification_delivery_attempt`.

Minimum:
- id;
- notification_intent_id;
- channel;
- provider_key;
- attempt_number;
- attempted_at;
- outcome;
- provider_message_id nullable;
- failure_code nullable;
- delivered_at nullable;
- correlation_id nullable.

Initial outcomes:
- `ACCEPTED`;
- `FAILED_RETRYABLE`;
- `FAILED_FINAL`.

Rules:
- append-only attempt history;
- unique notification/channel/provider/attempt number;
- provider ACCEPTED does not mean human/device delivery unless the provider explicitly proves it;
- no read/acted business state is inferred.

## Channel abstraction

Stable channel values may exist:
- `PUSH`;
- `EMAIL`;
- `SMS`;
- `DESKTOP`.

This does not enable those channels.

Define a provider-neutral port such as `NotificationDeliveryPort`.

Input:
- notification ID;
- channel;
- recipient delivery address/token reference supplied by a later adapter boundary;
- template code;
- safe preview;
- deep-link reference;
- idempotency/dedup key;
- correlation ID.

Output:
- provider key;
- accepted/failed outcome;
- provider message ID when available;
- safe failure code.

### Slice 06 production activation rule

No production provider binding is required or allowed to be invented.

Implementation must include a deterministic recording/test transport used by tests to prove:
- dispatch contract;
- idempotency;
- attempt recording;
- safe payload;
- failure handling.

Production runtime with no configured provider must not pretend a message was sent.

## Policy evaluation

Initial rule:

`ApprovalRequested + USER assignee -> ACTION_REQUIRED intent`

Everything else:
- unsupported event -> no notification intent;
- TEAM assignment -> no delivery intent;
- stale/non-actionable Approval at dispatch -> SUPPRESSED;
- unauthorized/cross-org -> fail closed.

Do not implement a generic rules DSL.

## Current-source revalidation

Immediately before dispatch, re-check Approval source through the verified Approval authority boundary:
- request still PENDING;
- assignment still ASSIGNED/current;
- actor/recipient still matches current assignment;
- current authority binding still matches;
- organization/team membership as applicable;
- OpenFGA `approval_request.can_decide`.

If any check fails:
- mark intent SUPPRESSED with a safe reason code;
- create no delivery attempt;
- never reassign Approval.

## Safe payload

Allowed:
- template code;
- generic safe preview;
- notification class;
- source/deep-link type and opaque ID;
- correlation/dedup metadata.

Forbidden:
- arbitrary subject JSON/body;
- salary/payment/invoice values;
- bank/national-ID data;
- evidence/file URLs or storage keys;
- access/refresh tokens;
- provider credentials;
- raw filenames;
- unrestricted user-authored comments.

Representative safe copy:

`Approval requires your attention.`

Full context is loaded only after authenticated navigation to the authoritative source.

## Events / durability

Consume `ApprovalRequested` through accepted Spring Modulith durable publication semantics.

Required:
- redelivery creates no duplicate intent;
- failed listener publication is recoverable;
- recovery does not duplicate intent;
- dispatch failure does not mutate Approval;
- Notification does not publish fake source-state events.

## API surface

No generic public create/send endpoint.

No user preference endpoint.

No device-token endpoint.

No notification read/acted endpoint.

No shared Android/Windows DTO/client is required for this minimal server-side abstraction because there is no provider/device registration or notification-center UI in scope.

Later provider/client activation adds those contracts explicitly.

## Audit / Activity / Inbox separation

Notification delivery attempt is operational delivery evidence.

It is not automatically:
- business Audit;
- object Activity;
- Inbox read state.

Material source actions remain audited/activity-projected by the source domain.

Do not spam Audit with transport retries.

## Observability

Record safe metrics/logs:
- intent created;
- intent suppressed by reason code;
- dispatch attempted;
- accepted;
- retryable failure;
- final failure;
- Modulith recovery.

Carry correlation/trace identifiers.

Never log sensitive payload/provider credentials.

## Offline

Notification delivery does not grant offline source authority.

A deep-linked client must use normal authenticated/current-source APIs.

Cached/OS-delivered notification content may be stale.

The source screen decides current truth.

## Explicit non-goals

Do not implement:
- FCM/APNs/provider selection;
- email SMTP/provider;
- SMS provider/Sender ID;
- Windows toast provider;
- device-token registration;
- notification preferences;
- quiet hours;
- digest;
- SLA/escalation timers;
- TEAM fan-out;
- external users;
- notification center UI;
- marketing/broadcast messages;
- arbitrary manual send;
- comments/chat/mentions;
- Kafka/RabbitMQ;
- generic automation DSL.

## Required evidence for VERIFIED

1. Flyway migration creates Notification-owned intent/attempt tables and constraints.
2. jOOQ generation/compile covers the new tables.
3. ApprovalRequested with USER assignee creates exactly one PENDING ACTION_REQUIRED intent.
4. exact event redelivery does not duplicate the intent.
5. TEAM-assigned Approval does not fan out deliveries.
6. unsupported event/producer does not create notification output.
7. cross-organization/unauthorized recipient cannot produce a dispatch.
8. current Approval authority/actionability is revalidated immediately before dispatch.
9. stale/reassigned/superseded/terminal Approval suppresses delivery and creates no attempt.
10. safe payload contains no sensitive subject body/amount/token/file/provider secret.
11. recording/test delivery port proves one dispatch contract without a production provider.
12. accepted test dispatch appends one delivery attempt and marks intent DISPATCHED.
13. failed dispatch appends an attempt without mutating Approval/Inbox.
14. attempt history is append-only and deterministic.
15. failed Spring Modulith projection can recover without duplicate intent/effect.
16. no generic create/send HTTP endpoint exists.
17. no preferences/quiet-hours/device-token/provider-specific API exists.
18. Inbox/Work Queue/Activity/Audit remain separate concerns.
19. Slice 05 Inbox/Work Queue regressions PASS.
20. Slice 04 Approval regressions PASS.
21. Slice 03 Activity/Modulith regressions PASS.
22. Slice 02 Evidence regressions PASS.
23. Slice 01 Shared Runtime regressions PASS.
24. Phase 1 OIDC regressions PASS.
25. Bootstrap foundation/database/local-platform/evidence-storage/Terraform/supply-chain gates PASS.

## Closure rule

Do not mark VERIFIED from unit tests alone.

Exact implementation head must pass:
- real PostgreSQL;
- current-source/OpenFGA authorization where dispatch eligibility is checked;
- Spring Modulith durability/recovery;
- migration/jOOQ compile;
- inherited regressions.

Only then:
- update canonical status;
- mark PR ready;
- merge the exact tested closure head;
- run post-merge main Bootstrap.

## Contract conclusion

**IMPLEMENTATION AUTHORIZED for the provider-neutral Notification Abstraction foundation only.**

The next action is production implementation against this contract without selecting or pretending to enable a real delivery provider.

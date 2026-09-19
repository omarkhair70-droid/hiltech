# Phase 2 / Slice 04 — Minimal Approval Engine Foundation

Date: 2026-09-19  
Status: **VERIFIED / READY TO MERGE**

## Reality basis

Canonical reality gate:
`docs/13-delivery/phase2/04_APPROVAL_ENGINE_AUTHORITY_REALITY_CLOSURE_2026-09-19.md`

Frozen minimal HILTECH operating model:
- routine Finance/Admin authority is low-friction and does not require Owner approval for every item;
- explicit unusual/non-routine cases may be escalated with a reason;
- Owner/final authority decides escalated exceptions;
- general numeric approval thresholds are `NONE` today;
- authority is configurable/relationship-driven and must survive personnel changes;
- Approval authority is not payment/bank execution truth.

## Product boundary

Approval is an authoritative decision system for explicit decisions that genuinely require another authority.

Approval is **not**:
- a wrapper around every routine action;
- an employee task list;
- a notification system;
- a generic workflow/BPM engine;
- a payment executor;
- a bank integration;
- a replacement for subject-domain state;
- a way to infer unusualness using AI or hidden heuristics.

Routine operations remain in their owner domain.

A subject module invokes Approval only when its frozen policy says approval is required or an authorized actor explicitly escalates an exception.

## Initial shared capability

The foundation must support two policy outcomes:

### NO_APPROVAL_REQUIRED

Used when the current actor already has the configured authority to complete the operation or the subject policy is routine.

No ApprovalRequest is created.

### EXPLICIT_EXCEPTION_APPROVAL

A subject module explicitly requests owner/final-authority review with:
- subject type/id/version;
- organization/context;
- requester;
- policy key/version;
- safe reason code/summary;
- correlation ID.

One initial SINGLE decision step is resolved through a configurable authority binding such as:
`OWNER_FINAL`.

The key is configuration data, not a hard-coded user.

## No named-person authority

Production code must contain no checks for Mohamed, Ahmed, Dr. Mohamed or any other named person.

Current people are seed/configuration reality only.

Authority assignment must be represented by data:
- organization/context;
- authority key;
- principal type USER or TEAM;
- principal ID;
- effective interval;
- active/version state.

The first required authority key is:
`OWNER_FINAL`

The schema may support future keys without activating them prematurely.

## OpenFGA boundary

Extend the authorization model with an Approval Request object boundary, not with named company roles.

Candidate semantics:

`approval_request`
- organization;
- requester;
- approver / approver team;
- explicit viewer if needed;
- `can_view`;
- `can_decide`.

The application must still re-check the **current authority binding** at decision time.

A stale OpenFGA approver tuple must not allow a decision after the authority binding changed.

## Persistence

Dedicated Approval-owned PostgreSQL tables:

### approval_authority_binding
Minimum:
- id;
- organization_id;
- authority_key;
- principal_type USER/TEAM;
- principal_id;
- effective_from;
- effective_to nullable;
- active;
- version;
- created_by;
- created_at.

Required:
- no overlapping active binding ambiguity for `OWNER_FINAL` within the same organization in the minimal slice;
- current effective binding can be resolved deterministically;
- changes are audited.

### approval_policy_version
Minimum:
- id;
- policy_key;
- version_number;
- active/effective interval;
- approval_required;
- step_mode;
- authority_key nullable;
- reason_required;
- schema_version.

Initial supported modes:
- NO_APPROVAL_REQUIRED;
- SINGLE authority step.

No scripts, arbitrary expressions, amount-threshold engine or AI policy selection in this slice.

### approval_request
Minimum:
- id;
- organization_id;
- subject_type;
- subject_id;
- subject_version;
- policy_key;
- policy_version;
- requester_user_id;
- state;
- reason_code;
- safe_reason_summary nullable;
- created_at;
- completed_at nullable;
- correlation_id nullable;
- version.

Initial states:
- PENDING;
- APPROVED;
- REJECTED;
- CHANGE_REQUESTED;
- SUPERSEDED;
- CANCELLED only if needed for internal consistency; no broad cancel UX required.

### approval_step
Minimum:
- id;
- approval_request_id;
- sequence;
- mode SINGLE;
- authority_key;
- state;
- required_count = 1.

### approval_assignment
Minimum:
- id;
- approval_step_id;
- principal type/id snapshot;
- state;
- assigned_at;
- acted_at nullable.

### approval_decision
Append-only:
- id;
- approval_request_id;
- approval_assignment_id;
- actor_user_id;
- decision APPROVE/REJECT/REQUEST_CHANGE;
- decided_at;
- comment/reason nullable according to policy;
- subject_version;
- operation_id/idempotency identity;
- correlation_id;
- authentication context reference where safe.

Unique/idempotency constraints must prevent duplicate decision effects.

## Subject boundary

Approval never reads arbitrary subject tables.

A subject module calls the Approval public application API with a typed `ApprovalSubjectRef` and exact version.

The Approval module persists the reference/version and safe approval metadata only.

The subject module remains responsible for:
- whether its current state can request approval;
- what its version means;
- applying the approved/rejected/change-requested result;
- superseding Approval when its material version changes.

The initial Phase 2 foundation may use test-only subject adapters for contract proof because Payroll/Finance/Procurement subject modules are not implemented yet.

Do **not** expose a generic public HTTP endpoint that lets arbitrary clients create approvals for arbitrary object IDs.

## Request lifecycle

### Routine
subject/action
→ policy evaluation returns NO_APPROVAL_REQUIRED
→ no request persisted
→ caller continues its own authoritative command.

### Explicit exception
subject/action
→ policy says/actor explicitly selects exception escalation
→ exact subject version captured
→ resolve current `OWNER_FINAL` binding
→ create PENDING request + SINGLE step + assignment
→ publish immutable approval-requested event
→ approver reads request
→ decision command checks:
  - authenticated actor;
  - current assignment;
  - current authority binding;
  - OpenFGA can_decide;
  - request still PENDING;
  - subject version still current through subject validation port;
  - idempotency/operation ID;
→ append ApprovalDecision
→ transition request
→ publish immutable result event
→ subject module consumes result through explicit boundary.

## Owner initiating own final decision

Do not manufacture self-approval.

If the actor already holds the current authority required by the subject policy, policy evaluation may return `NO_APPROVAL_REQUIRED` and the subject module can continue directly.

A request whose requester is also its assigned approver is not valid in the initial exception path.

## Version safety

Decision is for one exact subject version.

If the subject reports a newer material version:
- decision command fails closed;
- request transitions SUPERSEDED through controlled application logic where appropriate;
- old decision cannot apply to the new version.

## Current authority safety

At decision time, do not trust only the assignment snapshot.

Verify:
1. assignment identifies the actor;
2. current authority binding still resolves the actor/team for the required authority key;
3. OpenFGA can_decide is true.

If authority changed:
- decision is denied with stable error;
- stale tuple/assignment cannot authorize;
- reassignment/recovery is explicit, not silent.

## Decisions

Initial:
- APPROVE;
- REJECT;
- REQUEST_CHANGE.

Reason rules:
- REJECT requires reason;
- REQUEST_CHANGE requires reason;
- APPROVE comment optional unless future subject policy requires it.

No DELEGATE action in this initial slice.
No expiry automation.
No emergency mode.

## Authentication

Normal valid Phase 1 OIDC session is sufficient for the generic foundation.

The schema/command API must leave room for later subject policy to require fresh re-auth, but no fake payroll/payment re-auth policy is activated without reality.

## Audit / events / Activity

Every request creation and decision is audited with safe semantic context.

Publish immutable internal events through Spring Modulith:
- ApprovalRequested;
- ApprovalApproved;
- ApprovalRejected;
- ApprovalChangeRequested;
- ApprovalSuperseded.

Use durable publication/recovery semantics accepted in Slice 03.

Do not broaden the current WorkOrder Activity projection with fake generic approval rows.

Activity integration is a consuming-subject concern: a later real subject may project approval events into its authorized Activity context.

## Shared client

One common Android/Windows client contract for:
- fetch one authorized ApprovalRequest;
- list requests currently assigned to the authenticated actor using deterministic opaque pagination;
- decide APPROVE / REJECT / REQUEST_CHANGE with operation ID;
- receive stable errors for stale version, handled request, changed authority and denied visibility.

A polished Inbox/Needs You UI is out of scope.

## Visibility

Initial read rules:
- requester can view own request where subject visibility allows;
- current assigned approver can view;
- no organization-wide approval list by default;
- hidden subject/context must not leak through Approval metadata.

Because later subject visibility adapters do not yet all exist, unsupported subject types stay fail-closed.

Test-only subject adapters must not become production bypasses.

## Concurrency / idempotency

Required:
- one logical request per subject version + policy invocation identity;
- duplicate create command replays prior result;
- concurrent decisions yield one authoritative terminal/change-requested result;
- exact duplicate decision operation replays prior result;
- second different decision after terminal state fails deterministically;
- Modulith event redelivery produces no duplicate downstream effect.

## Explicit non-goals

Do not implement:
- full Payroll;
- supplier/payment/purchase approval policies;
- bank maker/checker;
- payment execution;
- amount bands;
- broad delegation;
- emergency approval;
- auto-expiry/escalation timers;
- Inbox / Work Queue;
- push/email/SMS/desktop notifications;
- comments/chat;
- arbitrary workflow scripting;
- BPMN engine;
- Kafka/RabbitMQ;
- named-person authority.

## Required evidence for VERIFIED

1. Flyway migration creates Approval-owned tables and constraints.
2. jOOQ generation/compile covers the new tables.
3. authority binding resolves current `OWNER_FINAL` deterministically.
4. no user/name is hard-coded in authority logic.
5. NO_APPROVAL_REQUIRED produces no ApprovalRequest.
6. explicit exception creates exactly one PENDING request for exact subject version.
7. duplicate create operation replays without duplicate request.
8. assigned current authority can read.
9. unauthorized/cross-org actor cannot read.
10. current authority is re-evaluated and stale assignment/tuple fails closed.
11. APPROVE succeeds once.
12. REJECT succeeds once and requires reason.
13. REQUEST_CHANGE succeeds once and requires reason.
14. concurrent conflicting decisions result in one authoritative outcome.
15. duplicate decision operation is idempotent.
16. changed subject version fails/supersedes; old approval cannot apply.
17. requester is not silently self-assigned in exception path.
18. request/decision Audit records are separate from Approval state.
19. Spring Modulith publication/recovery remains durable and deduplicated.
20. safe payload contains no sensitive subject body/tokens/storage/provider secrets.
21. shared Android/Windows client contracts compile/test from one DTO set.
22. deterministic opaque assigned-request pagination works without duplicate/skip.
23. Phase 1 OIDC regressions PASS.
24. Slice 01 shared runtime regressions PASS.
25. Slice 02 Evidence regressions PASS.
26. Slice 03 Activity/Modulith regressions PASS.
27. Bootstrap foundation/database/supply-chain/Terraform/local-platform/evidence-storage gates PASS.

## Closure rule

Do not mark VERIFIED from unit tests alone.

The exact implementation head must pass the real PostgreSQL/OpenFGA/Spring Modulith contracts and inherited regression workflows.

Only then:
- update canonical status docs;
- mark PR ready;
- merge exact tested head;
- run post-merge main Bootstrap.

## Verification closure — 2026-09-19

Canonical tested code head:
`5daaf57509f2f3a4013a16717531a77325c59d98`

Exact-head verification:
- Bootstrap Phase 0 run `35463223977` — **PASS**.
- Phase 2 — Shared Command Runtime run `35463223971` — **PASS**.
- Contract — OpenFGA First Slice run `35463223968` — **PASS**.
- Phase 1 — Native OIDC Production Smoke run `35463223979` — **PASS**.

Verified evidence includes the real PostgreSQL/OpenFGA/Spring Modulith local-platform contract, current-authority replacement defeating stale assignment/OpenFGA state, cross-organization read denial, successful APPROVE/REJECT/REQUEST_CHANGE paths, exact duplicate replay, true concurrent conflicting decisions with one authoritative result, exact subject-version supersession, full-precision/tamper-protected pagination, shared Android/Windows DTO/client tests, Audit separation, safe Approval persistence, and durable failed-publication resubmission without duplicate downstream effect.

No generic create-approval HTTP endpoint, Inbox, Notification system, payment execution, amount-band engine, Kafka/RabbitMQ, or named-person authority was added.

## Contract conclusion

**VERIFIED / READY TO MERGE.**

The slice is intentionally small and matches current HILTECH reality:
routine work stays routine; explicit exceptions receive an authoritative, version-safe decision without hard-coded people or invented bureaucracy.

Next after merge verification: **Inbox / Work Queue Foundation contract/reality closure**.

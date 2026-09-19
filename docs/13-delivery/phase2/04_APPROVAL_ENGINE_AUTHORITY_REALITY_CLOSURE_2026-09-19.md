# Phase 2 / Slice 04 — Approval Engine Authority Reality Closure

Date: 2026-09-19  
Status: **REALITY GATE OPEN / IMPLEMENTATION NOT AUTHORIZED**

## Purpose

Close the authority facts required to implement one shared HILTECH Approval Engine without inventing hierarchy, numeric thresholds, named-person rules or payment authority.

This is not a second architecture-discovery phase.

The structural Approval model already exists. The unresolved question is **who currently has authority for which decision, under what conditions, and what happens when that authority or the approved subject changes**.

Until those facts are validated, production Approval code is intentionally blocked.

## Why this is the next Phase 2 slice

Canonical Phase 2 order now stands at:
1. Shared HTTP / Command Runtime — VERIFIED / MERGED.
2. Evidence Metadata + Upload/Finalize — VERIFIED / MERGED.
3. Activity Events Foundation — VERIFIED / MERGED.
4. Approval Engine Foundation — **CURRENT, REALITY GATED**.
5. Inbox / Work Queue Foundation — later.
6. Notification abstraction — later.
7. shared read/query refinements — later.

Approval must become authoritative before Inbox or Notifications can safely treat approval work as actionable state.

## Canonical sources

Mandatory:
- `AGENTS.md`
- `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- `docs/13-delivery/IMPLEMENTATION_ORDER.md`
- `docs/05-workflows/APPROVAL_POLICY_MODEL.md`
- `docs/05-workflows/APPROVAL_SYSTEM.md`
- `docs/06-data/object-specs/APPROVAL_NOTIFICATION_SYNC_OBJECTS.md`
- `docs/07-security/PERMISSION_MAP.md`
- `docs/07-security/OBJECT_ACTION_PERMISSION_MATRIX.md`
- `docs/11-architecture/MODULE_OWNERSHIP_MAP.md`
- `docs/11-architecture/MODULE_DEPENDENCY_GRAPH.md`
- `docs/01-reality/REALITY_FACTS_REGISTER.md`
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
- `docs/01-reality/interview-packs/MOHAMED_OWNER_EXECUTIVE_SESSION.md`
- `docs/01-reality/interview-packs/AHMED_FINANCE_ADMIN_SESSION.md`
- `docs/02-people/HUMAN_MAP.md`
- `docs/03-product/role-experiences/OWNER_MOHAMED_EXPERIENCE.md`
- `docs/03-product/role-experiences/FINANCE_AHMED_EXPERIENCE.md`

## Already accepted structural model

The following are strong enough to carry into the reality gate and do not need to be rediscovered:

- one shared Approval capability serves multiple subject domains;
- subject modules remain authoritative for their own business objects;
- Approval stores a subject reference/version and policy context rather than copying subject internals;
- a decision applies to an exact subject version;
- a material subject change may supersede prior approval;
- policy may resolve SINGLE / ANY_OF / ALL_OF / QUORUM / SEQUENTIAL patterns;
- decisions are authoritative server-side actions;
- high-risk approval is online-authoritative;
- decision evidence is immutable/auditable;
- delegation, if supported, is explicit, scoped, time-bounded and cannot expand authority;
- escalation never means silent auto-approval;
- Activity may project meaningful approval facts later;
- Audit records security/compliance evidence separately;
- Inbox and Notifications are downstream consumers, not approval authority;
- no external event broker is required for the initial modular-monolith implementation.

## Current reality facts that are usable

Repository evidence supports these statements:

1. Mohamed is currently the owner and a central company decision-maker.
2. A primary product goal is to reduce routine chasing that depends on Mohamed personally while preserving decisions that genuinely belong to owner authority.
3. Ahmed is central to finance/admin preparation, payroll preparation, payment preparation/routing and related financial operations.
4. Product research expects Ahmed/preparer state to flow into owner-required decisions and the authoritative result to return to the originating workspace.
5. Payroll exact-version approval, high-value financial decisions, purchases/payments, advances/imprest, write-off/adjustment, project/commercial exceptions and staff exceptions are all candidate approval subjects.
6. Existing permission/object matrices intentionally mark many approval actions as POLICY rather than freezing named actors or thresholds.
7. Finance/payment execution authority is explicitly not assumed to be identical to approval authority.
8. Existing Reality documents explicitly say actual approval chains, thresholds, bank authority, delegation and several operational authorities still require validation.

These facts are enough to define the reality questions, but **not enough to generate executable approval routing**.

## Named people are evidence, not product authority

Current documents use Mohamed/Ahmed and other names because they describe today's observed/reported operation.

Production rules must not contain logic such as:
- `if user == Mohamed then approve`
- `Ahmed must always be step 1`
- a hard-coded user ID,
- a display role/title used as security authority.

Executable authority must resolve through:
- organization/relationship facts,
- typed policy configuration,
- subject context,
- explicit scoped delegation,
- OpenFGA/application authorization checks where appropriate.

If HILTECH personnel change, the policy should survive without code changes.

## Authority facts that must be frozen

### 1. Approval subject families

For each real decision family:
- what business object/version is approved?
- what action creates the request?
- is approval always required, conditionally required, or never required?
- what material edits invalidate/supersede it?

### 2. Requester / preparer / approver separation

For each family:
- who can prepare?
- who can request?
- who can approve?
- can requester and approver be the same person?
- can preparer approve their own prepared item?
- is a second-person rule used anywhere?

### 3. Routing and sequence

For each family:
- single approver?
- sequential chain?
- parallel approvals?
- any-one-of-many?
- all?
- quorum?
- does a technical review differ from financial/executive approval?

### 4. Threshold semantics

For every candidate amount/risk threshold:
- does a threshold actually exist today?
- what fact is compared: gross amount, net amount, margin, variance, write-off value, etc.?
- currency?
- inclusive/exclusive boundary?
- are there multiple bands?
- is the rule written, customary, or case-by-case?

If HILTECH uses **no numeric threshold**, record `NONE`; do not invent one.

### 5. Delegation / absence

Freeze:
- whether delegation exists today,
- who may create it,
- maximum scope,
- time bounds,
- whether high-risk actions disallow delegation,
- acting/backup behavior,
- what happens when an approver becomes unavailable mid-request.

### 6. Decision actions and consequences

For each family:
- APPROVE?
- REJECT?
- REQUEST_CHANGE?
- CANCEL by requester?
- EXPIRE?
- RETURN/REOPEN?
- whether comments/reasons are mandatory,
- exact state/result returned to the subject domain.

### 7. Emergency handling

Determine whether a real emergency path exists.

If yes:
- who can invoke it?
- what evidence/reason is mandatory?
- who can decide?
- what retrospective review is required?
- which actions can never use emergency handling?

No hidden emergency bypass.

### 8. Re-authentication

For each decision class:
- whether normal active session is enough,
- whether fresh re-auth is required,
- required maximum auth age,
- whether stronger auth is required for payment/payroll/high-risk cases.

### 9. Approval vs execution authority

Explicitly separate:
- approval to proceed,
- permission to execute a bank/payment action,
- payment provider/bank authoritative result,
- reconciliation authority.

An approved payment is not a successful payment.

### 10. Current-authority re-evaluation

At decision time:
- approver must still have current authority,
- expired/revoked delegation must fail closed,
- superseded subject version must fail,
- completed/reassigned request must not accept a stale decision.

## Minimum real scenario set

The gate should be proven with a compact set of real or directly validated HILTECH examples, not a theoretical catalog.

Target minimum:
1. one payroll approval example;
2. one supplier/payment or purchase approval example;
3. one employee advance / financial imprest / expense-type example;
4. one operational exception such as stock adjustment/write-off, project variation, or equivalent real exception.

For each scenario capture only the durable facts:
- subject,
- requester/preparer relationship,
- actual decision-maker relationship,
- routing order,
- amount/risk condition if any,
- allowed decisions,
- edit-after-request behavior,
- delegation/absence behavior,
- final execution/handoff boundary.

Do not commit raw payroll, bank or supplier-sensitive records.

## Minimum evidence collection

Preferred evidence:
- direct Mohamed owner/authority walk-through of recent real decisions;
- direct Ahmed finance/admin walk-through where financial workflow is involved;
- redacted/sanitized examples or a verbally confirmed exact flow;
- existing policy/document where one actually exists.

A short direct session is better than extrapolating from titles.

## Reality output format

Each validated decision family becomes a table entry containing:

| Field | Meaning |
|---|---|
| policy key | stable configurable key |
| subject type | exact subject family |
| trigger | typed action that requests approval |
| required? | ALWAYS / CONDITIONAL / NEVER |
| condition facts | allow-listed subject facts |
| requester rule | relationship/policy rule |
| step mode | SINGLE / ANY_OF / ALL_OF / QUORUM / SEQUENTIAL |
| approver rule | role/relationship/specific-user only if reality truly requires |
| self-approval | allowed/denied/conditional |
| decision actions | allow-list |
| reason required | per action |
| re-auth | requirement |
| delegation | allowed + scope |
| due/expiry | if real |
| emergency | if real |
| subject change | invalidation/supersede rule |
| result | authoritative outcome sent to subject module |
| execution boundary | what still requires separate authority |

## Freeze gate for implementation authorization

Slice 04 implementation becomes authorized only when all of the following are explicit:

1. at least the minimum real scenario set has been validated or deliberately marked not currently applicable;
2. real approver relationships are known without relying on display names;
3. threshold semantics are known or explicitly `NONE`;
4. self-approval rules are explicit;
5. delegation/absence behavior is explicit;
6. approve/reject/request-change/cancel/expiry semantics are explicit;
7. subject-version invalidation semantics are explicit;
8. high-risk re-auth requirements are explicit;
9. approval-vs-execution authority is explicit;
10. current-authority re-evaluation is required;
11. OpenFGA/application policy responsibility is clear;
12. sanitized fixtures can cover normal, stale, delegated/revoked and concurrent decisions.

Until then: **NO production Approval migration/API/policy evaluator.**

## Expected implementation slice after reality freeze

Once authorized, the production vertical should include:
- dedicated Approval module ownership;
- ApprovalRequest / ApprovalStep / ApprovalAssignment / ApprovalDecision persistence;
- policy-version binding;
- deterministic policy evaluation over allow-listed facts;
- subject-version binding and supersede behavior;
- current authority + delegation checks;
- decision idempotency/concurrency control;
- online/fresh-auth enforcement where required;
- append-only decision evidence;
- Audit records;
- Activity projection for material approval facts;
- thin shared Android/Windows client;
- real PostgreSQL + authorization + Modulith contract tests.

Rich Inbox, push/email/SMS, quiet hours and broad notification UX remain later slices.

## Explicit non-goals for the reality gate

Do not:
- invent approval thresholds;
- infer authority from job titles alone;
- hard-code Mohamed/Ahmed/Osama/any person;
- implement Inbox or Notifications;
- implement bank execution;
- implement Payroll/Finance business logic early;
- design a scripting language;
- introduce Kafka/RabbitMQ;
- build a generic BPM/workflow platform;
- reopen verified Phase 1 or Slices 01–03.

## Gate conclusion

**REALITY COLLECTION AUTHORIZED. PRODUCTION APPROVAL IMPLEMENTATION NOT YET AUTHORIZED.**

The next valid action is to collect/confirm the minimum authority facts above, promote the resulting policy table to an authority-frozen contract, then implement the smallest complete Approval vertical against that frozen reality.

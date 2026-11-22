# Phase 7 — Flexibility without invented workflows

Date: 2026-11-22
Source: owner clarification + PR #143 review comment #7073442697
Status: design constraints + first coded proof; NOT approval/purchasing-policy freeze

## Principle

**Five slices are engineering boundaries, not five mandatory operator pages.**
A routine, authorized stock request should require only the actual decisions
needed. Extra supplier quotations, comparison and escalation are activated by
documented sourcing/approval rules or explicit exceptions, never because the
implementation happened to have five milestones.

The system must be extensible **without** inventing one universal editable
workflow that could bypass identity, authority, stock facts or financial controls.
Configuration is for bounded, versioned policy choices. New semantics,
integrations, domain objects and security guarantees require code plus tests.

## Confirmed from current code

- Existing WorkOrder/Project is source truth for work demand, and
  ProjectAuthorizationPort.canRequestResources gates the initial request.
  That does **not** grant supplier selection, purchasing, PO issuance or payment.
- Canonical Warehouse stock balance + reservations remain authoritative; the
  preflight is advisory, timestamped, and cannot reserve or consume stock.
- Phase 2 ApprovalEngine uses policy/authority bindings, and its foundation
  forbids invented global thresholds and unnecessary routine approvals.
  It is **not yet wired to Phase 7**: there is no ready-to-run procurement
  approval/PO configuration in PR #143.
- V0039 records the first material/work-linked DRAFT immutably. V0040 adds
  append-only, idempotent owner-requester WITHDRAW transitions. The effective
  state is derived from event history; prior source/quantity snapshots never
  get overwritten. This is the *minimal concrete proof* of safe lifecycle change
  and does not pretend submission or full amendment already works.

## S01 source extensibility boundary

The existing POST is an intentionally restricted WORK_ORDER + STOCK_ITEM
adapter. This is NOT the target universal schema. The domain model already
declares a future SourceType/SourceRef and child RequirementLine shape in
docs/06-data/object-specs/PROCUREMENT_FINANCE_OBJECTS.md.

Before enabling other sources, implement a typed, authorize-and-snapshot
source resolver with separate bounded adapters:
- WORK_ORDER: current project/resource permission and authoritative work version;
- WAREHOUSE_REPLENISHMENT: current warehouse organization/source authority;
- ASSET_MAINTENANCE_OR_REPLACEMENT: current asset/incident/service authority;
- OFFICE_OR_FACILITY: approved internal request authority;
- SERVICE_OR_AD_HOC: explicitly owned and validated business source.

None of these other adapters or policies is silently enabled by this patch.
Each type must supply its current source version, organization, requester and
required spec; prevent cross-organization IDs and stale references. Don't
spread giant source-specific condition trees throughout supplier/PO modules.
When expanding V0039, migrate with FKs/typed constraints and durable evidence
rather than accepting arbitrary JSON references.

## Future lifecycle contract

Canonical proposed transition vocabulary, to validate against actual company
practice:
DRAFT -> SUBMITTED -> NEEDS_INFO / READY_FOR_SOURCING / REJECTED;
DRAFT -> WITHDRAWN; SUBMITTED -> WITHDRAWN (with policy if already actioned);
NEEDS_INFO -> revised SUBMITTED; Sourcing and PO are separate objects.

Only **DRAFT -> WITHDRAWN** is currently implemented. Amendments should create
a new full source/spec revision (not overwrite the immutable V0039 initial
quantity). Each transition should bind the prior version/state, requesting
actor, current authority, effective policy version, reason and correlated
evidence. Do not relax V0039 safeguards just to expose more states.

No configurable predicate/action engine or UI workflow builder until real
HILTECH decisions justify it; use the existing policy/version substrate.

## Changing routines without a deployment

Expected to be possible *after* validated policy binding is implemented:
- assign a purchasing role/team within current authority; change delegation;
- choose when human approval, two quotes, comparison or escalation applies;
- retain evidence of the policy revision used for the decision;
- add supplier records/preferences/terms in their owning domain, with
  appropriate commercial visibility.

Currently NOT delivered by #143 and **not** a reason to merge it:
- approved HILTECH procurement threshold and exception procedure;
- actual supplier sourcing policy, PO code/amount controls;
- general source adapters, multi-line requests and purchaser queue;
- complete Windows/Android client flow and on-device pilot.

## Tests / release gate

V0040 must pass Flyway 33->40, immutable snapshot/transition table
constraints and source-organization constraints. The service must test:
requester-only withdrawal, stale version denial, duplicate withdrawal
rejection, disabled flag, unchanged initial draft evidence, and permission
rechecks. Full source lifecycle and KMP clients remain S01 blockers.

No staging or production deployment or feature activation without proof
and authorized operating policy; keep PR #143 DRAFT.

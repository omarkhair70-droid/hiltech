# Mohamed — Owner / Control Brain

Status: `RESEARCHING`

## Product goal

HILTECH should reduce Mohamed’s operational load by bringing him only the information and decisions that truly require owner authority, risk judgment, financial approval, client escalation, or exception handling.

The product should not turn Mohamed into a dashboard operator.

## Core principle

> Management by exception.

## Questions to answer from reality

- What does Mohamed personally check every morning?
- What do employees repeatedly call/message him about?
- Which approvals genuinely need Mohamed?
- Which approvals only reach him because no policy/system exists?
- Which information does he repeatedly ask Ahmed, project managers, warehouse, or field teams to gather?
- What decisions can be delegated by threshold/rule?
- What does Mohamed need on desktop that he does not need on mobile?
- What does Mohamed need when outside the office?
- What information is too sensitive for anyone else?

## Candidate owner home

Not final UI.

Potential concepts:

### Company Pulse

- active projects,
- active sites today,
- project risks,
- important receivables/payables,
- critical operational issues,
- approvals waiting,
- payroll state,
- high-value asset/security exceptions,
- important client escalations,
- high-level staff/site activity.

### Needs You

A deliberately short queue of decisions such as:

- payroll ready for approval,
- high-value purchase approval,
- client variation/change approval,
- project-at-risk decision,
- payment exception,
- security/warehouse exception.

### Today at HILTECH

A summarized company timeline rather than raw event noise.

## Desktop experience hypothesis

Desktop may emphasize:

- dense company overview,
- project portfolio,
- finance summary,
- approvals,
- company activity,
- multi-pane drill-down,
- reports/trends,
- warehouse/security overview.

## Mobile experience hypothesis

Mobile may emphasize:

- needs-you queue,
- company pulse,
- approvals,
- project/site status,
- client escalations,
- authorized security/camera events,
- quick search,
- important activity summary.

The mobile product is not a remote desktop. It is another live HILTECH surface over the same state.

## Security/facility concept

Where supported by company hardware and policy, Mohamed may be allowed to enter an `Office / Security` context and see authorized:

- camera views,
- warehouse/office access events,
- recent incidents,
- door/security context.

This must be integrated safely rather than exposing raw NVR credentials or bypassing existing security controls.

## Automation opportunities to test

- auto-approve low-risk purchases below configured thresholds,
- escalate only when SLA/project risk crosses a threshold,
- summarize daily operations instead of forwarding every event,
- automatically derive project health from real operational signals,
- policy-route approvals to Ahmed/PM/owner based on amount/domain/risk,
- surface only payroll/payment exceptions requiring owner action.

## Anti-goals

- hundreds of notifications,
- raw field-event noise,
- requiring Mohamed to manually update project status,
- duplicating information already entered by teams,
- a dashboard full of decorative metrics with no action value.

## Completion requirement for Mohamed product surface

Before freeze, define:

- exact decisions requiring owner authority,
- exact visibility boundaries,
- mobile and desktop information hierarchy,
- notification policy,
- approval thresholds and delegation,
- company pulse calculations,
- search and drill-down behavior,
- security integration boundaries,
- audit requirements,
- failure/error states,
- handoff to Ahmed/PM/other roles.

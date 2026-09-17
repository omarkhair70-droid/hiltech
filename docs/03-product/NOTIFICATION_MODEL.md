# HILTECH Notification & Inbox Model

Status: RESEARCHING / PRODUCT MODEL v0.1

## Objective
Deliver the right information to the right person at the right urgency without turning HILTECH into notification spam.

Notifications are not the system of record.
The Inbox/Activity objects remain authoritative.

---

# 1. Communication Surfaces

## HILTECH Inbox
Durable work queue:
- approvals
- mentions
- requests
- assigned issues
- client actions
- system alerts
- important changes

## Push Notification
Attention mechanism for time-sensitive/relevant events.

## In-App Banner / Toast
Immediate feedback for current action/state.

## Email
Useful for external users, formal documents, summaries, and fallback.

## SMS
Reserved for explicit operational/business cases where justified.

## Desktop Notification
Optional for active desktop users.

---

# 2. Notification Classes

## ACTION_REQUIRED
User must do something.
Example: payroll approval.

## CRITICAL
Serious operational/security/service problem.
Example: major site outage / high-risk anomaly.

## TIME_SENSITIVE
Deadline or near-term change.
Example: site work tomorrow changed.

## STATUS
Meaningful update.
Example: client milestone accepted.

## INFORMATIONAL
Useful but non-urgent.

## DIGEST
Aggregated summary.

---

# 3. Audience Principle

Notify by relevance, not hierarchy.

Example:
Technician uploads photo:
- technician: immediate local confirmation, maybe no push
- supervisor: only if review required
- PM: reflected in project state, no push unless milestone/blocker
- Mohamed: no notification

---

# 4. Owner / Mohamed

Default:
- critical company exceptions
- approvals requiring owner authority
- major project/client escalation
- payroll ready if owner approval required
- high-value payment approval
- critical security/warehouse issue
- optional daily/weekly digest

Avoid:
- routine task completion
- every purchase
- every employee check-in
- every inventory movement

---

# 5. Finance / Ahmed

- payroll readiness/exceptions
- payment approvals/results
- invoice mismatch
- overdue receivable/payable according to role
- expense/advance queue
- procurement item ready for payment
- failed employee transfer

---

# 6. PM

- work blocked
- missing resource
- site issue
- schedule risk
- client approval/rejection
- material delay
- critical evidence/rework
- change/variation
- assigned project financial milestone where allowed

---

# 7. Field Staff

- today's/tomorrow's work
- assignment change
- drawing/instruction changed
- job cancelled
- supervisor feedback/rework
- asset return due
- expense/leave result
- payslip available
- critical site/access instruction

---

# 8. Warehouse

- reservation due
- incoming delivery
- overdue return
- calibration/maintenance due
- low stock
- approved adjustment
- discrepancy requiring action

---

# 9. Procurement

- new requirement
- quote due/received
- approval result
- supplier late delivery
- receiving discrepancy
- invoice mismatch
- replenishment request

---

# 10. HR

- new hire
- missing onboarding requirement
- expiring document/certification
- leave queue
- offboarding started
- asset/access not cleared

---

# 11. Client

- approval needed
- milestone completed
- document/handover ready
- support ticket status
- SLA-critical update
- maintenance visit
- invoice/payment notice where role allows

Do not expose internal operational noise.

---

# 12. Supplier / Subcontractor

Supplier:
- RFQ
- PO
- amendment
- delivery reminder
- rejection/return
- invoice status

Subcontractor:
- work assigned
- site/schedule change
- evidence/rework request
- acceptance
- commercial milestone where allowed

---

# 13. Aggregation Rules

Potential examples:
- multiple project task completions -> one milestone/progress summary
- repeated same alert -> update existing incident notification
- low-priority items -> digest
- multiple approvals -> approval queue count plus prioritized cards

---

# 14. Quiet / Escalation Rules

Per user/role:
- quiet hours for non-critical push
- critical override only where policy allows
- escalation based on unresolved action/SLA
- fallback channel for external user if app inactive

Exact rules require company validation.

---

# 15. Deep Linking

Every notification should open directly to the relevant authorized object/action.

Never drop user at generic Home if context is known.

---

# 16. Security / Privacy

Push lock-screen content should avoid sensitive values by default.

Examples:
Bad:
"Ahmed salary is 5,000 EGP."

Better:
"Payroll review requires your attention."

Critical/sensitive actions may require re-authentication after deep link.

---

# 17. Delivery State

Track:
- created
- queued
- sent
- delivered where provider supports
- read
- acted
- failed
- suppressed/aggregated

This supports debugging and avoids duplicate spam.

---

# 18. User Preferences

Users may control optional informational channels.

Users cannot disable legally/operationally mandatory alerts if policy requires them, but the system should still minimize noise.

---

# 19. Notification Event != Business Event

A business event may cause zero, one, or many notifications depending on policy.

Example:
asset.returned is a fact.
It may create no push at all.

## Completion gate
Requires:
- persona-by-persona event-to-notification matrix,
- urgency policy,
- quiet-hour rules,
- channel strategy,
- sensitive-content rules,
- provider/infrastructure decision,
- retry/deduplication design,
- delivery observability,
- UX for Inbox and preferences.

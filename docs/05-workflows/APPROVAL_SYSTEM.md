# Shared System — Approval Engine

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Provide one consistent approval model for HILTECH rather than implementing separate ad-hoc approval logic inside payroll, procurement, project changes, finance, HR, warehouse adjustments, and other domains.

## Core principle
Approvals are policy-driven and context-aware.

Do not hard-code:
"If amount > X then Mohamed"

Instead define policies that can change without rewriting domain logic.

---

# 1. Approval Request

Created by a domain action that requires authority.

Examples:
- purchase request
- payroll run
- project variation
- stock adjustment
- expense
- overtime
- payment
- quote
- asset write-off

Request contains:
- subject type/id
- requester
- context/project/org
- reason
- value/risk attributes
- policy key
- required evidence
- current state

---

# 2. Policy Evaluation

Policy may consider:
- role
- amount
- project/client
- department
- margin/variance
- urgency
- exception flags
- employee level
- security risk
- supplier type
- whether prior approvals exist

Output:
- no approval required
- single approver
- sequential approvers
- parallel approvers
- quorum/one-of-many
- escalation rule

Exact policy language/engine is not frozen.

---

# 3. Approver Experience

Approver should see:
- what is being approved
- why it exists
- relevant context
- financial/operational impact
- attachments/evidence
- who requested it
- prior approvals/comments
- what happens if approved/rejected

Actions:
APPROVE / REJECT / REQUEST_CHANGE / DELEGATE where policy allows.

---

# 4. States
DRAFT
PENDING
PARTIALLY_APPROVED
APPROVED
REJECTED
CHANGE_REQUESTED
CANCELLED
EXPIRED
SUPERSEDED

Exact state model requires validation.

---

# 5. Audit

Every decision records:
- approver identity
- timestamp
- action
- comment/reason where required
- device/session context where policy requires
- subject version/hash where critical

A later edit to the underlying subject may invalidate or supersede approval.

---

# 6. Version Safety

Critical rule:
An approval is for a specific version of the thing being approved.

Example:
Quote v3 approved.
Quote edited to v4.
Approval of v3 must not silently apply to v4.

Applies especially to:
- quote
- PO
- payroll run
- payment batch
- variation
- stock write-off

---

# 7. Delegation / Absence

Potential support:
- temporary delegation
- acting manager
- leave coverage

Delegation must be:
- explicit
- time bounded
- permission constrained
- audited

Not frozen until company reality is known.

---

# 8. Escalation

Optional policy:
If pending beyond SLA:
- remind approver
- notify backup
- escalate
- expire/re-evaluate

Avoid notification spam.

---

# 9. Cross-device

Same request can be reviewed on desktop or mobile.

Mobile:
fast context + approve/reject.

Desktop:
deeper documents/comparison/details.

Decision state is shared instantly.

---

# 10. Offline

Final high-risk approval is online-authoritative.

A user may read cached context offline, but approval execution should normally require authoritative server confirmation.

---

# Events
- approval.requested
- approval.assigned
- approval.approved
- approval.rejected
- approval.change_requested
- approval.delegated
- approval.expired
- approval.superseded
- approval.completed

---

# Major Objects
- Approval Request
- Approval Policy
- Approval Step
- Approver Assignment
- Approval Decision
- Delegation
- Escalation

---

# Completion gate
Requires:
- real approval examples from HILTECH,
- thresholds/policies,
- role/permission model,
- versioning semantics,
- mobile/desktop UX,
- notification strategy,
- technical workflow architecture,
- tests for edits-after-approval, concurrent decisions, expiry, and delegation.

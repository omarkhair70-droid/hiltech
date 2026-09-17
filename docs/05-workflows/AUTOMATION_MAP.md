# HILTECH Automation Map

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Identify work HILTECH should remove from people through deterministic rules, orchestration, and later AI assistance.

Automation must reduce repetitive work without hiding accountability or making unauthorized decisions.

---

# 1. Automation Classes

## A. Deterministic
Rule is explicit and predictable.
Example:
If asset calibration expired -> remove from normal availability.

## B. Workflow Orchestration
Coordinate multiple steps/people over time.
Example:
New hire -> documents -> account -> permissions -> equipment -> first project.

## C. Derived State
Compute status from existing truth.
Example:
Work Order readiness = blocked because material reservation missing.

## D. Reminder / Escalation
Time-based.
Example:
Approval pending beyond SLA -> remind/escalate according to policy.

## E. Recommendation
System suggests, human decides.
Example:
Suggest replenishment based on reservations and stock.

## F. AI Assistance
Summarize/search/draft/explain using authorized company data.
AI must not silently execute critical actions.

---

# 2. Executive Automation

Goal: management by exception.

Examples:
- summarize company-critical exceptions
- surface only approvals requiring Mohamed
- highlight projects at risk
- identify overdue high-value receivables
- notify about critical warehouse/security anomaly
- summarize daily company activity

Do NOT:
- ask Mohamed to approve routine items when policy allows delegation
- send every operational event

---

# 3. Project Automation

Potential:
- compute project progress from accepted work
- derive milestone readiness
- flag overdue dependencies
- detect resource conflict
- generate resource requirement from planned work
- update client-visible progress only after allowed review
- create handover checklist from project configuration
- generate handover package index from structured evidence
- warranty start trigger after acceptance

---

# 4. Field Automation

Potential:
- download tomorrow's jobs when connected
- prefetch drawings/evidence requirements
- queue evidence for sync
- remind technician of required missing evidence before completion
- block final completion if mandatory evidence absent
- auto-associate scan with active work/site where unambiguous, otherwise ask
- notify supervisor only for exceptions/blockers

---

# 5. Warehouse Automation

Potential:
- low-stock detection
- calibration/maintenance due
- overdue asset return
- reservation conflict
- automatically create replenishment suggestion
- mark unavailable assets blocked by condition/calibration
- correlate access event with unusual inventory activity for review
- prepare tomorrow's issue list from project reservations

Never:
- auto-accuse person of theft/loss
- auto-write-off inventory
- auto-charge employee without policy/human review

---

# 6. Procurement Automation

Potential:
- create purchase requirement from approved shortage
- detect existing available stock before sourcing
- remind on quote validity expiry
- detect delivery lateness
- three-way match PO / receipt / invoice
- route mismatches for review
- build supplier history metrics

Never:
- auto-select supplier solely from opaque AI score
- issue high-value PO without configured approval

---

# 7. HR / Employee Automation

Potential:
- role-aware onboarding checklist
- automatic default permissions
- certification expiry reminders
- access removal triggered by offboarding state
- assigned-asset return checklist
- manager/team change propagation
- leave impact on planning
- payroll eligibility snapshot

Critical:
Access revocation should be deterministic and fast.

---

# 8. Payroll / Finance Automation

Potential:
- collect approved inputs
- generate payroll draft
- identify exceptions
- compare month-over-month variance
- prepare payment batch
- reconcile known payment results
- publish payslip after successful/authorized payroll state

Never:
- infer missing salary
- silently correct failed payments
- mark payment successful without authoritative result
- auto-execute bank payment without explicit authorized path

---

# 9. Client Automation

Potential:
- publish approved milestone updates
- remind client of pending approval
- notify SLA status
- convert delivered project into maintenance opportunity
- suggest recurring preventive visit
- create new-project template from previous site where appropriate

---

# 10. Notification Suppression / Aggregation

Automation must also remove noise.

Examples:
- 30 technician events -> one project progress summary for owner
- multiple low-severity alerts -> digest
- suppress duplicate incident notifications
- do not notify actor about their own action unless confirmation is useful

---

# 11. AI Layer — Later

Candidate capabilities:
- "What needs me today?"
- "Why is Project X delayed?"
- "Show tools not returned this week."
- "Summarize client Y history."
- "Draft handover summary."
- semantic search over authorized documents
- explain payroll exception to finance user

AI must:
- respect same permissions as user
- cite underlying company objects/evidence
- distinguish fact vs suggestion
- never bypass approval
- never create hidden state changes

---

# 12. Automation Registry

Every automation should eventually define:
- ID
- trigger
- conditions
- input objects
- action
- side effects
- authority
- rollback/recovery
- notification
- audit event
- observability
- test cases
- kill switch/feature flag where high risk

## Completion gate
No automation is COMPLETE until deterministic behavior, failure handling, permission interaction, auditability, and tests are defined.

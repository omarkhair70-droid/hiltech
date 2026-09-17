# Master Workflow — Client Support & Service Lifecycle

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Keep the client relationship alive after/during project delivery through structured support, issue handling, SLA/service context, communication, resolution, and history.

This workflow connects:
Clients, Projects, Sites, Assets, Maintenance, Field Work, Engineering, Finance, Notifications, Documents.

---

# 1. Support Entry Points

Possible:
- client app
- internal employee on behalf of client
- phone/email converted into HILTECH ticket
- monitoring/automation generated issue
- warranty defect
- maintenance visit finding

Every support issue must have:
- client org
- requester
- project/site/asset context if known
- category
- severity
- description/evidence
- service/warranty context
- created timestamp

---

# 2. Ticket Classification

Dimensions:
- service request
- incident
- defect
- warranty
- maintenance
- access/request
- question
- change request
- billing/commercial query

Severity:
- critical
- high
- normal
- low
or final validated scale.

Do not use severity as substitute for priority/contract SLA.

---

# 3. Contract / Coverage Check

System determines:
- under warranty?
- under maintenance contract?
- managed service?
- out-of-contract?
- billable request?

Result may affect:
- SLA
- approval
- chargeability
- assignment
- client expectations

---

# 4. Triage

Assign:
- responsible team
- owner
- required skill
- due/SLA
- escalation path

State candidate:
OPEN -> TRIAGED -> ASSIGNED -> IN_PROGRESS -> WAITING_CLIENT / WAITING_VENDOR / WAITING_PART -> RESOLVED -> CLOSED

Can REOPEN.

---

# 5. Field / Engineering Execution

Ticket may create:
- remote diagnostic task
- field visit
- engineering review
- warehouse reservation
- procurement requirement
- subcontractor work

Support ticket remains parent context.

---

# 6. Client Communication

Client sees:
- status
- next action
- assigned HILTECH contact where appropriate
- appointment
- required client input
- resolution summary
- SLA/service state

Internal notes remain internal.

---

# 7. SLA

If service contract defines SLA:
track:
- response target
- acknowledgement
- assignment
- resolution target
- paused/waiting conditions where contract allows
- breach risk
- breach

Do not invent SLA without contract.

---

# 8. Resolution

Resolution captures:
- what was wrong
- what was done
- affected site/asset
- parts/materials used
- evidence/test
- root cause where known
- client-visible summary
- follow-up

Resolved != Closed if client confirmation/policy requires closure.

---

# 9. Reopen

Client/internal user may reopen based on policy if issue persists.

Preserve previous resolution attempt.

---

# 10. Commercial Impact

Out-of-contract or change-type work may create:
- quote
- variation
- billable work order
- invoice eligibility

Support should not silently create charges.

---

# 11. Asset / Site History

Resolved ticket becomes part of:
- asset history
- site history
- client relationship history
- maintenance insights

Repeated incident can later trigger recommendation/upgrade opportunity.

---

# 12. Automation

Potential:
- auto-triage suggestion
- SLA timers
- escalation
- duplicate incident grouping
- maintenance/warranty coverage lookup
- recurring issue detection
- client update templates

Critical decisions remain human/policy controlled.

---

# 13. Notifications

Client:
- ticket received
- appointment
- action required
- major status change
- resolved/closed

Internal:
- assigned
- SLA at risk
- waiting external too long
- client escalation
- re-opened

---

# Major Objects

- Support Ticket
- Incident
- Service Request
- SLA Instance
- Assignment
- Appointment
- Resolution
- Client Communication
- Internal Note
- Coverage Decision
- Escalation
- Reopen Record

---

# Completion Gate

Requires:
- current HILTECH support reality,
- warranty/maintenance contracts,
- actual client channels,
- SLA rules,
- escalation authority,
- billable support process,
- UI/client/internal surfaces,
- tests for reopen, SLA pause, out-of-contract, duplicate incidents.

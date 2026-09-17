# HILTECH Event → Notification Matrix

Status: PRODUCT POLICY v0.1 / NOT FROZEN

## Principle
Business event does not automatically mean push notification.

This matrix defines first-pass attention policy.

Legend:
- PUSH = likely push
- INBOX = durable action/status
- DIGEST = aggregated
- SILENT = state updates without interruption
- EMAIL = external/formal candidate
- CRITICAL = may override normal suppression
- POLICY = configurable

---

# Project / Work

| Event | Technician | Supervisor | PM | Owner | Client |
|---|---|---|---|---|---|
| work.assigned | PUSH+INBOX | SILENT | SILENT | SILENT | SILENT |
| work.started | SILENT | SILENT | SILENT | SILENT | SILENT |
| work.blocked | INBOX | PUSH | PUSH if material | DIGEST unless critical | client only if action needed |
| work.submitted_for_review | SILENT | INBOX | INBOX if PM reviewer | SILENT | SILENT |
| rework.requested | PUSH+INBOX | INBOX | INBOX | SILENT | SILENT |
| work.accepted | INBOX optional | SILENT | SILENT | DIGEST | milestone-derived only |
| milestone.progressed | SILENT | SILENT | INBOX | DIGEST | INBOX/PUSH if meaningful |
| project risk critical | SILENT | INBOX | PUSH | PUSH/CRITICAL | POLICY |
| project closed | INBOX | INBOX | INBOX | DIGEST | PUSH/EMAIL |

---

# Approvals

| Event | Requester | Approver | Owner |
|---|---|---|---|
| approval.requested | INBOX status | PUSH+INBOX | only if owner is approver |
| approval.approved | PUSH/INBOX | SILENT | DIGEST unless relevant |
| approval.rejected | PUSH+INBOX | SILENT | POLICY |
| approval.change_requested | PUSH+INBOX | SILENT | POLICY |
| approval.superseded | INBOX | PUSH if action open | if owner affected |
| approval.expiring/escalated | INBOX | PUSH | PUSH only if escalation reaches owner |

---

# Warehouse / Assets

| Event | Custodian | Warehouse | PM/Supervisor | Owner |
|---|---|---|---|---|
| asset.checked_out | INBOX/confirmation | SILENT | SILENT | SILENT |
| asset.return_due | PUSH | INBOX | POLICY | SILENT |
| asset.overdue | PUSH | PUSH/INBOX | INBOX | DIGEST/high-value critical only |
| asset.damage_reported | confirmation | PUSH | INBOX | high-value/critical only |
| asset.missing_reported | PUSH/INBOX | PUSH/CRITICAL | PUSH | CRITICAL if policy |
| calibration.due | assigned user if relevant | PUSH/INBOX | INBOX if blocks work | DIGEST |
| stock.low | SILENT | PUSH/INBOX | INBOX if project impact | DIGEST |

---

# Procurement

| Event | Requester/PM | Procurement | Finance | Owner |
|---|---|---|---|---|
| purchase.requirement_created | confirmation | INBOX | SILENT | SILENT |
| purchase.approval_requested | status | INBOX if approver | INBOX if approver | PUSH only if owner approver |
| po.issued | INBOX | confirmation | CONTEXT | SILENT |
| supplier late delivery | PUSH if project impact | PUSH | SILENT | DIGEST/critical only |
| receiving discrepancy | INBOX | PUSH | INBOX if invoice impact | SILENT |
| invoice mismatch | SILENT | INBOX | PUSH/INBOX | SILENT |

---

# Payroll / Finance

| Event | Employee | Finance | Owner |
|---|---|---|---|
| payroll draft ready | SILENT | PUSH/INBOX | SILENT |
| payroll approval requested | SILENT | status | PUSH+INBOX if approver |
| payroll approved | SILENT | PUSH/INBOX | confirmation |
| payroll employee paid | payslip/notification | SILENT | DIGEST |
| payroll payment failed | generic status only if appropriate | PUSH/CRITICAL | PUSH if owner action required |
| payment approval requested | SILENT | INBOX | PUSH if owner approver |
| payment unknown | SILENT | CRITICAL | CRITICAL if material |
| client payment received | SILENT | INBOX | DIGEST/high-value optional |
| receivable overdue | SILENT | PUSH/INBOX | DIGEST/critical |

Sensitive values are redacted from lock-screen notification by default.

---

# HR / People

| Event | Employee | Manager | HR | Owner |
|---|---|---|---|---|
| employee.invited | PUSH/EMAIL | SILENT | status | SILENT |
| onboarding missing item | PUSH | SILENT | INBOX | SILENT |
| leave requested | confirmation | PUSH/INBOX | POLICY | SILENT |
| leave approved/rejected | PUSH | SILENT | status | SILENT |
| certification expiring | PUSH | INBOX if scheduling impact | PUSH/INBOX | SILENT |
| offboarding started | relevant actions | INBOX | PUSH/INBOX | POLICY |
| asset return required | PUSH | INBOX | INBOX | SILENT |

---

# Support / Client

| Event | Client | Support/PM | Owner |
|---|---|---|---|
| ticket created | PUSH/EMAIL confirmation | PUSH/INBOX | SILENT |
| ticket assigned | status optional | PUSH to assignee | SILENT |
| waiting client | PUSH+INBOX | status | SILENT |
| SLA at risk | POLICY | PUSH/CRITICAL | critical only |
| ticket resolved | PUSH/EMAIL | INBOX | DIGEST |
| ticket reopened | confirmation | PUSH | escalated only |
| client approval requested | PUSH+INBOX | status | owner only if relevant |

---

# Security

| Event | Security Role | Owner | Normal Employee |
|---|---|---|---|
| camera unavailable | INBOX | DIGEST if critical | NO |
| access denied anomaly | PUSH/INBOX | POLICY | own event if useful |
| forced door / critical security | CRITICAL | CRITICAL | NO |
| warehouse security anomaly | CRITICAL | CRITICAL/POLICY | NO |

---

# Suppression Rules

1. Actor does not need push for every action they just completed.
2. If underlying action is already completed on another device, stale notification resolves.
3. Same incident updates existing notification/inbox item where possible.
4. Low-value repeated events aggregate.
5. Owner receives exceptions, not raw operational stream.
6. Client receives approved external truth only.

---

# Freeze Gate

Need:
- exact event registry normalization,
- user preference categories,
- quiet hours,
- escalation cadences,
- provider capabilities,
- lock-screen privacy rules,
- notification delivery tests.

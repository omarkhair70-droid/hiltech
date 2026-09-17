# HILTECH Automation Registry

Status: FIRST-PASS REGISTRY / NOT FROZEN

Each automation will eventually have:
ID / Trigger / Conditions / Inputs / Action / Authority / Side Effects / Audit / Notification / Failure / Kill Switch.

---

# AUTO-RULE-001 — Work Readiness

Trigger:
work requirement / reservation / assignment / document changes.

Action:
derive readiness:
READY or blocked dimensions.

Authority:
system-derived.

Never:
auto-start work.

---

# AUTO-RULE-002 — Missing Evidence Gate

Trigger:
SubmitWorkCompletion.

Condition:
mandatory evidence absent.

Action:
reject completion command with structured missing requirements.

Authority:
deterministic domain rule.

---

# AUTO-RULE-003 — Project Progress Derivation

Trigger:
work accepted/reopened/rework.

Action:
recalculate milestone/project progress based on configured progress model.

Never:
arbitrarily infer percentage without defined weighting/rules.

---

# AUTO-RULE-004 — Low Stock

Trigger:
stock movement/reservation.

Condition:
available below threshold.

Action:
create replenishment suggestion/action item.

Never:
issue PO automatically unless later explicit policy.

---

# AUTO-RULE-005 — Calibration Due

Trigger:
time/date / calibration record.

Action:
mark due; block normal reservation if calibration required.

Notification:
warehouse/asset responsible.

---

# AUTO-RULE-006 — Asset Return Reminder

Trigger:
expected return approaching/overdue.

Action:
notify custodian/warehouse according policy.

Escalate only when overdue threshold reached.

---

# AUTO-RULE-007 — Onboarding Orchestration

Trigger:
hire approved / employee invited.

Action:
generate role-specific requirements:
documents, policy, training, account, access, assets.

Completion derived from satisfied mandatory steps.

---

# AUTO-RULE-008 — Offboarding Orchestration

Trigger:
offboarding started.

Action:
create clearance actions:
project, asset, access, finance, final payroll, HR.

Critical:
access revocation can be high-priority deterministic action.

---

# AUTO-RULE-009 — Payroll Input Collection

Trigger:
payroll period collection phase.

Action:
collect approved eligible:
employment, overtime, adjustments, expenses, advances.

Never:
invent missing compensation.

---

# AUTO-RULE-010 — Payroll Variance Detection

Trigger:
draft generated.

Action:
flag unusual change/invalid values for review.

Output:
exception, not automatic correction.

---

# AUTO-RULE-011 — Three-Way Match

Trigger:
supplier invoice + PO + receipt available.

Action:
compare price, quantity, tax/terms.

Output:
MATCHED or mismatch facts.

Never:
force approval.

---

# AUTO-RULE-012 — Approval Reminder

Trigger:
approval pending beyond reminder window.

Action:
remind active approver.

Deduplicate.

---

# AUTO-RULE-013 — Approval Escalation

Trigger:
policy escalation threshold.

Action:
apply configured escalation path.

Never:
auto-approve.

---

# AUTO-RULE-014 — Client Pending Action

Trigger:
client approval/request waiting.

Action:
remind client according cadence.

Suppress after action completed.

---

# AUTO-RULE-015 — SLA Risk

Trigger:
time progression / ticket state.

Action:
compute SLA remaining/risk/breach.

Notify assigned internal user before breach according policy.

---

# AUTO-RULE-016 — Handover Completeness

Trigger:
project/evidence/document changes.

Action:
derive missing handover artifacts.

Never:
mark client accepted automatically.

---

# AUTO-RULE-017 — Warranty Expiry

Trigger:
date horizon.

Action:
surface upcoming expiry and optional maintenance/renewal opportunity.

---

# AUTO-RULE-018 — Preventive Maintenance Generation

Trigger:
preventive schedule due.

Action:
generate planned maintenance visit/work.

Avoid duplicate generation via schedule occurrence identity.

---

# AUTO-RULE-019 — Payment Unknown Reconciliation

Trigger:
payment outcome UNKNOWN.

Action:
schedule safe status/reconciliation check if integration supports.

Never:
blindly resubmit payment.

---

# AUTO-RULE-020 — Notification Aggregation

Trigger:
multiple low-value related events.

Action:
merge/digest according user/role policy.

---

# AUTO-RULE-021 — Executive Digest

Trigger:
scheduled digest.

Action:
summarize:
critical exceptions, approvals, project risk, finance risk, client escalation.

AI may assist language later but underlying facts remain deterministic links.

---

# AUTO-RULE-022 — Offline Prefetch

Trigger:
assigned upcoming work + suitable connectivity/background window.

Action:
prefetch job bundle/documents.

Respect storage/network policy.

---

# AUTO-RULE-023 — Sync Retry

Trigger:
retryable failed operation + connectivity/allowed schedule.

Action:
retry with same operation ID.

Never retry permanent/conflict/unknown-payment cases blindly.

---

# AUTO-RULE-024 — Access Revocation

Trigger:
employee offboarded / membership revoked / device revoked.

Action:
invalidate HILTECH access/session/entitlements according policy.

Priority:
critical.

---

# AUTO-RULE-025 — Security Correlation

Trigger:
security/access/warehouse anomaly.

Action:
correlate events and create review context.

Never:
assign guilt or automatically punish employee.

---

# Registry Rule

Before production every automation gets:
- owner module,
- deterministic spec,
- exact trigger event,
- idempotency,
- schedule/timer,
- permission/authority,
- audit,
- observability,
- failure/recovery,
- kill switch,
- tests.

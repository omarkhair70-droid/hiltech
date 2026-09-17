# HILTECH Approval Policy Model

Status: DOMAIN/POLICY MODEL v0.1 / NOT AUTHORITY-FROZEN

## Objective
Make approval routing configurable, explicit, auditable, and version-aware without hard-coding Mohamed/Ahmed into domain code.

---

# 1. ApprovalPolicy

Fields conceptually:
- id
- key
- name
- subjectType
- active
- versionNumber
- effectiveFrom
- effectiveTo — O
- conditions
- steps
- fallbackBehavior
- escalationPolicyRef — O
- createdBy
- approvedBy — O
- version

Example keys:
- PROCUREMENT_STANDARD
- PAYMENT_HIGH_VALUE
- PAYROLL_MONTHLY
- STOCK_WRITE_OFF
- PROJECT_VARIATION
- QUOTE_DISCOUNT
- EMPLOYEE_OFFBOARDING_EXCEPTION

---

# 2. Conditions

Policy conditions can inspect safe subject facts:

Examples:
- amount > threshold
- project = strategic
- margin < threshold
- supplier not preferred
- write-off reason
- payroll group
- security classification
- emergency flag
- client-specific requirement

Conditions must be deterministic and testable.

Avoid:
- free-form script execution in production policy.
- hidden AI deciding approver.

---

# 3. Step Types

## Specific Role
Example:
Finance Manager.

## Specific User
Only where company reality requires.

## Relationship
Example:
Project Manager of subject Project.

## Manager Chain
Example:
employee manager.

## Any Of
Any one authorized approver.

## All Of
All listed approvers.

## Quorum
N of M.

## Sequential
Step 2 only after Step 1.

---

# 4. Subject Version Binding

ApprovalRequest stores:
- subjectId
- subjectVersion
- policyVersion

If material subject data changes:
- request becomes SUPERSEDED,
- new approval generated if still required.

---

# 5. Policy Output

Evaluation returns:
- approval required? yes/no
- generated steps
- approvers/relationship rules
- due/escalation
- re-auth requirement
- comment/reason requirement

---

# 6. Example — Purchase

Input:
amount = 25,000
project = normal

Possible:
Procurement manager only.

Input:
amount = 250,000

Possible:
Procurement review -> Finance -> Mohamed.

Numbers are examples only and must NOT be frozen without HILTECH reality.

---

# 7. Example — Payroll

Prepare by Ahmed.
Approval policy may require Mohamed or another authorized signatory.

Exact payroll run version locked.

Change after approval:
supersede and re-approve.

---

# 8. Emergency Policy

Emergency process must still be explicit.

Potential:
requester marks emergency + reason
-> emergency authority
-> immediate action
-> mandatory retrospective review/audit

No "emergency" bypass hidden from audit.

---

# 9. Delegation

Delegation:
- time-bounded
- scope-bounded
- cannot expand authority
- audited
- critical action may disallow delegation

---

# 10. Escalation

Policy may:
- remind
- reassign to backup
- escalate to higher authority
- expire

Escalation does not imply auto-approval.

---

# 11. Testing

Every policy requires tests:
- below/above threshold
- missing approver
- delegated approver
- expired delegation
- subject changed
- approver loses permission
- multiple concurrent decisions
- emergency path

## Freeze Gate
Requires actual HILTECH approval lines/thresholds and OpenFGA/application policy spike.

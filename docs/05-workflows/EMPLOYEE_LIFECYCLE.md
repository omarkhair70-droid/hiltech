# Master Workflow — Employee Lifecycle

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Model the complete HILTECH employee journey from candidate/pre-hire through onboarding, active work, role/team changes, payroll participation, asset custody, leave, performance/skills, and offboarding.

This workflow connects People, Identity, Permissions, Projects, Field Work, Assets/Warehouse, Finance/Payroll, Security/Access, Documents, Notifications, and Audit.

---

# 1. Candidate / Pre-Hire

Potential sources:
- referral
- direct applicant
- internal recommendation
- recruitment campaign
- known technician/engineer

Possible objects:
- Candidate
- Application
- Interview
- Offer/Approval
- Proposed Role
- Proposed Compensation reference
- Required Documents

Events:
- candidate.created
- interview.scheduled
- candidate.accepted
- candidate.rejected
- offer.created
- offer.accepted

Recruitment depth is not frozen yet.

---

# 2. Hire Approval

Before employee activation:
- role defined
- reporting manager
- team
- start date
- employment type
- compensation reference
- required documents
- required certifications
- required company assets/PPE
- required system access

Sensitive compensation details remain permission-scoped.

Event:
- hire.approved

---

# 3. Identity Creation / Invitation

Create HILTECH identity linked to employee record.

## Important
Identity and Employee are related but not identical:
- a person may have an identity before Active employment
- external users also have identities
- employee status controls employee-specific access

Events:
- identity.created
- employee.invited
- account.activated

---

# 4. Preboarding

Employee completes:
- profile
- required IDs/documents
- contact/emergency data
- bank/payroll details where appropriate
- policy acknowledgements
- safety prerequisites
- required training before start where applicable

System determines missing requirements by employee type/role.

---

# 5. First-Day Activation

When required conditions are satisfied:
- employment state becomes ACTIVE
- role/team/manager become active
- default permissions apply
- project/site assignments may appear
- company assets/tools may be issued
- access-control credentials may activate
- employee appears in operational planning

Events:
- employee.activated
- permissions.assigned
- access.activated

---

# 6. Active Work

The employee participates in HILTECH based on role.

Common employee-level surfaces:
- Today / work
- assigned projects
- payslips
- leave
- expenses
- assigned assets
- certifications/training
- announcements
- profile/documents

Operational facts such as work orders, site activity, approved overtime, expenses, and tool custody can feed other domains instead of being re-entered.

---

# 7. Attendance / Time / Work Evidence

Model must distinguish:
- attendance presence
- project work
- overtime
- travel/site time if relevant
- leave
- absence

Do not assume these are the same concept.

Potential sources:
- site/job start/end
- office access/check-in
- approved schedule
- manual correction with approval

Exact HR/payroll rules require HILTECH validation.

Events:
- attendance.recorded
- timesheet.created
- overtime.requested
- overtime.approved
- attendance.exception.created

---

# 8. Leave

Flow:
request -> policy validation -> manager/HR approval -> schedule/resource impact -> approved/rejected -> payroll/time impact where applicable.

Events:
- leave.requested
- leave.approved
- leave.rejected
- leave.started
- leave.completed

---

# 9. Expenses / Advances

Employee may:
- submit project/business expense
- request advance
- attach receipt/evidence
- link to project/site/client purpose

Flow:
submission -> manager/project approval where required -> finance review -> reimbursement/settlement -> payroll or separate payment according to policy.

Events:
- expense.submitted
- expense.approved
- expense.rejected
- advance.requested
- advance.issued
- advance.settled

---

# 10. Skills / Certifications / Eligibility

Track facts relevant to work assignment:
- certification
- training
- expiry
- equipment authorization
- site/client eligibility
- safety qualification

A work planner should be able to know whether a person is eligible for a job.

Events:
- certification.added
- certification.expiring
- training.completed
- worker.eligibility.changed

---

# 11. Role / Team / Manager Change

One authorized change can affect:
- org structure
- permissions
- home experience
- approval rights
- project visibility
- training requirements
- compensation reference
- device/access policy

Events:
- employee.role_changed
- employee.team_changed
- employee.manager_changed
- authorization.recomputed

Historical assignments remain preserved.

---

# 12. Company Asset Custody

Employee may hold:
- test equipment
- tools
- laptop/phone
- access card
- PPE
- vehicle or other assets

Employee record links to active custody, but asset movement history remains owned by Asset/Warehouse domain.

Events:
- employee.asset_assigned
- employee.asset_return_required
- employee.asset_returned

---

# 13. Payroll Participation

Employee record provides valid payroll context but payroll itself is a separate workflow/domain.

Inputs may include:
- salary structure/reference
- active employment dates
- approved overtime
- deductions
- bonuses
- advances
- expenses/reimbursements
- leave/attendance effects

Rule:
HR/personnel facts should feed payroll; payroll should not invent HR truth.

---

# 14. Temporary Suspension / Restricted State

If HILTECH reality requires it, model may support restricted employee states where:
- access is limited
- project assignment stops
- assets may need return
- payroll handling changes

This is not frozen and requires legal/HR validation.

---

# 15. Offboarding Trigger

Potential reasons:
- resignation
- termination
- contract end
- role conversion

Creates coordinated offboarding case.

Event:
- employee.offboarding_started

---

# 16. Offboarding Coordination

Required checks:
- project/work transfer
- client/site responsibilities reassigned
- company assets returned
- warehouse custody cleared
- expenses/advances settled
- final payroll inputs prepared
- documents completed
- access credentials revoked
- sessions/devices invalidated where appropriate
- physical access revoked
- sensitive group/communication access removed

No single department should have to remember the entire checklist manually.

---

# 17. Finalization

Employee becomes FORMER only when defined conditions are satisfied.

Historical data remains:
- project history
- audit trail
- approved financial history
- asset movement history
- documents according to retention policy

Events:
- employee.offboarded
- access.revoked
- employment.closed

---

# Major Objects Revealed
- Person
- Identity
- Candidate
- Employee
- Employment
- Role
- Team
- Manager Relationship
- Permission Assignment
- Document
- Certification
- Training Record
- Attendance Record
- Timesheet
- Leave Request
- Expense
- Advance
- Compensation Reference
- Payroll Eligibility
- Asset Custody Link
- Offboarding Case

DISCOVERED, not final.

---

# Permission Boundaries
- compensation
- bank/payroll data
- personal identity documents
- medical/emergency data if any
- performance/disciplinary data if later included
- peer employee information
- access-control/security data

---

# Completion gate
Must not be marked COMPLETE until:
- validated against actual HILTECH employment/admin reality,
- HR/legal requirements are known,
- payroll integration is fully defined,
- permissions and retention rules are frozen,
- UI/mobile/desktop surfaces exist,
- technical module/file structure is build-ready,
- tests include role change and offboarding security cases.

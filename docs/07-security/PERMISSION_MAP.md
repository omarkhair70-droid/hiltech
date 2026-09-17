# HILTECH Permission Map

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Define who can see, create, change, approve, execute, export, and audit every important HILTECH object.

HILTECH must not use a simplistic global ADMIN / USER model.

Permissions must be:
- role-aware,
- organization-aware,
- project/site-aware,
- object-aware,
- context-aware,
- and auditable.

---

# 1. Identity Model

A person may act as:
- HILTECH employee,
- HILTECH owner/executive,
- finance/admin,
- project manager,
- engineer,
- supervisor,
- technician,
- warehouse user,
- procurement user,
- HR/admin user,
- sales/tenders user,
- client organization user,
- supplier organization user,
- subcontractor organization user.

A single human may hold multiple roles.

Permissions are evaluated from:
- identity,
- organization membership,
- role,
- team,
- project assignment,
- object ownership/custody,
- approval delegation,
- data sensitivity,
- current lifecycle state.

---

# 2. Permission Dimensions

For each object/capability define separately:

- VIEW
- LIST / DISCOVER
- CREATE
- EDIT
- TRANSITION_STATE
- ASSIGN
- APPROVE
- REJECT
- EXECUTE
- DELETE / RETIRE
- EXPORT
- SHARE
- VIEW_HISTORY
- VIEW_FINANCIALS
- VIEW_SENSITIVE_FIELDS
- ADMINISTER_POLICY

Never assume VIEW implies EDIT.

---

# 3. Organization Boundary

## HILTECH internal users
Can access internal data only according to role/context.

## Client users
Can access:
- their own organization,
- allowed projects/sites/assets,
- client-visible documents,
- allowed approvals,
- allowed invoices/support/maintenance.

Cannot access:
- unrelated clients,
- internal margins,
- supplier quote comparisons,
- employee payroll,
- restricted HR,
- restricted security operations.

## Supplier users
Can access:
- their supplier organization,
- their PO/RFQ/order/delivery/invoice context.

Cannot access:
- competitor quotes,
- HILTECH internal costing,
- unrelated projects,
- other suppliers.

## Subcontractors
Can access:
- assigned project/work packages/sites,
- required documents,
- required evidence/workflow,
- approved commercial milestones if allowed.

Cannot access:
- unrelated project internals,
- HILTECH employee data,
- full client commercial information unless explicitly allowed.

---

# 4. Executive / Mohamed

Default intent:
maximum company visibility, but not automatic execution permission for every operational action.

Typical:
- company-wide dashboard: YES
- all projects: YES
- finance summary: YES
- payroll approval: according to policy
- procurement approvals: according to threshold/policy
- HR sensitive data: restricted but executive-authorized where needed
- cameras/security: if assigned
- operational edits: not necessarily default

Principle:
Owner visibility != owner must manually perform every task.

---

# 5. Finance / Ahmed

Typical:
- finance: broad
- payroll preparation/review: broad
- payment preparation: yes
- payment approval/execution: depends on bank/legal authority
- employee compensation: yes where required
- project operational details: enough for financial context
- warehouse: financial/valuation context as authorized
- cameras/security: no by default
- HR sensitive non-financial data: no by default

---

# 6. Project Manager

Typical:
- assigned projects: broad operational access
- project team: assign/manage within authority
- work orders/tasks: broad
- materials/equipment requests: yes
- procurement request: yes
- purchase approval: policy-dependent
- project cost/margin: permission-dependent
- client updates: yes where assigned
- employee payroll: no
- other projects: no unless portfolio authority

---

# 7. Engineer

Typical:
- assigned project/site technical data
- drawings/specs/tests/evidence
- technical issue creation/review
- asset/site technical history
- internal costing: no by default
- payroll: no
- procurement: technical specification input only unless separately assigned

---

# 8. Supervisor

Typical:
- assigned site/work package
- crew work visibility
- task assignment/reassignment within authority
- material receipt/usage confirmation
- equipment checkout context
- evidence review
- daily closeout
- payroll/finance: no except own employee self-service
- project commercial terms: no by default

---

# 9. Technician

Typical:
- own assigned work
- required site/project context
- relevant drawings/instructions
- own tool/asset custody
- submit evidence/issues/material usage
- own employee self-service
- peer payroll: never
- unrelated project/client data: no

---

# 10. Warehouse

Typical:
- inventory/asset records
- receiving
- checkout/return/transfer
- stock count
- condition/damage reporting
- reservations
- limited project context needed for issue
- financial valuation: permission-dependent
- project margin/client contract: no by default
- stock adjustment approval: separate authority

---

# 11. Procurement

Typical:
- purchase requirements
- supplier quotes
- comparisons
- PO preparation
- supplier history
- delivery status
- project requirement context
- internal cost: yes where needed
- payroll: no
- stock write-off: no unless separate authority

---

# 12. HR / Admin

Typical:
- employee identity/employment/admin data
- onboarding/offboarding
- leave
- employee documents
- role/team updates
- payroll input references where needed
- payment execution: no
- project cost/margin: no by default
- warehouse custody visibility for offboarding: yes

---

# 13. Sales / Tenders

Typical:
- leads/opportunities/tenders
- client contacts
- quotes and commercial documents
- pricing/costing according to role
- sales pipeline
- won-to-project handoff
- payroll: no
- HR sensitive data: no
- warehouse operations: no except availability context where relevant

---

# 14. Object-Level Context Examples

## Project
Permission can depend on assignment:
- PM assigned to Project A -> broad Project A access.
- PM not assigned to Project B -> no access unless portfolio role.

## Asset
Permission can depend on custody:
- technician holding Fluke #3 -> may view/use/report issue.
- technician cannot edit acquisition value or retire asset.

## Client
Client engineer sees technical project data.
Client finance user may see invoices but not technical internal notes.

## Payroll
Employee sees own payslip.
Finance sees payroll run.
Owner may approve.
No user sees peer payroll without explicit payroll authority.

---

# 15. Sensitive Data Classes

Classify at minimum:

## PUBLIC
Marketing/public website content.

## INTERNAL
Routine company operational data.

## RESTRICTED
Project/client commercial, supplier pricing, internal documents.

## HIGHLY_RESTRICTED
Payroll, bank/payment data, personal IDs, credentials, sensitive security/camera/access data.

## CRITICAL_ACTION
Payments, payroll approval, stock write-off, user access administration, destructive/sensitive state changes.

Data classification and action classification are related but separate.

---

# 16. Permission Decision Model

Conceptual request:

Can(subject, action, object, context)?

Inputs:
- subject identity
- organization membership
- role(s)
- relationship to object
- lifecycle state
- delegation
- policy
- data classification
- device/session trust if required

Output:
ALLOW / DENY
plus optional obligation:
- require re-authentication
- require online state
- require approval
- require reason
- require second factor

---

# 17. Deny by Default

If no explicit rule grants access, deny.

External organization users must never receive access because an object ID is guessable.

All backend queries must enforce authorization; UI hiding is not security.

---

# 18. Permission Changes

Role/team/project changes may alter access immediately.

Examples:
- employee transferred off project -> project access removed
- offboarding -> sessions revoked and company access removed
- client contact removed -> client access removed
- temporary approval delegation expires automatically

Events:
- permission.granted
- permission.revoked
- permission.changed
- delegation.started
- delegation.expired

---

# 19. Audit

Critical access/changes should preserve:
- actor
- action
- object
- result
- reason where needed
- timestamp
- session/device context where applicable

Sensitive VIEW events may require audit for selected objects such as payroll/security.

---

# 20. Open Questions Before Freeze

- Exact company roles/titles.
- Whether Ahmed combines finance/admin/HR today.
- Who has bank execution authority.
- Project financial visibility by role.
- Warehouse adjustment/write-off authority.
- Camera/access visibility.
- Whether supervisors approve overtime.
- Client invite administration.
- Supplier/subcontractor account model.
- Temporary delegation rules.
- Device trust requirements.
- Re-authentication rules for critical actions.

## Completion gate
This map is not complete until:
- every major object/action has a transition/permission table,
- real HILTECH authority lines are validated,
- external organization boundaries are tested,
- technical authorization model is chosen,
- permission tests are part of implementation,
- offboarding/revocation is proven end-to-end.

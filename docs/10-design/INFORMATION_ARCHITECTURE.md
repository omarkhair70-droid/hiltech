# HILTECH Information Architecture

Status: RESEARCHING / IA MODEL v0.1 / NOT VISUAL DESIGN

## Purpose
Define how HILTECH's information is organized before choosing final screens, navigation styling, or visual design.

This document does NOT freeze tab names or layouts.

---

# 1. Core IA Principle

HILTECH is one product with multiple role-aware experiences.

The information architecture is built around:
- Context
- Work
- Objects
- Actions
- Exceptions

Not around separate apps per department.

---

# 2. Global Context Model

A user can move through:

Company
-> Organization / Client
-> Project
-> Site
-> Area/Room/Zone
-> Work / Asset / Document / Issue

The exact hierarchy can vary by object, but context should always remain visible.

Examples:
HILTECH -> Project A -> Site Cairo -> Data Center -> Rack R4 -> Switch X

HILTECH -> Employee Hassan -> Assigned Asset -> Fluke #3

HILTECH -> Supplier S -> PO #18 -> Delivery #2

---

# 3. Universal Product Areas

These are conceptual areas, not final nav labels.

## Home
Role-aware summary and action queue.

## Work
Things requiring the user's action:
- assignments
- approvals
- reviews
- requests
- issues

## Explore / Objects
Search and browse relevant:
- projects
- sites
- people
- assets
- inventory
- clients
- suppliers
- finance objects
according to permission.

## Inbox
Durable attention/work queue:
- approvals
- mentions
- requests
- alerts
- external actions

## Search / Command
Global permission-aware access.

## Profile / Self
Own:
- account
- sessions/devices
- payslips
- leave
- expenses
- assigned assets
- notification preferences

---

# 4. Role-aware Home

Home is not one universal dashboard.

Examples:

Mohamed:
Needs You + Company Pulse + Exceptions.

Ahmed:
Finance Actions + Payroll + Receivables/Payables.

PM:
My Projects + Blockers + Client Actions + Resource Readiness.

Technician:
Today.

Warehouse:
Issue/Return/Delivery/Scan.

Client:
My HILTECH.

Same shell, different priority and permitted modules.

---

# 5. Desktop IA

Desktop optimizes:
- density
- comparison
- multi-pane work
- keyboard/search
- bulk operations
- long sessions

Potential shell:
- left navigation / contextual sections
- top global search/command
- main workspace
- optional right inspector/details/activity
- persistent context breadcrumb

Not visually frozen.

---

# 6. Mobile IA

Mobile optimizes:
- immediate work
- scan
- approvals
- context
- capture
- quick decisions

Possible conceptual primary areas:
- Home
- Work
- Projects/Explore
- Inbox
- More/Profile
- Scan as contextual/global action

Exact bottom-nav count and labels require UI research.

---

# 7. Object Detail Pattern

Important objects should use a consistent mental model:

## Header / Identity
What object is this?

## State
What state/health is it in?

## Actions
What can this user do now?

## Context
What does it belong to?

## Content
Relevant domain data.

## Activity / History
What happened?

## Related Objects
Project/site/person/asset/document/etc.

This pattern can adapt by object and device.

---

# 8. Contextual Actions

Avoid giant universal forms.

Actions appear where they make sense.

Examples:
Asset -> Checkout
Work Order -> Start
Payroll Run -> Submit for Approval
Client Variation -> Approve
PO -> Confirm Receipt

---

# 9. Cross-domain continuity

User should not mentally jump between “apps”.

Example:
Project shortage
-> open requirement
-> open procurement
-> open PO
-> open receipt
-> open project impact

All are connected objects.

---

# 10. Navigation by Role, not data duplication

Hiding a nav item does not create a different product.

Technician may never see Finance navigation.
The finance objects still exist in the same HILTECH truth.

---

# 11. Search as first-class IA

Because HILTECH will become large, hierarchy alone is insufficient.

Search should understand:
- project
- client
- employee
- asset ID/serial
- PO
- invoice
- work order
- site
- supplier

Later semantic search may augment exact search.

---

# 12. Activity as first-class IA

Every important object can expose history.

Company-level activity is an aggregation.

Activity should link back to object and actor.

---

# 13. Document placement

Do not create a disconnected “Documents universe” as the primary model.

Documents live with context:
Project / Site / Asset / Employee / PO / Invoice / Handover.

A Documents area may exist as cross-object search/filter, not as the sole source of organization.

---

# 14. Mobile/Desktop continuity

Same object IDs/state/actions.

Device-specific presentation.

Examples:
Desktop payroll = table/split view.
Mobile payroll approval = focused decision card.

Desktop warehouse = inventory grids.
Mobile warehouse = scan flow.

---

# 15. External User IA

## Client
My HILTECH -> Projects / Sites / Documents / Support / Billing allowed.

## Supplier
Orders -> Deliveries -> Invoices.

## Subcontractor
Assigned Work -> Site -> Evidence -> Review.

They remain inside HILTECH shell but receive tightly restricted IA.

---

# 16. IA Risks to research

- Too many domains in one nav.
- Overloading Home.
- Confusing project/site hierarchy.
- Making global search too powerful without context.
- Desktop becoming generic ERP.
- Mobile exposing desktop complexity.
- External users seeing internal terminology.
- Duplicating same object in multiple modules.

---

# 17. Next steps before freeze

1. Validate object/context hierarchy with real HILTECH projects.
2. Map every feature ID to IA location.
3. Build task-frequency matrix per role.
4. Conduct UI/UX Reference Research.
5. Create mobile navigation candidates.
6. Create desktop navigation candidates.
7. Test representative flows:
   - owner approval
   - payroll
   - field job offline
   - warehouse checkout
   - procurement
   - client approval
8. Freeze navigation only after these tests.

Current status: IA MODEL v0.1.

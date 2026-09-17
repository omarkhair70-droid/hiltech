# HILTECH Mobile Navigation Candidates

Status: IA EXPLORATION v0.1 / NOT FROZEN

## Goal
Find a navigation structure that keeps HILTECH one product while allowing radically different role experiences.

Do not expose every domain to every person.

---

# Candidate A — Home / Work / Search / Inbox / Me

```text
Home
Work
Search
Inbox
Me
```

Global Scan:
floating/context action where role supports it.

## Why strong
- Home can fully reshape by role.
- Work gives one place for assigned/actionable items.
- Search handles scale.
- Inbox handles durable attention.
- Me handles self-service/account.

## Risks
- Projects may feel hidden for PM.
- Warehouse needs scan more prominent.
- Client may not think in "Work".

---

# Candidate B — Home / Context / Action / Inbox / Me

```text
Home
Projects/Context
[Primary Action]
Inbox
Me
```

Primary Action changes:
- Technician: Scan / Start Job.
- Warehouse: Scan.
- PM: New/Quick action.
- Client: New Request.
- Sales: Add Opportunity / Visit note.

## Strength
Very role-shaped.

## Risk
Changing middle action too much may hurt consistency.

---

# Candidate C — Home / Explore / Work / Inbox / Me

```text
Home
Explore
Work
Inbox
Me
```

Scan available through Work/context.

## Strength
Clear mental separation:
- Explore = objects.
- Work = actions.

## Risk
May add one extra tap for scan-heavy roles.

---

# Candidate D — Role-Specific Primary Tabs Inside Shared Shell

Example Technician:
```text
Today | Jobs | Scan | Inbox | Me
```

Warehouse:
```text
Today | Inventory | Scan | Returns | Me
```

Client:
```text
Home | Projects | Actions | Support | Me
```

## Strength
Maximum persona optimization.

## Risk
Product feels like many hidden apps.
Navigation learning does not transfer across roles.
Multi-role users become confusing.

Current direction:
Do NOT lead with this unless real usability tests show shared IA is insufficient.

---

# Current Preferred Hypothesis

Candidate A or C, with context-sensitive Scan/Primary Action.

Reason:
Preserves one HILTECH mental model:
- Home = what matters now.
- Work = what I need to do.
- Search/Explore = find anything I am allowed to access.
- Inbox = attention.
- Me = self/account.

For warehouse/field:
Scan gets an always-accessible action affordance, not necessarily a permanent primary tab for everyone.

---

# Role Mapping

## Mohamed
Home = Company.
Work = Needs You.
Search = whole authorized company.
Inbox = approvals/escalations.
Me = account/device/preferences.

## Ahmed
Home = Finance.
Work = exceptions/approval/payment.
Search = employee/invoice/payment/project.
Inbox = finance attention.
Me = own self-service.

## PM
Home = Projects.
Work = blockers/reviews/actions.
Search = project/site/person/asset.
Inbox = approvals/client actions.
Me = own self-service.

## Technician
Home = Today.
Work = jobs/rework.
Scan = globally available.
Search = limited assigned objects.
Inbox = assignments/rework.
Me = payslip/leave/assets.

## Warehouse
Home = Warehouse Today.
Work = issue/return/receive/count.
Scan = globally prominent.
Search = assets/stock.
Inbox = reservations/exceptions.
Me = own employee context.

## Client
Home = My HILTECH.
Work = Actions for You.
Search/Explore = own projects/sites/docs/assets.
Inbox = approvals/support.
Me = org role/preferences.

---

# Deep Link Rule

Navigation should never force:
Push -> Home -> manually find object.

Instead:
Push -> exact object/action.

Back navigation returns to logical source/context.

---

# Multi-Role Users

A user may have:
Finance + HR
PM + Sales
Owner + Security

Avoid manual "switch app" unless necessary.

Home/action feed can union capabilities.
Sections reveal allowed domains.

If context becomes too complex, role/context switcher can be explored later.

---

# Offline Navigation

Offline:
- cached Home subset.
- cached Work.
- recent Search.
- Inbox cache.
- Me.

Unavailable online-only actions visibly disabled/explained.

---

# Final Decision Tests

Before freeze, test navigation against:
1. Mohamed approval.
2. Ahmed payroll.
3. PM blocker.
4. technician offline job.
5. warehouse checkout.
6. client approval/support.
7. multi-role user.
8. Arabic RTL.
9. one-handed Android.
10. tablet adaptive.

Current preferred direction:
Shared 5-area shell + contextual scan/action.
Not frozen.

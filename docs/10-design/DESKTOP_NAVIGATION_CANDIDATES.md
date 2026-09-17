# HILTECH Desktop Navigation Candidates

Status: IA EXPLORATION v0.1 / NOT FROZEN

## Goal
Support deep, high-density office work without turning HILTECH into a giant ERP sidebar with 35 modules.

---

# Candidate A — Role-Aware Sidebar + Global Command

Concept:

```text
HILTECH
[Search / Command]

Home
Work
Projects
People
Assets
Finance
Clients
...
---------
Inbox
Me
```

Only role-relevant destinations appear.

## Strength
Familiar and scalable.

## Risk
Can slowly become ERP menu bloat.

Need strict nav-governance rule.

---

# Candidate B — Home / Work / Explore + Context Workspaces

Top-level:
```text
Home
Work
Explore
Inbox
```

Explore opens object categories permitted to user.

Once object/workspace opened:
left contextual navigation changes.

Example:
```text
Project A
 Overview
 Sites
 Work
 Team
 Materials
 Documents
 Issues
 Client
 Handover
```

## Strength
Keeps global shell small.
Context carries detail.

## Risk
Discoverability of domain areas may require strong Search/Command.

---

# Candidate C — Workspace Tabs + Command Palette

Global shell minimal.

Users navigate primarily through:
- Search/Command.
- Home.
- recent/favorites.
- opened workspace tabs.

Example:
```text
[Project A] [Payroll Sep] [Fluke 03]
```

## Strength
Excellent for power users.

## Risk
Too advanced as only navigation model.
Need simple novice path.

---

# Candidate D — Role Workbench

Home launches workbenches:
- Executive.
- Finance.
- Projects.
- Warehouse.
- People.

## Risk
Feels like mini-app launcher.
Conflicts with "one product" thesis if overused.

Do not lead.

---

# Current Preferred Hypothesis

Hybrid B + C:

Global:
- Home
- Work
- Explore
- Inbox
- Search/Command

Then:
- contextual workspace navigation.
- optional pinned/recent workspace tabs.
- keyboard-first command access.

This prevents giant sidebar while supporting serious desktop usage.

---

# Desktop Shell Candidate

```text
┌────────────────────────────────────────────────────┐
│ HILTECH   Search / Command                     Me │
├──────────┬──────────────────────────────┬───────────┤
│ Global   │ Context Workspace            │ Inspector │
│ Home     │                              │ Activity  │
│ Work     │                              │ Details   │
│ Explore  │                              │           │
│ Inbox    │                              │           │
│          │                              │           │
└──────────┴──────────────────────────────┴───────────┘
```

Inspector only appears when valuable.

---

# Context Examples

## Payroll
```text
Global shell
  ↓
Payroll Sep
  - Overview
  - Employees
  - Exceptions
  - Approval
  - Payment
  - Reconciliation
  - History
```

## Project
```text
Project A
  - Overview
  - Sites
  - Plan
  - Work
  - Team
  - Materials
  - Equipment
  - Issues
  - Changes
  - Client
  - Documents
  - Handover
  - Finance [permission]
```

## Warehouse
```text
Warehouse
  - Today
  - Inventory
  - Assets
  - Reservations
  - Receiving
  - Returns
  - Transfers
  - Count
  - Maintenance
  - Exceptions
```

---

# Global Search / Command

Shortcut candidate:
Ctrl/Cmd + K.

Search:
Project A
PO-0081
Fluke 03
Ahmed Hassan

Commands depend on context:
- Open.
- Assign.
- Approve.
- Checkout.
- Request.
- Create.

Never show unauthorized action.

---

# Recent / Pinned Context

Useful for Mohamed/Ahmed/PM:
- recent projects.
- current payroll.
- current tender.
- current asset.

Not a browser-history clone; object-first.

---

# Keyboard Grammar

Candidate:
- Ctrl/Cmd+K Search/Command.
- / focus search in list.
- arrows/enter list navigation.
- Esc back/close inspector.
- platform-consistent shortcuts.

Exact shortcuts not frozen.

---

# RTL

Arabic:
- sidebar/context pane direction must follow intentional RTL design.
- inspector pane order tested.
- table pinned START/END not left/right.
- shortcuts unaffected by text direction.

---

# Final Decision Tests

Use:
- Mohamed command center.
- Ahmed payroll.
- PM project.
- Procurement quote comparison.
- Warehouse inventory.
- Client desktop.

Test novice + power user.

Current:
Hybrid Context Workspace + Command Palette is leading.

# HILTECH Low-Fidelity Prototype Decision Log

Status: ACTIVE / decisions remain provisional until full low-fi + RTL pass.

## LF-001 — Owner Mobile Priority
Decision direction:
`Needs You` before Company Pulse.

Reason:
owner's primary mobile job is decision/exception handling, not dashboard consumption.

Confidence:
HIGH.

---

## LF-002 — Company Pulse Density
Decision direction:
do not display every KPI on Mohamed mobile.

Use:
small set of meaningful exceptions/signals with drilldown.

Confidence:
HIGH.

---

## LF-003 — Approval Detail
Decision direction:
approval detail must include exact version + why approver required + flow/history + consequence before actions.

Confidence:
HIGH.

---

## LF-004 — Field Home
Decision direction:
Technician mobile home is `Today`, not ERP navigation.

Confidence:
VERY HIGH.

---

## LF-005 — Scan
Decision direction:
Scan is a high-priority contextual/global action for warehouse/field, but not necessarily a permanent navigation tab for all roles.

Confidence:
HIGH.

---

## LF-006 — Warehouse Mobile
Decision direction:
scan-first operational modes beat inventory-table-first design on phone.

Confidence:
VERY HIGH.

---

## LF-007 — Client Home
Decision direction:
Client home begins with:
Actions for You → Projects → Support.

Internal department/module vocabulary stays hidden.

Confidence:
HIGH.

---

## LF-008 — Payroll Desktop
Decision direction:
dense payroll grid + persistent selected-employee inspector.

Do NOT force finance to repeatedly leave the payroll table to inspect each employee.

Confidence:
VERY HIGH.

---

## LF-009 — Desktop Context Workspace
Decision direction:
Project Command Center validates:
Context navigation + main workspace + optional inspector.

Supports the leading desktop IA hypothesis instead of a global giant ERP sidebar.

Confidence:
HIGH.

---

## LF-010 — Waiting On
Decision direction:
`Waiting on` / next responsible party is a first-class list/card attribute for projects, approvals, procurement, client actions and support.

Confidence:
VERY HIGH.

---

## LF-011 — Low-Fi Visual Language
Current grayscale/Inter representation has no authority over final HILTECH brand system.

Confidence:
FINAL RULE.

---


## LF-012 — Job Readiness Before Execution
Decision direction:
Technician Job Detail shows actionable readiness/prerequisites before START JOB.

The job screen must explain blockers and Waiting On rather than presenting a generic unavailable action.

Confidence:
HIGH product/domain direction; **canvas validation pending**.

---

## LF-013 — Custody Collision Is First-Class
Decision direction:
Warehouse checkout must surface authoritative current custody/version on collision and never offer silent last-write-wins override.

Confidence:
VERY HIGH domain/security direction; **canvas validation pending**.

---

## LF-014 — Global Search Is Permission-Safe Object Search
Decision direction:
Desktop search groups results by authorized object type and exact identifiers.

It is not a raw index of all database fields and not a hidden admin console.

Confidence:
HIGH architecture direction; **canvas validation pending**.

---

## LF-015 — Shared Work Queue Is Actionable Work, Not Generic Tasks
Decision direction:
Shared Work Queue aggregates role-authorized actionable items with Waiting On, due/urgency and contextual inspector.

It does not flatten every domain into a fake universal Task object.

Confidence:
HIGH product architecture direction; **canvas validation pending**.

---

## LF-016 — Advance and Imprest Stay Distinct in Finance UX
Decision direction:
Ahmed Finance must expose Employee Advance and Financial Imprest/Cash Custody as distinct concepts.

Advance = recoverable employee obligation.
Imprest = accountable company cash in custodian custody with append-only ledger/settlement.

Do not hide both inside generic Expenses.

Confidence:
VERY HIGH domain direction; **canvas validation pending**.

---

## Decisions Still Open

- shared mobile bottom navigation exact labels.
- Search vs Explore as primary mobile destination.
- desktop global nav exact shape.
- workspace tab model.
- final inspector width/density.
- tablet behavior.
- final typography.
- color/status visual language.
- motion.
- Arabic pane/order details.

These remain intentionally open until remaining low-fi and RTL prototypes are tested.

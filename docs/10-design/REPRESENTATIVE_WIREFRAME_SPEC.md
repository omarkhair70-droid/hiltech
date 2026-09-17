# HILTECH Representative Wireframe Specification

Status: WIREFRAME SPEC v0.1 / NOT VISUAL DESIGN

## Purpose
Define the structural layout of the most important HILTECH surfaces before visual styling.

This specification answers:
- what is on screen,
- in what priority,
- what remains visible while user works,
- what actions are primary,
- what becomes a supporting pane,
- how mobile/desktop differ.

It does NOT define final:
- colors,
- fonts,
- component styling,
- illustration,
- motion timing,
- exact spacing.

---

# WF-01 — Mohamed Mobile Home

## Goal
Answer in seconds:
What needs me? What is at risk? What changed?

## Structure

```text
┌────────────────────────────┐
│ HILTECH            Profile │
│ Good morning, Mohamed      │
├────────────────────────────┤
│ NEEDS YOU                  │
│ [Payroll Sep]      Urgent  │
│ [Payment #218]             │
│ [Variation #4]             │
│        View all →          │
├────────────────────────────┤
│ COMPANY PULSE              │
│ Projects at risk      2    │
│ Client escalations    1    │
│ Critical assets       0    │
│ Receivables overdue   ...  │
├────────────────────────────┤
│ TODAY AT HILTECH           │
│ • Site A milestone done    │
│ • PO #81 delayed           │
│ • New tender received      │
├────────────────────────────┤
│ Executive digest           │
└────────────────────────────┘
```

## Rules
- Needs You always above analytics.
- no decorative chart before actionable exceptions.
- owner actions must state why owner is required.
- sensitive money values may require tap/re-auth depending policy.

## States
- no actions.
- stale/offline.
- critical exception.
- degraded finance source.
- role/permission changed.

---

# WF-02 — Mohamed Approval Detail

## Mobile

```text
┌────────────────────────────┐
│ ← Payroll Approval         │
├────────────────────────────┤
│ September 2026             │
│ Version v4                 │
│ Prepared by Ahmed          │
│ [Amount / employees]       │
├────────────────────────────┤
│ WHY YOU                    │
│ Owner approval required    │
├────────────────────────────┤
│ IMPORTANT CHANGES          │
│ +34% line variance ...     │
├────────────────────────────┤
│ APPROVAL FLOW              │
│ Ahmed ✓ → Mohamed ●        │
├────────────────────────────┤
│ Evidence / History         │
├────────────────────────────┤
│ Reject  Change   APPROVE   │
└────────────────────────────┘
```

## Primary design rule
Decision actions remain visible near bottom, but do not cover context.

## Conflict state
Replace actions with:
"This version changed. Review v5."

---

# WF-03 — Ahmed Payroll Desktop

## Goal
Review many employees efficiently while preserving source traceability.

```text
┌───────────────────────────────────────────────────────────────────┐
│ HILTECH  Search/Command                        Ahmed / Finance     │
├──────────┬────────────────────────────────────────────┬────────────┤
│ Finance  │ Payroll — September 2026                  │ Inspector  │
│ Payroll  │ [Draft v4] [12 exceptions] [Request...]  │            │
│ Payables ├────────────────────────────────────────────┤ Employee   │
│ ...      │ Employee | Base | OT | Ded | Net | Δ | ! │ source     │
│          │ Ahmed H. | ...  | .. | ... | ... |↑ | ! │ breakdown  │
│          │ Mona A.  | ...  | .. | ... | ... |  |   │ evidence   │
│          │ ...                                        │ history    │
│          ├────────────────────────────────────────────┤            │
│          │ 42 employees | totals | exceptions        │            │
└──────────┴────────────────────────────────────────────┴────────────┘
```

## Interaction
Selecting row updates inspector without losing table context.

Inspector:
- component breakdown.
- source.
- previous month comparison.
- exception.
- audit.

## Primary actions
- resolve exception.
- controlled adjustment.
- freeze/request approval.

## Never
Open every employee in a separate full screen for basic review.

---

# WF-04 — PM Project Command Center Desktop

```text
┌────────────────────────────────────────────────────────────────────┐
│ Search/Command          Project A      AT RISK                     │
├────────────┬──────────────────────────────────────────┬─────────────┤
│ Overview   │ HEALTH / PROGRESS                        │ Inspector   │
│ Sites      │ 72%   2 blockers   next milestone ...  │             │
│ Plan       ├──────────────────────────────────────────┤ Selected    │
│ Work       │ BLOCKERS                                 │ blocker /   │
│ Team       │ Material Site 2     Waiting Procurement │ work /      │
│ Materials  │ Client Access       Waiting Client      │ activity    │
│ Equipment  ├──────────────────────────────────────────┤             │
│ Issues     │ SITES / WORK                             │             │
│ Changes    │ [Site A] [Site B] ...                  │             │
│ Client     ├──────────────────────────────────────────┤             │
│ Docs       │ RECENT ACTIVITY                          │             │
│ Handover   │ ...                                      │             │
└────────────┴──────────────────────────────────────────┴─────────────┘
```

## Rule
"Waiting on" always visible for blockers/actions.

---

# WF-05 — Technician Today Mobile

```text
┌────────────────────────────┐
│ Today              Sync ✓  │
├────────────────────────────┤
│ 08:00                      │
│ Banque ... / Site A        │
│ Fiber test                 │
│ READY                      │
│ [Open Job]                 │
├────────────────────────────┤
│ 11:30                      │
│ Site B                     │
│ Install ...                │
│ BLOCKED: Material          │
├────────────────────────────┤
│ Tomorrow                   │
│ 2 jobs downloaded          │
└────────────────────────────┘
         [SCAN]
```

## Job Detail

```text
Project > Site > Area

Fiber test
READY

Instruction
Drawing rev 7
Required tool: Fluke 03
Evidence 0/3

[START]

Materials
Tests
Evidence
Issues
Activity
```

## During offline
Header:
OFFLINE • 3 changes waiting

Primary actions remain available if classified local-first.

---

# WF-06 — Warehouse Scan Mobile

## Entry

```text
┌────────────────────────────┐
│ Warehouse Today            │
│ 7 issues • 4 returns       │
├────────────────────────────┤
│        SCAN ASSET          │
├────────────────────────────┤
│ Issue Today                │
│ Returns Due                │
│ Receive Delivery           │
│ Count                      │
│ Exceptions                 │
└────────────────────────────┘
```

## Asset scanned

```text
Fluke 03
AVAILABLE

Calibration: valid
Location: Main Warehouse

[CHECKOUT]

History
Condition
Maintenance
```

## Checkout sheet
- Recipient.
- Project/Site.
- expected return.
- condition.
- confirm.

No free-text recipient.

---

# WF-07 — Client My HILTECH Mobile

```text
┌────────────────────────────┐
│ My HILTECH                 │
├────────────────────────────┤
│ ACTIONS FOR YOU            │
│ Variation #4        Review │
│ Handover Package     Open  │
├────────────────────────────┤
│ PROJECTS                   │
│ Project A          72%     │
│ Project B      Delivered   │
├────────────────────────────┤
│ SUPPORT                    │
│ Ticket #41     In progress │
│ [New Request]              │
├────────────────────────────┤
│ Documents / Maintenance    │
└────────────────────────────┘
```

No internal-only module vocabulary.

---

# WF-08 — Global Search / Command Desktop

```text
┌──────────────────────────────────────────┐
│ Search HILTECH...                        │
│ Fluke 03                                 │
├──────────────────────────────────────────┤
│ ASSET                                    │
│ Fluke 03    Checked out → Ahmed          │
│                                          │
│ RECENT RELATED                           │
│ Project A                                │
│ Movement history                        │
├──────────────────────────────────────────┤
│ COMMANDS                                 │
│ View Asset                               │
│ Transfer...          [if authorized]     │
│ Report Damage...     [if authorized]     │
└──────────────────────────────────────────┘
```

## Rule
Results and commands both permission-aware.

---

# WF-09 — Shared Inbox / Work Queue

## Mobile

```text
Work

ACTION REQUIRED
Payroll approval
Rework WO-118

TODAY
Job Site A
Return Fluke 03

WAITING
Expense approval
Client variation

[filters]
```

## Desktop

Columns:
- Type.
- Object.
- Context.
- Urgency.
- Waiting on.
- Due/SLA.
- State.

Selecting:
opens object/action inspector.

## Rule
Work Queue is not notification history.
It is unresolved actionable state.

Inbox may additionally contain informational/status items.

---

# Adaptive Mapping

## Phone
One active content plane.

## Tablet
List + detail.

Example:
Technician jobs list + selected job.
Client projects + selected project.

## Desktop
List/context + detail + optional supporting inspector.

---

# Shared Structural Components Revealed

These wireframes imply reusable primitives:

- AppShell.
- ContextHeader.
- NeedsYouCard.
- Status/Health indicator.
- WaitingOn indicator.
- ActionQueueItem.
- ObjectListRow.
- DenseDataGrid.
- DetailInspector.
- ActivityTimeline.
- ApprovalFlow.
- EvidenceChecklist.
- SyncStatus.
- ScanAction.
- EmptyState.
- Error/Conflict panel.
- VersionBadge.
- SensitiveValue.
- PermissionGuardedAction.
- ExternalIntegrationHealth.

These are component families, not visual designs yet.

---

# Next Prototype Pass

Create low-fidelity visual prototypes for:
1. Mohamed Home.
2. Approval Detail.
3. Payroll Desktop.
4. Project Command Center.
5. Technician Today + Job.
6. Warehouse Scan + Asset.
7. Client Home.
8. Search/Command.
9. Work Queue.

Each prototype must be tested in:
- Arabic RTL.
- English LTR.
- loading.
- empty.
- offline/stale where relevant.
- error/conflict.
- permission differences.

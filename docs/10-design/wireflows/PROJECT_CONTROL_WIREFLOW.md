# Wireflow — Project Manager Control

Status: EXPERIENCE WIREFLOW v0.1

## Goal
PM can move from portfolio risk to specific blocker/action without reconstructing project reality manually.

---

# Desktop Primary Flow

```text
My Projects
   ↓
Project A — AT RISK
   ↓
Project Command Center
   ↓
Risk: Material not ready
   ↓
Site 2
   ↓
Work Package
   ↓
Work Order WO-118
   ↓
Requirement
   ↓
Warehouse reservation / Procurement
   ↓
Resolution
   ↓
Readiness updates
   ↓
Work becomes READY
```

---

# Portfolio List

Columns/fields:
- Project.
- Client.
- PM.
- Health.
- Progress.
- Next milestone.
- Waiting on.
- Top blocker.
- Planned end.
- Financial signal if permitted.

Critical pattern:
**Waiting on** must be visible.

---

# Project Command Center

Potential panes:
```text
Left:
Project areas/modules

Center:
current work/project view

Right:
activity / selected issue / inspector
```

---

# Risk Drilldown

Project health:
AT_RISK

Reasons:
1. Material delivery late.
2. Client access approval pending.

Each reason opens real object.

No unexplained red dot.

---

# Create Work Order

```text
Project
  ↓
Site
  ↓
Work Package
  ↓
Create Work Order
  ↓
Scope
Schedule
Assignee
Required evidence
Materials
Equipment
Drawing
Dependencies
  ↓
Readiness calculated
```

If blocked:
do not prevent creation.
Show blocker dimensions.

---

# Resource Need

```text
WO requires:
Fluke
200m fiber
Technician with certification
Drawing rev 7

System:
Fluke → available
Fiber → 100m only
Technician → available
Drawing → current

Readiness:
BLOCKED_MATERIAL
```

PM can:
- request procurement.
- reschedule.
- change requirement if authorized.

---

# Field Feedback

Technician:
starts offline
captures evidence
submits

Supervisor:
accepts

PM desktop:
progress changes.

No separate manual progress entry when accepted work can derive it.

---

# Variation Flow

```text
Field issue
   ↓
Create Change/Variation
   ↓
Technical impact
   ↓
Schedule impact
   ↓
Cost/price impact
   ↓
Internal approval
   ↓
Client approval
   ↓
Baseline version updated
```

PM sees:
old vs new baseline.

---

# Client Action

Project card:
```text
Waiting on Client
Variation #4
2 days
```

Open:
exact request, client status, reminder policy.

---

# Handover

```text
Delivery Review
   ↓
Handover completeness
   ↓
Missing:
- OTDR report site 3
- as-built drawing
   ↓
Resolve
   ↓
Ready for client
```

---

# Mobile PM

Priority:
- project risk.
- blocker.
- assignment.
- client action.
- evidence.
- quick approval.

No giant schedule table.

---

# Error/Edge

- project paused.
- work reassigned while offline.
- material reservation lost.
- supplier delivery late.
- client rejects variation.
- stale drawing.
- accepted work reopened.
- project close blocked by company asset still on site.

---

# Success Criteria

PM can answer:
"What is blocking delivery and who owns the next action?"
within one drill path.

# HILTECH Data Visualization System

Status: RESEARCHING / NOT FROZEN

## Principle
A chart earns space only when it helps a decision faster than a number/table.

Do not build an executive dashboard made of decorative charts.

---

# 1. Visualization Families

## KPI
Single value + context:
- active projects.
- receivables.
- overdue assets.

Must show meaning/timeframe.

## Trend
Time series:
- cash.
- incidents.
- project progress.
- usage.

## Distribution
- project states.
- asset condition.
- workload.

## Comparison
- budget vs actual.
- planned vs completed.
- supplier facts.

## Timeline
- project.
- asset movement.
- incident.
- approval.

## Topology
- network/device relation.
- upstream/downstream impact.

## Geographic
- sites/projects/field where location is operationally useful.

---

# 2. Table Before Chart

For exact operational/financial work:
table often primary.

Chart:
overview/anomaly.
Table:
exact values/action.

Allow drill from visualization to objects.

---

# 3. Status Color

Do not reuse status red/green indiscriminately as chart series palette.

Charts need categorical/sequential/diverging palette separate from semantic statuses.

---

# 4. Accessibility

Elastic Charts reference demonstrates useful principles:
- chart title.
- description.
- underlying tabular representation.
- semantic grouping.
- texture/pattern where possible.

HILTECH must provide a non-color-only interpretation for important charts.

---

# 5. Executive Visuals

Mohamed:
fewer charts, stronger exceptions.

Potential:
- portfolio risk trend.
- cash/receivable trend.
- project health distribution.
- deadline timeline.

No chart if action list communicates better.

---

# 6. NOC

Topology/health visualization can be richer:
- nodes/edges.
- filtering/grouping.
- impact.
- timeline.

But exact numeric telemetry remains inspectable.

## Freeze gate
Requires chart library research for Compose, data-volume tests, RTL labels, accessibility, print/export needs and representative business validation.

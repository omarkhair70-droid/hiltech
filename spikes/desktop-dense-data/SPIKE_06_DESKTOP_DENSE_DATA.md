# SPIKE-06 — Desktop Dense Data

Status: RUNNING

## Goal

Determine whether Compose Desktop remains credible for HILTECH's dense office workflows before production code.

Representative use case:
- Ahmed payroll grid,
- warehouse/asset tables,
- large project work queues.

## Dataset

50,000 synthetic payroll rows.

## Operations

The spike exercises:
- search/filter,
- exceptions-only view,
- sort,
- grouping/summary,
- selection,
- 10k-row bulk selection,
- selected-object inspector,
- keyboard up/down selection,
- keyboard space bulk toggle,
- LTR/RTL layout toggle,
- logical start/end layout behavior.

## Render proof

CI runs the real Compose Desktop application under Xvfb.

The application:
- creates the 50k-row data source,
- composes the dense table using LazyColumn,
- reaches a real Compose frame,
- emits `HILTECH_DENSE_UI_RENDER_PASS`,
- exits cleanly.

This is stronger than compile-only evidence, but it is not yet a human visual-quality review.

## Performance rule

CI tests use deliberately wide 5-second safety ceilings for data transformations.

These are not final HILTECH performance budgets.

Purpose:
reject pathological client-side data operations before deeper UI work.

## Pass

ACCEPT platform viability if:
- 50k-row data model operations pass,
- 10k-row bulk selection passes,
- Desktop app compiles,
- real Compose Desktop process reaches first rendered frame under Xvfb,
- no architectural workaround outside Compose is required.

Human UX review is still required before final visual freeze.

## Production status

Disposable feasibility evidence only.
Not production payroll UI.

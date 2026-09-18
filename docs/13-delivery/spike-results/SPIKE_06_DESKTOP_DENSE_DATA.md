# SPIKE-06 Result — Desktop Dense Data

Date: 2026-09-18
Decision: **ACCEPT — COMPOSE DESKTOP DENSE-DATA FEASIBILITY PASSED**

## Environment

- Kotlin 2.4.20
- Compose Multiplatform 1.11.1
- Java 21
- Linux/Xvfb CI renderer

GitHub Actions run: 35305553872.

## Dataset

50,000 synthetic payroll rows with:
- employee identity code/name,
- project,
- base salary,
- overtime,
- deductions,
- net pay,
- variance,
- workflow state.

## Proven

- data generation passed.
- filtering/search passed.
- variance sorting passed.
- grouping/summary passed.
- 10,000-row bulk selection passed.
- desktop compilation passed.
- representative inspector/table screen compiled.
- keyboard selection/toggle path compiled.
- LTR/RTL layout toggle compiled.
- real Compose Desktop process reached a rendered frame under Xvfb.
- runtime marker: `HILTECH_DENSE_UI_RENDER_PASS rows=50000`.

## Interpretation

Compose Desktop remains viable as the HILTECH Windows office surface.

The spike rejects the hypothesis that dense payroll/warehouse screens inherently require abandoning Compose Desktop before implementation.

## Not Proven Yet

- final table/grid component API,
- pinned-column polish,
- real user-perceived scrolling benchmark on HILTECH office hardware,
- final Arabic dense-screen visual review,
- accessibility/keyboard completeness,
- final performance budgets.

## Production Status

Disposable feasibility evidence only.

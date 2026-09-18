# SPIKE-02 — Arabic / RTL Adaptive Layout

Status: RUNNING

## Goal

Prove the shared Compose client can render HILTECH's representative information structures in:

- Arabic RTL,
- English LTR,
- mixed Arabic + IDs/numbers/IP addresses,
- phone width,
- tablet width,
- desktop width.

This is a structural/technical spike, not the final visual design.

## Representative content

The same shared UI includes:
- project list/detail information,
- payroll employee/inspector information,
- asset detail information.

## Adaptive modes

- < 600dp: STACKED.
- 600–1099dp: SPLIT.
- >= 1100dp: WIDE.

Thresholds are spike values, not final design tokens.

## Bidi rule

LTR identifiers inside Arabic text are explicitly isolated with Unicode LTR isolates.

Representative mixed values:
- WO-42
- 10.20.30.4
- Fluke-03
- EMP-00421
- money/date/version strings.

## CI proof

- shared policy tests.
- Android compile using the same shared UI.
- Desktop compile.
- real Compose render under Xvfb for:
  - 360×800 RTL,
  - 800×1000 RTL,
  - 1440×900 RTL,
  - 1440×900 LTR.

## Pass

ACCEPT structural feasibility if:
- all adaptive widths resolve deterministically,
- mixed identifiers remain explicitly isolated,
- Android build passes,
- all four real desktop render cases reach a Compose frame,
- no physical left/right assumption is required by the shared layout code.

## Still Open

- final Arabic font.
- final pane ordering preference after human review.
- final navigation labels.
- final typography, color, motion and spacing.
- real Android device screenshots.
- accessibility review.

## Production status

Disposable structural evidence only.

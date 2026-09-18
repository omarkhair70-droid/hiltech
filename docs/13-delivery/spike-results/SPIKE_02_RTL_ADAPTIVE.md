# SPIKE-02 Result — Arabic / RTL Adaptive Layout

Date: 2026-09-18
Decision: **ACCEPT — RTL/ADAPTIVE STRUCTURAL FEASIBILITY PASSED**

## Environment

Shared KMP/Compose client from SPIKE-01.

GitHub Actions run: 35305946228.

## Representative Content

Shared screen structure included:
- project information,
- payroll employee/inspector information,
- asset information.

## Mixed Direction Content

Arabic labels were combined with explicitly isolated LTR values such as:
- WO-42
- 10.20.30.4
- Fluke-03
- EMP-00421
- money/date/version values.

Unicode LTR isolate characters were part of the tested policy.

## Adaptive Modes

Spike thresholds:
- <600dp → STACKED
- 600–1099dp → SPLIT
- >=1100dp → WIDE

Thresholds are feasibility values, not final design tokens.

## Evidence

Passed:
- shared policy/bidi tests,
- Android build,
- Desktop build,
- 360×800 RTL real render → STACKED,
- 800×1000 RTL real render → SPLIT,
- 1440×900 RTL real render → WIDE,
- 1440×900 LTR real render → WIDE.

## Accepted Direction

The shared Compose client can support Arabic-first HILTECH structure without requiring a separate RTL codebase.

Use:
- logical start/end layout thinking,
- explicit bidi isolation for machine identifiers,
- adaptive layout policy rather than mirrored fixed coordinates.

## Still Open

- final Arabic font,
- final navigation,
- exact desktop pane ordering,
- typography/spacing/color/motion,
- accessibility review,
- real Android screenshots,
- human review of Arabic visual quality.

## Production Status

Disposable structural evidence only.

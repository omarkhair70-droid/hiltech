# UX-03 — Context-first vs Workbench-first (native Compose) Evidence

Date: 2026-11-22
Study track: Chat 02 / Issue #128 / Draft PR #134
Status: **RESEARCH / NATIVE SYNTHETIC EVIDENCE / NOT APP NAVIGATION ACCEPTANCE**

## Exact scenario and guardrails

The study renders two navigation grammars in **existing shared Kotlin/Compose** with the same Phase5 synthetic authoritative Warehouse read DTO:
1. `WORK` -> current actual `WarehouseCommandCenterSection` using ACTION_FIRST experimental ordering
2. `OBJECTS` -> current actual `WarehouseQuickScanControls` (manual input / source lookup, **not physical camera**)

The experiment is a navigation container over actual existing components. It does not introduce a new web app, business API, access authority or data store. The caller must provide **currently admissible** areas; the study is not a permission engine. Main signed-in `HiltechShell` and real Windows/Android app entrypoints are **not switched to this UI**.

The source scope uses identity, organization and specific warehouse context ID; new context should reset local selected area/history. On any render, the selected area is revalidated against the provided areas before content is rendered. These are presentation safety guards **in addition to**, never replacing, backend object authorization.

## Pass 01 — source head `492e907e94b317c49a025ac2711a57ab37a55e00`

Remote authorized Windows QA clone:
`D:/02_QA-and-Builds/HILTECH/UX-128/repo-main`.

Command:
`./gradlew.bat :apps:desktopApp:renderPhase5WarehouseEvidence --no-daemon`, JDK17 scoped to the process.

Observed: **BUILD SUCCESSFUL in 2m 55s; exit 0; 15 tasks (8 executed, 7 up-to-date)**. Log includes:
`UX128_NAV_MODEL_PASS forbidden_destination=PASS back_context=PASS revoked_destination=PASS`.

Generated native Desktop Compose screenshots, synthetic fixture, not Android runtime:
- `ux128-global-context-windows.png` 1150x1000
- `ux128-global-workbench-windows.png` 1150x1000
- `ux128-global-context-phone.png` 430x960 Desktop Compose phone-width rendering
- `ux128-global-workbench-phone.png` 430x960 Desktop Compose phone-width rendering

### Pass01 visual review

- **Windows / Context-first**: persistent narrow list of admitted destinations and a broader work surface with existing Warehouse detail. Good for cross-object switching; content width tradeoff on narrow windows needs proof.
- **Windows / Workbench-first**: source work fills the canvas, with destinations in a horizontal strip. High density but less clear object navigation when many entries exist.
- **Phone**: first pass had very similar screen hierarchies, so the A/B was **not sufficiently discriminating**. Not a valid basis for design selection. Therefore revised B to a genuinely focused first-action state with explicit `تغيير المساحة` disclosure, whereas A keeps areas visible.
- **No visual identity freeze**. Both still inherit utilitarian Material controls and mixed Arabic/English technical labels from existing source components. The study is testing IA/back, not the final Industrial/Precise/Alive craft.

## Pass 02 — revised experimental grammar, screenshot + actual pointer

- `WORKBENCH_FIRST` on compact width hides the alternate navigation list until user taps `تغيير المساحة`, prioritizing immediate first task. This adds a tap to cross-area navigation (source behavior, not measured operator time).
- `CONTEXT_FIRST` keeps admitted entries visible on compact width; object choice can be one pointer action when the desired button is visible.
- Headless `ImageComposeScene` on phone-width sends actual Press/Release to `OBJECTS`, captures, then returns to `WORK`; the callback must confirm both transitions.
- Pure nav checks reject an unadmitted `PEOPLE` area and clear a revoked `OBJECTS` destination; this is **not proof** that any actual person’s server entitlements were used.
- One pass exposed a **history loop defect** when selecting a prior area via tabs created a new history entry. Correction in code: selecting any already-present previous context consumes the intervening trail. The next test explicitly clicks the visible Back control and asserts no cyclic history.
- Workbench is only proven against one warehouse fixture; technician / PM / admin / owner tasks still open.

**Verified final Pass02 source SHA:** `759219184383075537c6d48e7dcad971e0e659f8`.

Ran the exact checked-out source in the isolated Windows QA directory using
`./gradlew.bat :apps:desktopApp:renderPhase5WarehouseEvidence --no-daemon` with JDK17 scoped to that process. **BUILD SUCCESSFUL in 5m 31s**, 15 tasks (7 executed, 8 up-to-date), exit code **0**.

Harness output:
- `UX128_NAV_MODEL_PASS forbidden_destination=PASS back_context=PASS revoked_destination=PASS`
- `UX128_NAV_POINTER_PASS objects=PASS return=PASS context_scope_reset=PASS`

The interaction sequence is a real headless Compose `ImageComposeScene` pointer **Press + Release** on the visible Context-first `OBJECTS` button, then a real Press + Release on the visible `رجوع للسياق السابق` button, with callbacks checked. This is not a physical Android test. The pure state model additionally asserts that selecting the previous area via tab **consumes** the old history and cannot create the detected navigation loop. `scope.key` changes for a different warehouse context; remember state is keyed to the principal/organization/object context. The variant-specific on-device permission revalidation is **not** established because the inputs are synthetic fixtures.

Screen capture filenames (actual Compose render, local only):
`ux128-global-click-00-work.png`, `ux128-global-click-01-objects.png`, `ux128-global-click-02-return.png`, plus four `ux128-global-{context,workbench}-{phone,windows}.png` candidate comparison renders.

All are generated in the authorized Windows QA folder:
`D:/02_QA-and-Builds/HILTECH/UX-128/repo-main/apps/desktopApp/build/reports/phase5-warehouse/`. PNG binaries are **not claimed uploaded to GitHub**.

### Pass02 critical decision

The original back-loop defect is **FIXED in the pure model and headless Compose Back interaction now passes**. Neither Context-first nor Workbench-first is an accepted HILTECH shell. Workbench on 430px places `مهام المخزن` and a `تغيير المساحة` disclosure before the operational attention queue; Context-first exposes both destinations directly. The reduced chrome vs increased discovery step is a real tradeoff but operator time was not measured.

Still a **P1 missing capability**: source-linked Asset/Project details and exact filter/scroll/back-stack context are not routed through a complete live global shell. Empty/loading/denied source nav projection, screen-reader/keyboard, high font scale, Android runtime and multi-role real-source journeys remain OPEN.

## Why no production activation yet

The study does **not** establish:
- source-generated admitted global destination list for signed-in users;
- object-level Project -> Site -> Work -> Asset selection and filter/scroll memory;
- full Inbox/notifications navigation from Phase2 backend contracts;
- Android emulator app/current device taps, biometric/camera flows, Android system Back;
- keyboard arrows/Tab/Enter/Esc on Windows, TalkBack, 200% text, RTL screen readers;
- loading/revoked/stale source state from live backend and real warehouse/operator acceptance.

The real `HiltechShell` remains long-column baseline. No live user can choose this UX through current application entrypoints.

## Recommendation, NOT approval

Keep both hypotheses. On compact field use, prefer to **test** focused Workbench-first against a visible-context alternative; on desktop PM/warehouse operations, compare contextual list/detail with a full-width task canvas using the same real source facts. First decide what authoritative destinations and return paths are possible; then refine type/colors/motion, rather than freezing dashboard aesthetics from these synthetic screenshots.

For source/public reference boundaries, current Android list/detail/back stack docs: https://developer.android.com/develop/adaptive-apps/guides/list-detail and Windows keyboard guidance: https://learn.microsoft.com/en-us/windows/apps/design/input/keyboard-interactions . Both are **design guidance**, not guarantees that APIs are integrated into this Kotlin/Compose version.

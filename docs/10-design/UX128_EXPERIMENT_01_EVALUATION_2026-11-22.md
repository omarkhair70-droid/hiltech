# UX-128 Experiment 01 — Warehouse native Compose comparison / critique

Date: 2026-11-22. **Design research, NOT operational acceptance.**

## Version and provenance
- Independent UX branch: `ux/128-baseline-role-first-20261122` / Draft PR #134.
- Existing production default: `WarehouseCommandCenterPresentation.BASELINE` in `WarehouseCommandCenterSection.kt`; no app toggle enabled, no release.
- Experimental presentations: `ACTION_FIRST` and `OBJECT_FIRST` in `WarehouseCommandCenterExperiment.kt`. Both consume **the identical** `WarehouseCommandCenterDto` (Phase 5 canonical source-derived read model) and call the original onDrill/onOpenStorageLocations/onOpenWorkResource callbacks.
- Recorded test harness: `Phase5WarehouseRenderEvidence.kt` captures A/B from the existing synthetic Phase 5 fixture. **This is native Compose Desktop image rendering at Windows and phone-width viewports, not Android runtime screenshots or a usable instrumented field-device test.** Phase 5 source Android emulator screenshots were separately audited in the baseline; variant-specific Android runtime acceptance remains OPEN.

## Pass 01 (tested source head `bf335cc539d8e615227616da162ee2b47953edb9`)
Build: `:apps:desktopApp:renderPhase5WarehouseEvidence` on isolated Windows QA clone; **BUILD SUCCESSFUL** (19 tasks) with Temurin JDK17 set for the command session. Original Gradle launch using default JRE failed toolchain resolution, corrected without modifying repository or machine-wide Java settings.

Native Compose screenshots produced:
- `ux128-action-windows.png` 1150×1000
- `ux128-object-windows.png` 1150×1000
- `ux128-action-phone.png` 430×960
- `ux128-object-phone.png` 430×960

### What the images actually show
1. **A action-first** exposes overdue returns / calibration / receiving discrepancy in the first viewport on phone. It improves the task scan order over source baseline, but still has generic material button treatment, no integrated Quick Scan, no measured time-to-task result and excessive per-item chrome.
2. **B object-first** foregrounds inventory/balances and reservations at the cost of delaying overdue/blocked work. This may be useful for object retrieval, but as Warehouse Today creates a likely safety/attention tradeoff.
3. **Critical visual failure in B Windows**: 290dp right-side panel with long action buttons wraps `مصدر الحالة` into vertical glyph fragments. Therefore Pass 01 B **FAILS visual layout acceptance**; cannot be treated as a viable option.
4. **Both candidates** present ISO UTC timestamps with poor Arabic mixed-direction ordering. Human meaning and machine provenance remain mixed.
5. **Both candidates** use surface-tinted stacked records, resembling generic UI cards rather than compact operational list/detail grammar.

### Explicit Pass 02 corrections
Commit `f96e9483c648256676b112aaf9d46004b2d5e279`:
- Short, accessible buttons `التفاصيل` and `المصدر` with per-object semantics and 48dp minimum target height.
- Direction-isolated LTR UTC values while preserving source precision text; timestamps no longer embedded inside Arabic inline runs.
- Remove tinted `Surface` wrapper around every row and preserve dividers instead.
- Only show a human explanation for low-stock policy when the source reason identifies missing threshold; otherwise preserve the source reason instead of inventing one.
- Re-rendered **Pass 02** from tested source commit `f96e9483c648256676b112aaf9d46004b2d5e279`: `:apps:desktopApp:renderPhase5WarehouseEvidence` **BUILD SUCCESSFUL in 2m 39s**, 15 tasks. Visually re-inspected `ux128-object-windows.png` and `ux128-action-phone.png`. B no longer breaks the right-rail action labels; A's UTC timestamps are visually isolated from Arabic. Card tint removed. These corrections address the *observed visual defects*, not operator acceptance.
- Latest 4 synthetic screenshots remain in the isolated authorized Windows QA workspace `D:/02_QA-and-Builds/HILTECH/UX-128/repo-main/apps/desktopApp/build/reports/phase5-warehouse/` (also copied into local, staged `docs/10-design/reviews/ux128-20261122/`). **Binary image commit/push from the remote Desktop Commander session was stopped by the security gateway**; these PNG files are NOT claimed uploaded to GitHub and the PR still only contains Kotlin/markdown.

## Scorecard (research-only; operator measures pending)
| Dimension | Baseline | A first render | B first render |
| --- | --- | --- | --- |
| Primary warehouse attention visible before scrolling on 430px | Several stacked sections, not role-ranked | Improvement in screen order | Worse than A for exceptions |
| Source/audit inspectability | Immediately exposes raw source/IDs, visually noisy | Explicit source expansion; interactions untested | Explicit source expansion; interactions untested |
| Windows narrow-pane button legibility | Not responsive list/detail | Legible in inspected preview | **FAIL**, vertical wrapping |
| Arabic/Latin timestamp readability | Poor | Poor, fixed in unverified pass 02 | Poor, fixed in unverified pass 02 |
| Android device screenshot | Existing Phase 5 CI synthetic | **Not tested** | **Not tested** |
| Keyboard/TalkBack/200% fonts | **Not measured** | **Not measured** | **Not measured** |
| Task time and errors with operators | **Not measured** | **Not measured** | **Not measured** |

## Initial design direction (NOT decision or freeze)
Use A as **Warehouse Today / action queue hypothesis** and explore B as **Inventory / search context hypothesis**, rather than choosing one universal starting screen. Need to validate on actual warehouse operator jobs, not aesthetics. On the current project the Phase 5 Quick Scan is an existing separate section and was NOT wired as a new candidate action: do not advertise a working `Today -> Scan` shortcut yet.

## Integration / testing limits
- No Android phone/emulator was launched for these A/B variants in this test; screenshots labelled `phone` mean desktop Compose scene of phone width.
- No source authority, stock movement, custody, audit or feature flags changed.
- No keyboard, TalkBack, physical scanning, role-revocation, server-bound E2E or actual operator test completed by this experiment.
- Source-backed status is never inferred from color. `limited` queues show partial list count with `+`; no false whole-queue totals.
- Do not merge or deploy PR #134 without owner review and exact-head tests; eventual product rollout has its own acceptance gate.

## Reference notes
- Material3 Adaptive (official): list/detail when width genuinely supports contextual panes, not as an excuse for a permanent sidebar: https://developer.android.com/develop/adaptive-apps/guides/list-detail
- Fluent 2 layouts: explicit grid alignment and rhythm: https://fluent2.microsoft.design/layout
- Carbon data tables: global vs item-specific actions and density rules: https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines
- These are behavioral comparisons, not HILTECH visual identity choices.

## Next safe experiment boundary
1. Review fixed Pass 02 images and record a FAIL/PASS per viewport.
2. Implement a real navigation handoff from role-scoped Warehouse Today to existing Quick Scan / Asset Passport, without adding unauthorized actions or rewriting the Phase 5 ledger. This is a separate bounded UX slice requiring interaction tests.
3. Validate Android/Windows UI states, actual operator job time, mixed-direction identifiers, offline and role revocation.
4. Maintain functional correctness through Phases 6–13; defer whole-product signature typography, theme/motion craft freeze until core Phase 13 completion, without shipping known accessibility/interaction defects now.


## Pass 02 evaluation conclusion
- **Visual viewport:** 430×960 phone-width Desktop Compose and 1150×1000 Windows-layout render inspected; no button character stacking in repaired B; chronological timestamps readable LTR. These are screenshot evidence only, not accessibility or Android runtime proof.
- **Best first operational hypothesis:** A for Warehouse Today due to top-of-screen overdue/calibration/receiving attention. B is a stronger candidate for an Inventory/Object-focused view. These are **not mutually exclusive** and neither is visually/operationally frozen.
- **Remaining visible shortcomings:** extremely utilitarian default Material controls; short source values still mixed-language in sections; right-rail information hierarchy needs keyboard/focus testing and narrow-window constraints. No working Warehouse Today -> Quick Scan action yet.
- **No approval/deploy:** keep Draft PR #134 and Issue #128 open; Stage 2 must prove native Android/Windows interaction and human operator tasks before merging.


## Experiment 02 — actual Warehouse Today ↔ code lookup focus (2026-11-22)

### Source integration (review-only, no activation)
- Shared native Compose `WarehouseOperatorFocusFlow.kt` switches between two pre-existing Phase 5 read surfaces: the warehouse source-driven command center and `WarehouseQuickScanSection`.
- `WarehousePhysicalOperatorSection` now accepts `presentation` with the default **`BASELINE`**. Neither the current Windows nor Android app passes an experimental presentation; therefore their shipping path remains the existing long-column interface. No changed backend service, role authority, stock movement, custody, access-control or offline contract.
- For experiment opt-in, the existing API, warehouse/user context, `runTask` error path and canonical drill handlers remain in place. The tab says **`بحث بالكود`**, not a false claim of a working physical camera reader.
- Scoped `WarehouseQuickScanSession` holds tag/asset/stock search text and last read-only query result when switching focus; it is keyed by warehouse and organization and is discarded with its owning operator composition. It is not an authorization cache, offline source of truth, or confirmation of current physical custody.
- Observation callback and selected-tab semantics added for native pointer proof. Buttons are operational controls, not inert decoration.

### Exact test evidence
Tested source HEAD **`c4f508cfcb8c2c3ae535c7f339c4756ef1dc1c34`** in an isolated Windows QA clone. Command: `./gradlew.bat :apps:desktopApp:renderPhase5WarehouseEvidence --no-daemon`, with JDK 17 set only in this command session. **BUILD SUCCESSFUL in 1m 51s; 15 actionable tasks (4 executed, 11 up-to-date), process exit 0.**

The existing Phase 5 synthetic fixture generated four extra screenshots of the A/B focus modes at 430×960 using real shared Compose code:
- `ux128-action-flow-today-phone.png`
- `ux128-action-flow-lookup-phone.png`
- `ux128-object-flow-today-phone.png`
- `ux128-object-flow-lookup-phone.png`

The additional headless **actual pointer** test (not merely two preselected previews) generated:
- `ux128-click-00-today.png`
- `ux128-click-01-lookup.png`
- `ux128-click-02-return.png`

The test sent `PointerEventType.Press` + `Release` to the navigation controls in the real `ImageComposeScene`, asserted `TODAY → CODE_LOOKUP → TODAY`, and asserted retained entered code `synthetic-current-tag`. Both post-click screenshots were visually inspected and matched the expected modes. **PASS for headless Desktop Compose pointer transition and session-scoped input preservation; NOT proof of a physical touch device, camera, backend authorization, role-revocation behavior or full user workflow.**

The resulting 11 `ux128-*.png` captures are local to the authorized QA working copy:
`D:/02_QA-and-Builds/HILTECH/UX-128/repo-main/apps/desktopApp/build/reports/phase5-warehouse/`.
A prior attempt to push PNG files from Remote Desktop Commander was blocked by its safety gateway; **binary screenshots are not claimed to be committed or present in GitHub**. Code and this text manifest are in Draft PR #134.

### Critical design review after proof
- **Today / action-first is more immediately useful as the operator's attention start** than the object-first ordering; still an operator-test hypothesis rather than a design freeze.
- **Lookup is still too technical**: the existing result UI exposes raw `canonical`, UUID and `Scan/search PASS` messages and offers several fields in a long stack. This remains a top-priority human-copy / progressive-disclosure experiment.
- **Drill-to-detail is still below the fold.** Clicking an Asset/Stock action uses the existing source handlers to populate detail state farther down the operator Column. The focus switching test does NOT establish usable drill-back navigation or detail visibility. This must be tested and solved before describing `Today → Scan → Passport` as a complete journey.
- Native Android app **does wire the warehouse operator under `HiltechShell.warehouseOperator`**, but it is still on the default non-experimental presentation. No Android build, emulator run, physical test, TalkBack, keyboard-focus, 200% type, latency or operator timing was established by this test.
- The current experiment retains previously fetched lookup results when input changes. A user-facing freshness/staleness indicator or result-clearing policy must be settled before shipping; avoid implying the old result proves a new code.

### Next bounded UX question
How should an authenticated warehouse operator enter a code, inspect the **source-confirmed object identity and current custody**, then return to their exact attention context with a visible, direct detail target, without losing provenance or accidentally interpreting an old search result as current? Test this against the existing authoritative Asset Passport and Stock Item read path. Do not invent barcode mapping, camera support, new permissions, or inventory write operations. Keep the default baseline while engineering continues Phase 6.

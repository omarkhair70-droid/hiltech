# UX-128 — Technician Today → JobBundle focus: source-native experiment

Date: 2026-11-22
Status: **EXPERIMENT / NOT INTEGRATED / NOT ACCEPTED**
Branch: `ux/128-field-focus-native-20261122` forked from `main` `2947618203fd6b3e8d0ce937f5cee8d41e6e9405`; separate from research Draft PRs #134/#142.
Tracked: Issue #128 and Phase6 native/real-device review gate #116.

## Scope and issue

After the current Phase6 integration, `AssignedWorkSection` already renders a source-backed Today list and selected JobBundle:
- >=760dp: useful existing two-pane list/detail on wide screen. Preserve it.
- compact: renders the *whole Today list above the selected JobBundle* within a shared outer scroll. On multiple WorkOrders, the action may be below fold. This is a source-inspected usability hypothesis, **not yet a measured technician failure**.
- the same source contains WorkOrder selection via `onSelectWork(workOrderId)`, cached/read-only/offline distinctions, local pending intents, Evidence, completion gate, test and document source states. Do not invent statuses, authorities, or allow stale/cached execution.

## Candidate A — current baseline

Today list remains visible and the selected bundle follows the list in one long scrolling Column. Strong overview, weak selected-object focus on compact.

## Candidate B — actual native opt-in in shared/core

`FieldWorkPresentation.FOCUSED_COMPACT` in existing `AssignedWorkSection`, default `BASELINE`, with explicit `focusScopeKey` required for the focus experiment (blank scope falls back to baseline). The shipped Android and Windows entrypoints do **not** pass these parameters.

On compact only:
1. Today shows the exact existing `TodayColumn` and `onSelectWork` source handler.
2. Pressing a currently listed WorkOrder enters local focused mode and calls that exact existing handler; if its bundle is still loading, show neutral loading/unavailable text (not the previous bundle).
3. Detail appears only when selected bundle ID AND selected WorkOrder ID exactly match the currently listed ID. This prevents showing prior object's data after switching to a different WorkOrder or after source removal.
4. A visible `الرجوع لشغلي اليوم` action returns to the existing Today list, without changing the source assignment or triggering a server write.
5. The actual existing `JobBundleDetail` is reused with all current flags, server source, cache/queued and consent semantics intact.
6. Wide viewport continues using the original >=760dp split without UX fork.

The focus selection is **presentation state**, not a permission cache. The caller must key it by authenticated actor + org + relevant context. Source authority remains the sole authority; a role label or a cached copy cannot authorize a mutation.

## Native test contract

Opt-in Gradle task:
`./gradlew -I docs/10-design/ux128-field-focus-headless.init.gradle :apps:desktopApp:renderUx128FieldFocus --no-daemon`.

`UX128FieldFocusHeadlessEvidence.kt` renders the **actual shared Kotlin/Compose AssignedWorkSection** (no web, fake backend, screen capture or `Robot`) at:
- 430×960 Today; press/release current source card → focused detail; cached read-only; press/release visible Back → Today; select same item again; revoke/swap to empty list.
- 1150×980 existing wide Today + JobBundle in parallel.

The fixture declarations are copied from the existing Android *debug-only* `AssignedWorkEvidenceActivity`, not invented HILTECH customer values or seeded production data. Keep documented that copying test data creates test maintenance cost if DTO contracts change. These are **synthetic Compose renders**, not live Android device/role tests.

**Native Desktop Compose test outcome — PASS on exact tested source SHA `19f5c2ff7a0fb2bb6153008e5600441d88808a59`.** The isolated Windows QA clone used JDK17 only for this command and ran `./gradlew.bat -I docs/10-design/ux128-field-focus-headless.init.gradle :apps:desktopApp:renderUx128FieldFocus --no-daemon`. **BUILD SUCCESSFUL in 44s; 15 tasks (4 executed, 11 up-to-date); process exit 0.** Harness log: `UX128_FIELD_FOCUS_PASS selected_source=PASS back_reselect=PASS cached_read_only_source=PASS revoked_hides_detail=INSPECT_IMAGE wide_layout=INSPECT_IMAGE no_robot=true synthetic_fixture=true`. Pointer Press/Release on Today's existing WorkOrder card invokes original onSelectWork with the exact ID; Press/Release on the RTL visible Back action returns to Today; the same WorkOrder can be selected again. Source state change to `revoked` removes the detail. This is a **headless Desktop Compose pointer proof** using synthetic Android debug fixtures, **NOT actual Android device/operator/production authorization acceptance**.

Prior attempts failed inside the isolated test harness: (1) missing Desktop test Material3 import, (2) missing Kotlin delegate imports, (3) a test pointer `Press` on the RTL Back button but `Release` outside it, which canceled the click. After correcting **both** Back coordinates to the same button point, the test passed. These failures were **not claimed as app-level navigation failures**.

Native output PNGs live only in local authorized Windows QA folder `D:/02_QA-and-Builds/HILTECH/UX-128/field-focus-qa/apps/desktopApp/build/reports/ux128-field-focus/`:
- `ux128-field-00-today-phone.png`
- `ux128-field-01-focused-phone.png`
- `ux128-field-02-cached-phone.png`
- `ux128-field-03-return-phone.png`
- `ux128-field-04-revoked-phone.png`
- `ux128-field-05-wide-preserved.png`

Visually inspected Today, focused JobBundle, cached/read-only, return to Today and revoked/empty list captures. The wide capture was generated by the harness but its visual correctness remains **to be reviewed**, not assumed solely from a successful task. PNG files were **not uploaded to GitHub**.

## Threats, research questions and release gates

- Full `HiltechShell` outer scroll may retain an old scroll offset when switching focus. Android system Back and accessibility focus also need an integrated navigation contract. Current visible in-component Back is only an experiment.
- `focusScopeKey` is caller-provided; actual session/org/Work scope must be guaranteed by Engineering. Do not take a display role or an object code as sufficient.
- Real data can be revoked, reassigned or version-superseded after read; UI must fail closed and show real authoritative status. The internal focus check is in addition to, not instead of, API checks.
- No unauthorized completion or material mutation, no offline execution enablement, no changing `submit` vs reviewer `accept`, no unapproved engineering measurement thresholds.
- Need 200% type, compact landscape, TalkBack, RTL/LTR identifiers, one-handed reachability, selected list restoration and actual performance/task timing on approved device.
- PM source route remains a separate dependency: `ProjectCommandCenterSection` has real reviewer actions and raw ProjectSite/WorkPackage references, but no source-authorized open/detail/back contract. A fake clickable UUID or a panel pretending to load source data would not be acceptable. Block PM route implementation until source-entitled object selection is defined, then compare context inspector vs review-first screen on real read models.
- Current Phase6 feature flags and production cutover remain unchanged. Any integration follows owner + Engineering review, exact-head checks, real device gate #116 and explicit rollout authorization.

## Result/evidence record

To be updated after actual test with:
- source SHA and Gradle exit;
- screenshot names and inspection (including failed/blank);
- click/back and stale/cached/revoked outcomes;
- QA lane impact and PR/Issue links.

Never label this as final Industrial. Precise. Alive. visual treatment; it is task/navigation UX evidence only.

## Critical visual review and unresolved acceptance

1. **Source-related defect visible in Today:** `السياق جاهز للعرض — التنفيذ يبدأ في Phase 6` is obsolete on a repository where Phase6 code already exists. Replace with feature/authority-driven human copy, not a blanket statement that production execution is live.
2. **Cached/read-only state is present and meaningful.** Preserve the explicit server freshness warning and disabling of server actions. The focused layout must still explain local queued intents vs server-confirmed state after Phase6 flags are configured.
3. **Focused details are much more reachable** than the current source baseline's below-list detail on compact; however this is **a structural hypothesis tested with only one WorkOrder**, not quantified operator improvement. Field details themselves still have dense technical status strings, policy codes and long vertical content.
4. **No Android system Back** handling in the experimental presentation; visible in-component Back is proven only through headless Desktop Compose pointer. No screen reader, 200% text, landscape, 2+ WorkOrders, real assignment revocation timing, or server-authorized route proof.
5. Source-ownership coordination: actual Android and Windows applications continue default `BASELINE`. Do not merge or enable this presentation until Engineering/owner review, role/scope key validation, native Android/Windows and real-device operator gates.

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


## Pass 02 — twelve assignments, scroll-to-focus and back-position recovery (2026-11-22)

**Tested exact code SHA:** `005fa08a389ea580d3f2391d12c46a1175869b6a`, in isolated authorized Windows QA clone, JDK17 scoped to task. Reproduction: `./gradlew.bat -I docs/10-design/ux128-field-focus-headless.init.gradle :apps:desktopApp:renderUx128FieldFocus --no-daemon`.

**Result: BUILD SUCCESSFUL (45s; 15 tasks, 4 executed, 11 up-to-date), exit 0.** Console includes:
- `UX128_FIELD_MULTI_PASS source_list=12 selected_current=PASS focused_top=PASS return_scroll=PASS`
- `UX128_FIELD_FOCUS_PASS selected_source=PASS back_reselect=PASS cached_read_only_source=PASS revoked_hides_detail=INSPECT_IMAGE wide_layout=INSPECT_IMAGE no_robot=true synthetic_fixture=true`

The actual Kotlin/Compose `AssignedWorkSection` (not a parallel UI) was tested with **12 distinct WorkOrder IDs** derived from the existing Android debug test fixture. The headless `430×720` scene renders the actual source Today list, scrolls the host's existing `ScrollState` **550px** to a mid-list assignment, clicks that card using native Compose pointer Press/Release, opens matching source `JobBundleDetail`, returns with the visible RTL Back button, and asserts restoration to that same 550px position. The selected screenshot identifies `WO-QA-05` as the task opened; the exact ID is verified against the currently assigned items. The test then removes that source assignment and captures the remaining list, with no old JobBundle on screen.

**Real UX defect uncovered and repaired:** In an earlier run the focused details still inherited a `127px` scroll offset when the former long Today list had been replaced. The JobBundle heading and visible Back button were partly offscreen. The new *opt-in* experiment accepts `focusHostScroll: ScrollState?` from its owner, stores the original Today scroll offset per `focusScopeKey`, waits one Compose frame **after layout** before scrolling focused detail to zero, and on Back waits for list layout before restoring the saved position. It does not add a nested vertical scroll (the parent remains sole scroll owner). Shipping entrypoints do not pass this optional parameter and are still `BASELINE`.

**Evidence integrity gate:** The first 12-item screenshot after programmatic scrolling displayed the stale top-list pixels even though the ScrollState had changed. The final harness now advances actual Compose frames before capturing and rejects an identical image pair for top vs scrolled list. Latest inspected screenshots show the real mid-list with `WO-QA-05`, focused detail at top, and returned mid-list. Local QA PNGs `ux128-field-06-multiple-top.png` through `ux128-field-10-multiple-revoked.png` are **not committed to GitHub**.

Also inspected the `1150×980` wide-mode PNG from the same fixture, confirming the original side-by-side Today + selected JobBundle remains visible. This is a **synthetic Desktop Compose verification**, not a real Windows app account, Android runtime, or operator acceptance.

### Failure and boundary record
- A 12-item run initially missed a card because a newly composed startup scroll reset occurred after the synthetic scroll; harness was fixed to settle Compose and assert actual pre-click offset.
- Next run *correctly* caught the real offset defect (`127px` rather than expected `0`), leading to the post-layout scroll fix.
- The final tested source head passed both exact-position and screenshot-difference checks. No earlier failures were mislabeled as successful.
- Preserving a *pixel* offset is appropriate only while the list order/height remains materially stable. When assignments are reordered or removed, a future robust route should anchor to a WorkOrder ID (or a stable list item key) rather than claiming identical positional meaning. This limitation is explicit.
- Actual `HiltechShell` parent-scroll integration, Android system Back, role-scoped source read, 200% text, accessibility focus, and offline/revocation from server are **OPEN**. No claim that source authority is enforced by a simple local list-membership check.
- PM Project/Site/WorkOrder drill and Inbox delivery remain separate UX work; no fake source routes or providers introduced.

**Pass02 closure:** test of multi-WorkOrder selection + parent-scroll return on synthetic source fixtures PASSED. UX #128 as a whole remains OPEN. No visual system or production integration approved.

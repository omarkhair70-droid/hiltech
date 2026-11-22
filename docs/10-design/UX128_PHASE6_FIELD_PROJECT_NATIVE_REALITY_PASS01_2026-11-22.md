# UX-128 — Phase 6 Field + Phase 4 Project: Native UI Reality Pass 01

Date: 2026-11-22
Lane: Chat 02 Product, Research & Design
Source pin: main **cfad5413b4d2543a8b288a29a36fd735889b584d**
Review-only branch: `ux/128-phase6-pm-field-audit-20261122` (forked from that exact main)
Status: **SOURCE-OBSERVED / ROLE-ACCEPTANCE OPEN**. Evidence captures/tests added to this report when completed.

## Why the earlier warehouse-only navigation comparison must NOT be merged blindly

The earlier UX exploration in Draft PR #134 starts at the older `c30fb707...` base. Engineering has since merged Phase 6 slices through S06 to `main`. The safe research path is therefore a fresh read-only/current-source checkpoint. Do not rebase, merge or overwrite Phase6/Phase7 branches, and do not claim that an old two-tab warehouse synthetic preview validates a production navigation for project, field or owner roles.

The critical new reality: Field already implements an adaptive **Today + JobBundle list/detail** in source `AssignedWorkSection.kt`; desktop >=760dp uses side-by-side panes. A generic global shell that blindly wraps or nests this screen could duplicate navigation, obscure the JobBundle or create an extra scroll. Keep its existing source contracts, assignment context and offline fail-closed behavior.

## Exact source facts (not extrapolated from a screenshot)

### Technician / engineer field `AssignedWorkSection.kt`
- Owns `HiltechFieldState`: `today`, `selectedWorkOrderId`, `selectedBundle`, cached timestamps, locally queued execution intent, local Evidence, personal issued stock, material intents and engineering tests.
- The **wide** layout uses `BoxWithConstraints` with cutoff **760dp**: a `TodayColumn` constrained to 320–460dp beside `JobBundleDetail` when selected.
- The **compact** layout currently emits `TodayColumn` and then `JobBundleDetail` below it in one Column. This is a **source-demonstrable focus/reachability hypothesis**, not measured real-user effort: after selecting a job the detail may land below today's list instead of becoming a focused destination.
- Today source has loading, empty, failure and explicitly cached/not-server-current warning. Bundle content explicitly says cached copies are read-only; `FieldExecutionControls` is disabled when cached. Never remove or soften these safeguards to make a smoother screen.
- Visible Today copy mixes Arabic with raw `lifecycleState`, `readinessState`, `assignment.targetType`, `Waiting On`. The `currentlyActionable` label still says “السياق جاهز للعرض — التنفيذ يبدأ في Phase 6” in current source, despite Phase6 execution code now being present. Audit for capability-flag-dependent copy before changing.
- The JobBundle exposes project/site/access instructions, WorkOrder version, authoritative completion gate, locally queued submission status and a confirmation step distinguishing submission from approval. Structured engineering-test and Work-document features remain feature-flag OFF by default.
- Existing on-select callback `onSelectWork(workOrderId)` binds selection to source ID. Do not replace this with local selected-row authority or allow a cached bundle to issue server commands.

### PM / project review `ProjectCommandCenterSection.kt`
- Existing source `ProjectCommandCenterDto`, `ProjectSummaryDto`, and `ProjectCommandCenterUiAction` are consumed by one vertically arranged Compose Column.
- It truthfully distinguishes `progressPercent = null` from measured progress and preserves project/review version in the command calls.
- Its health signals print source codes/IDs inline; a user can see “Waiting On” but no dedicated focus/review/detail navigation is provided by this component.
- Explicit `Drill references: N ProjectSite · N WorkPackage` then raw `PROJECT_SITE/UUID` / `WORK_PACKAGE/UUID` text: a **P1 navigation gap** relative to the desired Project → Site → WorkOrder → resource/evidence → return. The component's `onAction` callback handles review/hold commands, not opening a ProjectSite object. Do not present raw text as a working drill.
- `PutOnHold`, `Resume`, `Accept`, `RequestRework` rely on source baseVersion and review authority; changing the visual wrapper must not bypass command validation or manufacture permissive controls.

### Phase 6 source/rollout reality
- Engineering has added source-controlled S04 engineering tests and approved Work document revisions, S05 completion submission and S06 durable recovery. Relevant contracts: `docs/13-delivery/phase6/10_...`, `12_SLICE04D_...`, `13_SLICE05_...`, `14_SLICE06_...`.
- **Submission is not reviewer acceptance.** A cached JobBundle cannot authorize work mutation. No new accepted-work state may be inferred from local queued Evidence.
- Document current revision = highest *released/approved* revision; a newer pending revision must not silently displace the older approved one. UI should show authoritative revision, source/age and approval ambiguity.
- Flags still default OFF: `HILTECH_FIELD_ENGINEERING_TESTS_ENABLED`, `HILTECH_FIELD_WORK_DOCUMENTS_ENABLED`, `HILTECH_FIELD_COMPLETION_ENABLED`; operator/Windows supervisor/device acceptance and production rollout are NOT verified or authorized by these implementation docs.
- Latest observed `main` Bootstrap Actions for `cfad5413...` was queued at audit time. The older `c30fb707...` run passed but **cannot certify current main UI**.

## Governance contradiction observed (do not silently edit Engineering ownership)

`AGENTS.md` still describes “Current execution Phase 4 Slice 01”, while `docs/00-program/CURRENT_PROGRAM_STATUS.md` is updated 2026-09-21 and says Phase5 planning next. Newest git source and Phase6 implementation docs show Phase6 slices integrated and Phase7 planning opened. This is **stale status text**, not proof that branches or tests are old. Ownership should update the canonical program pointers; Chat02 can flag drift in Issue #128, but should not overwrite engineering status without coordination.

## Candidate comparison — based on actual source contracts, not mock dashboard

| Role/context | Current source baseline | Hypothesis A (context continuity) | Hypothesis B (task focus) | Risk to reject |
| --- | --- | --- | --- | --- |
| Technician compact (430px) | Today list, selected JobBundle *below* it | Today + selected context with visible return path | Selecting a job makes JobBundle the main visible destination; a clear back to Today retains list/selection | Hiding available work, lost selection, stale/cached work becoming actionable |
| Technician wide (>=760dp) | Today + JobBundle already side by side | Preserve two-pane list/detail and expose source context | Avoid adding redundant global sidebars; focus detail only if spatially warranted | Narrow center, double navigation, field controls clipped |
| PM desktop | Source Command Center long column, raw object IDs and unlinked drill references | Project selected context, side list and action-aware detail inspector | “Needs Review / Waiting On” focused action surface, contextual object route | Losing source version, permitting unauthorized review, hiding blocker explanations |
| PM phone | Long column adapted by outer scroll only | Project/Site/WorkOrder breadcrumb with one selected detail | Action-first review then direct selected WorkOrder, source return | Full desktop table crammed onto phone, lost source/site context |
| Engineer (Phase6) | Existing field work/tests/evidence schema from source, flag gated | WorkOrder → actual approved revision/test → verified evidence | Exact task/revision focus with read-only offline mode | A draft drawing shown as current; user-supplied PASS mistaken for canonical PASS |

**Critical design correction:** there is probably NOT one universal “Context-first vs Workbench-first” winner. Android field can use focused selection while preserving wide list/detail; a PM may need multi-object context. The shared infrastructure can have one route identity/authority contract without forcing identical chrome on every role.

## Native acceptance experiment to execute on this clean main

1. **Capture current project UI** from current checked source. The legacy `:apps:desktopApp:renderPhase4Slice05Evidence` produced a `HILTECH_PHASE4_SLICE05_RENDER_PASS` marker, but visual inspection showed its `java.awt.Robot` screenshots captured the **wrong foreground application**, not HILTECH; **reject these PNGs as invalid visual evidence**, regardless of Gradle success. The QA-only `UX128Phase6ProjectHeadlessEvidence.kt` and opt-in `ux128-phase6-pm-headless.init.gradle` now render the actual `ProjectCommandCenterSection` directly via `ImageComposeScene`, reusing the legacy test synthetic source DTO without Robot/window focus. Final results recorded after direct-head execution and image inspection.
2. **Field source audit from exact main**, preferably native Android instrumentation or official current-head CI artifacts if available. Never treat Phase4 mobile fixture or plain code inspection as actual Phase6 Android operator test.
3. Use same source fixtures to compare **(A) contextual project inspector vs (B) action-focused review**, **(A) compact Today → detail below list vs (B) selected JobBundle first** as *candidate* navigation designs before coding UI changes. First estimate task-step and scroll cost, then build a safe opt-in Compose experiment only after role/source fixture integration is clear.
4. Validate: role revocation, cached vs current, current approved revision, queued vs confirmed, feature flag OFF/ON, no false “accepted”, physical-device UX/RTL/large text/Back and Windows keyboard.
5. **No touched Phase6 domain file, forced app navigation, production flag activation, irreversible command, merge or release** in this current audit.

## Status register

- UX-00 Phase6 source audit: **PARTIAL / SOURCE INSPECTED**.
- UX-01 reference research: previously documented six-track literature pass, not finished hands-on product research.
- UX-02 IA: **ACTUAL ROLE SOURCE DEPENDENCIES MAPPED; UNAPPROVED**.
- UX-03 Nav: **WAREHOUSE STUDY PRE-Phase6 EXISTS; FIELD+PM USER JOURNEYS NOT ACCEPTED**.
- UX-05 Operational review: **FIELD/PM P1 GAPS IDENTIFIED**.
- UX-07 QA: native Phase4 Project Compose **source-fixture headless capture PASS**, current Phase6 Android live UI acceptance **NOT VERIFIED**. Findings and limitations below.
- UX-08 post-Phase13 product-wide polish remains dependent on functional source and human approval.

## QA evidence integrity correction: foreground Robot screenshots invalidated

**Legacy attempt (current main `cfad5413...`)**:
- Command `./gradlew.bat :apps:desktopApp:renderPhase4Slice05Evidence --no-daemon` opened the older native `Window` test harness on an authorized Windows computer. The test printed `HILTECH_PHASE4_SLICE05_RENDER_PASS`.
- **Visual inspection of its PNGs proved the capture was NOT the HILTECH window**. Another foreground application covered the requested rectangle; `Robot.createScreenCapture(bounds)` is not window-owned. Do **not** circulate, commit or use these PNGs as product evidence. They may contain unrelated desktop contents and are excluded from the UX artifact register.
- A console PASS can therefore assert state-fixture test flow without proving that actual product pixels were captured. This is a **QA P1 false-positive risk** for native visual regression claims, not a HILTECH product UI defect.
- Review-only replacement: test-only `UX128Phase6ProjectHeadlessEvidence.kt` renders actual source `ProjectCommandCenterSection` offscreen through `ImageComposeScene.render()`, using the original private Phase4 test fixture **only via JVM test-runtime reflection**. No new production service or invented PM data, no desktop foreground capture, no external image retrieval.
- Reproduce using the opt-in Gradle init script: `./gradlew -I docs/10-design/ux128-phase6-pm-headless.init.gradle :apps:desktopApp:renderUx128Phase6Pm --no-daemon`. Verify returned exit status and manually inspect HILTECH-only images. This test code is deliberately non-shipping.


## Visual evidence after correcting false-positive screenshot capture

**Tested UX QA code SHA:** `21af8ad8dc1bb0d6c9d3dc561cea9deed488e7c2` (source main at branch fork `cfad5413...`).  
Reproduction: `./gradlew -I docs/10-design/ux128-phase6-pm-headless.init.gradle :apps:desktopApp:renderUx128Phase6Pm --no-daemon` using isolated Windows QA working clone and JDK17 scoped to the command.  
**Build: SUCCESSFUL in 2m32s, 19 tasks (4 executed, 15 up-to-date).** The runner printed `UX128_PHASE6_PM_HEADLESS_COMPOSE_RENDER_PASS exact_fixture=Phase4Slice05 synthetic=true screenshots=12 no_robot=true`. It renders actual `ProjectCommandCenterSection` directly via offscreen `ImageComposeScene`, not `Robot` and not a web mockup.

**Twelve actual Compose PNGs**, generated at `D:/02_QA-and-Builds/HILTECH/UX-128/phase6-main-audit/apps/desktopApp/build/reports/ux128-phase6-project/`. Three source-legacy Phase4 test fixtures `review-blocked`, `active-health`, `on-hold`; each captured at 1220x1180 Windows and 430x960 compact width, both initial and +650px scroll states. Manually inspected at least `ux128-pm-review-blocked-windows-top.png`, `ux128-pm-review-blocked-phone-top.png`, `ux128-pm-active-health-windows-top.png`, `ux128-pm-active-health-phone-scrolled.png`. **The screenshots are local-only; PNG binaries are NOT uploaded to GitHub and should not be presented as a shareable link.** Android app/emulator and user/operator acceptance not involved.

### What the actual images expose — source and role impact

| Evidence / visible defect | Whose work | Why it matters | Priority and bounded experiment |
| --- | --- | --- | --- |
| Top-level critical project state shares a single text stream with `Accepted weight 3.000000 / 5.000000`, `Project version 11`, `source WORK_ORDER/UUID`, `REWORK_BACKLOG` and `WAITING ON` raw reasons | PM, owner | Source provenance is valid but the **decision** and responsibility do not stand out; operational meaning requires decoding internal codes | P1 — issue/action hierarchy above one-click source inspector. Keep exact provenance one action away, no loss of accuracy |
| Windows at 1220px shows one right-aligned continuous column with much unused surrounding space; there is no true visible list-detail selection or object side inspector | PM, engineer, owner-review | Large screen gives no advantage for simultaneous project/work/site comparison or seeing review causes alongside authoritative action | P1 — compare source-centric two-pane context vs action-first review without cloning ERP dashboard |
| Compact 430px also exposes raw `PROJECT_SITE/UUID` and `WORK_PACKAGE/UUID` strings, but no corresponding context route; repeated long mixed Arabic/English strings wrap across lines before the real decision controls | PM in field, mobile supervisor | The hierarchy hides the correct object to open; raw IDs become visual obstacles; mixed RTL/LTR legibility needs explicit treatment | P1 — project->site/work contextual drill with selected filter/back + birectional isolation; prove real accessible focus order |
| Blocked review state disables `قبول` but keeps `طلب إعادة عمل` visible; when READY, `قبول` becomes enabled | Reviewer | **Existing domain guard is valuable.** A redesigned UI must keep unavailable actions visibly explained and never infer eligibility from visual labels | P0 preservation — same source `beforeAccept` and version guard in both candidates; exact-head click/disabled/denied tests |
| `CRITICAL`, raw `Waiting On` codes and `source WORK_ORDER/UUID` run across wide and compact | All Arabic-first operators | Human-first phrase and actionable reason are missing; user risks mistaking generic health signal for direct permission to act | P1 — primary Arabic reason + source status, with expandable source/code for audit, never invented repair/action |

### Candidate decision — NO design freeze

- For PM/owner on Windows, test **context + selected project/work inspector** against **attention/review-first** using the same actual DTO, preserving exact Work version and callback. Both candidates must allow reaching real ProjectSite/WorkOrder source (requires authorized route contract; a raw UUID label is not a route).
- For PM on Android compact, show either **one selected review WorkOrder** or **one project context level** at a time with back; never stack two full desktop panes into a long phone scroll.
- For technicians, do **not** overwrite existing wide Today+JobBundle panes. Only a compact focus/return presentation experiment with source assignment and cached restrictions should follow coordination with Engineering.

### Accessibility and performance acceptance gates

- Compare first-correct-action taps and scrolling from entry to source review decision; test with 2+ active WorkOrders, blocked and READY; measure rather than invent numbers.
- At 430/600/760/1220px and 200% fonts: no clipped actions or hidden reason, correct RTL reading of mixed IDs/UTC timestamps, source revision always inspectable.
- On Windows: Tab / Enter / Escape and selection-to-inspector focus, and Narrator; on Android: system Back, TalkBack traversal and one-handed touch targets. Official references: https://developer.android.com/develop/adaptive-apps/guides/list-detail , https://developer.android.com/develop/ui/compose/accessibility/traversal and https://learn.microsoft.com/en-us/windows/apps/design/input/keyboard-interactions .
- Source denied, missing data, cached vs current, 401/403/409 and in-flight pending review: no fake green “approved” or disabled-action misrepresentation.
- No PM/technician operator evidence collected in this QA run, and no production preview has been enabled.

**Pass01 closure statement:** an auditable, actual source Compose visual baseline is now established for Phase4 PM components at the current-main source pin, with a corrected screenshot pipeline and concrete UX priority findings. **This does NOT close UX-00, UX-02 or UX-03 globally, and does not assert Phase6 native Android operational acceptance.**

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

1. **Capture current project UI** with existing `:apps:desktopApp:renderPhase4Slice05Evidence` and Project/Site fixtures on exact checked source. Record JPG/PNG as local synthetic evidence with SHA, screen size, viewport and whether an actual app or headless Compose. This proves visual source baseline, not role acceptance.
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
- UX-07 QA: native main Project evidence **pending command result**, Android live UI acceptance not verified.
- UX-08 post-Phase13 product-wide polish remains dependent on functional source and human approval.

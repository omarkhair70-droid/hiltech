# HILTECH Product Quality — Evidence Baseline & Experiment 01
Date: 2026-11-22
Lane: Chat 02 / Product, Research & Design
Status: RESEARCH + EXPERIMENT CONTRACT ONLY. No native variant implemented, tested, approved, merged or deployed by this document.

## Safety / source version
- GitHub main observed at c30fb707186cc0bbb38ba95b924737bd1bc56997 (Phase 6 S04B pending-only merge).
- This independent UX branch starts from that main. Do not edit the active Phase 6 S04 stacked branches or claim that this older base includes open S04 PRs.
- Control-plane PR #131 remains unmerged as observed; read its ACTIVE_EXECUTION_BRIEF.md for the newer operational handoff. Issue #128 is the quality tracker, #116 the physical field-device gate and #117 S04 engineering scope.
- Local D:/01_Workspaces/HILTECH/Internal-OS on the authorized Windows PC was observed at historical 534e26a (2026-09-21). It is not a valid current-build source. An isolated QA directory is used for artifact inspection, not this working tree.
- Real technician, warehouse and supervisor signoff, real field devices and production rollout remain NOT VERIFIED.

## Audit evidence: actual Compose, synthetic data (NOT operator/product release acceptance)
E1: GitHub Actions run https://github.com/omarkhair70-droid/hiltech/actions/runs/44794707208
- Artifact hiltech-phase5-warehouse-synthetic-render: warehouse-command-center.png, stock-inventory.png, warehouse-quick-scan.png (Windows/Compose synthetic rendering).
- Artifact hiltech-phase5-asset-android-synthetic: android-quick-scan-tag.png, android-receiving-discrepancy.png, android-stocktake-variance.png (Android/Compose synthetic emulator rendering).
- Verified by visual inspection of those individual captures from the run downloaded into a separate local QA directory.
E2: GitHub Actions run https://github.com/omarkhair70-droid/hiltech/actions/runs/44897330438
- Artifact hiltech-phase6-s03-native-evidence: 00-callback-context-restored.png, 01-private-evidence-queued.png, 03-corrupt-evidence-protected.png.
- These prove TEST-HARNESS state outcomes in native emulator screenshots, not the production technician Today/JobBundle/Evidence UX or physical camera/operator acceptance.
E3: Code anchor: shared/core/src/commonMain/kotlin/com/hiltech/shared/core/HiltechShell.kt on inspected main, signed-in content in one outer verticalScroll and conditional sections (People, AssignedWork, WarehouseSetup, ProjectControl etc.).
E4: docs/10-design/DESIGN_THESIS.md, DESIGN_SYSTEM.md, INFORMATION_ARCHITECTURE.md, STATUS_SEMANTICS.md, ACCESSIBILITY.md (all explicitly unfrozen for visual/system completion).
E5: PR #131 unmerged docs/00-program/ACTIVE_EXECUTION_BRIEF.md on branch program/session-control-and-execution-brief-20261122; docs/02-people personas; Issue #128 baseline.

## Observed defect ledger (not speculative UI claims)
| ID | Evidence | Observable gap | Role / consequence | Priority |
| --- | --- | --- | --- | --- |
| UX-01 | E1 Android quick-scan-tag; E3 shell | Identity and security/debug prose precedes the scan task; long stacked shell | Storekeeper/field scanner: more visual search before next action | P1 |
| UX-02 | E1 Android quick-scan-tag and stocktake-variance | Mixed Arabic/English, raw canonical/AssetTag/synthetic/UUID/source terminology dominates or fragments reading | Storekeeper/technician: slower identification, potential bidi interpretation errors | P1 |
| UX-03 | E1 Windows warehouse-command-center | One uninterrupted vertical text stack, repeated generic detail buttons, no consistently scannable action-vs-reference division | Storekeeper/manager: harder triage of overdue return, calibration, shortage, reconciliation | P1 |
| UX-04 | E1 Windows stock-inventory | Technical object/stock ledger metadata visually competes with business decisions; long undifferentiated hierarchy | Storekeeper: harder compare stock and open the correct movement | P1 |
| UX-05 | E2 Phase6 native Evidence | State proofs display test assertion messages in a neutral harness, not a human-facing operational recovery path | Technician/supervisor: no user-facing success/repair acceptance from these artifacts | P1 evidence gap |
| UX-06 | E1 Android receiving-discrepancy | Full raw receipt/line UUIDs and English codes before discrepancy action | Receiving operator: essential discrepancy entry pushed below large metadata blocks | P1 |
| UX-07 | E3 design & source | Shared semantic token implementation, adaptive shell, keyboard/focus/RTL and native performance are not established as complete by reviewed evidence | All roles: consistency/accessibility and role relevance not yet demonstrated | P2 verification |

Do NOT infer the frequency/severity in real operation from synthetic renders alone. These are inspection-priority rankings, pending usability evidence.

## Role-to-task design impact (scope truth)
- Mohamed / owner: exception decisions and cross-domain consequence, not raw event-feed or a dashboard operator. Full Company Brain is Phase 13: contract now, avoid implementing future backend.
- Management / People / finance admin: pending approvals, sensitive document/action boundaries, exact current version and who owns next action. Finance/Payroll capabilities planned in their phases, not fabricated.
- Project manager: project -> site -> work -> blocker/resource/evidence; contextual drill-through and reliable source state; no duplicate read model.
- Engineer: approved drawing revision, Test/OTDR/Fluke result tied to correct Work/Link/Asset and review authority. S04 work in progress: do not modify.
- Warehouse operator: now/overdue/reserved/blocked -> scan -> physical identity / current location / custody -> authorized review or movement, with source ledger provenance inspectable.
- Technician: Today -> assigned job and site -> offline action/evidence -> queued vs server-confirmed vs conflict. Leave Phase6 S04 active code untouched.

## Original thesis critique
Industrial must NOT mean decorative machinery, black HUD, gridlines, or fake live telemetry. Precise must NOT mean exposing implementation codes as human copy. Alive must NOT mean indiscriminate motion: an honest queued->server-confirmed change, a real custody transition, or authoritative conflict is meaningful motion. 'Calm when dense' is not achieved by merely increasing whitespace; preserve fast side-by-side comparison on desktop.

## Chosen bounded Experiment 01 — ROLE-FIRST WORK SURFACE: WAREHOUSE TODAY -> SCAN -> OBJECT
Purpose: address UX-01, UX-02, UX-03, UX-04 and UX-06 using Phase 5 merged, read-only real UI data contracts and synthetic evidence. Avoid active Phase6 Field S04 code. This is a native Compose design experiment, NOT a separate web dashboard.

### Two candidates to compare on the same fixture/state
A. Action-first, contextual: Mobile opens with warehouse role/location and a primary 'مسح كود' action, followed by a short attention queue (due return / discrepancy / calibration) and recent objects. Desktop opens an actionable queue with count + reason + responsible party, list-detail workspace and secondary history; inspector shows source provenance only on demand.
B. Object-first, contextual: Mobile starts with current warehouse object/location and recent asset continuity, then contextual scan; Desktop starts from object/location groups with compact exception filters and persistent inspector. Faster for known-object retrieval, potentially slower for urgent work.

Neither is approved. Both must preserve server-derived authorization and object source truth, must not invent color/status counts, and must preserve the existing commands/callbacks rather than rewriting stock movement or custody.

### Interaction and semantic constraints
- User-facing first line: task/object/site in human Arabic. Diagnostic code, UUID, source revision and audit facts are secondary but one interaction away.
- Distinguish lifecycle, risk, action-required and sync authority. LOCAL / QUEUED / SYNCING / CONFIRMED / CONFLICT / DENIED must never collapse into a single 'done' state.
- First viewport prioritizes authorized next action and blocking reason. If disabled, explain source permission/version/offline reason without suggesting a possible action.
- Android: one handed scanning, manual code fallback, camera denial, revoked authorization, error/retry, font scaling, TalkBack and 48dp-or-more target hypothesis for field controls.
- Windows: list -> detail with keyboard, focus, selection, context navigation, correct RTL START/END alignment; no empty generic 'open details' repetition.
- Preserve exact numeric/UOM, asset id, source revisions, audit data in an accessible detail inspector; never round stock silently.
- Keep light/dark/high-contrast and motion opt-out as required evaluation states. Typeface IBM Plex Sans Arabic remains a candidate, NOT frozen.

### Measurable comparison, not aesthetic voting
1. Time and taps from authenticated role home to valid Quick Scan and specific Asset Passport, with identical seeded asset.
2. Time to locate one overdue tool, one blocked calibration and one received discrepancy from Warehouse Today.
3. Accuracy of correctly reporting authoritative state and next responsible party; no 'local saved' mistaken for server accepted.
4. Keyboard-only desktop completion, TalkBack reading order, RTL/LTR mixed IDs, 200% mobile type scaling, tablet adaptive layout.
5. Cold startup, recomposition/jank and data/scroll cost before and after, on exact head; record metrics rather than assume.
6. Recovery: missing QR, revoked role, stale version, empty, loading, failed, offline/cache, no-provider, and partial results.
7. Side-by-side screenshots from ACTUAL native Compose same fixtures/states at phone, tablet and Windows widths, with filenames + run SHA + CI links.

Decision criterion: reject a candidate if it hides provenance, worsens common task performance, breaks authorization/sync honesty, harms accessibility, or turns full text into generic cards. Starting hypotheses (not measured targets): first useful action visible without scrolling and a meaningful improvement in median task completion time in a small blinded operator test.

## External reference pattern matrix (studied, not copied)
- Android official adaptive list/detail and navigation: context pane changes with available width; risk: generic shell can drown a scan-first workflow. https://developer.android.com/develop/adaptive-apps/guides/list-detail
- Fluent 2 Layout and Windows list/details: visual rhythm, predictable desktop workspace; risk: too much chrome/sidebar for a small company's operations. https://fluent2.microsoft.design/layout ; https://learn.microsoft.com/en-us/windows/uwp/design/controls-and-patterns/master-details
- IBM Carbon data table guidance: dense comparisons, selection, row actions; risk: a blind table on Android and implementation-heavy bulk behavior. https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines
- Hilti ON!Track: physical tag -> known Asset identity with manual identification fallback; risk: RFID/gateway inference not present in HILTECH. https://help.ontrack3.hilti.com/hc/en-us/articles/34399860005137-How-to-add-a-Hilti-asset-in-ON-Track
- Procore field/offline: offline drawings/evidence and human recovery; risk: cache != current authority and workflow complexity must not be imported indiscriminately. https://support.procore.com/procore-mobile-android/user-guide/workflows-android/tutorials/offline-support-for-linear-and-diagram-workflows-android
- WCAG 2.2 + Compose semantics: focus, target spacing, accessible state reading. https://www.w3.org/WAI/WCAG22/quickref/ ; https://developer.android.com/develop/ui/compose/accessibility/semantics

## Immediate next engineering-safe sequence
1. Freeze exact-main screenshot baseline and record evidence hashes/versions; include remaining Phase 5 screens and Phase 6 actual production JobBundle UX when its safe build becomes available. Do not treat Phase6 S03 harness screenshots as product visuals.
2. Implement A and B on THIS independent UX branch in the real shared Compose presentation layer with non-production synthetic fixtures; do not change business contracts/commands/authorization, and do not touch stacked S04.
3. Run exact-head native screenshot tests on Windows and Android; compare same screen states, RTL, accessibility, performance. Add actual captures and measured results to this document.
4. Review with owner/operator, choose or reject. Only then make a narrow reviewed production UX PR, explicitly tracking integration with Phase6 branch movements.
5. Continue functional UX safety per phase while preserving full-product visual, motion and identity finishing pass after Phase13 core is complete.

## Unverified at this checkpoint
- No live HILTECH staging backend, physical Android device or user/operator test was exercised in this UX session.
- No native alternative (A/B) has yet been built, launched, screenshot-tested or accepted.
- The current remote Windows local clone is historical; it is not evidence of current main behavior.
- No deploy, branch merge, CI rerun or production flag change was performed by this document.

# UX-128 — Integrated product and role-experience audit

**Date:** 2026-11-23 (Egypt)
**Working source:** Draft PR #171 `ux/128-phase7-unified-native-20261122`, based on Phase7 integrated source PR #163.
**Scope:** source-backed product/interaction/design only. Engineering owns domain contracts; DevOps owns final merge and runtime.

## What the source review shows

This audit inspected PR diffs/status for UX #134/#142/#144/#152/#154/#156/#171 and current Engineering/DevOps PRs #163/#174/#178/#181/#184, #164/#175/#179/#180, #166/#170/#167/#168/#182.

| Role/area | Actual native source | Current UX decision |
| --- | --- | --- |
| Technician / Android | `AssignedWorkSection`, `HiltechFieldState`, actual Today / JobBundle / evidence / material-use / engineering-test callbacks | Complete real source-safe Today → focused WorkOrder → back/scroll/assignment-change; build upon #144 research but do NOT overwrite latest Engineering file |
| Warehouse / Android + Windows | `WarehousePhysicalOperatorSection`, asset/stock selections, authoritative physical handoff | Apply semantic source status, fast scan/item selection, authorized receipt/exception flow; never recreate stock/asset logic; use #134 evidence as research |
| Procurement & supplier | S01–S06 canonical Engineering #163 plus service procurement S07 #174/#178/#181/#184; previous source-connected UX #156 | Port only proven Buyer/Issuer presentation to the **current** integrated Engineer source; preserve PO receiving, supplier communication, invoice matching, service acceptance and original approval authority |
| Finance / employee self-service | #164 source-verified obligations, #175 expense/advance/imprest draft, #179 preflight; #180 pure future bank-result rules | Share native design language, distinguish DRAFT/REVIEW/UNKNOWN from payable/approved/paid; never show a bank payment control that does not exist |
| Payroll / staff | #166 KMP Compose period, exceptions, run history, own payslips, release sources | Read-only/self-only/reviewer surfaces as domain calls allow; do not broaden employee visibility or imply a bank transfer |
| Sales / tenders | #170 source-based opportunity → BOQ/quote → guarded award, native workbench not yet globally mounted | Test navigation and confidential cost vs customer price separation using actual source guards |
| Handover / service | #167 source-linked handover/support/maintenance Compose drafts not globally mounted | Evidence revisions, missing requirements, visit dates, permissioned triage, no invented warranty/SLA |
| Executive | #168 source-backed restricted read-only Home, not globally mounted | Prefer source freshness, UNAVAILABLE/UNKNOWN/PARTIAL over false zero; open original authorized destination for actions |
| Cross-role product | Design evidence #152, real DS integration #171, host `HiltechShell` | One semantic design system across Windows/Android; keyboard, RTL, scale, focus, density, motion, safe error/empty states; no parallel fake ERP |

## Code executed in UX #171

1. At source code commit `123d8c70bcfbd43fa294dab786352a9d1e7af82a`, semantic Compose `HiltechOperationalDesign` was transferred into the main-combined Engineering source and used in its **real** `SupplierPoCommunicationSection`, preserving server actions. Exact-head shared procurement desktop tests and Windows Kotlin compile were reported `BUILD SUCCESSFUL`.
2. At `3af4e7aaf0f7cb75b042f61992ee24142729d668`, the **actual** `AssignedWorkSection` Today heading/cards got semantic source-backed statuses, offline/cache/error and WorkOrder blocker presentation, on the same Windows/Android Compose code.
3. At `0a7657b18c1991334539664d3cc513a1a3c05c73`, Today + JobBundle rendering in both compact and wide source paths was hardened: a JobBundle is not displayed unless `selectedWorkOrderId`, `bundle.workOrderId`, and **a current Today assignment ID** all match; stale selected data shows a visible explanatory status rather than prior details.
4. At `19a652364c55504f7ae6c3b9bce13630bbf2e44a`, that guard was extracted to a pure Kotlin helper, `fieldSourceAllowsSelectedBundle`.
5. At `2b6eb6a667ba7322b6a66a568df96a4e53bd0d61`, `FieldSourcePresentationTest` gained six unit test methods: matching source, changed selection, assignment removed, null/empty selection, missing Today snapshot and reordered Today.

### Verification distinction

**Pure Kotlin compiler check:** the helper compiled with `kotlinc` and a small local runner executed six representative assertions, all PASS. That is a narrow helper verification, **not** a Gradle/Android/Windows/Compose build.

**Not verified yet:** The six checked-in `kotlin.test` methods have not run in the repository's CI at this SHA; the latest complete Android/Windows build was interrupted by Remote Desktop Commander usage limits after Android SDK setup and Android task processing. GitHub returned no workflow runs for the current branch head at this check. Hence do NOT mark PR #171 globally green or production ready.

**Current branch source base is behind the evolving integration PR #163.** The latest seven #163 commits changed server/docs workflow/receiving boundaries; no UX branch's four main presentation-file paths overlap those changes, but integration owner must still synchronize source/CI before merge.

### Next exact work, no Phase7 duplication

1. **Build gate now:** current PR #171 exact SHA: `./gradlew :shared:core:desktopTest --tests 'com.hiltech.shared.core.FieldSourcePresentationTest' :apps:desktopApp:compileKotlin :apps:androidApp:assembleDebug` with Android SDK installed, then report job/runs and source SHA. Fix any genuine app compile finding; do not claim success from the standalone Kotlin helper alone.
2. **Technician focused route:** adapt **tested opt-in** PR #144 research into current signed-in Android host with identity/org/work-order scope and the host's *one* scroll owner; ensure source removal/regrant does not show stale detail, Back returns to same ID after reorder, offline reads never enable commands. Preserve `FieldExecutionControls`, Evidence, FieldMaterial, Engineering Test and assigned asset callbacks.
3. **Warehouse operator:** lift real operator tasks from current `WarehousePhysicalOperatorSection` and PR #134, then test scan → authorized item → action → confirmed receipt/history and no authority on stale scans.
4. **Full product language:** reconcile #152 candidates with the actual semantic DS; test real state grammar across technician/warehouse/finance/payroll/sales/service/executive. Avoid only making nonfunctional visual galleries.
5. **Cross-lane native mounts:** as Phase8–11/13 API contracts stabilize, pair one product experience owner with each module; leave domain, Flyway, ApprovalEngine, Main.kt/MainActivity and DevOps shared entrypoints to their respective integration owners. Non-stock Phase7 is now its own service-engineering lane.

## Merge / deployment ownership

At this review PRs #171 and #163 remain **Draft/unmerged**, and Phase7 service #181/#184, Phase8, Payroll, Commercial, Service and Executive remain separate Draft branches. DevOps #182 identifies conflicting *provisional* Flyway V0061+ numbers between service Phase7 and Finance; UX must never resolve SQL versioning by copying source code from an older branch.

Only Engineering and DevOps can promote the reviewed exact combined SHA to staging. Source guard UI code, screenshot evidence and Android APK compilation do **not** imply live identity, device usability, company financial authorization, supplier dispatch, payroll payment or production acceptance.

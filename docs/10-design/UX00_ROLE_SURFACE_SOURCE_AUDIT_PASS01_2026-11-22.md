# UX-00 — Native Role/Surface Source Audit, Pass 01

**Date:** 2026-11-22  
**Status:** SOURCE-VERIFIED PASS 01 / NOT RUNTIME OR OPERATOR ACCEPTANCE  
**Track:** Chat 02 — Product, Research & Design; Issue #128; Draft PR #134  
**Code baseline:** `main` `c30fb707186cc0bbb38ba95b924737bd1bc56997` when inspected; independent UX candidate branch may be ahead.  
**CI:** main Bootstrap Phase 0 GitHub Actions run [44948725551](https://github.com/omarkhair70-droid/hiltech/actions/runs/44948725551) `success`. **Build/Bootstrap success is not native UI acceptance.**

## Inspection method and evidence limits

Read actual source on main, not a historic wireframe or chat summary. Inspected both app entrypoints, shared `HiltechShell.kt`, source-bound operator/Project/Field/People sections, Phase2 Inbox/Notification contracts and role personas. Prior UX-128 sessions inspected Phase5 native Compose synthetic Windows and Android images and Phase6 S03 synthetic evidence tests. This pass did **not** sign in as any live HILTECH employee, build/run the exact-main app interactively, or test a physical phone/Windows keyboard/employee usability. Label all screen experiences below **source-supported, not device-verified**.

- [Common shell](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/HiltechShell.kt) uses one `Column(fillMaxSize().verticalScroll(...).padding(24.dp))` and state branches including SignedOut, Working, OfflineFieldReadOnly, SignedIn, AccessDenied, ConfigurationRequired and Failure. The SignedIn state can contain People, Onboarding, Projects, Warehouse, Field, AssetLookup; the rendering includes identity/session detail and all present feature sections in sequence.
- [Android entry](../../apps/androidApp/src/main/kotlin/com/hiltech/android/MainActivity.kt) calls `HiltechShell`; passes Field work/evidence callbacks and `warehouseOperator`, which creates `WarehousePhysicalOperatorSection` for an authenticated identity and an associated organization. It does not enable the UX-128 experimental presentation.
- [Windows entry](../../apps/desktopApp/src/main/kotlin/com/hiltech/desktop/Main.kt) calls the same `HiltechShell`, passes People/Project/stock actions and the Phase5 physical warehouse operator. It does not enable UX-128 experimental presentation.
- [Warehouse physical operator](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/WarehousePhysicalOperatorSection.kt) on source main places Quick Scan above Warehouse Home/Command Center, receiving/stocktake/asset/stock controls and detail surfaces, with state-bound API calls and explicit online-only behavior.
- [Warehouse Quick Scan](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/WarehouseQuickScanSection.kt) is a **typed/manual code lookup + text search**, not a verified camera scan. Visible `canonical`, ID and synthetic-like diagnostic strings make the UI technical.
- [Project Command Center](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/ProjectCommandCenterSection.kt) includes real source-driven review/approval-oriented actions such as `Accept`, `RequestRework`, `PutOnHold`, `Resume`. Do not infer a comprehensive PM workbench or approved drawing control from this.
- [Assigned Work](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/AssignedWorkSection.kt) provides source-backed technician/assigned work presentation. Shell's `OfflineFieldReadOnly` explicitly states that local data is not current server authority and does not permit starting/finishing jobs, physical material mutations or unrestricted evidence actions.
- [Inbox shared DTO](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/inbox/InboxContracts.kt) includes source type/id/version, action key, attention class, read flag, actionable flag, timestamps and safe title/summary. Source-SignedIn shell does not show a dedicated Inbox section in the inspected surface. **Backend/API foundation is not evidence of completed user-facing Inbox navigation.**
- [Phase2 Inbox/Work Queue contract](../13-delivery/phase2/05_INBOX_WORK_QUEUE_FOUNDATION_SLICE_2026-09-19.md) separates current actionable Work Queue from durable Inbox/read state; initial required producer Approval. Read/unread is not approval/resolve.
- [Phase2 Notification contract](../13-delivery/phase2/06_NOTIFICATION_ABSTRACTION_FOUNDATION_SLICE_2026-09-20.md) supports notification intents and neutral delivery architecture; **no production push/SMS/email/desktop delivery provider activated in Slice06**. TEAM fan-out, preferences, digest/escalation and device-token lifecycle were deferred. No claim of working OS notifications.

## Role matrix — what source proves vs what design still owes

| Role | Actual source-bound entry / object | Current experience risk | Desired navigation/working question | Evidence required before acceptance |
|---|---|---|---|---|
| **Mohamed / owner** | Generic SignedIn identity/feature stack; research persona `docs/02-people/owner-mohamed.md` | No proven owner-first actionable decisions / exceptions; raw feeds could create noise | Needs You -> permitted approval/source object -> decision -> context return, company pulse only from sourced facts | Actual owner authority map, real exceptions, Phase13 Company Brain contract; do not pretend shipped |
| **Finance / admin (Ahmed)** | Generic SignedIn / People and foundational Approval/Inbox; `finance-ahmed.md` is a hypothesis, not proof of native payroll UX | Sensitive data, maker-checker and payment states could be confused with plain approvals | Payable/Payroll exception -> source revision -> maker/checker -> corrected/reconciled state | Actual finance phase/contracts, permission matrix, human finance interviews; no bank provider invented |
| **HR / office admin** | People, Onboarding and Offboarding are source-bound shared sections exposed conditionally | Long shell hides time-sensitive cert/document/offboarding actions; risk of showing personally sensitive fields to wrong context | Employee -> employee lifecycle/required action -> authoritative status -> back to team | Screens, precise auth, expired/revoked/denied & confidentiality tests |
| **Project manager** | Projects and [ProjectCommandCenterSection](../../shared/core/src/commonMain/kotlin/com/hiltech/shared/core/ProjectCommandCenterSection.kt) review actions | Project->site->work/resource details are not a proven short connected native flow; dense text & generic command center | Project -> site -> WorkOrder -> blockers/resources/review -> context-return; preserve versions | PM role data, on-site evidence and selected WorkOrder source state, desktop keyboard/native phone proof |
| **Engineer** | Shared Project/Field surfaces and Phase6 in-flight work; persona `engineer.md` defines target | No verified engineering-only entry for latest approved drawing vs obsolete revision/test evidence; Phase6 S04 not finished | Assigned technical task -> approved revision/test instrument/evidence -> review source | Phase6 exact stable head after Engineering, drawing version/signed-off source, operator validation; **no UX edits to S04** |
| **Warehouse operator** | WarehousePhysicalOperator/QuickScan/CommandCenter and receiving/stocktake/passport/stock source calls | Quick Scan and source detail are embedded among many stacked sections; raw IDs, lack of focal drill-back | Today -> context -> manual tag / accessible scan if actually supported -> authorized passport & custody -> return | Phase5 genuine operator/device; verified current source; cross-context nav, pointer/touch, stale lookup evidence |
| **Technician** | AssignedWork and offline read-only states; Phase6 Field intents/Evidence underway | Long shell above work; offline/local/queued/server-confirmed meanings can blur in mixed UX | Today -> assigned job -> exact authorized start/block/resume -> evidence -> sync/accept/rework, with truthful recovery | Current Phase6 stable slices, Android real runtime, offline/restart/sync/denied tests and field tech observation |

**Role status rules:** `implemented server contract` ≠ `source-bound UI` ≠ `native smoke-tested UI` ≠ `human accepted workflow`. Future personas/Phase13 screens must not be counted as currently delivered UI. Current shared state is not necessarily a fine-grained true role navigation policy: server authority remains decisive.

## Exact current-shell information hierarchy gap

SignedIn currently shows organization name/identity/status, team list and a security explanation, then potentially onboarding, session/device controls, People, Asset Lookup, Assigned Work, Warehouse Physical Operator, Warehouse Setup, Projects, Employee onboarding and Sign-out. The same outer scroll is used for authenticated Android and Windows.

**Observed from source:** correct functional fragments can exist yet have no strong navigation grammar: a technician may need to scroll past identity/security to today's assignment, and a warehouse operator may scroll past scan to the requested asset passport. This is a testable usability **risk**, not a claim of measured real-user delays. Do not rewrite the entire shell blind: gather signed-in screenshot, role/permission fixtures and interaction tests first.

## Global UX ownership boundaries

- **Home** = person's current legitimate context and attention, not decorative organization KPIs.
- **Work** = current source-authorized action queue (actual Work Queue, current approvals, field assignment).
- **Explore/Search** = access to permitted objects by robust identifiers & context, never an authorization shortcut.
- **Inbox** = durable attention/read state; read != resolved; source action may become unavailable.
- **Notifications** = delivery/suppression routing, only when real provider/permission exists, not part of core source truth.
- **Me** = profile/device/session/self-service and own issued items; do not make it a mandatory preamble before operational work.

These are **conceptual IA labels** from existing unapproved research docs, not frozen tabs, visual placements or newly implemented functionality.

## P0/P1 follow-ups and the tests they deserve

| Rank | Defect/gap hypothesis | Owner role | Test type / next gate |
|---|---|---|---|
| P0 | Single long SignedIn shell without role-first/visible source object navigation | All | Exact-main role-fixture screen captures + first-authorized-action counts/taps on Android and Windows |
| P0 | Distinguish offline cached read, queued local intent, server confirmed, denied/stale/conflict | Technician, PM | Phase6 integrated source-gate E2E when stable; no replacement synthetic data |
| P1 | Warehouse/PM deep detail loaded below unrelated sections | Warehouse, PM | Source-based navigation to same-viewport contextual detail and back; actual pointer and Android tests |
| P1 | Inbox and Work Queue absent from reviewed shell presentation while Phase2 backend exists | Owner, admin, PM | User action list from real Inbox DTO with authority revalidation, read vs resolve test |
| P1 | Arabic/English IDs, UTC source versions, error codes visible before human action | All | LTR isolation, numeric accuracy, TalkBack, 200% font; human clarity |
| P1 | Security/session content dominates authenticated entry | All | Move to Me/Settings without hiding revocation, re-auth or access-denied recovery |
| P2 | Owner decision view/finance workbench risks fake breadth before later phases | Owner, finance | Contract-level IA only until actual phase/source ready |

## Pass 01 outcome / not-done declaration

**DONE for pass 01:** two actual app entrypoints and shared shell read, role code vs persona distinction documented, Phase2 attention-vs-notification semantics identified, priority defects mapped to code, last main Bootstrap metadata checked.

**STILL OPEN:** exact-main authenticated native app running under each role, full current-state screenshot inventory (all loading/empty/fail/denied/offline cases), QA action timing, Android emulator/phone taps & TalkBack, desktop keyboard, actual operator/manager review, changes due to ongoing Phase6 integration. Therefore UX-00 milestone remains **PARTIAL**, not CLOSED.

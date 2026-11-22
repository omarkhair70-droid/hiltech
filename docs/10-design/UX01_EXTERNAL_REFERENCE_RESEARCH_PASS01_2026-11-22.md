# UX-01 — External Product Reference Research, Pass 01

**Date researched:** 2026-11-22  
**Status:** SIX-TRACK INITIAL SOURCE RESEARCH / NOT FULL BENCHMARK OR DESIGN FREEZE  
**Related:** `UI_REFERENCE_RESEARCH_PLAN.md`, `UX00_ROLE_SURFACE_SOURCE_AUDIT_PASS01_2026-11-22.md`, Issue #128 and draft UX PR #134.

This is a **critical source-and-pattern study**, not a moodboard, copied dashboard, or claim to have operated production accounts in the reference products. Official vendor guidance/docs and Android/platform design-system resources were checked online on 2026-11-22. Cite product versions from source only where published. The task is to decide what behaviors HILTECH should test, not import other firms' interfaces or backend features.

### Reading rule
Each entry states (1) source and verified documented behavior, (2) HILTECH task/role gap it helps investigate, (3) risk/anti-pattern we reject, (4) a concrete acceptance test. Cross-references to HILTECH `main` refer to source inspection, not live authenticated operator measurements. A platform-specific API example is not automatically supported by our Kotlin/Compose dependency versions; code/design adoption requires build evaluation.

## Track 1 — Enterprise operating systems, work surfaces, approvals and object contexts

**R1. ServiceNow Horizon — unified shell vs contextual record workspace.**  
Source: [Horizon workspace structure](https://horizon.servicenow.com/workspace/basics/structure); [Creating an experience](https://horizon.servicenow.com/workspace/basics/creating-an-experience); [Workspace tabs/record navigation](https://www.servicenow.com/docs/r/application-development/workspace-builder/configure-workspace-settings.html). Docs distinguish unified navigation, shell, page content, and tabbed record navigation.  
HILTECH translation: a small global Home/Work/Explore/Inbox/Me mental model with **project/site/asset** contextual workspace and return path; one selected record on phone, list/detail on Windows.  
Reject: multiplying workspaces into disconnected mini-apps, accumulating persistent tabs until the job context disappears, importing agent collaboration features or ServiceNow AI controls.  
Test: PM opens Project→Site→WorkOrder then jumps to asset provenance and returns to same project/site; keyboard Back restores record selection; no source context lost.

**R2. SAP Fiori — worklist then selected object.**  
Source: [Worklist floorplan](https://www.sap.com/design-system/fiori-design-web/v1-120/page-types/floorplans/work-list); [Floorplan decision guide](https://www.sap.com/design-system/fiori-design-web/v1-120/page-types/floorplans/when-to-use-which-floorplan). Documented worklist row navigation opens object detail; page type varies with objective.  
HILTECH translation: source-derived actionable queue→record identity/status→permitted next action, with owner and version visible where decision-critical.  
Reject: huge generic enterprise List Report as default Home, dense table on a phone, treating approval policy as a UI dropdown or mapping every state to one color.  
Test: owner sees one approval genuinely assigned by source and distinguishes `read`, `pending`, `superseded` from `approved` across navigation.

**R3. Jira Service Management — filtered work queues.**  
Source: [Queues](https://support.atlassian.com/jira-service-management-cloud/docs/what-are-queues/), [work-item view](https://support.atlassian.com/jira-service-management-cloud/docs/about-the-issue-view-in-jira-service-management/). Queues are scoped/filterable and support triage; record detail exposes more contextual fields on demand.  
HILTECH translation: show urgent/blocked/ready only when backed by source status, with role and project/warehouse scope, plus row-to-source detail.  
Reject: free-form queue filters that display non-actionable cases as urgent, global "team task" CRUD bypassing domain authority, an endless dashboard of counts.  
Test: Warehouse overdue item vs resolved/authority-revoked item, list refresh and row selection produce truthful action enablement.

**R4. IBM Carbon — dense desktop list/table with controlled bulk actions.**  
Source: [Data table guidelines](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines), [accessibility](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/accessibility). Source documents expansion, sort, selection and batch action bar behavior (disable conflicting single-row controls while batch mode).  
HILTECH translation: Windows stock/finance rows can be scanned and compared; a side inspector should expose movement/source; bulk selection **only** for authorized domain actions.  
Reject: importing Carbon styling, high-density table on mobile, exposing unimplemented batch mutation or permitting bulk actions when version/authority constraints vary.  
Test: keyboard-only selecting/reading warehouse rows, blocked calibration not actionable, no horizontal clipping at compact width.

## Track 2 — Construction / field, drawings, offline and on-site work

**R5. Microsoft Dynamics 365 Field Service — agenda/job execution with explicit offline configuration.**  
Source: [Field Service mobile overview](https://learn.microsoft.com/en-us/dynamics365/field-service/mobile-power-app-overview), [Offline profile](https://learn.microsoft.com/en-us/dynamics365/field-service/mobile/set-up-offline-profile), [mobile setup](https://learn.microsoft.com/en-us/dynamics365/field-service/mobile/set-up-field-service-mobile). Docs describe assigned work, job info/tasks, and configurable offline data; new mobile experience capabilities vary with offline support.  
HILTECH translation: Technician Today→assigned WorkOrder→current site instructions→execution/evidence, while distinguishing cached read, queued intent and server-confirmed state in every relevant surface.  
Reject: claiming all data/actions available offline because the UI cached a record; falling back silently to another shell without explaining missing privileges; importing unrelated dispatch infrastructure.  
Test: cold/online assigned work; stale/revoked while offline; queued Evidence; reconnect conflict; no false "job completed."

**R6. Procore — project drawing sync and revision credibility.**  
Source: [Sync and Download Drawings (Android)](https://support.procore.com/procore-mobile-android/user-guide/drawings-android/tutorials/sync-and-download-drawings-android); [View Drawings (Android)](https://support.procore.com/procore-mobile-android/user-guide/drawings-android/tutorials/view-drawings-android). Docs state drawings can be available offline once downloaded/cached, but new revision/markup requires synchronization to be current.  
HILTECH translation: Engineer/PM must visibly know **which revision**, timestamp and source approval; offline drawing view must not imply latest approved drawing.  
Reject: "latest" badge over a device cache or exporting an incomplete drawing viewer as completed Phase6 S04 capability; no evidence missing/failed upload hidden.  
Test: open approved drawing offline, simulate higher source revision, resume online; visible "cached / update required" and reliable return to WorkOrder.

## Track 3 — physical assets / warehouse and manual fallback

**R7. Hilti ON!Track — identity from tag, with explicit fallback.**  
Sources: [How to add Hilti asset](https://help.ontrack3.hilti.com/hc/en-us/articles/34399860005137-How-to-add-a-Hilti-asset-in-ON-Track); [Local Inventory overview](https://help.ontrack3.hilti.com/hc/en-us/articles/47545534556049-Local-Inventory-Overview). Docs describe scanning tools, selecting location/worker, and fallback lookup using an inventory number when tag/name missing.  
HILTECH translation: the operator should find one known object quickly by a typed label/code (available now), and later by camera if/when actually implemented and device-tested. Current custody derives from **ledger/source movement**, not visual selection.  
Reject: claiming camera/RFID/gateway support from a text-entry form; storing a scanned code as an authoritative asset without source resolution; conflating site/warehouse identity.  
Test: normal code, no code/illegible tag, duplicate/missing code, wrong-warehouse match, stale custody, denied ownership, and accurate source detail.

**R8. Hilti ON!Track — source and freshness of automated observations.**  
Source: [Inventory Local and Smart overview](https://help.ontrack3.hilti.com/hc/en-us/articles/34399857311377-Inventory-Local-and-Smart-Overview), [Local Inventory how-to](https://help.ontrack3.hilti.com/hc/en-us/articles/34399853888785-How-to-check-Local-inventory). Docs distinguish manual scan and Bluetooth/gateway inventory, including observation provenance and update times.  
HILTECH translation: "last seen in warehouse" is an *observation*, not necessarily the current authorized custody or checkout state. UI should distinguish observed physical location, declared recorded movement and reconciliation pending.  
Reject: fabricating near-real-time telemetry, Bluetooth tags, CCTV/door conclusions, or automatically adjusting stock from absence without policy.  
Test: operator can tell "confirmed warehouse movement" apart from "uncorroborated sighting / no observation."

## Track 4 — Finance, approvals, irreversible actions and reconciliation

**R9. Dynamics 365 Finance — invoice approval owner, age and due date.**  
Source: [Vendor invoice center workspace](https://learn.microsoft.com/en-us/dynamics365/finance/accounts-payable/vendor-invoice-workspace). Docs surface pending invoices, the approver and time/due date; completion of workflow approval can precede final posting.  
HILTECH translation: Finance/admin should see *who owns next action* and lifecycle distinctions `draft → submitted → approved → posted/paid` only when the relevant HILTECH finance phase implements those states.  
Reject: collapsing approved into paid, or showing "release payment" without source authorizer, maker-checker, ledger and correction path.  
Test: approved-but-not-posted invoice should never appear as reconciled/paid; unauthorized user cannot trigger new state via hidden UI.

**R10. Stripe — transaction/payout/reconciliation are different reports.**  
Sources: [Compare Dashboard exports and financial reports](https://support.stripe.com/questions/compare-dashboard-exports-to-financial-reports), [Payout reconciliation](https://support.stripe.com/questions/payout-reporting-options), [Payout composition](https://support.stripe.com/questions/find-what-transactions-were-included-in-or-impacted-a-payout-amount). Official help warns Dashboard balances and financial reconciliation reports use different structures and purposes.  
HILTECH translation: on future financial screens, show label and source for amount, date, period, currency, status, fee and reconciliation link; avoid a decorative "bank balance" KPI with no provenance.  
Reject: presuming Stripe or direct bank APIs for HILTECH, equating expected invoice with cash received, auto-settling from UI feedback.  
Test: finance user finds which movement/receipt contributes to a total and can distinguish pending vs confirmed vs reversed; do not build test until contracts exist.

## Track 5 — industrial observability, missing evidence and alarm truth

**R11. Grafana Alerting — Normal, Alerting, No Data, Error are not equivalent.**  
Sources: [No Data and Error states](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/nodata-and-error-states/), [stale alert instances](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/stale-alert-instances/). Docs treat no signal, datasource error and missing series specially; stale resolution can reflect eviction rather than physical recovery.  
HILTECH translation: if later device/service monitoring is adopted, UI needs `data unavailable` ≠ `healthy`, age/source of observation and a separately verified operational issue; similarly when the source work/asset state is not loaded, don't show all-clear.  
Reject: decorative live green dots, assumed sensor connectivity or "everything good" because no exception was fetched.  
Test: stale/offline/failed read has an explicit unknown state without changing authoritative asset/work lifecycle.

**R12. Azure Monitor — alert firing/resolution and action routing.**  
Source: [Alerts overview](https://learn.microsoft.com/en-us/azure/azure-monitor/alerts/alerts-overview). Docs distinguish alert condition and notification/action group routing.  
HILTECH translation: an issue/risk event could feed an attention queue and optionally delivery intent; the UI must distinguish underlying event, active condition, who has action, and whether notice was delivered.  
Reject: equating sending an alert with resolving it, or importing Azure Monitor server integration during core feature delivery.  
Test: send/suppress/no-provider does not change source WorkOrder/Approval and cannot falsely clear an attention item.

## Track 6 — consumer-grade flow, navigation, motion and cross-platform semantics

**R13. Linear — Inbox as attention, quick triage and keyboard entry.**  
Sources: [Linear Inbox](https://linear.app/docs/inbox), [Notifications](https://linear.app/docs/notifications). Docs describe prioritized inbox notifications, direct keyboard navigation and delivery channels controlled by user preferences.  
HILTECH translation: separate unread/read from source action still required; make Inbox fast to scan on Windows and focused on Android; source-aware deep link into Approval/WorkOrder.  
Reject: making "mark read" complete the job, arbitrary red badges, automatic email/push when no provider exists, tracking every event as a social notification.  
Test: read item stays source-actionable if Approval pending; superseded item disables approval action when opened later.

**R14. Android Compose Adaptive — responsive list-detail with back stack.**  
Source: [Build list-detail layout](https://developer.android.com/develop/adaptive-apps/guides/list-detail), [Navigation3 list-detail recipe](https://developer.android.com/guide/navigation/navigation-3/recipes/material-listdetail). On expanded widths list and detail can coexist, while compact device shows one destination; back/navigation must manage pane state.  
HILTECH translation: same Project, Asset or Inbox object state, different on-phone and Windows/tablet presentation; navigation history/back should retain list filters and selection.  
Reject: one fixed column width or bottom-nav pattern for Windows, a desktop inspector squeezed onto phone, assuming versioned Compose APIs already present.  
Test: 430px phone and 1150px Windows capture of identical authorized object and filter; back returns exactly to source selection; no duplicated fetch/action.

**R15. Fluent 2 — rhythm and adaptive density, not a visual costume.**  
Source: [Fluent 2 Layout](https://fluent2.microsoft.design/layout). Docs describe spacing, baseline alignment and adapting grouping to screen/form factor.  
HILTECH translation: dense and navigable Windows tables, flexible inspector and keyboard focus; touch-dense Android warehouse screens rather than arbitrary 24px whitespace everywhere.  
Reject: wholesale Fluent UI clone, generic sidebar with 30 destinations, fixed "premium" spacing that makes operational lists unscannable.  
Test: 120/200% text scaling, RTL/LTR mixed IDs, narrow Windows width, keyboard action count, inspector hidden vs shown.

**R16. Apple HIG Motion — movement should explain state, not be decoration.**  
Sources: [Motion](https://developer.apple.com/design/human-interface-guidelines/motion), [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility). Docs favor meaningful feedback and accessible reduced motion alternatives.  
HILTECH translation: use micro-motion sparingly for state transition from queued to server-confirmed, a selected document revision, or a pane opening. Preserve static text and focus/status meaning when motion is reduced.  
Reject: permanent animated graphs/pulses, misleading "success" animation before actual command acknowledgement, copying Apple visual language into a Kotlin/Compose industrial product.  
Test: same event with reduced motion enabled, screen reader announces authoritative status, no jank or wrong state inference.

---

## Cross-track pattern/anti-pattern matrix

| Proposed interaction rule (candidate, not freeze) | Verified reference anchors | Role + current HILTECH gap | Benefit hypothesis | Critical failure to guard |
|---|---|---|---|---|
| **Role current work → contextual source record** | SAP Worklist, Jira queues, ServiceNow workspace | Owner/PM/admin/warehouse vs one signed-in scroll | Fewer steps to *authorized* next action; better return context | Wrong role visibility, stale state, generic task authority |
| **Persistent current context, optional inspector on wide screen** | ServiceNow tabs, Android adaptive, Fluent | PM and warehouse detail currently below source flow | Faster comparison and source traceability | Pane overflow, broken RTL, selection lost, one-size-fits-all tabs |
| **No-data/queued/confirmed/conflict as distinct semantics** | Grafana, Dynamics offline, Procore | Technician and engineer read/cache/offline | Prevent false "completed/latest" | Phantom success from cached/local data |
| **Evidence and provenance on demand, not debug first** | Carbon expansion, Fiori object page, Hilti inventory | Warehouse, project review | Human action visible without hiding audit | Critical version/authority invisible during high-risk action |
| **Attention/read/delivery remain separate** | Linear Inbox, Azure alerts, HILTECH Phase2 | All roles, especially Mohamed and approvers | Lower noise with accurate source action | Read=resolve, delivered=accepted, disabled provider silently treated as delivery |
| **Financial lifecycle and correction/reconciliation explicitly labeled** | Dynamics Finance, Stripe reports | Finance/admin future phase | Trustworthy accounting/approval decisions | Approved=paid, "balance" with unspecified source |
| **Meaningful motion with stop/reduce** | Apple HIG, Android accessibility | All roles, higher risk in field | Convey transition and relationship, not decoration | motion-only feedback, high jank, artificial life |

## Original thesis critique after this pass

**Industrial** should mean physical context, custody, revisions, evidence, scheduled work and traceability — not black dashboards, thin neon gridlines, gauges or invented telemetry.

**Precise** should mean source authority, right-version decisions and unambiguous feedback — not visible UUIDs/reason codes before human task labels.

**Alive** should mean the real movement of a task/asset/approval through source-confirmed states, with observable pending/error paths — not autoplay cards or high-frequency animation.

This critique is **research direction**, not a new visual identity freeze.

## Testable IA/navigation hypothesis to investigate next

A global five-area **semantic** model is worth comparing against the current long shell: `Home / Work / Explore (Search) / Inbox / Me`, with a contextual workspace for Project/Site/WorkOrder/Asset and a role-visible primary action (manual code lookup for warehouse; Field Today for technician). But the names/positions are not frozen. Compare with a role-specific workbench-first hypothesis:

**Candidate A — Context-first global shell:** stable global locations, source record contexts, role-tailored Home and primary action.
**Candidate B — Role-first workbench:** immediately focused role workspace with object context and limited global escape/search.
**Current baseline — one long signed-in column:** no app-level contextual navigation, as source inspected.

Do not implement a production global navigation until role authority/source routes and platform back-stack are mapped. The earlier Warehouse `Today/Lookup` tabs test only a local screen-level interaction.

### Decision tasks (reproducible synthetic-first, real operator later)
1. **Technician:** assigned WorkOrder -> evidence queued -> return to exact WorkOrder after switching to My assets; can distinguish local from server confirmation.
2. **PM:** Project -> Site -> WorkOrder -> blocked resource -> Asset Passport -> return to previous project filter, authorization still enforced.
3. **Warehouse:** overdue instrument -> current movement/passport -> manual lookup fallback -> return; avoid the current lower-down detail section.
4. **Owner:** real assigned pending Approval from Work Queue -> source detail -> resolve; marked Inbox read is not an Approval transition.
5. **Admin:** employee onboarding missing document -> verified employee record -> task context; no sensitive data leak to unauthorized observer.

Metrics: task steps/scroll depth, target discoverability, correct-state comprehension, keyboard/touch/back, stale/denied/empty/failed, bidi/large text and measurable jank. No invented performance thresholds before baseline.

## Honest closure status

**Pass 01 complete:** official current sources found across all **six** requested research tracks; documented at least two concrete behaviors per track, design tradeoffs, anti-patterns and HILTECH-specific tests. This was literature/documentation analysis — **not 16 hands-on product tests, not 16 captured reference screenshots, not human interviews**.

**UX-01 milestone STILL OPEN:** need selected reference walkthroughs (where access is possible), visual/interaction capture with dates, disability/keyboard/runtime verification, alternative outcomes per role and direct HILTECH interviews. External reference screenshots must respect usage rights; avoid importing their artwork into the repo. No current architecture, navigation or visual design is frozen by this pass.

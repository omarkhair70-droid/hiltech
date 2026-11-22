# HILTECH UX-128 — Product Language & System Foundations / Pass 01

Status: RESEARCH + CODED CANDIDATE / NOT A VISUAL FREEZE / NO PRODUCTION ACTIVATION
Date: 2026-11-22
Source base: `main@cba4a5ff274c2237944d572fe1098f903d0ae9e2`
Lane: `ux/128-foundations-reference-audit-20261122`
Owner issue: #128
Scope: Chat 02 Product, Research & Design; **do not merge, deploy, or change Engineer/DevOps branches**.

## 0. Why this pass exists

Previous Chat02 research in draft PRs #134, #142 and #144 proved native Kotlin/Compose experiments for Warehouse (tasks/code lookup), context/workbench navigation and Field Today -> Job Bundle. They did **not** finish the product design system or prove global integration. This pass turns the original September design research into an auditable multi-platform system contract, with one small executable, cross-platform semantic slice rather than another unreviewed visual theme.

Do not replace `DESIGN_THESIS.md`, `DESIGN_SYSTEM.md`, `STATUS_SEMANTICS.md`, `ICONOGRAPHY.md`, `MOTION_RESEARCH.md`, `ACCESSIBILITY.md` or `TYPOGRAPHY_AND_BIDI_RESEARCH.md`. Those files remain product premises and candidate directions; this is a linked, evidence-led evaluation.

## 1. Source audit — confirmed, not invented

| Actual source | Observation | Design consequence |
|---|---|---|
| `HiltechShell.kt` shared `SignedIn` entry | One shell hosts People, Projects, Warehouse, Field and identity/session concerns | Need role/task-aware destination contract, not simply a recolored giant scroll |
| `AssignedWorkSection.kt` | Field state has Today, selected bundle, local evidence, queued intents, cached timestamps, error states and personal issued stock | UI must distinguish local, cached, queued, denied and server-confirmed states |
| `inbox/InboxContracts.kt` | An Inbox item has `attentionClass`, `state`, `read`, `actionable`, `sourceType/sourceId/sourceVersion`, `safeTitleCode` and `safeSummary` | Read state is NOT action authority; display must preserve safe source context without exposing raw codes to operators |
| Earlier UX research #134 | Warehouse Action-first vs Object-first + context/workbench native studies tested with synthetic Desktop Compose evidence | Retain both as candidates until real role/permission/navigation acceptance |
| Earlier UX research #142 | Project PM captures and incorrect foreground Robot screenshot issue | Never accept arbitrary screenshot output as proof; verify visible source origin |
| Earlier UX research #144 | 12 WorkOrders, return-to-ID after reorder and consume focus on revoke/regrant | Preserve focus by object identity and owner/scope, not index or stale selection |

No authenticated operator session, real handoff, accessibility pass or physical Android UI proof is claimed for this pass. Actual CI/test output is recorded separately below only if executed.

## 2. Design decisions to preserve across roles

1. Reality first: Project, Site, WorkOrder, Asset, Custody, Approval, Invoice and Employee are the vocabulary. The user should not need to parse opaque UUID, status enum, `sourceId` or internal command names.
2. One *grammar*, adaptive shells: same object identity, state and action semantics; different density and pane arrangements across Windows and Android.
3. Status has independent axes: lifecycle, health, action owner, sync authority, operation result. A red/green/grey universal status field is invalid.
4. Navigation preserves source authority and the selected object's identity; source removal, actor/org/scope switch, or permission loss must consume stale UI focus. No local UI state can grant an operation.
5. Arabic is a first-class authoring layout. Logical start/end, Latin identifiers, decimal data, and right-to-left path direction require screenshots and keyboard/screen reader reviews.
6. Motion may explain change of ownership, selection or sync, but cannot imply a command was server-accepted before receipt and source confirmation.
7. No dashboard, generic AI gradient, consumer-brand surface or ornamental engineering icon is a substitute for a coherent workflow.

## 3. Proposed system architecture (not frozen values)

### 3.1 Tokens

- Foundation primitives: palette ramps, type scales, font candidates, spacing, strokes, radii, elevation, focus thickness, icon canvas/grid, animation timing.
- Semantic aliases: `surface.workspace`, `surface.inspector`, `surface.editable`, `text.primary`, `text.secondary`, `text.identifier`, `border.divider`, `border.focus`, `action.primary`, `action.destructive`, `status.{critical,caution,attention,info,positive,unknown}`, `sync.{local,queued,confirmed,conflict,stale}`.
- Component variants: `objectHeader`, `statusLabel`, `workItem`, `detailInspector`, `sourceBreadcrumb`, `actionBar`, `evidenceRow`, `stateNotice`, `dataTable`, `quickCodeLookup` — only after source-/role-validated prototypes.
- Platform policies: font rendering, compact vs comfortable vs field-touch density, hover vs touch, keyboard versus Android Back, single vs two-pane. Sharing semantic tokens must never force pixel-identical components.

Do not pick final HEX values, custom font, icon source library or motion duration in code before a comparative specimen using real content. No dynamic color should overwrite business status semantics.

### 3.2 Status contract — first coded vertical slice

`shared/core/src/commonMain/kotlin/com/hiltech/shared/core/ux/HuxStatusPresentation.kt` introduces **experimental and unused-by-production** explicit presentation keys. Each one returns:
- the independent meaning axis;
- a tone *intent* rather than a fixed color;
- an abstract icon *purpose* rather than a selected glyph library;
- human-visible Arabic and English copy;
- an accessible label that includes the axis.

This does **not** parse arbitrary backend strings, assign severities, infer a role, authorize actions, or transform `QUEUED` into `CONFIRMED`. Engineering must review every future adapter against source enum/authority before use.

Counterexample: `SYNC_QUEUED` = "بانتظار المزامنة", NOT `RESULT_SUCCESS`. `SYNC_PROVIDER_UNAVAILABLE` = source unknown, NOT `RESULT_FAILURE`. `ACTION_WAITING_EXTERNAL` = waiting owner, NOT approval granted.

### 3.3 Icon grammar and research options

Generic/common actions: search, edit, scan-by-code, back, close, filter, sort, upload, download, retry, notifications, settings, camera, attach evidence, expand/collapse, history. Reuse a tested icon family and semantic accessibility labels; do **not** draw these all manually.

Domain-specific candidates needing source-accurate optical studies: rack, ODF, fiber link, splice, OTDR, Fluke tester, patch panel, port, site, asset passport, custody, calibration, engineering test verification, physical handover. A custom icon must pass a comprehension test with engineer + warehouse + technician before becoming a global symbol.

Icon test contract:
- 16/20/24px glyph specimens for density comparison (candidates, not chosen sizes);
- 24/32/40/48px interactive container specimens; native Android field target should be evaluated with a generous touch area, usually >=48dp;
- test monochrome, outline, filled/selected, disabled, dark, high contrast;
- label unfamiliar equipment icons — icon alone must not explain ownership or authority;
- mirror only semantic direction (Back, previous/next, flow), not physical equipment geometry;
- icons do not become warning/red merely because an object is selected.

Decision still open: base family (Compose defaults vs Fluent/Carbon-compatible set with licensing review), custom glyph set, stroke/optical grid, RTL variants, final icon size and token names. Source labels and icon semantics must be decoupled.

### 3.4 Typography and numeric presentation

Required Arabic + Latin specimen strings (synthetic, never implied live business facts):
- `WO-QA-05 — موعد مراجعة الموقع`
- `FLUKE-01 — العهدة الحالية`
- `ODF-07 / rack 3 — حالة المسار`
- `10.24.3.15 — عنوان الجهاز`
- `2,500.75 EGP — التكلفة`
- `2026-11-22 14:30 UTC — آخر مزامنة`
- `Waiting on client — بانتظار العميل`

Test paragraphs, headings, dense Windows table rows, field cards, financial figures, 200% font scaling, copy/paste and screen reader spoken order. IBM Plex Arabic is a **candidate only**. Compare with native platform families and another Arabic/Latin pairing; confirm licenses, metrics, fallback and field readability. Do not hardcode LTR wrappers around a whole RTL screen just to repair one identifier.

### 3.5 Layout / density / adaptive

| Context | First information | Candidate composition | Rejection signal |
|---|---|---|---|
| Warehouse operator, handheld | Work requiring action; code entry available in one move | Task list -> code lookup -> asset passport | 3+ scroll groups or lost entered code |
| Technician in field | Today's authorized tasks, selected work and queued offline work | Focused detail on narrow view, list/detail on wide | Back loses anchor, hidden action, stale task reopened |
| Project manager | Blocked progress and why; source scope | Project -> Site -> WorkOrder with contextual return | Raw `REWORK_BACKLOG` / UUID as primary content |
| Mohamed / owner | Decisions needing him and provenance | Decision workbench with drill into source | Activity feed passed off as actionable priorities |
| Finance / admin, Windows | Correct version, approval step, numbers and exceptions | Compact table plus inspector and keyboard actions | Colour alone or ambiguous plus/minus financial deltas |

Mobile navigation candidates stay candidates until entitlement and operator testing. Don't install a global `NavigationSuiteScaffold` by default: evaluate it as a platform reference for adaptive navigation, preserving the product's required source authority and workflow.

### 3.6 Motion, feedback and reduced motion

| Event | Meaning to preserve | Acceptable candidate | Must not do |
|---|---|---|---|
| Today -> Job Bundle | same task, new focus | spatial continuity and visible Back | jump into old scroll position |
| Asset scan/code lookup | matched identity and source freshness | restrained identity reveal | imply camera scan exists when only manual lookup is wired |
| Custody transfer | owner and handover state changed | movement trace after confirmed source state | animate local draft as a completed transfer |
| Evidence queued | saved locally, pending server receipt | discrete queued indicator and retry | green success/confetti before server confirmation |
| Permission revoked | action/destination no longer available | focus clears, reason remains readable | re-open old detail automatically |
| Approval denied/blocked | next action and reason | inline reason and accessible focus | only a red flash or generic toast |

Reduced-motion option: immediate content/state changes and stable focus/announcements, not removed context. Measure interactions under lower-power mobile and heavy desktop data; no infinite ambient animation in dense views.

### 3.7 Work Queue / Inbox / Notifications contract

- Work Queue: current actionable attention derived from authoritative source (not every unread Inbox item).
- Inbox: durable attention/read state tied to source/version; `read=true` is **not** completion.
- Notification: delivery/intent boundary; no invented push provider, quiet hours, fan-out or deduced recipients.

A specimen must show: actionable unread, read but still actionable, resolved source, stale source version, permission denied, offline cached, unknown delivery, and a deep link returning to the correct source. Copy should say "what happened / what is needed / which object / when the source was checked", not technical enums.

## 4. Quality gates and next experiments

| Track | Gate that must PASS before saying 'complete' |
|---|---|
| UX-00 Reality audit | All role entrances mapped to actual code + latest phase source + screenshot provenance |
| UX-01 Research | Accepted/rejected patterns with linked evidence and testable hypotheses |
| UX-02 IA | Navigation/object graph + source entitlement and lifecycle edge cases |
| UX-03 Navigation | real Shell integration on both platforms; identity-based Back, scope switches, 401/403/409 and offline |
| UX-04 Design system | semantic tokens, type, glyphs, density, motion, 3 theme modes, reusable Compose components plus specimens |
| UX-05 Role flows | warehouse/field/PM/owner/admin/engineer workflows tested against real source and real operators |
| UX-06 Attention | Work Queue vs Inbox vs Notification truth and disclosure, human comprehension |
| UX-07 Accessibility/perf | RTL, font scale, TalkBack, keyboard/focus, reduced motion, large data, contrast, touch |
| UX-08 Finish after Phase13 | integrated visual/behavioral polish measured against established interaction grammar |

**Immediate runnable next proof:** compile and run `HuxStatusPresentationTest` on shared KMP desktop; then a dual-layout status specimen gallery showing the *same source-backed object*, with Arabic/English, dark/light/high contrast, offline/denied/unknown, true focus and native Android device. No visual approval before this.

## 5. External primary-source references reviewed 2026-11-22

1. Android Developers — Build adaptive navigation: https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation
   - Adopt adaptive *decision criteria*, not identical chrome for Android and Windows.
2. Microsoft Fluent 2 — Iconography: https://fluent2.microsoft.design/iconography/
   - Borrow comprehension, naming and geometric discipline; not Microsoft brand or an unlicensed asset.
3. Microsoft Fluent 2 — Motion: https://fluent2.microsoft.design/motion/
   - Motion as consistent physical/context response; compare to real source changes.
4. IBM Carbon — Status indicators: https://www.carbondesignsystem.com/building-blocks/core/patterns/status-indicators
   - Pair text with icon/shape; use emphasis sparingly.
5. IBM Carbon — Notifications: https://www.carbondesignsystem.com/building-blocks/core/components/notification/guidelines/
   - Actionable, inline, toast and modal variants require distinct purpose and next step.
6. W3C WCAG 2.2: https://www.w3.org/TR/WCAG22/
   - Evaluate keyboard/focus, target size, reflow, contrast and non-colour-only status.
7. Original repository docs: `docs/10-design/DESIGN_THESIS.md`, `DESIGN_SYSTEM.md`, `STATUS_SEMANTICS.md`, `ICONOGRAPHY.md`, `MOTION_RESEARCH.md`, `TYPOGRAPHY_AND_BIDI_RESEARCH.md`, `ACCESSIBILITY.md`.

## 6. Release safety

- Experimental code is isolated to a new `ux` package; no app entrypoint, API, domain policies, server, flags, existing tests or Gradle config are touched.
- No arbitrary live state is mapped from strings. No operational entitlement is inferred from presentation.
- No screenshots or device acceptance claimed solely from a passing compiler.
- Review split: if a reusable state grammar is approved, Engineering wires a narrow adapter on current head with new tests; full icon/visual decisions remain UX experiments until validated.
- PR remains Draft. Do not merge or deploy without owner review and coordination with concurrent engineering.

## 7. Additional code-backed icon and size candidates

`shared/core/src/commonMain/kotlin/com/hiltech/shared/core/ux/HuxIconDensityCandidates.kt` contains:
- a bilingual, accessible *semantic icon intent* catalog separating actions / objects / statuses; these are NOT bundled glyphs or finalized image assets;
- explicit left-to-right/right-to-left mirroring *only* for directional Back/Next; physical objects and fiber links must not flip automatically;
- visible text requirements for uncommon engineering object icons (ODF, splice, rack, OTDR, Fluke, custody and more);
- three **unapproved** size specimens: desktop compact (40dp target / 44dp row / 20dp glyph), comfortable touch (48/56/24) and field touch (56/64/24);
- two motion-preference policies: standard and reduced. The reduced mode must preserve state changes without motion.

The purpose is to compare legibility, touch safety and information density against the same real screens before selecting global tokens. Values and icon set must not be applied to the production UI based only on this file.

The two companion KMP common tests verify bilingual accessible copy, status-axis isolation, optimistic-vs-server-confirmed messaging, source-unavailable-vs-failure, engineering object text labels, RTL mirroring and provisional touch-target constraints. Desktop Gradle test results must be recorded by exact SHA, separate from any physical Android test.

## 8. Comparative operational-product research — Pass 02 (official flows)

This pass reviewed actual product-help flows and screenshots, not decorative design-showcase mockups. Treat them as pattern evidence, not assets to copy.

| Product / official source | Strongest behavior observed | Rejected copying | HILTECH experiment |
|---|---|---|---|
| Hilti ON!Track — [asset transfers](https://help.ontrack3.hilti.com/hc/en-us/articles/34399854140817-How-to-transfer-assets-on-the-mobile-app), [asset detail/map](https://help.ontrack3.hilti.com/hc/en-us/articles/34399800470545-What-is-the-smart-map) | Tool identity, location/owner and explicit transfer actions; scanner and manual asset entry have distinct flows | Tool management home tiles and Hilti's visual brand; pretend that HILTECH's manual code lookup is a camera scanner | Start warehouse task -> enter actual tag -> show asset passport -> source-authorized custody action, preserving location and person context |
| Procore — [Drawings](https://support.procore.com/procore-mobile-ios/user-guide/drawings-ios), [multi-column tablet layout](https://www.procore.com/whats-new/modern-multi-column-experience-for-ipad) | Revision visible while navigating drawing; related source items opened from real drawing; tablet pane structure preserves context | Floating tool palettes everywhere or showing drawing acceptance before source validates revision | Project -> Site -> Drawing revision -> WorkOrder/test evidence -> Back; test current vs superseded revision explicitly |
| PlanRadar — [defect management on digital plans](https://www.planradar.com/product/defect-management/) | Site issues anchored to plans/BIM, making spatial context meaningful | A plan canvas when HILTECH has no authoritative drawing/map coordinate | Use source-approved anchor only; otherwise ordinary WorkOrder relation, no fake map pin |
| IBM Plex — [font family](https://www.ibm.com/design/language/typography/typeface/), [open-source license](https://github.com/IBM/plex) | Arabic variant and technical Latin ecosystem worth testing as a pairing | Concluding it is the HILTECH identity merely from industrial associations | Render the same 12 HILTECH bilingual specimen scenarios against platform default and another licensed Arabic candidate |

**New rejection gate:** A reference pattern is adopted only when HILTECH has the corresponding *source evidence, permission contract, offline behavior and operator comprehension*. The difference between "manual code lookup" and "camera scan" is not cosmetic; confusing them changes the user's expectation of an available capability.

### Concrete cross-reference tests from this pass

1. Warehouse: compare Action-first vs Object-first with a late-return asset, but require a reliable asset ID, ownership/source timestamp and a visible manual lookup entry.
2. Engineer: document revision must be source-approved; stale/replaced drawing cannot look like an approved current drawing.
3. PM: from blocked project through site/work package into action, with Back preserving the same project and without raw backend enum as the primary label.
4. Field: list/detail switching must survive reorder, deletion, regrant, offline and 200% text before a visual treatment wins.
5. Fonts: Arabic diacritics, Latin serials, decimal cost values and text scaling must be compared on two platforms; no font redistribution is done in this research branch.

# HILTECH UX-128 — Native source-connected buyer UI and operational design system

Date: 2026-11-22
Source: phase7/s04-po-warehouse-receipt-20261122@b82576066c0633b6b642cb94109bdaeb3585e26b
Branch: ux/128-sourcing-native-integration-20261122
Status: IMPLEMENTED IN SEPARATE UX BRANCH / NOT MERGED / NOT DEPLOYED / NOT COMPANY ACCEPTED

## What changed, beyond earlier isolated studies

Earlier PR #152 explored status/typography/bidi tokens and PR #154 contrasted decision-first vs comparison-first screens. This branch integrates that learning into the actual SupplierSourcingBuyerSection in shared/core, which is already invoked by the authenticated Windows HiltechShell.
The screen loads real permission-scoped need/supplier data automatically and keeps actual callbacks to open RFQs, inspect rounds/comparisons, capture private supplier quotes and run canonical PO-candidate preflight/approval-request flow. The existing backend, API contracts, feature gates and operations are not replaced.

## Implemented shared Design System

File: shared/core/src/commonMain/kotlin/com/hiltech/shared/core/design/HiltechOperationalDesign.kt
Theme: semantic Light/Dark/High Contrast candidate palettes; text/surface/divider/accent/focus and success/caution/blocked meaning.
Ramps: HiltechSpace and HiltechShape.
Reusable components used by the live screen: HiltechOperationalTheme, HiltechPanel, HiltechSectionHeading, HiltechKeyValue, HiltechStateTag.
Typography, icons and global production design freeze remain open. This is a real code foundation, not a specimen gallery.

## Live source and state rules

- Real SupplierSourcingApiClient: eligibleNeeds, eligibleSuppliers, roundsForRequirement, openRfq and comparison.
- Real SupplierQuoteCaptureForm uses existing authorized document upload and file picker; no test quote is injected into production.
- Real PurchaseOrderCandidateAction requests only an authorized S03 preflight; its permission is not inferred from UI selection.
- Changes to organization/installation clear old sensitive state before reload. 401/403 in read/actions discards restricted snapshot.
- Original operationId for RFQ creation is retained on uncertain outcomes; existing server idempotency is mandatory.
- Mixed-currency or server non-comparable offers are never automatically ranked. Source issues and technical compliance remain distinct.
- Only currently loaded authorized response is searchable; full source identifiers stay inspectable.
- Windows layout uses responsive requirement+detail panes with compact viewport fallback, not nested fake dashboard panels.
- Production enablement flags remain default OFF: HILTECH_SUPPLIER_SOURCING_UI_ENABLED, HILTECH_PO_CANDIDATES_UI_ENABLED and HILTECH_PO_APPROVAL_REQUEST_UI_ENABLED.

## Sources studied and their relevance

- Local DESIGN_THESIS.md: Industrial. Precise. Alive.; reality-first, task-specific density, visible source states, Arabic as native.
- Local DESIGN_SYSTEM.md: foundational, semantic, component tokens; accessible modes.
- Android adaptive list/detail: https://developer.android.com/develop/adaptive-apps/guides/list-detail — use contextual panes and compact fallback, not copy a web app.
- IBM Carbon data table: https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines — row-level evidence and actions, not decorative charts.
- Fluent 2 layout: https://fluent2.microsoft.design/layout — density follows task and viewport.
- Fluent 2 accessibility: https://fluent2.microsoft.design/accessibility — keyboard and semantic non-color-only status.

## Evidence and acceptance boundaries

Runnable QA: Gradle init script docs/10-design/ux128-live-sourcing.init.gradle with tasks :shared:core:desktopTest --tests com.hiltech.shared.core.procurement.* and :apps:desktopApp:renderUx128LiveSourcing.
Renderer draws the SAME production Composable BuyerSourcingLiveScreen, using synthetic source-shaped DTOs only, in 1440 desktop, 830 compact, Dark, High Contrast, source changed and 180% font scale variants.
No real company data, real login, Android-device screenshot, physical warehouse operator acceptance or PO release is implied by these renders.

Unclosed: exact-head quality gate, actual authorized-supplier/approval role journey, Android screenshot/200% text/RTL/TalkBack, Windows keyboard focus and PDF evidence access, large lists, source-revocation acceptance, full visual typography/iconography system, branch review and safe integration.
Phase7 S04/S05/receipt/three-way-match remain Engineering-owned and must not be mistaken for this buyer UI integration.
Only after owner/Engineering review, exact-head CI and real operator acceptance could the live feature flags be considered for activation.

## Verified native checkpoint — UX integration QA pass

**Live Compose source mount**: The existing signed-in Windows `SupplierSourcingBuyerSection` was replaced, not duplicated. It consumes `SupplierSourcingApiClient`, `SupplierQuoteCaptureForm`, and `PurchaseOrderCandidateAction` exactly as the Phase7 host provides, while the new `HiltechOperationalDesign` components are imported directly into the live UI. No new independent API, form backend, supplier award, PO mutation or operational enablement.

**Exact source code tested**: `16d4ca32f203cf1dfaf0066d638b18ca8d852d89`, plus QA-only changes to the screenshot fixture (realistic arithmetic, SHA-256 evidence fixture, non-Material3 Compose Foundation render background).

**Source code regression**: Gradle task `:shared:core:desktopTest --tests 'com.hiltech.shared.core.procurement.*'` reported **42 Kotlin tests / 9 suites, 0 failures, 0 errors, 0 skipped**, from XML files. This includes the seven targeted sourcing-presentation tests plus the existing procurement client/contract tests.

**Visual regression**: `:apps:desktopApp:renderUx128LiveSourcing` used `ImageComposeScene` with the SAME operational Compose `BuyerSourcingLiveScreen`, rendering **7/7 PNGs**: normal Windows 1440px, compact 830px, Dark, High Contrast, revoked quote-source, 180% scaled typography, and a 2000px full-flow comparison/inspector. Full final code+render command: **BUILD SUCCESSFUL**, exit 0. See `evidence/ux128-live-sourcing/manifest.json` for per-image size/dimensions/SHA-256 and provenance.

**Visual review**: The initial source panel consumed excessive vertical space before evidence. The real UI now shows compact current requirement + human-readable technical state, with a genuinely interactive source identifier expansion. The buyer ledger and selected quote inspector were visually reviewed in full-height capture. High-contrast, dark and compact images are QA evidence only; keyboard, 200% text and accessibility behavior on real Windows remain open.

**Contrast sample calculations for proposed semantic token pairings only**: Light primary text/surface 15.57:1; Light secondary 7.10:1; light good/caution/blocked text on their status surfaces 7.13:1, 7.18:1, 7.62:1. Dark primary 13.93:1, secondary 10.94:1, good/caution/blocked 8.99:1, 7.98:1, 8.09:1; high-contrast white/black 21:1. These measurements are not a measured whole-screen WCAG or operator acceptance.

**Not closed**: Windows authorized buyer/approver real account walkthrough, role revocation across actual server calls, actual supplier PDF evidence and keyboard/screen reader tests; Android physical device/RTL/200% scaling; product-wide final icon family, motion, type and all-role screens; final Phase7 S04/S05 acceptance, owner-reviewed integration merge and release. Keep this PR Draft, no live purchasing enablement.

## Workflow closure pass / Phase7 buyer UI (22 Nov 2026)

The latest work is **not a new screen prototype**. It updates production-mounted
`SupplierSourcingBuyerSection`, its existing `SupplierQuoteCaptureForm` and shared
presentation rules, with no new backend route or invented purchasing power.

### Confirmed quote -> live comparison, without phantom currentness

- `SupplierQuoteCaptureForm` accepts optional `onQuoteRecorded(receipt)`, invoked
  only **after** `SupplierSourcingApiClient.recordQuote` returns a confirmed source
  receipt. Default is no-op for compatibility.
- The buyer host closes capture, immediately invalidates the old quote comparison,
  then reloads `roundsForRequirement` and `comparison` from the SAME restricted
  source. The new quote is selected only if its ID and revision reappear in the
  fresh current comparison, not merely because the callback says it saved.
- If the current source changed, the response is explicitly historical and
  cannot enable candidate purchase preflight. On 401/403 during refresh, the
  restricted buyer snapshot is erased. If the refreshed view lags, the UI says
  the server recorded the revision but the latest source still needs verification.
- This does NOT bypass existing company approval, PO issuance, or server checks.

### Confirmed RFQ -> no accidental duplicate after later read error

- Keep the original idempotency operation ID on uncertain create outcomes.
- After a confirmed `openRfq` response, set `sourcingRoundExists` in the
  authorized UI projection BEFORE separately fetching the rounds and queue.
  If either subsequent read fails, report **created, refresh unavailable**,
  rather than **creation failed**. No second draft button is exposed locally.

### Bounded source lists and accessible paging

- Work-source queue: ten authorized requirements per page, and search runs
  over the whole loaded (authorized) list; zero/missing results are not
  invented. Quote ledger: fifteen current source quotes per page.
- Both pages show explicit independent *Needs/Quotes page X/Y* controls.
  Page positions are safely clamped when filtering or server data changes.
- `remember` keys use scope/search or RFQ ID rather than whole mutable list
  equality, to avoid O(N) equality checks every recomposition.
- Selection uses button semantics; labels and source-state text remain explicit.
- Existing `HiltechShell` owns scrolling. Pagination avoids a nested
  independently scrolling list inside the global Windows scroll container.
- Performance guidance considered: https://developer.android.com/develop/ui/compose/performance/bestpractices
  and accessibility semantics: https://developer.android.com/develop/ui/compose/accessibility/semantics .
  This is not a measured 1000-user backend load-test or a claim of fully
  compliant accessibility.

### Reproducible QA checkpoint

- Native compile and shared procurement suite `BUILD SUCCESSFUL`:
  **46/46 Kotlin desktop tests**, 9 suites, 0 failures, 0 errors, 0 skips.
- **8/8 native Compose renders**, same source-connected Composable with
  clearly synthetic DTOs, including a new 11-needs / 10-visible page-control
  visual. The screenshot set includes source-revoked, compact, Dark, High
  Contrast, typography scaled 180%, full quote inspector, and list paging.
- Updated PNGs and SHA-256 capture manifest under
  `docs/10-design/evidence/ux128-live-sourcing/`.
- The code branch remains Draft and feature flags default OFF. Real authorized
  user journeys, quote file permissions, actual 200%/RTL keyboard accessibility,
  Android devices, isolated desktop navigation, typography/icon library and
  Phase7 S04/S05 Engineering acceptance are still separately open.

### Integration ownership and next non-optional product UX work

1. Dedicated native Procurement workspace navigation from `HiltechShell`
   (the global vertically scrolled shell is not the finished user experience).
2. Source-connected buyer/technical reviewer/PO issuer/receiving role flows
   through active Engineering S03/S04/S05 contracts, not synthetic UI buttons.
3. Company-wide semantic tokens, Arabic bidirectional copy, final type,
   icon family, focus/keyboard/reader tests and mobile role-specific layout.
4. Review PR + exact-head source revalidation on the latest Engineering branch,
   then authenticated staging and real operator acceptance before activation.

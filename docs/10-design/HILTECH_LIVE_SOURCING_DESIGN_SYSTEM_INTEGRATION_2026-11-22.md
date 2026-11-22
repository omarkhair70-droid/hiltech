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

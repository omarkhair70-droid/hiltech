# HILTECH / UX-128 — Source-Typed Full-Screen Procurement Experience, Pass 03

Date: 2026-11-22
Lane: UX-only worktree `ux/128-procurement-fullscreen-study-20261122`
Source baseline: `phase7/s03-approval-po-preflight-20261122@7ac0c5cecd49e5b2d59ff491c1fbb05ad23095fe`
Status: **research + experimental Compose only; no signed-in operational workflow, no company purchasing authorization**.

## Why here / conflict control
Chat 02 previously built semantic status/icon/density studies in Draft PR #152 and old navigation research in #134/#142/#144. These were not a full cross-role screen. The 2026-11-22 Phase7 source branch implements real S01/S02 procurement contracts and an S03 PO-candidate/preflight boundary. Its latest source files are **not assumed merged to main**. Engineering is still active on Phase7; do not edit its branches/files. This new worktree adds only isolated `shared/core/.../ux/procurement` experiments, KMP tests, and an explicit Desktop QA renderer, to be reviewed in a stacked PR targeting that Phase7 source branch. It does **not** alter existing `PurchaseRequirementOperatorSection`, `SupplierSourcingBuyerSection`, APIs, work/warehouse records, server, authorization, flags, migrations or shell.

Canonical Phase7 plan: `docs/13-delivery/phase7/02_PHASE7_FIVE_SLICE_EXECUTION_AND_S01_CHECKPOINT.md`. Five slices:
S01 purchase requirement and stock-first, S02 RFQ + supplier quote comparison, S03 approval and PO issuance lifecycle, S04 delivery and canonical Warehouse receipt, S05 3-way-match and Finance handoff. Current source checkpoint is **NOT** evidence of all-five-slice completion or real company acceptance.

## Grounding audit (exact typed source)
- S01 `PurchaseRequirementDraftDto`: `workOrderId`, stock item/version, requested amount/unit, technical status, last review, `sourceCurrent`, `assessmentRequiresRefresh`, server-projected `canSubmit` etc.
- S01 `PurchaseRequirementReviewPreviewDto`: permission-scoped, side-effect-free live stock review; `visibleFreeQuantity` may be null due to warehouse visibility. Never display 0 for null and never infer stock reservation.
- S02 `SupplierRfqComparisonDto` / `SupplierQuoteComparisonRowDto`: quote source version, supplier ID, currency, revision, technical compliance, evidence reference, issues. `allQuotesDirectlyComparable` must be honored; best-looking total is **not** a supplier selection.
- S03 documentation explicitly says the buyer can inspect a PO candidate and request canonical approval, but no UI surface here performs PO issue or awards a supplier. The two default-OFF server/client gates remain intact.
- Supplier names and human-readable work/item labels must be supplied by properly scoped source adapters. The test harness uses visibly marked synthetic QA labels; it never pretends those names were read from production.

## Real-screen design experiments, not a dashboard recreation

| Direction | Visual and interaction decision | Intentional downside / test |
|---|---|---|
| **Decision-first buyer workbench** | WorkOrder/material source rail → current-source/compare warning → offer cards → evidence inspector. Persistent source chain before choosing any quote. | Longer scan than a compact data table, possible wasted width. Test decision latency under dense 100+ offers. |
| **Comparison-first quote ledger** | Dense quote sequence and review headers → always-visible source/provenance + selected inspector. No automatic price sorting or unsupported currency conversion. | Risks hiding action priority under numbers; test mixed currencies, conflicting technical assessments and keyboard operation. |
| **Field requirement / Android-width** | Assigned WorkOrder and material → state and reason → advisory Warehouse snapshot → online-only review action. RTL and source-validity-first. | Potentially dense for 430px width; must test real Android layout, 200% scale, offline and role revocation before any approval. |

Shared *reusable* Compose components: `HuxStatusLabel`, `HuxLabelValue`, source/quote inspector, quote record surface, and source warning, with typed screen models `HuxBuyerStudy` and `HuxFieldStudy`. These are candidates for extraction/real adapters, not final brand tokens or a production-ready design system. Two buyer directions render the **same underlying DTO** with different hierarchy, not different invented backend workflows.

### Critical authority and failure invariants
1. `canCompareTotals` requires source-current, one currency and server direct-comparability claim; it does not imply technical compliance of every quote, award, PO, stock or payment authority.
2. `isPurchaseAuthorizedHint` is an explicitly conservative conjunction of typed source flags; this screen has no PO/approval action regardless of hint.
3. `canShowFreeStock` requires online, source-current, not refresh-required, visible draft/preview quantity and no `WAREHOUSE_REVIEW_REQUIRED`. Absence of stock visibility is not zero; quantity is **advisory only**.
4. `canOfferSubmit` requires a callback wired by a host, online, current draft and the source's `canSubmit` hint. The backend must still validate role and source version on any future actual submission.
5. Quote selection is a **read-only local inspector interaction**, not persistence or company supplier selection. Host must clear selection on scope/organization/permission or RFQ changes.
6. No invented approval policy, quantity threshold, supplier terms, direct currency conversion, incoming invoice, warehouse ledger, or real commercial/purchaser contact.

## Reproducible QA
```powershell
.\gradlew.bat -I docs/10-design/ux128-p7-fullscreen.init.gradle :shared:core:desktopTest --tests 'com.hiltech.shared.core.ux.procurement.*' :apps:desktopApp:renderUx128ProcurementFullscreen --max-workers=2 --no-daemon --console=plain
```
This test-only Gradle init task renders Compose Desktop via `ImageComposeScene`, not a foreground Robot screenshot or actual Android execution. Synthetic screenshot variants:
- `01-buyer-decision-workbench.png`, 1440×940
- `02-buyer-quote-ledger.png`, 1440×940
- `03-buyer-stale-source.png`, 1440×940
- `04-field-requirement-stale.png`, 430×960
- `05-field-requirement-current.png`, 430×960

Captured files live only on authorized Windows QA storage under `apps/desktopApp/build/reports/ux128-procurement-fullscreen` and are not on GitHub unless explicitly uploaded as safe sanitized evidence. A source/head/PNG SHA-256 manifest should be committed after a successful render. **Do not mark tests PASS until exact-head command exit and resulting XML/screens are actually inspected.**

## Next steps / gates

- Compare two desktop directions using **same** source, same labels, same quote errors; record concrete readability/scan/recovery findings, not a preferred style from one static image.
- Validate field view on physical Android, long labels, RTL, TalkBack, System Back, 200% text and offline/role loss.
- Integrate only a narrow, reviewed component/adaptor after Phase7 source bindings and Engineering agree; don't wholesale merge an old stacked research prototype.
- Continue Chat02 research for Warehouse, Field, PM, Finance and Phase13 executive Home, preserving original whole-product design polish gate after Phase13.

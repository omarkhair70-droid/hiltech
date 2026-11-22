# UX-128 — Source-connected internal Purchase Order Issuer experience

**Date:** 2026-11-22
**Branch:** `ux/128-sourcing-native-integration-20261122` / Draft PR #156
**Engineering source:** current Phase7 S04 canonical PO issue, source approvals, receiving linkage.
**State:** native code integrated in UX branch; not merged, deployed or company accepted.

## Scope and original backend source truth

This iteration replaces the **actual** `PurchaseOrderIssuerSection` used by the authenticated Windows `procurementOperator` callback from `Main.kt`, not a disconnected design gallery. The new `PurchaseOrderIssuerLiveScreen` accepts only typed, authorized issuer queue and canonical receipt DTOs.

Production issuance still calls **existing** `PurchaseOrderIssueApiClient.queue`, `.destinations`, `.issue` and `.issuedOrders` (available as a separate lifecycle source). Its existing DTOs are untouched; the canonical server `PurchaseOrderIssueService.issue` explicitly requires `expectedCandidateVersion == 1L`, and independently validates current company issuer authority, candidate lock, source, approval, destination, policy, and idempotency. The UI uses `CANONICAL_ISSUER_CANDIDATE_VERSION=1L` with this verified server contract. No company-specific policy, approval, new role or money mutation is introduced.

### Actual operator journey

1. The original desktop Procurement workspace routes to **PO issuance** only when the existing `HILTECH_PO_ISSUER_UI_ENABLED=true` host flag is set; actual API authority remains server-controlled.
2. On entry, clear previous restricted queue and fetch the current server-authorized approved/unissued candidate queue. On denial, show an explicit non-leaking state; on 401/403 during issue/destination fetch or post-issue source refresh, erase restricted issuer queue and receipt snapshot.
3. Responsive **list/detail**: operator searches only the returned authorized queue, pages eight rows at a time, selects one candidate and inspects actual supplier, quantity, money, approval policy version, original approval request and stock/source IDs. No private buyer quote details are supplied.
4. The selected source automatically calls the existing canonical authorized delivery-destinations API. A destination must belong to the returned list.
5. Intentional **two-step issue**: Review actual supplier+money+destination, then explicitly Confirm internal PO issue. Both clicks are real native Compose controls. Server request carries exact candidate ID, canonical expected version, real selected storage location and stable UUID idempotency key.
6. If the attempt outcome is uncertain, retain both operation ID and destination in the existing signed-in issuer state; a retry must reuse the same operation+target. Changing the delivery destination while a prior operation is uncertain is blocked. The operator can first invoke the EXISTING `issuedOrders` API to verify the canonical server-issued register by exact candidate ID. If found, show the existing PO without resubmitting; an absent read is NOT proof a prior command failed.
7. On a confirmed server `PurchaseOrderIssueReceiptDto`, verify it belongs to the selected candidate and approval request, display durable receipt code/state/total/supplier dispatch/stock receipt/payment flags and original identifier in the same issuer work area. Remove the confirmed candidate from current queue and try refreshing server queue; if server's queue is temporarily lagging, never re-display the confirmed candidate as actionable.
8. Existing cancellation/destination amendment sections are preserved as **separately gated, explicitly opened lifecycle tools**, not implied by issuer authority and not visually mixed into issuance.
9. Switching/closing workspace still explicitly warns that unsubmitted forms reset and in-flight operations may have completed; do not pretend to cancel a sent request.

## Concrete Design System reuse

Uses existing native semantic `HiltechOperationalTheme`, `HiltechPanel`, `HiltechSectionHeading`, `HiltechKeyValue`, `HiltechStateTag`, and `HiltechSpace/Shape` from shared core. Light/Dark/High-contrast palettes, factual textual statuses, row-specific actions, selected surface, inspector and horizontal split adapt at 920dp. No generic web dashboard or fabricated totals/rankings. Source IDs remain inspectable/selectable with bidi isolation.

## Native tests and evidence

- `PurchaseOrderIssuerPresentationTest.kt` covers scoped queue search, server receipt matching, removal of only the issued candidate, exact-candidate lookup in the canonical issued-PO register, selected-source invalidation, authorized delivery destination selection, exact same-operation retry and destination lock, 401/403 sensitive state denial, and canonical expected version. Run alongside all previous purchase/client tests and workspace routing tests.
- `apps/desktopApp/src/test/kotlin/com/hiltech/desktop/evidence/UX128LiveIssuerVisualEvidence.kt` renders the **same production issuer screen Composable** with synthetic typed queue/receipt DTOs and an explicitly non-networked action slot (do not claim a real issuer click or company identity). 9 cases: wide, full-height compact, Dark, High Contrast, 180% scale, empty queue, confirmed PO receipt, paging, and reconciliation from the existing issued-PO register. Synthetic receipt cases remove the already-issued candidate from their test queues to match actual production behavior. `ux128-live-issuer.init.gradle` registers a reproducible JavaExec task.
- After successful exact-HEAD tests and render, archive SHA-256 image manifest via `ux128-live-issuer-manifest.ps1` under `docs/10-design/evidence/ux128-live-issuer/`.

## Distinct release gates NOT accomplished by screenshots/build tests

- Actual signed-in company PO issuer account with permitted and denied cases; source currency/approval policy and exact destination checks on real data; server audit and idempotency replay; actual authorized PDF/document workflow.
- Real Windows keyboard/focus navigation, screen reader, Arabic RTL and 200% scaling; compare dark/high contrast on physical monitor and narrow resize.
- Source-concurrent issue/receipt race handling under actual S04/S05 warehouse receiving linkage and approved destination amendment/cancellation contract. The UI is not a PO-to-Warehouse auto-receipt workflow.
- Product-wide final font, iconography, motion and all role-specific screens (technical reviewer, receiving and matching) still open. No declared final Design System freeze.
- Engineering/DevOps code review, current base reconciliation, CI, staging and operational acceptance. Keep Draft, flags default OFF, no merge/deploy.

This is a functional native product-integration checkpoint, not a replacement for company governance or real end-to-end acceptance.

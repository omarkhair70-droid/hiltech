# UX-128 â€” Procurement work areas inside the native HILTECH workspace

Date: 2026-11-22
Status: code integrated in independent Draft UX branch, not merged/deployed/accepted.

## Product integration, not a separate presentation

The authenticated Windows desktop shell already mounts a genuine `procurementOperator` with three separately flagged server-backed components: `PurchaseRequirementOperatorSection`, `SupplierSourcingBuyerSection` and `PurchaseOrderIssuerSection`. Before this change, all enabled modules appeared stacked in one long screen. Now `ProcurementWorkAreaRouter`, a composable in shared Core, exposes one active work area at a time:

- **Work requirements:** original work-order selected purchase needs, draft/edit/review/withdraw source, linked catalog and inventory checks.
- **Supplier sourcing:** original typed RFQ, source-verified quote upload/compare, optional candidate preflight and approval request.
- **PO issuance:** original authorized issuer queue, destinations, internally issued PO, plus separately flagged cancellation and delivery amendments.

The existing original API clients, distinct roles, authorized organization/installation source, idempotency keys, file upload, approval, issuer and warehouse receipts were **not replaced or broadened**. No supplier notification, financial operation, external dispatch or warehouse stock receipt is added by this navigation.

## Routing and authorization

All three areas are gated independently by the **existing default-OFF** Desktop env variables:
`HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED`, `HILTECH_SUPPLIER_SOURCING_UI_ENABLED`, `HILTECH_PO_ISSUER_UI_ENABLED`.

The outer `HiltechShell` now offers Procurement if any of these three flags is true and an identity/organization has signed in; it did not previously consider an issuer-only installation. The nested route is scoped by identity and organization and feature-set, defaults to the first visible area and falls back if an area was disabled. A client-visible tab **is not a permission grant**; each original operator still uses the server's authority.

The router uses reusable native `HiltechOperationalTheme`, `HiltechPanel`, `HiltechSectionHeading` and focusable Compose navigation controls. It warns before switching areas that non-submitted inputs will reset and in-flight server actions may still complete, requiring source verification before retry. This is explicit non-transactional safety, **not** automatic cancellation.

Only the active section is composed, so prior unsigned quotation or pending PO issuance form data cannot remain silently presented when switched. It is intentionally not a fully persistent multi-workspace draft manager.

An authorized user with no assigned WorkOrder receives a meaningful empty message rather than a blank requirements area. No unauthorized WorkOrder or supplier data is substituted.

## Verified and unverified

QA includes pure Kotlin tests in `ProcurementWorkAreaNavigationTest.kt`: no flags, requirements-only, issuer-only, sourcing+issuer, all three, and disabled-route fallback. In addition to these six cases, run the original 51 navigation/procurement cases and actual Desktop Windows Kotlin compile against the **exact code SHA**, then record the outcome in PR #156.

The broader Engineering S04 branch has advanced on receiving tests and a canonical PO-receipt linkage; this UX branch does not modify those server files or contract rules. Reconcile current Engineering source before approval for merging.

### Release gates not represented by these unit tests

- Signed-in **real** role switching, network-denied supplier and issuer queue, actual authorized server responses, and source freshness after route switching.
- In-flight server action followed by route switch and reentry: audit trail/duplicate protection.
- Windows keyboard focus/navigation, 200% font scaling, screen reader labels, Arabic RTL, narrow window, latency/large data.
- The final typography, coherent icon family, shared navigation hierarchy and accessible animation throughout all HILTECH role screens.
- Warehouse receiving/three-way match UI, technical reviewer UI and any future client role workflow must remain backed by their own implemented APIs and explicit permissions, not synthetic UI actions.
- Engineering/DevOps CI, code review, staging and company-owned operational acceptance. Keep PR Draft, no flag enabling, merge or production deploy based on these tests alone.

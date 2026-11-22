# Phase 7 / S01 — Internal material demand to technical intake

Date: 2026-11-22
Scope: WORK_ORDER + STOCK_ITEM only, real production purchasing policy **NOT frozen**
Status: implementation checkpoint; technical code does not equal operational S01 release

## Concrete implementation

Current source truth:
- WorkOrder and ProjectAuthorizationPort.canRequestResources authorize the requester
- current StockItem and canonical unit/version, ReservationPersistence.balances
- current per-location WarehouseAuthorizationPort.canViewStorage gates quantities
- source and actor are checked before and **inside** mutating transactions
- cross-organization references never become authoritative

Original V0039 draft root remains immutable. V0040 adds requester WITHDRAW.
V0041 adds append-only versioned events and optional immutable full revision
snapshots. Event version is 1 higher than the current root's last version;
Idempotency-Key must match the operation UUID and audit is correlated.

| Transition | Who | Precondition | Effect |
| --- | --- | --- | --- |
| DRAFT -> SUBMITTED | original requester with current request authority | source/work/item fresh, WorkOrder active | creates submitted intake, no PO |
| DRAFT/NEEDS_INFO -> DRAFT | original requester | current work/item versions provided | immutable revised quantity/spec/evidence |
| SUBMITTED -> NEEDS_INFO | project manager (canManageProject) | reason required | asks requester to revise |
| SUBMITTED -> READY_FOR_SOURCING | project manager (canManageProject) | live source versions current | technical readiness only |
| SUBMITTED -> REJECTED | project manager (canManageProject) | reason required | stops technical intake |
| DRAFT/SUBMITTED/NEEDS_INFO -> WITHDRAWN | original requester | expected version matches | preserves historical evidence |

`READY_FOR_SOURCING` is *not* a purchase order, approval, budget
authorization, supplier award, stock reservation, invoice or payment.
Project manager has no derived purchasing authority. Buyer authority is
**intentionally absent** until the company provides actual authority
and the procurement subject policy is bound to the existing ApprovalEngine.

## API

- POST `/v1/work-orders/{id}/purchase-requirements`
- GET `/v1/work-orders/{id}/purchase-requirements`
- GET `/v1/purchase-requirements/{id}`
- GET `/v1/purchase-requirements/{id}/review-preview`
- POST `/v1/purchase-requirements/{id}/submit`
- POST `/v1/purchase-requirements/{id}/revise`
- POST `/v1/purchase-requirements/{id}/withdraw`
- POST `/v1/purchase-requirements/{id}/technical-review`
- GET `/v1/projects/{projectId}/purchase-requirements/intake` (project manager only)

Project intake filters to organization + project and submitted / needs-info /
technically-ready requests. Exact work+stock duplicate advisory includes
active pending requests only, excludes withdrawn/rejected.
All changes are online-only and require authenticated, idempotent commands.
No offline business mutation is queued.

## Native clients / product experience

The same common Compose component is wired into signed-in Android and
Windows shell. Current real WorkOrder version and StockItem version are read
from their owning clients. Requester can save, submit, revise, withdraw and
live-review. Project manager Windows has a project-scoped technical intake
picker and can return/technically accept/reject. It does not pretend to
approve supplier contracts, cash, goods movements, or procurement payments.

Independent default-OFF gates:
- Server `hiltech.procurement.requirements.enabled=false`
- Android Gradle `hiltech.procurementRequirementsEnabled=false`
- Windows `HILTECH_PROCUREMENT_REQUIREMENTS_ENABLED` absent/false

## Conditions required for S01 complete

- [ ] Exact-head server tests, shared KMP Desktop tests, Android Debug APK,
      Windows package and PostgreSQL migration V0041 + its constraints
- [ ] E2E with real HILTECH technician and manager identities on authorized
      devices, including user/no-access, source changes, withdrawn visibility,
      revised resubmission, permission revocation and duplicate warning
- [ ] Remaining typed demand sources with their actual owning authority:
      warehouse replenishment, asset repair/replacement, office/facility,
      approved ad-hoc/service — NOT implemented by the WorkOrder table
- [ ] Source-specific business examples, procurement receiver/authority
      mapping and approved transition/policy boundaries
- [ ] Current canonical files reviewed for delivery/definition of complete;
      no undocumented financial or purchasing authority
- [ ] PR review, actual CI green and operator acceptance before merge/flag flip

Do not silently mark S01 closed or start S02 before the listed missing
boundaries have credible evidence. This checkpoint is substantial code, not a
claim of operational procurement acceptance.

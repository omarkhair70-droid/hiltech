# Phase 7 / Procurement — Actual Source Delta Preflight

Date: 2026-11-21
Status: **PRECONTRACT ONLY / NO PRODUCTION IMPLEMENTATION**
Baseline: `main` at `13c49c8b07060bd5495f6dcb8f219efe18ffb5c5`.
The [Phase 6 S03 merge/operational gates](../07_TO_13_SOURCE_REUSE_AND_DELTA_GATE_2026-11-21.md) are separate; preparing this preflight does **not** declare Phase 6 complete.

## Existing source, verified

| Source file / migration | Reusable | Boundary |
| --- | --- | --- |
| `server/application/src/main/kotlin/com/hiltech/server/work/FieldAssignedWorkService.kt` + readiness/Work resources | WorkOrder / Project / Site and resource readiness/source data | A WorkResource shortage is a **candidate demand trigger**, not an approved PurchaseRequirement |
| `server/application/src/main/kotlin/com/hiltech/server/warehouse/WarehouseWorkResourceSource.kt` | stock/current resource state | not a procurement budget or supplier price |
| `server/application/src/main/kotlin/com/hiltech/server/warehouse/ReceivingApi.kt` + `ReceivingService.kt`, `ReceivingPersistence.kt` | existing goods receipt, stock/asset receipt and discrepancy, warehouse authority, idempotency | `ReceivingSourceType { PO, TRANSFER, RETURN, INITIALIZATION, OTHER }` exists, with `sourceRef: String?`. That string is **not** a canonical PO foreign key, PO version, supplier authorization or price/quantity agreement |
| `server/application/src/main/kotlin/com/hiltech/server/approval/ApprovalEngineService.kt` | version-bound shared approval machinery and stale-subject guard | requires **new, actual Procurement subject source/approval authority**. Do not set a default "approved" flag |
| `server/application/src/main/kotlin/com/hiltech/server/inbox/InboxApi.kt`, `notifications/NotificationDelivery.kt` | current actor/role inbox and safe notification delivery rules | no Procurement-specific workflow notifications yet |
| `server/application/src/main/kotlin/com/hiltech/server/documents/EvidenceLifecycleApi.kt` | classified private evidence / file upload | cannot turn a PO attachment into an approved supplier invoice by uploading a file |

Existing discovery docs: `docs/05-workflows/PROCUREMENT_FLOW.md` status `RESEARCHING / SYSTEM MODEL v0.1`; `docs/06-data/object-specs/PROCUREMENT_FINANCE_OBJECTS.md` status `NOT ACCOUNTING/SCHEMA-FROZEN`. No dedicated PurchaseRequirement/PO/RFQ code or dedicated procurement DB migrations were identified in the inspected `main` source tree; do not conflate proposals with current production tables.

## Narrow candidate slice order — subject to source/reality freeze

### P7-S01: Purchase demand source and stock-before-buy

- Create a PurchaseRequirement **only after** freezing exact actor, organization, WorkOrder/resource source, item/service/UOM, project/site and permitted creation conditions.
- Explicitly distinguish Work resource shortage from a procurement obligation. The same shortage must not silently create duplicate purchase requests.
- Authoritative Stock projection can advise already-on-hand or reserved quantities **without** revealing other warehouses or unauthorized locations and without mutating stock.
- Support manual non-stock/service requirements through a separately permitted source. Do not fabricate a stock-item ID for services.
- Stable idempotency key, source version, immutable audit, denied states, and operator-visible approval/source status.
- Tests: no stock purchase when satisfied if policy requires checking first; duplicate request; stale WorkOrder; other organization/site; unauthorized user; multiple concurrent request creation; units/quantities.

### P7-S02: Supplier discovery/quotes and purchase approval

- Explicit Supplier legal organization/identity and scope; no exposing supplier A quote to supplier B.
- Quote versions, currency/tax/delivery/warranty terms, expiry, comparison provenance and authorized recommendation.
- Freeze amount/exception authority with owner and actual Procurement team. Approval binds exact request/quote/PO proposal version; unauthorized reviewer or changed terms must invalidate/supersede approval.
- Tests: supplier data separation, expired quote, currency or tax mismatch, changed version, duplicate approval, unauthorized access.

### P7-S03: PO issuance/version and receipt integration

- Persist authorized PurchaseOrder/lines/versions before considering a goods receipt "PO-based". PO current state, destination, units and accepted supplier require explicit source-of-truth.
- Reuse existing Warehouse Receiving/Discrepancy and stock/asset movement logic; **do not build a second inventory ledger**. Add a canonical PO ID/line/version binding with database validity only after exact source/authorization freeze. Legacy `sourceRef:String` is context, **not** proof.
- Test partial, over/short and damaged receipt, serial mismatch, wrong location and source version. Reconciliation must read immutable receipt facts, not a manually toggled "received" flag.

### P7-S04: Three-way match and Finance handoff

- Bind **approved PO version ↔ authorized receipt versions ↔ supplier invoice version**, preserving quantities, price/tax, partial delivery, credit/return and discrepancy state.
- Finance handoff creates a payable *intent/reference* only after Phase8 authority/schema is approved; do not invent a payment result or bank settlement.
- Tests: missing receipt, duplicate invoice, overbilling, changed PO, partial receipts, wrong supplier, invoice privacy and unknown handoff outcome.

The four numbered units are **proposed decomposition only**, not a pre-approved slice count or timeline.

## Exact open reality decisions

- [ ] Who can raise, validate, reject and approve spending? Role/team exceptions, budgets and amount limits.
- [ ] Actual HILTECH supplier directory model, documents and 2–3 representative quote/PO/invoice/delivery packets (redacted).
- [ ] Procurement source when no WorkOrder exists (office, tools, emergency, maintenance).
- [ ] How warehouse receipt lines reference PO/PO line, how older `sourceRef` entries are migrated or treated as non-authoritative.
- [ ] Approved quantity and price tolerance, partial receipt, prepayment, tax/FX, cancellation and return/credit flows.
- [ ] Whether Finance phase boundary permits an interim approved-obligation read-only state before Phase8 ledger/payable implementation.

## No-go

No user-visible PurchaseOrder, purchase approval, supplier credential, finance ledger, payment obligation or cross-role warehouse permission has been built by this document. Do not begin actual Phase7 implementation on an unmerged Phase6 state without owner-approved sequencing and exact source/authority contract.

## First executable implementation gate

Once Phase6 is dispositioned, pick **one** real procurement requirement with one correct source and allowed requester; freeze a vertical P7-S01 contract and prove its negative cases end-to-end in source-controlled tests. A broader procurement UI comes **after** that canonical requirement is trustworthy.

`PHASE7_SOURCE_REUSE = INSPECTED`
`PHASE7_PROCUREMENT_DOMAIN = NOT_IMPLEMENTED_BY_THIS_PR`
`PHASE7_CONTRACT_FREEZE = PENDING_REAL_OPERATOR_APPROVAL`

# Phase 7 — Procurement: source audit and reality gate

Date: 2026-11-22
Status: **SOURCE-AUDITED / PLANNING PROPOSAL / NOT FROZEN / NO PRODUCTION PROCUREMENT COMMANDS AUTHORIZED**
Baseline: `main` at `c30fb707186cc0bbb38ba95b924737bd1bc56997`. Phase 6 S01-S06 engineering exists in separate, stacked open PRs; Phase 6 field operator acceptance and production activation remain independent.

## Purpose

Define an implementable procurement boundary on the existing HILTECH OS, without inventing company purchasing authority, supplier terms, financial approval thresholds, or a second warehouse ledger.

## Existing repository truth

| Existing source | Reuse in Phase 7 | Must not duplicate |
| --- | --- | --- |
| `docs/05-workflows/PROCUREMENT_FLOW.md` | Demand -> stock check -> quotes -> comparison -> approval -> PO -> delivery -> receipt -> invoice match -> finance handoff | Domain source remains a *researching v0.1 model*, not approved HILTECH purchasing policy |
| `docs/06-data/object-specs/PROCUREMENT_FINANCE_OBJECTS.md` | PurchaseRequirement, SupplierQuote, PurchaseOrder, Delivery, Invoice, MatchResult candidate fields | Supplier invoices, Payable and PaymentInstruction are distinct; no finance/bank execution in Phase 7 |
| `docs/06-data/transition-tables/PROCUREMENT_AND_FINANCE_TRANSITIONS.md` | Candidate transitions with immutable issued PO/quote history | Do not turn proposed state names into frozen production states yet |
| `docs/03-product/role-experiences/PROCUREMENT_EXPERIENCE.md` | Purchasing work queue, comparisons, approval and delivery exceptions | Role UI is a draft; no claim of operator signoff |
| `server/.../warehouse/StockLedgerApi.kt` | Canonical StockItem catalog, balance and movements via existing warehouse-owned APIs | No procurement-maintained stock balance or direct StockMovement writes |
| `server/.../warehouse/ReservationApi.kt` | Source WorkOrder resource requirements, reservations and live candidates | A stock check is an evidence snapshot, not a reservation or permission to buy |
| `server/.../warehouse/ReceivingApi.kt` | Warehouse-owned receiving, accepted/rejected quantity, serials and discrepancies | No second Receipt/asset-intake owner that books the same stock twice |
| `server/.../approval/ApprovalEngineService.kt` | Existing version-bound approval requests, subject guards and authorization | No automatic policy keyed only to job title, amount or a guessed approver |
| Phase 2 audit / documents / idempotency / Work Queue | Reuse source audit, immutable attachment references, operation identity and existing actionable queues | No new competing queue, file store, audit engine or employee/supplier directory |

The existing `ReceivingApi` accepts `sourceType`, `sourceRef`, and `sourceLineRef`; it does **not** by itself prove that a canonical Procurement PO-to-receipt binding already exists. Freeze this adapter deliberately when that slice arrives.

## Domain ownership map

- **Procurement owns:** approved business demand, supplier RFQ/quote versions, comparison rationale, approved purchasing decision, PO versions/issuance, source linkage and purchasing exceptions.
- **Warehouse owns:** StockItem/stock quantity, reservation, receiving, serial/asset registration, inventory movement and discrepancy receipt truth.
- **Project/Work owns:** WorkOrder, Work resource requirement, project/site context and lifecycle authority.
- **Approval engine owns:** approver evaluation and immutable decision history; Procurement is a versioned approval *subject*.
- **Documents/Evidence owns:** upload, integrity/visibility and immutable file references; Procurement owns only its attachments' business linkage.
- **Finance (Phase 8) owns:** supplier invoice financial obligation/payable, payment execution and reconciliation. Phase 7 may prepare a match/handoff contract only; no payable or bank posting.
- **Organization/Identity owns:** employee, organization membership and supplier identity relationships. Do not create a second company/person login system.

## Business process facts to validate with HILTECH

These facts affect configuration and acceptance; they are not requests that staff design the software.

1. One redacted **real purchasing request** (WhatsApp message, form, spreadsheet or email): who requested what, for which project/site, and who checks existing stock?
2. One **supplier quote** and a second competing quote if available: quantities, units, part numbers, discounts, taxes, currencies, delivery and validity; whether phone/WhatsApp quotes count.
3. One **actual Purchase Order** or ordering message: PO numbering, issue authority, revisions, cancellation, and supplier acknowledgement.
4. One **delivery note + actual warehouse receipt**: partial quantity, incorrect item, damaged serial, receiving at warehouse vs direct to site.
5. One **invoice or price mismatch** path: who resolves a discrepancy and what Finance receives (a redacted example is sufficient).
6. Explicit **approval authority/policy**, including delegation, high-amount exceptions and urgent purchases. Do not presume Mohamed/Ahmed approval solely by title.
7. Source truth for **supplier organizations**, project budget/cost allocation and who may access competitor prices.
8. Record whether purchasing is per exact StockItem, catalog request, non-stock service, asset purchase or emergency purchase.

No supplier private prices, payroll/bank details, real buyer IDs or unredacted commercial documents are to be committed as test fixtures.

## Engineering invariants before first command

- Preserve source WorkOrder/project and requirement versions in the PurchaseRequirement. A changed source or withdrawn demand must be reflected, not silently approved on stale context.
- Check warehouse **current source** at authorization and before irreversible purchase issuance. A previous screen's 'available' quantity is not authoritative.
- A procurement request is not a StockMovement; a PO is not a StockReservation; a received delivery is not a Finance payment.
- Issued quote and PO versions are immutable. Amendments supersede with audit, preserve old supplier terms and re-evaluate approval on material changes.
- A quote from supplier A is not readable by supplier B or unprivileged employees. Distinguish procurement prices from general item descriptions in field-level permissions.
- Critical commands: stable operationId/idempotency, version preconditions, current organization membership, source-subject guards, concurrency tests and append-only events.
- Purchase approvals must bind the exact approved specification, amount/currency, supplier, destination and PO version. A new amount, supplier or quantity cannot reuse old approval.
- Partial delivery, over-delivery, replacement, rejection and discrepancy must be explicit; accepted stock movement is committed only by canonical Warehouse.
- An invoice-to-PO-to-receipt match is a record of evidence and exceptions; it is **not** a bank transaction or a claim that an invoice was paid.
- Internal procurement works without external supplier login or paid integrations. External supplier portal remains Phase 14.

## Phased gates

- **G0 — source audit:** this document, repository paths and domain ownership checked. No operator acceptance implied.
- **G1 — reality packet:** at least one source example per request, quote/PO, and receiving context; explicit permission/approval roles can be configured and tested. Sensitive samples stay out of Git.
- **G2 — contract freeze:** exact state machine, command/event/authorization map, data versioning, canonical Warehouse/Approval/Finance handoffs, offline classification and mobile/Windows screen behaviors.
- **G3 — vertical coding:** DB + domain + current-source authorization + API + KMP client + relevant Windows/Android UI + audit + tests in a *single slice*, without backend-only theater.
- **G4 — engineering verification:** PostgreSQL/OpenFGA contract, concurrent stock-check/approval race, cross-organization data separation, quote-version immutability, UI scenarios and exact-head GitHub CI.
- **G5 — operational acceptance:** representative purchaser + Warehouse receiver + approver walk a true request -> supplier decision -> PO -> actual receipt -> exception sequence on authorized devices.
- **G6 — owner-controlled merge / staging / activation:** never infer production activation from green CI. Phase 6 operator signoff and any overlapping integration safety remain explicit.

## Immediate next executable target

Propose **Phase 7 / Slice 01: Purchase Requirement + current-source stock-before-buy** as the first vertical, with a narrow configurable validation gate and **no issuing PO, no money movement and no approval threshold baked into code**. See `01_PHASE7_PROPOSED_SLICE_MAP_2026-11-22.md`.

This audit is not a Phase-7 design freeze and does not authorize skipping the Phase-6 operator acceptance or merging its stacked PRs.

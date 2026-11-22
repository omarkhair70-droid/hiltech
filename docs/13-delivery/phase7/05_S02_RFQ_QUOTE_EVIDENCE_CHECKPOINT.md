# Phase 7 / Slice 02 — Supplier RFQ and quotation evidence

Date: 2026-11-22
Base: stacked on Phase 7 S01 branch at `6b592b9a`, NOT main.
Status: engineering checkpoint; no production buyer authority or RFQ issuance.
Do not merge this stacked branch directly into main ahead of S01.

## Working S02 source model

S01 `READY_FOR_SOURCING` is a **technical decision**, not purchasing
authorization. S02 stores one immutable internal RFQ draft per requirement
version. The purchaser must be explicitly authorized before the RFQ can be
opened and quote evidence can be recorded. `SupplierSourcingAuthorizationPort`
defaults to denial; there is **no fall-through** to project manager, inventory
user, WorkOrder requester, owner name or arbitrary threshold.

V0042 adds:
- `procurement_supplier_rfq` immutable source snapshot, organization FK,
  linked original S01 requirement + captured technical requirement version;
- `procurement_supplier_quote_evidence` immutable supplier quotation
  version history with (RFQ, supplier, revision) uniqueness, source evidence
  reference + SHA-256, ISO-style three-letter currency, quantity, unit price,
  tax amount, validity, shipping/payment/warranty terms, created-by audit.
- Both tables use existing immutable trigger and do not duplicate an inventory
  or finance ledger.

**Data classification: restricted.** The read/compare path is gated by
the procurement sourcing authorization port. Supplier self-service, automatic
RFQ emails, competitor quote disclosure, exchange rate conversion, supplier
award, purchase order, finance approval and payment are **not implemented**.

## Financial/technical interpretation

The comparator is deterministic and read-only. Latest recorded revision per
supplier is projected while underlying older versions remain immutable.
Subtotal = quote quantity x quote unit price; total adds the recorded tax
amount, with full BigDecimal precision and **no silently rounded numbers**.

Comparison emits explicit issues when:
- currencies differ; it does NOT invent conversion rates;
- quoted quantity or unit differs from captured need;
- validity is missing/expired;
- technical compliance has not actually been assessed.

`supplierSelected` and `purchaseAuthorized` are always false.
Lowest amount is NOT a business award, and 3 supplier quotes are NOT a blanket
requirement. Any minimum sourcing count, escalation policy or approval
threshold would need a real versioned, non-bypassable source.

## Endpoints (all feature OFF/default denied)

- POST `/v1/purchase-requirements/{id}/supplier-rfqs`
- GET `/v1/purchase-requirements/{id}/supplier-rfqs` (buyer-only scoped RFQ list)
- GET `/v1/sourcing/organizations/{orgId}/eligible-needs` (buyer-only, technically ready needs)
- POST `/v1/sourcing/rfqs/{rfqId}/quote-evidence`
- GET `/v1/sourcing/rfqs/{rfqId}/comparison`

The shared KMP Android/Windows transport is in
`shared/core/.../procurement/SupplierSourcingApiClient.kt` with the exact
same typed routes, bearer identity, device id, idempotency header and
restricted-source errors. It is a client contract, **not yet a native buyer
screen**, and offers no offline write queue.

A dedicated **Windows buyer view** is mounted in the signed-in HILTECH
shell only if `HILTECH_SUPPLIER_SOURCING_UI_ENABLED=true` is explicitly
set. It reads eligible needs and existing RFQ rounds from real server
source history and can create a draft RFQ from a current eligible need
without hand-entering UUIDs. It can inspect recorded quote comparisons.
There is NO supplier quote entry form until a secure supplier lookup,
actual supplier-document links and restricted technical compliance workflow
are bound. The server sourcing feature and buyer authority still default
to **deny** independently of the optional UI flag.

Default feature: `hiltech.procurement.sourcing.enabled=false`.
All writes require authenticated matching Idempotency-Key and immutable
current S01 technical version; quote revision is locked and sequential.
There is no offline procurement write queue.

## Outstanding S02 code/operations before close

- [ ] Passing exact-head Kotlin tests and PostgreSQL migration V0042 contract
      (new dedicated GitHub Actions workflow in
      .github/workflows/phase7-s02-supplier-sourcing-contract.yml;
      isolated PostgreSQL service, no operator database changes)
- [ ] Bind purchasing/sourcing authorization to a real versioned company source,
      never infer from project manager technical permission
- [ ] Governed document/evidence repository integration and restricted links,
      including revision retention and download authorization
- [ ] Supplier contact, RFQ dispatch and revision lifecycle policy / UX,
      while keeping routine orders minimal
- [ ] Explicit technical compliance recording by the right role, with evidence
- [ ] Windows buyer view compiled + secure supplier selector / quote capture,
      evidence repository and connected-role E2E
- [ ] Review/CI/acceptance before merge; S01 remains dependency

Do not call S02 complete until these independent gates pass.

## Follow-on technical review checkpoint (branch: phase7/s02-technical-review-events-20261122)

Immutable technical assessments (V0043) attach to exact quotation evidence revisions, with
an explicitly separate default-deny technical reviewer, idempotent command, source-version
recheck, audit entry, justified sequential review revisions, and restricted buyer comparison
projection. They do not rewrite a quote, award suppliers or approve spending.

POST `/v1/sourcing/quotes/{quoteId}/technical-assessments` accepts a typed decision
(COMPLIANT / NON_COMPLIANT / NEEDS_CLARIFICATION), justification and expected review revision.
Neither the technical reviewer mapping nor governed quote documents is configured yet.
Do not call S02 closed or enable production. Exact-head CI/PostgreSQL proof is required.

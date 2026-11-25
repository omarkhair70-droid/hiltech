# Phase 7 / Slice 02 — Supplier RFQ and quotation evidence

## Current supplier register contract

This section supersedes the historic default-deny implementation and the
coupling between supplier administration and the sourcing rollout switch.
`SUPPLIER_MASTER_DATA_ADMIN` is a separately delegated, current company
responsibility. Its source is `supplier_master_data_authority_binding`; it is
neither a buyer grant nor an ApprovalEngine approval slot. Every read and write
requires an active HILTECH organization, active identity, current organization
membership and an effective direct registrar binding. Administration of that
binding belongs to the responsibility administration service.

The internal register is available by default; an explicit
`hiltech.procurement.supplier-master-data.enabled=false` remains an operational
kill switch. Buyer sourcing availability and approval requirements are
unchanged. Creating a supplier atomically creates its SUPPLIER identity,
company-local profile, first ACTIVE relationship event and audit record. Codes
and recording dates are assigned by the server. The command has a stable
operation ID and replayable receipt.

New company supplier codes use a transactional per-company sequence such as
`SUP-000123`. Buyer search and the register display the same local code. The
global technical identity remains immutable and separate. Existing linked
supplier codes are preserved and reserved; profile edits never renumber them.
Concurrent creation, command replay and rollback cannot duplicate issued codes.

Profiles and directory results are purchasing-organization scoped. Existing
linked suppliers may gain a local profile; changing that profile never renames
another company's global identity. Unrelated global suppliers are neither
listed nor linkable by guessing an ID. Profile corrections use a checked
version and immutable profile history. Active and inactive relationship events
remain append-only; suspension prevents new sourcing without deleting historic
quotes, orders or invoices. Reasons are operator attestations, not fabricated
verified documents. The native flow provides name search, create/edit, review,
confirmation, suspension/reactivation and exact-request retry after an unknown
network outcome. A command receipt and a subsequent directory refresh are
reported separately.

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
The follow-on native buyer form records linked suppliers, price/quantity/
terms and revisioned quotations only after a canonical restricted document
reaches READY; the technical reviewer remains separately authorized. The server sourcing feature and buyer authority still default
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

## Organization-scoped supplier directory — follow-on V0044

An active `organization_type=SUPPLIER` record is **not** a globally valid
buyer-supplier relationship. V0044 stores append-only supplier link events
per purchasing organization. An INACTIVE event at a higher revision revokes
an older ACTIVE event, without erasing its source history. The link source
requires a reference, actor and timestamp. A separate administrative API
has now been added, but it is gated by BOTH sourcing and
`hiltech.procurement.supplier-master-data.enabled` (default OFF) plus an
independent `SupplierMasterDataAuthorizationPort` (default deny). No
hypothetical company relationships or supplier approvals were seeded.

The read endpoint `GET /v1/sourcing/organizations/{orgId}/suppliers` is
buyer-only, feature-gated and organization-scoped. Recording an RFQ quote now
also requires the supplier to have a current active link for the RFQ owning
organization. Windows renders the returned current linked names, and the KMP
transport serves Android/Windows with the same restricted result.

**Still open:** evidence/document upload, buyer/reviewer role binding,
company-authorized supplier link creation, actual quote capture forms,
supplier outreach and final buyer acceptance. No production activation.

## Quotation document security gate (after supplier link)

The old source reference and caller-supplied SHA-256 are **not** by themselves
proof of a finalized, retrievable, restricted document. S02 now requires a
`SupplierQuoteDocumentVerificationPort` check in the same idempotent quote
command, bound to actor, purchasing organization, RFQ, supplier, source reference
and expected file hash. The default adapter denies ALL references.

This closes the arbitrary-reference write path without inventing cloud storage
or exposing supplier quotes. A genuine adapter must validate finalized object
bytes, source ownership, classification, virus/security status, and current
document access before the record-quote API may become usable. Do not enable
the sourcing feature until that adapter and role policy have been proven.

## Canonical supplier quote document pipeline — V0045

A buyer-only idempotent `POST /v1/sourcing/rfqs/{rfqId}/document-slots`
now creates an immutable, supplier-specific Evidence target. Each slot is
bound to one owning organization, one existing RFQ, one currently linked
supplier, an actor and an audited source operation. The slot is neither a
quote price nor a supplier award.

After this step, the **existing private Evidence infrastructure** accepts
`targetType=SUPPLIER_QUOTE_DOCUMENT`, `targetId=documentSlotId`,
`evidenceRequirementKey=SUPPLIER_QUOTE_BINARY`,
`evidenceTypeCode=SUPPLIER_QUOTE`. Only PDF / JPEG / PNG / WebP files
within the existing 16 MiB limit can be reserved. Reservation, private
object storage PUT, SHA/size/media type verification and finalization
reuse `/v1/evidence`; quotation Evidence is always
HIGHLY_RESTRICTED / INTERNAL_ONLY. The existing generic signed-download
endpoint deliberately refuses this classification.

`JdbcSupplierQuoteDocumentVerification` is the production binding for
`SupplierQuoteDocumentVerificationPort`. It checks an exact canonical
`evidence:<UUID>` reference, immutable slot, supplier + RFQ + organization,
READY Evidence and READY upload session, finalized SHA-256 and restricted
classification. An arbitrary URL, a supplier's other RFQ document, HR
Evidence or a quarantined/unfinished object can never become quote evidence.
Buyer authorization and an active supplier relationship are rechecked.

`SupplierQuoteDocumentUploader` reuses the authenticated KMP sourcing and
Evidence clients for a strict, online-only slot → reservation → private
binary PUT → finalize sequence. It exposes a canonical reference to quote
capture **only** on READY. It has no offline write queue.

**Latest native integration:** Windows now supplies a bounded PDF/image
file picker and JVM SHA-256, passes the bytes to the shared authenticated
supplier document uploader, and shows a buyer-only linked supplier/terms
capture form. The form fetches current quote revisions before append-only
submission; the server rechecks the revision atomically. No offline queue,
supplier notification, award, PO, invoice or payment was added.

**Remaining integration:** Prove real buyer/reviewer authorization and
supplier master-data authoring in the company's current policy source,
restricted document delivery/reviewer UX, and real connected-role E2E
with company-owned sample quotations. All sourcing features remain OFF
by default.

## Buyer-only protected quotation document retrieval

Quotation source Evidence is classified `HIGHLY_RESTRICTED`; its generic
`/v1/evidence/{id}/download-target` endpoint **still denies** presigned
downloads. The sourcing-specific
`POST /v1/sourcing/quotes/{quoteId}/protected-document` instead sends a
private authenticated buyer response with `Cache-Control: no-store`,
`X-Content-Type-Options: nosniff` and an attachment filename that is generated
from the immutable quote identifier, not from a user-supplied filename.

The protected server reads the immutable quote source, rechecks organization
buyer authority, current supplier link and source hash via the canonical
Evidence verification adapter, then verifies the exact READY/RESTRICTED
record, internal object key prefix, content type, capped length, actual
SHA-256 and file signature before returning a single bounded binary payload.
Successful disclosure writes a dedicated audit event. Unauthorized, unready,
cross-organization, unverified and tampered sources do not return bytes.

**Privacy boundary:** technical assessment authority does not imply access
to documents that may include prices or other commercially restricted terms.
A technical reviewer-specific redacted technical evidence workflow would
require a separately authorized, company-approved policy. There is no
automatic document export from the Windows buyer screen; this endpoint is
for an explicitly authorized internal client operation only.

The S3 private-read adapter is intentionally bounded to 16 MiB and does not
manufacture signed URLs. Disposable mock tests cover cross-tenant,
nonbuyer, quarantine and altered-object rejection. The real S3 contract
test additionally verifies the object size bound. Feature flag and
authority defaults remain DENY.

## Separate supplier master-data control (administrative S02 follow-on)

A company-authorized supplier registrar has a **separate** API and KMP
transport, not an unrestricted extension of buyer sourcing:
- `GET /v1/sourcing/organizations/{orgId}/supplier-registry?query=...`
  returns existing supplier organizations with current ACTIVE/INACTIVE link
  revision (or zero when no link exists), scoped to authorized custodians.
- `POST /v1/sourcing/organizations/{orgId}/supplier-links` appends an
  idempotent ACTIVE/INACTIVE event using the caller's recorded reference and
  expected revision number. Organization-row locking serializes concurrent
  supplier link changes; the V0044 mutation-rejection trigger protects history.
- Ordinary buyers and technical reviewers do not automatically gain supplier
  registry custodial authority. A globally active supplier organization
  cannot be quoted until its buyer-organization link is active.

The source reference is an **operator attestation**, not automatically
verified third-party evidence. The real company role/policy and trusted
supplier-record validation must be established before enabling the two
default-OFF gates. Revocation and reactivation retain history; there is no
auto-award, purchase approval, PO, payment or supplier email. The registry
returns names and revisions, not raw global company or staff details.

S02 engineering can be reviewed while the live mapping is still absent;
this is a deliberate rollout gate, not a reason to grant a fictional user
or to alter production settings.

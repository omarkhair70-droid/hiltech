# Supplier technical review contract

This contract describes the nonfinancial technical review path implemented with
migration V0125. It is an engineering contract, not a deployment or acceptance
claim. Source checks, PostgreSQL cases, native interactions and actual device
acceptance remain distinct evidence classes.

## Responsibilities and source boundary

The existing `PROCUREMENT_BUYER` responsibility prepares a technical packet for
an exact recorded supplier quote. The existing
`PROCUREMENT_TECHNICAL_REVIEW` responsibility reviews that packet. Both roles
continue to resolve through the current operating-authority source, company
membership and configured sourcing availability. No new technical role,
ApprovalEngine policy or project-management prerequisite is introduced.

The standalone technical queue is company scoped and searches item names and
codes, the company's supplier name and code, and the supplier quote reference.
Search text is literal, including `%` and `_`. Empty queues remain available to
the assigned responsibility. Results are paged; the current native client loads
40 records at a time.

Technical projection DTOs deliberately exclude prices, taxes, currencies,
payment terms and references to the priced quotation original. The pre-existing
protected quotation reader still requires buyer authority or a separately
assigned exact purchase-candidate review. Technical responsibility alone does
not satisfy either path.

## Preparing the packet

A packet records the offered specification, explicit deviations (including an
explicit statement when there are none), supplier technical source and a
traceable source reference. Manufacturer, model and source date are optional.
Publication requires a reason and the buyer's explicit confirmation that the
technical content and selected attachments contain no prices or payment terms.
The software does not claim to redact or recognize commercial terms inside a
document automatically.

Structured text with an identifiable technical source is sufficient. A second
PDF is not mandatory. Where an original must be consulted, the buyer uploads a
separate PDF, PNG or JPEG and selects it in the packet. Each file is capped at
16 MiB and a packet may select up to ten originals. A draft upload is not
disclosed to technical reviewers before packet publication.

Technical originals use the canonical Evidence reservation, private upload and
finalization lifecycle, with target `SUPPLIER_TECHNICAL_DOCUMENT`, evidence type
`SUPPLIER_TECHNICAL_SOURCE`, `HIGHLY_RESTRICTED` classification and
`INTERNAL_ONLY` visibility. Reservation and finalization require the slot's
creator to retain current buyer authority and the exact current quote and need.
Finalization checks authority again after storage verification, before marking
the evidence READY. No technical role receives generic Evidence access.

Packets are immutable numbered revisions. The RFQ lock serializes publication
against quote recording, technical assessment and candidate preparation. Each
packet pins its exact quote and current requirement version, technical content,
selected original hashes and a source digest. The historical packet retains
the supplier/item/source names presented at publication. A subsequent display
name correction does not rewrite that snapshot or manufacture a technical
change.

## Reviewing the source

The reviewer compares the pinned requirement and offered specification in the
same native screen, then records one of three existing assessment outcomes:

| Outcome | Required source |
| --- | --- |
| `COMPLIANT` | Exact current packet, explicit source-review acknowledgement, justification, and confirmed display of every attached technical original for this packet and reviewer. |
| `NON_COMPLIANT` | Exact current complete packet, explicit source-review acknowledgement and justification. |
| `NEEDS_CLARIFICATION` | Exact current quote and need, and the current packet identity when one exists. May describe a missing packet or unavailable original without claiming technical compliance. |

An original is returned only through a quote/packet/evidence-scoped reader. It
checks current company authority, exact evidence relationship, READY state,
size and SHA-256 before returning a bounded raster page. Authority and evidence
are checked again after private storage access. The shared protected page viewer
keeps bytes in session memory and validates PNG dimensions before decoding.
The renderer caps one page at 1800 pixels per edge, rejects unsupported formats
and applies the shared PDF/image allocation bounds. It does not buffer all
document pages or issue a public download URL.

Delivering a page and confirming its display are separate operations. The
native viewer confirms the exact receipt after successfully composing the
decoded page. This receipt proves display, not human comprehension or approval.
A receipt belongs to one reviewer, packet, original and hash and is not reusable
for a corrected packet. The source guard is invoked inside the existing
technical-assessment command, so calling that earlier endpoint directly cannot
omit the packet or its required proof.

## Corrections and purchasing

Publishing a technical correction makes the earlier assessment ineffective for
new purchasing decisions. Historical assessments remain visible. New purchase
candidates pin the exact effective assessment UUID, whose immutable row pins
the packet and its source digest. Both the purchase approval source guard and
issuance check that exact identity. A later COMPLIANT review cannot revive a
candidate prepared from a previous technical assessment. The buyer uses the
existing close/replace flow and prepares a successor candidate.

V0125 adds nullable source columns to preserve earlier rows. Existing issued
orders are not rewritten. A historical candidate without a provable exact pin
is not current for new approval or issuance. Earlier technical decisions without
a published nonfinancial packet remain history; a current packet and review are
required before preparing a new candidate. There is no synthetic backfill of
technical approval evidence.

## Native interaction and recovery

Windows and Android share the production queue, packet editor, review controls,
history, original viewer and typed API client. Wide screens present the working
form and bounded requirement/evidence context in two columns; narrow screens
stack them. Dates use the native shared calendar; supplier and item references
come from named source records. Arabic and English strings and RTL/LTR behavior
use the shared language and design components.

Packet and assessment commands retain their exact operation and payload in the
full-session-bound client. An uncertain response does not become a new command
on navigation, role rejection or sign-in recovery. A definitive current
rejection allows correction; a later denial cannot establish whether an earlier
lost response committed. Success acknowledgement is retained if the subsequent
source refresh fails.

Uploads retain the slot, reservation, evidence and successful PUT checkpoints.
A lost finalization response first checks canonical metadata; READY recovers the
receipt, otherwise the same finalization is retried. Successful PUT bytes are
not uploaded again merely because finalization is uncertain. File bytes and
signed URLs are not persisted by the workflow. An expired, unfinalized upload
has an explicit end-attempt action; no uploaded file is silently published.

## Verification boundaries

`SupplierTechnicalReviewSourceTest`, the existing supplier/candidate source
tests and `SupplierTechnicalWorkflowTest` exercise source guards, receipt
validation, exact retries and upload recovery. `SupplierTechnicalPostgresContractTest`
uses actual migrations, JDBC command transactions, operating authority and
Evidence lifecycle with explicitly synthetic in-memory binary storage. It
covers company scope, historical names, the priced-document boundary, text-only
review, original delivery versus display, correction, revocation during storage
read/finalization, immutable records and concurrent publication.

`PurchaseOrderReviewPostgresContractTest` also verifies that a technical packet
correction followed by a new compliant review leaves the former candidate stale
and that its real successor captures the new exact assessment. PostgreSQL tests
require the existing contract-test environment and normal database settings;
they must not be reported as executed when skipped. Actual device/server
journeys and provider transport are separate acceptance evidence.

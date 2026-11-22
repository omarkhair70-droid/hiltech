# Phase 7 S07B — Service supplier RFQ and protected quotations

Date: 2026-11-23. Branch: phase7/s07b-service-supplier-quote-20261123.
PR #178 stacked on S07A #174, which is stacked on S06 #162.
Status: engineering draft, no merges and all commercial flags OFF.

## Source-bound path

An actual active HILTECH Team service need has been submitted and independently
marked READY_FOR_SOURCING (S07A). Only a buyer with an explicitly configured,
current ApprovalEngine service-buyer authority may open a version-frozen
SERVICE_RFQ. Existing supplier registry and its current organization-specific
active links are used; no duplicate supplier registry or invented quote authority.

Each supplier quote has a dedicated document slot. The existing Evidence reserve,
upload, finalize and storage path is extended with a distinct
SERVICE_QUOTE_DOCUMENT target. Slot/binary metadata is current-source scoped,
HIGHLY_RESTRICTED, INTERNAL_ONLY and restricted to PDF/image. PostgreSQL denies
non-matching organization, unverified supplier, source or non-READY binary.
Supplier quotation terms, amount, tax, currency and revision are append-only.

A current, authorized buyer can read a restricted comparison; it does not choose
a winner, issue a service order or authorize payment. Highly restricted quote
files can be delivered through a separate audited, hash/magic-verified,
no-store, protected endpoint rather than a generic direct object URL.

The typed KMP client calls real APIs; the shared app shell is not modified.
No fake WorkOrder, StockItem, Warehouse receiving or stock three-way matching.

## Required proof before promotion

- Exact-head S07B Actions: disposable PostgreSQL all migrations, domain unit
  tests, Android/Windows build, full combined Bootstrap/Modulith after DevOps.
- Actual company-authorized buyer vs requester/reviewer revocation, current
  supplier link transitions, wrong-org/replay/stale source and artifact access.
- Private file visibility and hash/proof on actual files; protected bytes only.
- Pending service-order approval, service acceptance and invoice/Finance path
  are separate vertical contracts; this PR alone is not Phase 7 closure.

## Migration/DevOps ownership

V0061 (S07A) and V0062 (S07B) are provisional and conflict with Finance
#164/#175 which provisionally use the same numbers. DevOps #173 owns final
gap-free allocation. Keep every branch Draft until numbering and ordered merge
are verified. The S07B branch adds changes to EvidenceLifecycleApi,
EvidencePersistencePort and JdbcEvidencePersistence; DevOps/Documents integrator
must resolve those shared edits with other branches. NO live supplier dispatch,
approval to buy, invoice payable or payment feature is enabled.

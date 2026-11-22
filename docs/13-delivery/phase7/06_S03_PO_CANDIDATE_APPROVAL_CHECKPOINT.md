# Phase 7 / Slice 03 — Buyer Selection → Canonical Approval → Issued PO

Date: 2026-11-22
Status: **S03 implementation checkpoint / PO approval and issuance NOT YET IMPLEMENTED**
Base: stacked on S02 PR #148 (which itself follows #146 → #143).
No main merge or live company purchasing enablement.

## Role reality and no fictional permissions

We already have the HILTECH team model and do not need to ask for an
organizational list again to implement the internal procurement engine.

- **Mohamed** is HILTECH owner / company decision maker. His product home
  is management-by-exception; routine engineering checks and purchase
  processing must not become obligatory owner approvals.
- **Mostafa and Ayman** are engineers. The product supports project engineer
  technical reviews and source-linked material requests, not implicit
  authority to issue a PO, execute payments or inspect unrelated commercial
  supplier offers.
- **Technicians** submit field needs and technical evidence in their
  assigned WorkOrder context, with offline-first field capture where
  permitted. They do not receive company buyer permissions by default.
- **Warehouse keeper** is a distinct internal operational function and
  the warehouse source owns reservations, custody, transfers and receiving.
  Materials may go via the main warehouse or a project/site warehouse;
  procurement must never create a second stock ledger.
- **Ahmed / Finance-Admin** sees authorized payables, invoice matches and
  exceptions. Payment is owned by Finance's later domain, not by the
  purchase sourcing service.
- **Project manager, buyer, supervisor and reviewer** are contextual
  responsibilities; team/role assignment can change via current
  RoleTeam/Approval authority bindings, not a list of hard-coded names.

Existing documentation: `docs/03-product/ROLE_COVERAGE_MATRIX.md`,
`docs/07-security/AUTHORIZATION_MATRIX.md`,
`docs/03-product/role-experiences/{OWNER_MOHAMED_EXPERIENCE,FINANCE_AHMED_EXPERIENCE,ENGINEER_EXPERIENCE,WAREHOUSE_EXPERIENCE,PROCUREMENT_EXPERIENCE}.md`,
`docs/05-workflows/PROCUREMENT_FLOW.md`, and
`docs/05-workflows/APPROVAL_POLICY_MODEL.md`.
Those are real domain/persona plans, but not records that a particular
person can authorize a specific amount. The implementation must remain
safe and configurable without inventing that additional fact.

## New coded S03 candidate boundary: V0046

An explicit authorized buyer can select one current, verified supplier
quote from a current sourcing round. Before recording, the server:
1. Checks company buyer capability and exact RFQ/organization scope.
2. Re-reads technical request WorkOrder/StockItem source and expected
   requirement version via S02's existing comparison service.
3. Confirms **latest quote revision** per supplier, ACTIVE company supplier
   link, READY finalized restricted quotation file and exact SHA-256.
4. Requires technically COMPLIANT review; checks quantity/unit and
   quote validity. A warning/error does not silently become permission.
5. Requires a human rationale; the RFQ row is locked against concurrent
   quotation revisions before checking and freezing the candidate.
6. Persists V0046 `procurement_po_candidate` as immutable, linked to the
   exact RFQ, requirement, quote and stock item with immutable quantity,
   currency, price, tax, computed total, evidence SHA and buyer audit event.
7. Uses the existing idempotency runtime. A repeat command cannot produce
   a second candidate or rewrite historical evidence.

Endpoints:
- `GET /v1/sourcing/rfqs/{rfqId}/po-candidate-preflight?quoteId=...`
  — buyer-only preview listing source, supplier, quote or compliance
  blockers; no write, award or approval.
- `POST /v1/sourcing/rfqs/{rfqId}/po-candidates`
  — buyer-only, idempotent explicit candidate proposal after all checks.

Typed shared KMP client: `PurchaseOrderCandidateApiClient`.
Both endpoints are behind existing sourcing flag AND the new
`hiltech.procurement.po-candidates.enabled=false` default-OFF gate.
`purchaseAuthorized=false` and `purchaseOrderIssued=false` always.
The first schema deliberately permits **one initial immutable candidate**
per RFQ; a change after candidate creation needs an explicit supersession
lifecycle, not a silent mutation or second competing active selection.

## Remaining S03 engineering (NOT DONE)

- The canonical ApprovalEngine subject-version guard is now implemented
  for `PROCUREMENT_PO_CANDIDATE` version 1. It checks the immutable
  candidate, RFQ/source revision, current WorkOrder and StockItem, supplier
  link, latest quote revision, technical COMPLIANT decision, quote validity,
  and READY restricted Evidence. A changed fact invalidates the subject.
  **Still to implement:** initiating the correct ApprovalEngine request,
  selecting the company's active policy/authority version, and consuming
  the final decision for PO issue. No hard-coded Mohamed/Ahmed or thresholds.
- Human approval or explicitly configured no-approval path, authorized PO
  issue/numbering/delivery destination and supplier-facing PO version.
- Amendment/supersession/cancellation/reapproval rules and native buyer /
  approver / owner experiences.
- Full PO issue action needs verified company authority and must remain
  impossible until approval/PO policies exist.
- Cross-role acceptance with company-owned example sourcing documents.

This is a safe engineering checkpoint, not an invented business policy or
a statement that Slice 03 is complete. Source-version, unit, price, Evidence
and supplier relationship remain authoritative and rechecked at every
future financial side effect.

## Native Windows buyer preflight

The existing restricted buyer sourcing section exposes the typed S03 API only
when `HILTECH_PO_CANDIDATES_UI_ENABLED=true`. A buyer explicitly opens
preflight for a comparison row, sees blockers, and supplies a rationale before
recording an internal DRAFT_FOR_APPROVAL candidate. The client rechecks the
preflight just before submission; the server independently rechecks the
entire source under RFQ row lock. Read/write requires both server feature
flags plus the existing scoped buyer authorization. No offline queue, owner
approval claim, supplier email, accounting operation or issued PO.

The ApprovalEngine's `ApprovalSubjectVersionSource` implementation is the
trusted **current-source check**, not a new permissions mechanism. An owner,
engineer, procurement user or warehouse keeper does not gain any extra
financial authority by appearing in the domain documentation. The
company's actual approval bindings remain in the canonical ApprovalEngine.

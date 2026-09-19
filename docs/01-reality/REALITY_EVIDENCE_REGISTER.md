# HILTECH Reality Evidence Register

Date: 2026-09-19
Status: **ACTIVE / PHASE-QUEUED**

## Purpose

Preserve durable product learning from real HILTECH operating material without committing the raw private source files.

This register supplements `REALITY_FACTS_REGISTER.md`.

Evidence classes:
- VERIFIED_DOCUMENT — directly observed in a supplied internal document.
- INTERNAL_REPORTED — reported internally but not independently proven from a source document.
- SYNTHETIC_VALIDATION — representative fixture/proof only.

An entry can be recorded now and consumed only when its affected phase arrives.

---

## RE-001 — Daily field/work allowance ("البوكت")

Evidence: **INTERNAL_REPORTED**

Observed/reported operating meaning:
- a per-person daily allowance can be earned for an assignment/work day,
- it is not restricted to technician vs engineer role,
- the daily rate can vary by assignment/location/work context,
- transport execution/cost is a separate concern from the employee allowance.

Product consequence:
- model it as a configurable compensation earning component,
- eligibility must be linked to real assignment/day/context rather than free text,
- rate/policy must be versioned/configurable, not hard-coded,
- transport/travel expense must remain a separate object/accounting path.

Consume in:
- Phase 4/6 for assignment/work-day context,
- Phase 9 Payroll for earning calculation,
- Phase 8 Finance where actual transport expense/custody is relevant.

Does not block Phase 1.

---

## RE-002 — Legacy payroll files require reconciliation/provenance

Evidence: **VERIFIED_DOCUMENT**

Observed:
- legacy payroll workbook structure contains monthly employee components and formula-driven totals,
- at least one inspected workbook contained broken/external formula behavior and a detail-vs-summary inconsistency.

Product consequence:
- imported payroll is never trusted solely because Excel has a total row,
- preserve source/import provenance,
- recompute component/employee/period totals,
- surface reconciliation exceptions before approval,
- broken formulas/references become explicit import errors rather than silent values.

Consume in:
- Phase 9 Payroll.

Recommended future sanitized fixture:
- synthetic payroll import with one broken reference and one non-reconciling summary.

Does not block Phase 1.

---

## RE-003 — Stock identity and costing must be separated

Evidence: **VERIFIED_DOCUMENT**

Observed in warehouse/inventory ledgers:
- Brand/Manufacturer and Manufacturer Part Number are operationally important identifiers,
- the same manufacturer part can appear in different stock contexts/receipts with different recorded unit prices,
- legacy rows can mix descriptive/template fields inconsistently.

Product consequence:
- StockItem master identity includes manufacturer/brand + manufacturer part number where applicable,
- supplier/legacy aliases can be modeled separately,
- cost is not treated as one timeless master-product price,
- receipt/lot/cost layer or valuation event owns historical acquisition cost,
- import normalization validates contradictory legacy fields and retains source provenance.

Consume in:
- Phase 5 Assets / Warehouse.

Recommended future sanitized fixture:
- same MPN received twice at two different costs.

Does not block Phase 1.

---

## RE-004 — Progress measurement / interim client claim is distinct from invoice

Evidence: **VERIFIED_DOCUMENT**

Observed in project/commercial material:
- awarded BOQ quantities/rates feed measured execution,
- previous/current/cumulative quantities are tracked,
- a contractual due percentage can be applied,
- tax is calculated,
- the resulting claim/certificate precedes or feeds invoice/receivable handling.

Product consequence:
- do not collapse progress measurement into Project progress,
- do not collapse interim claim/certification into ClientInvoice,
- later contract closure must define the chain:
  `BOQ → Measurement → Interim Claim/Valuation → Review/Certification → Invoice → Receivable`,
- exact retention/adjustment/tax/certification semantics remain phase-local until validated.

Consume in:
- Phase 8 Finance Operations,
- Phase 10 Sales / Tenders / Commercial,
- Project/Work read models where measured/accepted quantities are needed.

This is a later-domain refinement and does not reopen the frozen first production slice.

---

## RE-005 — Raw Excel is evidence, not canonical truth

Evidence: **VERIFIED_DOCUMENT**

Observed across payroll/inventory/project workbooks:
- spreadsheets can contain copied template columns,
- formula references can break,
- the same real-world concept may be duplicated across multiple sheets,
- human-entered totals/labels can conflict with row-level data.

Product consequence:
- importers must validate and normalize,
- authoritative domain state is created through typed import/review commands,
- raw source provenance is retained outside normal business truth,
- generated system documents should derive from canonical objects instead of maintaining several manually synchronized sheets.

Consume in:
- every phase that introduces legacy-data import.

---


## RE-006 — Owner coordination load is a primary internal-OS problem
Evidence: **INTERNAL_REPORTED**

Reported operating meaning:
- Mohamed carries substantial routine calls, follow-up and cross-site/person coordination,
- HILTECH OS should reduce status chasing and make exceptions/required decisions visible.

Product consequence:
- later management surfaces should optimize for exception visibility, ownership and pending decisions,
- do not turn this into hard-coded "Mohamed screens"; model authority/role/context so the system survives organizational change.

Consume in:
- Phase 3/4 Project/Work management,
- Phase 7 reporting/dashboard,
- approval/inbox slices where pending decisions surface.

---

## RE-007 — Tool custody currently uses photo + WhatsApp handoff
Evidence: **INTERNAL_REPORTED**

Reported operating meaning:
- Osama is reported as the daily storage handler,
- issue flow commonly includes photographing tools and sending the update through WhatsApp/to Ahmed,
- return is expected after work/day according to the real case,
- asset value/custody risk is more important than warehouse volume.

Product consequence:
- photos are Evidence, not the authoritative movement ledger,
- Warehouse/Asset UX must make issue/return faster than the current manual handoff while preserving who/what/context/time/condition,
- exact acknowledgement/return exception rules remain validation work.

Consume in:
- Phase 5 Assets / Warehouse.

---

## RE-008 — Finance terminology must remain distinct until Ahmed/Dr. Mohamed reality validation
Evidence: **INTERNAL_REPORTED**

Reported operating meaning:
- Ahmed Fawzy and Dr. Mohamed participate in employee/work-related payment/disbursement operations,
- salary, advance, financial custody/imprest, Pocket Money/site allowance terminology and reimbursement are not interchangeable concepts.

Product consequence:
- preserve distinct domain candidates,
- do not assign accounting semantics from colloquial names,
- current Excel/files and direct workflow observation are evidence inputs, not canonical truth.

Consume in:
- Phase 8 Finance Operations,
- Phase 9 Payroll.

---

## RE-009 — Field capture should combine WhatsApp-like speed with structured official transitions
Evidence: **INTERNAL_REPORTED**

Reported operating meaning:
- field staff need fast text/photo/file/voice/problem/completion capture tied to context,
- heavy forms for every observation would not match the reported operating reality.

Product consequence:
- contextual notes/evidence remain low-friction,
- formal state transitions stay typed, versioned, authorized and audited,
- do not infer a full chat application without a separate product decision.

Consume in:
- Phase 4 Work Execution,
- Phase 2 Evidence/Inbox foundations where applicable.

---

## RE-010 — Arabic-first and conditional capabilities
Evidence: **INTERNAL_REPORTED**

Reported operating meaning:
- internal usage is primarily Arabic,
- active-task location features may be useful when policy/workflow requires them,
- CCTV/NVR/access control/NOC/GPS-IoT/specific bank or accounting integrations are not confirmed current reality.

Product consequence:
- preserve Arabic-native/RTL/Bidi requirements,
- keep tracking configurable and task-scoped rather than assuming all-day monitoring,
- keep unverified integrations conditional until their phase proves a real authoritative boundary.

Consume in:
- all UI/business phases for Arabic,
- field/location phases for tracking,
- integration phases only when reality validation promotes a conditional capability.

---

## RE-011 — Sales/purchases invoice report proves real tax-document and counterparty lifecycle
Evidence: **VERIFIED_DOCUMENT**

Source reviewed:
- user-supplied customized sales/purchases Excel report,
- raw workbook remains outside GitHub under the raw-evidence policy.

Observed document reality:
- the report separates sales invoices sent from purchase invoices received,
- observed coverage spans purchases from 2022-07-24 through 2026-05-19 and sales from 2023-01-29 through 2026-05-14,
- the supplied report contains 158 sales invoice rows and 115 purchase invoice rows,
- counterparties are identified by legal/display name and tax-registration identifier,
- invoice-level fields include value before discount, discount, value after discount, VAT, schedule tax, withholding/tax-account deduction, service charge and total,
- negative invoice/adjustment rows exist on both sales and purchase sides, so correction/return/credit semantics are real and cannot be modeled as deletion,
- recurring customers and suppliers appear across many invoices, proving Counterparty identity/history is a durable business concept,
- different tax-field combinations occur across invoices; tax handling cannot be one fixed percentage assumption,
- the report does not contain authoritative bank/payment/collection/reconciliation status.

Product consequence:
- Finance/Sales/Procurement must preserve Invoice identity, exact counterparty tax identity, line/document tax totals and immutable adjustment/credit relationships,
- Client/Supplier invoices must not be collapsed into generic Payment objects,
- negative corrections/returns require explicit document relationship/state rather than overwriting the original invoice,
- counterparty identity should be normalized once and referenced across Sales/Procurement/Finance,
- tax treatment must be data/configuration-driven and retain imported source provenance,
- invoice issuance/receipt proves an obligation/document state only; it does **not** prove money was paid or collected,
- bank/payment execution and reconciliation remain a separate authoritative boundary,
- future import must reconcile external invoice report values rather than trusting duplicated manual totals.

Consume in:
- Phase 8 Finance Operations,
- Phase 10 Sales / Tenders / Commercial,
- Procurement/Supplier invoice lifecycle,
- accounting/e-invoice integration work when the actual authoritative system is validated.

This evidence does not reopen or expand Phase 2 Slice 04 Approval scope.

---

# Current phase rule

Phase 2 implementation proceeds normally.

Entries above are queued for their relevant business phases and must not cause speculative implementation, Phase reset, or reopening of verified Phase 1 work.

If a later phase discovers a genuine cross-cutting contradiction, use formal change control rather than silently rewriting earlier contracts.

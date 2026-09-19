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

# Current phase rule

Phase 1 implementation proceeds normally.

Entries above are queued for their relevant phases and must not cause speculative implementation now.

If a later phase discovers a genuine cross-cutting contradiction, use formal change control rather than silently rewriting earlier contracts.

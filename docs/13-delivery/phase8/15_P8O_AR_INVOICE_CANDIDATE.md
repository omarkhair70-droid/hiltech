# Phase 8O — Customer invoice candidates (isolated draft)

Date: 2026-11-23. This is **not** invoice issuance, AR recognition, customer
collection, tax certification or production billing.

## Real source
Phase10 publishes `commercial_finance_award_source` only after confirmed
commercial release. Finance 8J reads the exact source and SHA-256 fingerprint.
Award total, signed contract and Client PO are evidence of a commercial award,
NOT independently authorized billing milestones or legal tax invoice issuance.

## Implemented
- `FinanceArInvoiceCandidateRules.validate` rejects cross-party billing,
  invalid award fingerprint/currency, nonpositive/over-award invoice values,
  missing independently verified billing milestone evidence, legal tax review,
  contractual billing authority or issue/due dates.
- `pending_ar_invoice_candidate.sql` defines unnumbered immutable draft
  storage with organization/customer, commercial fingerprint, billing evidence
  FK, and no default issuer, invoice number or payment status.
- Negative JUnit tests; no financial or external customer side effects.

## Remaining before an invoice can be issued
- Actual P10 source/materialization and verified billing milestone/Evidence
  ownership checked inside a transaction, not just trusted client fields.
- Canonical company tax invoice numbering, accounting and tax approvals,
  issue/credit-note rules, immutable issued document and audit trail.
- Source-level locking to ensure cumulative issued instalments cannot exceed
  verified billable amount; reject stale sales quotes and duplicates.
- Signed issuance and AR double-entry posting with independent authority.
- Customer collection bank confirmation, partial allocation, reversal and aging.
- Windows and Android screens and native end-to-end flow, DevOps migration
  numbering, disposable PostgreSQL CI and company acceptance.
- All flags default OFF until authorized company policy and stage integration.

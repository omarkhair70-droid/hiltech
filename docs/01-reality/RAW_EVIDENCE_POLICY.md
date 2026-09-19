# HILTECH Raw Operational Evidence Policy

Date: 2026-09-19
Status: **ACTIVE**

## Rule

Raw internal company documents are private evidence, not normal Git source artifacts.

Do not commit raw operational files merely because they helped product discovery.

Examples:
- payroll spreadsheets,
- salary/payment sheets,
- bank exports,
- employee files,
- raw purchase/sales invoices,
- unredacted client BOQs/claims,
- unredacted warehouse exports,
- credentials/secrets.

## Why

Raw business files can contain:
- employee compensation,
- customer/commercial pricing,
- names/contact details,
- operational references,
- formulas/errors that are evidence but not canonical truth.

They are also binary and poor code-review artifacts.

## Repository representation

When a raw source teaches the product something durable, commit one or more of:

- a fact in `REALITY_FACTS_REGISTER.md`,
- a concise entry in `REALITY_EVIDENCE_REGISTER.md`,
- a sanitized synthetic fixture,
- a typed contract/object change,
- a phase-local decision record.

Do not copy unrelated rows or commercially sensitive values just to prove the source existed.

## Sanitized fixtures

A fixture may be committed when:
- it is synthetic/redacted,
- it contains no real secret or unnecessary personal/commercial data,
- it reproduces the structure/edge case needed for a test,
- its purpose is documented.

Good examples:
- a payroll fixture whose totals intentionally do not reconcile,
- two stock receipts for the same manufacturer part number at different costs,
- a synthetic progress claim with previous/current/cumulative quantities.

## Phase consumption

Evidence may be recorded before its implementation phase.

Recording it does not authorize premature implementation.

The relevant phase must still perform its own contract/design/reality closure before production code is added.

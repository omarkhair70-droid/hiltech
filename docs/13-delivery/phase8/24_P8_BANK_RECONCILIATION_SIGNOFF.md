# Phase 8 original reconciliation closure — internal company signoff

Engineering scope: source-backed **internal** statement matching, not a bank
gateway callback or legal statutory external audit. No new Phase 8 module.

- V0097 follows reconciled V0001–V0096. A bank statement is already
  independently imported, evidence-attached and immutable by V0087.
- All original bank rows must be **completely** covered by effective,
  independently approved posted AP/AR journal allocations. Employee advance,
  unclassified charges or bank fees remain unmatched until accounted for in
  their own recognized ledger; they cannot be waved through.
- Reconciliation proposal locks each statement line and hashes exact IDs,
  amounts, flow, source allocation IDs, and original statement SHA. Partial
  or overallocated statement rows cannot be proposed or signed.
- Independent configured company Finance reviewer approves the exact subject
  version; separate operator requires finalized restricted signed evidence
  tied to the proposal. The record is append-only and cannot be signed twice.
- Transaction locks and late-allocation/void triggers prevent a concurrent
  allocation or correction from changing a statement AFTER it was signed.
- Native Windows/Android typed API supports prepare, review, sign and status.
  Distinguishes `internallyReconciled` from `verifiedByBankProvider`.
- Settings default OFF: `hiltech.finance.bank-reconciliation.signoff-enabled`,
  `.signoff-policy-key`, `.signoff-preparer-authority-key`,
  `.signoff-operator-authority-key`, `.reader-authority-key`.
  Authority mappings, reviewer and real bank provider authenticity are
  company inputs — none are seeded or inferred.

Acceptance: PostgreSQL Flyway migration, immutable/forgery rejection,
signed hash/version unit test, native HTTP contract, independent PG guards,
positive approved split-collection regression and company-authenticated
operator E2E in unified staging. A green compile does not establish the last
two; never silently claim production close.

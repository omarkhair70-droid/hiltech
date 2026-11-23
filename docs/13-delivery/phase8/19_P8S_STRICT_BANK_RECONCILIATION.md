# Phase 8S — Strict bank reconciliation preflight

Branch: 8S stacked after 8R. Draft, default OFF. No bank connection or money movement.

Implemented pure Finance domain rules for finalized verified bank statement
lines, exact immutable bank transaction IDs and SHA-256, direction/currency,
opening-to-closing balance tie-out, amount-per-bank-line limits, separate
source-ledger approvals, partial allocation exceptions, deterministic approval
snapshot and an explicit `reconciled=false` until separate accounting authority.

Positive tests cover full and partial matching; negative tests reject forged
or duplicated statement lines, incorrect closing balance, wrong flow, foreign
bank source IDs, incomplete import, unposted/unapproved allocations and bank
over-allocation.

**Not yet operational:** verified bank statement import or connector, canonical
bank account registry, locked append-only matching tables, independently
approved reconciliation record, adjustment/reversal events, UI, statement
evidence ownership, runtime test with live company fixture, and DevOps
migration numbering and release.

Do not publish bankReconciled=true, paidAmount or cash balance based on these
preflight rules alone. No real bank transactions are initiated.

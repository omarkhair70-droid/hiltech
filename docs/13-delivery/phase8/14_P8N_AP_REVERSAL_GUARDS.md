# Phase 8N — append-only AP reversal (Draft)

Status: **isolated engineering implementation**. Stacked on 8M. Feature defaults OFF.
No company authorization, production migration numbering, actual money transfer,
or operational activation has been performed.

## Implemented on this branch
- `FinanceApReversalRules`: checks source/target org, currency, source balance,
  exact mirror lines, duplicate/no backdate, and close-event safety.
- `finance_ap_reversal_proposal`, `finance_ap_journal_reversal` and mirrored
  line table in **unnumbered** `database/phase8/pending_ap_journal_reversal.sql`.
  Foreign keys, immutable triggers, separate independent approval source,
  deferred completeness, and unique original journal guard.
- `FinanceApReversalApprovalSubject` recomputes a deterministic version
  from immutable original lines, target period, reason, policy and authority.
  Rejects stale source, policy/authority changes and closed period.
- `FinanceApReversalService` implements guarded propose → request approval →
  post → status, with typed REST endpoints, authenticated identity,
  company membership/authority, idempotency, audit events and open-period
  transactional locking. No bank reversal/refund inferred.
- 8L period closure source view V2 incorporates original AP and reversal
  events in its fingerprint; an in-flight period close cannot ignore reversals.
- JUnit reversal rule, approval version and disposable PostgreSQL schema tests.
  The latter tests DDL and rejects forged source inserts; **not** yet a
  complete approved posting integration fixture.

## Explicit safety gates before activation
1. CI must pass on exact head (unit and PostgreSQL; service compile included).
2. Add an **approved positive PostgreSQL posting fixture**, including exact
   independent request/reviewer/operator, and negative race tests for period
   close versus reversal, duplicate operations, and stale approvals.
3. Verify all SQL checks for approval source, precise accounting period
   scope, concurrent proposal/period locks, and independently approved
   accounting policy in real staging. No seeded users, chart or policies.
4. DevOps assigns migration version after review and resolves conflicts with
   P7/P8/P9/P10/P11/P13. Existing branch remains Draft.
5. Integrate reversal into AP ledger evidence/reporting. Until actual
   settlements and verified reversals are included, `outstandingBalance`
   and `paidAmount` must remain nullable/unavailable.
6. Mount screens in Windows/Android and test role-based end-to-end workflows.
7. Phase 8 still additionally requires AR/customer invoices, expenses &
   imprest settlements, payment and collections source, bank reconciliation.

## Critical domain distinction
An AP journal reversal is an **accounting adjustment**, never a bank refund,
a payment cancellation or a second write to the immutable original. A closed
source period can only be corrected in a separately approved open later period.

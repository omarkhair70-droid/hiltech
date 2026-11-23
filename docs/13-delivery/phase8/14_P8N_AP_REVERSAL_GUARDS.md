# Phase 8N — AP reversal preflight (implementation slice 1)

Status: **Draft / not production-operational**. Stacked on 8M and unmerged P8K/8L migrations.

## Code delivered
- Pure `FinanceApReversalRules.plan` builds equal-and-opposite journal lines using original immutable posted ledger lines.
- Denies different organization, currency mismatch, duplicate reversal, duplicate source lines, unbalanced amounts, malformed lines, and backdated reversal.
- A source whose period is already closed is not edited. Its correction may only be posted in a later open period. Target periods closed by the append-only close event are denied.
- JUnit tests for all these denial cases; no DB mutations, no bank execution, no user-facing claims of reversal.

## Required before this can post anything
1. Add **unnumbered** append-only reversal journal schema with unique original journal FK, exact mirrored lines, source-hash snapshot, independent approval ref, and DB triggers rejecting UPDATE/DELETE and preventing concurrent duplicate reversals.
2. Revalidate source/current period state under the same transaction and period row lock as P8K and P8L. Reject stale signed approvals, changed posted source and invalid user authority; integrate shared IdempotentCommandExecutor + AuditEventWriter.
3. Define approved company Finance reversal policy and distinct preparer/reviewer/posting operator authority; feature default OFF.
4. PostgreSQL contract tests for concurrent inserts, closed-period race, exact same org/source/currency, balancing and independent approval.
5. Update supplier ledger report to distinguish historical gross, independently confirmed reversals, and true outstanding (still NULL until real payments).
6. Coordinate globally numbered migrations and combined app/server CI with DevOps.

Do not treat preflight or a draft PR as a booked accounting reversal. No money movement is allowed.

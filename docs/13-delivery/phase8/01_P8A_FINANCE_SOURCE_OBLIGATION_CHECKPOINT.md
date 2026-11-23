# HILTECH Phase 8 / 8A — Finance supplier obligation intake

Date: 2026-11-22
State: **ENGINEERING DRAFT, NOT ACCOUNTING POSTED, NOT RELEASED**

## Actual upstream sources
- Branch starts from Phase 7 S06 PR #162, itself stacked on #158. Both are **Draft** and neither is production acceptance. The planning brief remains in PR #161.
- An intake is possible only after P7 immutable supplier invoice with verified READY Evidence, actual PO/delivery three-way MATCHED event, and Finance handoff. The current match fingerprint is recomputed through the existing S05 service while the PO row is locked.
- Source amount, invoice, currency, supplier, match revision and Evidence identity come exclusively from P7 database; no caller-supplied amount or arbitrary accounting source.
- A Finance candidate is an immutable **ACCOUNTING_REVIEW_REQUIRED** evidence record. It is NOT yet an approved payable, journal entry, creditor balance, payment preparation, pay instruction, bank transfer or payroll input. No accounting status may be inferred from source capture.

## This branch owns
- Server: FinancePayableCandidateService and isolated /v1/finance read/intake routes.
- Database: **provisional** V0061 Finance candidate with source-integrity trigger, compound organization FKs, immutable record, duplicate invoice/handoff/idempotency prevention.
- KMP: typed online Finance API + Compose review surface for Windows and Android compilation; Windows capture is an optional integration-owner mount, Android initially read-only. This branch **does not edit** shared Windows Main, Android MainActivity, Gradle, ApprovalEngine, Procurement, People, or P7 source tables.
- Reuse: existing ApprovalPersistencePort current company membership + active company authority binding, P7 Finance matching, Audit writer, IdempotentCommandExecutor, Evidence references.
- Default safety gates: `hiltech.finance.payable-candidates.enabled=false` and empty `hiltech.finance.payables.review-authority-key`. Server rejects calls if either is absent; no invented Ahmed/Mohamed/employee privilege. Separate UI flags must also remain disabled until integrator wires them.

## Verification and key negative scenarios
- Unit tests: OFF denies without DB read; flag ON without company-defined authority denies; nonmember or malformed authority denies.
- PostgreSQL migration contract: new table, immutable and source-integrity guards, schema migration up from P7 V0060.
- Still to prove with actual HILTECH policy and redacted company docs: current authorized reviewer, wrong-org and revoked role, concurrent same-invoice intake, stale changed warehouse/PO, duplicate and replay on real DB, client app end-to-end and rollback. Do not call this Phase 8 completed.
- At code level NO bank adapter, payment result, account posting, accounting approval, tax policy, supplier messaging, or production activation.

## DevOps / multi-chat collision protocol
- **Flyway V0061 is provisional, NOT an allocation.** DevOps/integration owner must assign the final available version based on actual ordered merges; do not merge this PR with a conflicting numbered migration.
- This Phase 8 lane intentionally avoids all common shells, shared ApprovalEngine, company auth configuration, global workflows and Phase 7 source files.
- DevOps must adjust existing `DatabaseMigrationContractTest` hardcoded migration count (currently 27 migrations from V0034 to V0060) in the integration lane after assigning version. Do NOT edit that shared test from this branch.
- Integration: P7 stacked merge, then P8 Finance branch rebase, source-contract validation, exact-head CI, independent cross-role staging, rollback drill. No green CI equals a release.
- P10 should publish a versioned, approved client billing milestone/issued invoice contract for Receivable. This lane will not fabricate a commercial invoice from a Project row.
- P9 may consume only a future explicit, approved and legally permitted advance-deduction interface, never draft liabilities or an automatic FinancialImprest shortage deduction.

## Subsequent verticals
8B: Employee Expense vs Advance vs FinancialImprest are three independent business objects with receipts/Evidence and append-only custody/settlement events. Require actual company rules for approvals, funding and salary recovery.

8C: Exact beneficiary/source/version payment **preparation** and ApprovalEngine request. No execution gateway without bank path/signatory mandate; UNKNOWN external result never automatically retried.

8D: Immutable payment outcomes, partial allocations, return/reversal and reconciliation, then source-linked Project finance read model. No manual bank success claims without external proof.

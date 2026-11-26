# Phase 8K — Supplier AP accounting recognition (real ledger, disabled pending company policy)

Date: 2026-11-23
Status: **Engineering Draft — feature OFF by default; UNNUMBERED SQL; no production posting, payment, or bank transfer performed.**

## What exists (and what is genuinely distinct)
Previous 8A–8I established an immutable supplier invoice candidate from P7's actual 3-way matched invoice with verified restricted Evidence, and a SOURCE-DOCUMENT accounting review through canonical Phase2 ApprovalEngine. That is NOT a booked AP balance.

8K introduces **a new Finance-owned double-entry general-ledger subledger** for supplier payable recognition:
- Company-approved, organization-specific Chart of Accounts `finance_gl_account`. **No account IDs, tax rate, default expense classification or company policy is invented or seeded.**
- Company-approved, explicitly OPEN Finance period `finance_gl_period`. No current-period inferred from system date without a real approved record.
- Immutable source-bound `finance_ap_posting_proposal` and independently reviewable account allocation lines. Required AP credit equals exactly the matched gross source invoice; explicit debit allocations must sum exactly to that amount at max six decimal places, in one currency. No user-supplied source amount/supplier/client/matched-status.
- Original source-document review must be `APPROVED` for **exact current** 8I source version. Recheck latest actual P7 purchase order/warehouse three-way matching through 8I on subsequent approval and posting.
- An **independent** company Finance `FINANCE_AP_JOURNAL_POSTING` policy in existing `ApprovalEngineService` must be ACTIVE, SINGLE, and reviewer authority **separate from the proposal preparer**. Account allocation, company period, original approved document, and policy/authority revisions go into the guard's hash-derived exact version.
- **A third separate, currently authorized Finance posting operator** must finalize the journal only after the exact proposal-review request has an actual `APPROVED` decision by an independent actor. No default owner approval and no reviewer bypass.
- The journal is written atomically with its matched debit/credit lines to `finance_ap_posted_journal` / `finance_ap_posted_journal_line` and audited. Unique `payable_candidate_id` enforces once-only recognition for a supplier invoice; operation IDs are idempotent.
- PostgreSQL guards reject mutable ledger history, unbalanced or incomplete journal, fake independent approval, duplicate AP posting, a wrong source/supplier/amount/currency, credit to non-AP accounts, inactive/wrong-company chart account and out-of-period posting. A deferred constraint ensures journal and all lines are present and balanced in the same transaction.
- Typed authenticated KMP `propose / requestPostingApproval / post / status` API. The responses distinguish `recognizedAccountsPayable` from `paymentApproved`, `moneyTransferred`, and `bankReconciled`, all false without separate real Treasury bank evidence.

## Company accounting configuration — all hard gates
- `hiltech.finance.ap-posting.enabled=false`
- `hiltech.finance.ap-posting.preparer-authority-key=` unset
- `hiltech.finance.ap-posting.operator-authority-key=` unset
- `hiltech.finance.ap-posting.policy-key=` unset; this must correspond to an ACTIVE approved SINGLE policy and a current distinct reviewer binding in canonical Phase2
- Existing `hiltech.finance.payable-candidates.enabled=false` and `hiltech.finance.supplier-accounting-review.enabled=false` remain OFF.
- No Chart of Accounts or accounting periods are seeded. The company accountant must supply its authorized versioned chart, fiscal calendar, currency/tax treatment and independent Finance authority. Finance Owner must verify these decisions; do not invent a "common Egyptian chart".
- Recoverable VAT/tax **is explicitly blocked** until a canonical restricted Finance tax-treatment Evidence target and actual company-specific reclaim policy exist. A PDF supplier invoice by itself is NOT evidence that VAT is reclaimable. Current available path requires a company accountant's reviewed `TAX_REVIEWED_NO_RECOVERABLE_TAX` disposition and an independently approved account allocation. This is a business attestation, not an automatic tax rate.
- Separate approval of supporting documents does NOT permit posting without separately reviewed complete journal lines.

## Endpoints (feature disabled, schema unnumbered)
- `POST /v1/finance/organizations/{orgId}/payable-candidates/{candidateId}/journal-proposals` — explicit company chart account allocations, fiscal period, treatment reference, tax disposition and a new idempotency operationId. Source amount and supplier are loaded from immutable P7/P8A.
- `POST /v1/finance/organizations/{orgId}/ap-journal-proposals/{id}/request-posting-approval` — existing Phase2 `ApprovalEngine.requestException`, exact source-version guard, no payment.
- `POST /v1/finance/organizations/{orgId}/ap-journal-proposals/{id}/post` — independent approved posting and third-person operator, writes immutable balanced ledger record, no payment.
- `GET /v1/finance/organizations/{orgId}/ap-journal-proposals/{id}` — protected current Finance posting state.
- No bank API, supplier email or external transfer, no payroll deduction. **Actual payment** remains Phase8 Treasury work, separate from AP recognition.

## Tests and required integration
- `FinanceApLedgerRulesTest`: exact balanced monetary precision; invalid source, fractional excess, missing AP credit, duplicate account, unexpected credit, tax evidence mismatch, proper source/line hash.
- `FinanceApLedgerSchemaPostgresContractTest`: disposable PostgreSQL applies `database/phase8/pending_ap_ledger_posting.sql` inside rollback after canonical Flyway V0001–V0062 and checks tables/SQL immutability. **This is a SQL schema test, NOT live Finance enabled or company chart evidence.**
- `FinanceApJournalPostingServiceTest`: no finance authority, default-OFF, and unapproved supplier source fail before any journal SQL mutation.
- `FinanceApJournalPostingApiClientTest`: authenticated KMP contract, stringent line amount shape, actual posted GL versus no bank transfer.
- **Hard DevOps gate:** do not assign a numbered migration on the Finance branch. PR #195's integration branch already renumbers Finance8A provisional V0061 -> V0065 and Finance8B provisional V0062 -> V0066 after P7; DevOps must review this new unnumbered SQL for later canonical sequencing with P9/P10/P11/P13 and perform full merged PostgreSQL/Modulith, native Windows/Android, test-data accounting approvals, native shell and backup/recovery.
- No Finance chart-of-account administration UI, approved posting correction/reversal flow, period-close workflow, VAT recovery, AR invoice issuance, cash/bank, payroll settlement or company-wide accounting balances are provided here. These must be separately completed and proved.
- The existing `FinancePaymentPreflightService` reports only unposted candidate truth. It must be upgraded to join actually posted AP before any Finance production reporting of booked liabilities; do not display old preflight as current company ledger.
- Do not merge/activate until **exact-head CI**, actual non-overlapping company posting policy/role, legal/tax review and source-to-approved-journal-to-posted-GL staging with rollback and negative tenant/access tests.

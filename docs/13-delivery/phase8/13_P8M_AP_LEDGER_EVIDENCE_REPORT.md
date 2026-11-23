# Phase 8M — supplier posted-journal historical evidence, NOT outstanding balance

Date: 2026-11-23
Status: Draft. All Finance/reporting activation OFF. No production ledger or bank transfer.

## Actual source and meaning
- Read exactly `finance_ap_posted_journal` plus original `finance_ap_posting_proposal` on **one organization**, optionally on one company-owned fiscal period. Source is P8K's already approved immutable accounting event, not a pending supplier invoice or a three-way-match candidate.
- Group by original supplier organization and posting currency. Sum `gross_amount` only within that same currency and only for actually posted records. Count journal IDs and retain latest posting timestamp.
- Result label is **historically recognized gross supplier AP**, not paid amount, remaining payable, maturity, or outstanding today. Reversals, credit notes and payments are still not fully integrated. Even historical gross will eventually need to reference valid append-only reversals.
- `settledAmount`, `outstandingBalance`, `overdueBalance`, `bankConfirmedAmount`, company payables, company supplier payments and reconciliation totals are **NULL**; never use zero as a placeholder for source absence.
- No issued customer invoices, earned revenue, payroll, treasury custody, bank balance, tax declaration or company-wide trial balance is calculated.
- If P8K's posted tables are not installed, return `sourceAvailable=false` and `sourceStatus=POSTED_AP_LEDGER_SOURCE_NOT_INTEGRATED`. If installed but empty, that means **no posted records in this source/scope**, not a zero company liability.
- Protected `GET /v1/finance/organizations/{orgId}/supplier-ap-evidence-report` with optional `periodId` UUID. Scope validates Finance org membership, current company viewer authority, and real fiscal period ownership before any supplier amount query. 100 group ceiling + explicit `hasMoreGroups` so no truncated company totals.
- Typed authenticated KMP client and optional Compose report panel for Finance Windows/Android. **Native component is not yet mounted** into app role navigation.
- JUnit negative tests for default-OFF and missing tenant/authority; KMP MockEngine typed route and false-balance coverage; dedicated CI includes parent 8L disposable PG and both app compiles.

## Hard gates
- `hiltech.finance.ap-reporting.enabled=false`
- `hiltech.finance.ap-reporting.viewer-authority-key=` unset by default; real Finance viewer binding must be established through company policy.
- Depends on unnumbered P8K supplier journal schema and (stacked source) 8L. DevOps owns eventual globally numbered migrations, full account authority, staging and integrations. Never import P9 payroll or P10 commercial totals without separately verified financial source/recognition.

## Next work
- Implement source-unique original journal reversal and credit notes with independent authority, and test no fake net payable.
- Integrate confirmed actual supplier payment results and bank reconciliation, then derive current AP outstanding = approved original postings + verified reversals/credits - independently verified settled payments, using correct currency and cutoff.
- Integrate customer invoice issuance and AR/collections separately. Only after real coverage and accounting policy can actual full-company trial balance, cash position or period close be certified.

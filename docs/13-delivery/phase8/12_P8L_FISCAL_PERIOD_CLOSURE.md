# Phase 8L — company fiscal close gate (append-only; disabled pending complete finance ledger)

Date: 2026-11-23
Status: **Engineering Draft**. No company-wide fiscal close has been applied. P8K and P8L SQL remain **UNNUMBERED** until DevOps integrates their canonical dependency chain.

## What exists
- `database/phase8/pending_gl_period_close.sql` is an additive SQL extension to the real immutable supplier AP journal in `database/phase8/pending_ap_ledger_posting.sql`. It does not edit, delete, relabel or overwrite GL history.
- `finance_gl_period_close_source` computes a deterministic SHA-256 digest from the company's actual period boundaries/status and **all presently posted P8K AP journals** (ID, source candidate, amount, currency, journal fingerprint). It includes count and sorts by ID for stable snapshots.
- `finance_gl_period_close` stores the exact snapshot, current policy/version, independent actual ApprovalEngine request, operator and a durable unique close event. One close per (organization,period); an identical timed-out request can be read historically. Past closures are immutable.
- A PostgreSQL `BEFORE INSERT` guard on posted AP journals locks the actual `finance_gl_period` row `FOR UPDATE` and rejects insert when a close exists. The period-close insert obtains the **same lock** and recomputes the committed snapshot before checking independent real `APPROVED` approval/decision; no after-close payable posting race is allowed, including raw SQL bypasses.
- Separate existing canonical Phase2 policy key `FINANCE_GL_PERIOD_CLOSE` subject, source-version guarded. Any new posted AP journal, period source revision, company policy or reviewer authority version invalidates an old pending request.
- A real Finance preparer must request approval. A **different assigned reviewer** must approve, and a separate current authorized operator closes, with real ApprovalEngine decision event checked in SQL. The engine is not bypassed, and no synthetic approver/authority is seeded.
- Protected paths `POST /v1/finance/organizations/{orgId}/periods/{periodId}/request-close-review`, `POST .../close`, `GET .../close`. The read path preserves a real historic closure when the period is no longer current. Typed KMP native request/close/status client, no bank API or fake collection.
- Rules reject closure before the last day has finished (UTC), cross-company actors, missing actual policy, forged approval, changed posted-journal snapshot, duplicate close and posting after a close.
- Unit tests cover zero-configuration fail-closed, missing current company authority and SOURCE; real disposable PostgreSQL test installs **both** unnumbered SQL files inside a rollback transaction and checks period source fingerprints and rejection of early/forged-close SQL. KMP MockEngine verifies authenticated exact paths and distinguishes period closure from a bank operation.

## Critical safety limit: supplier AP is NOT the full company ledger
This first source proof sees supplier AP journals in Phase8K. It does **not** yet include customer invoices/receivables, cash and bank, employee advances, imprest custody, payroll, inventory capitalization/asset postings or complete adjustments/reversals. **It therefore cannot certify a company-wide close, financial statements or trial balance** in isolation.

Two independent flags, both **OFF** until actual integrated full-ledger completeness is demonstrated:
- `hiltech.finance.gl-period-close.enabled=false`
- `hiltech.finance.gl-period-close.full-ledger-ready.enabled=false`

Other mandatory company settings start unset:
- `hiltech.finance.gl-period-close.policy-key=` (must be company ACTIVE SINGLE review policy)
- `hiltech.finance.gl-period-close.preparer-authority-key=`
- `hiltech.finance.gl-period-close.operator-authority-key=`

Neither Finance owner nor DevOps may set `full-ledger-ready` until **all** actual subledger posting sources are integrated in the closure-source fingerprint and their writers are protected from post-close mutations. The current SQL guards only the supplier AP journal writer. Future bank/AR/payroll/asset writers must acquire this period lock and reject a real closure. Default OFF does not itself prove full ledger completeness. Users must not be shown "2026 company accounts closed" based solely on the AP journal sample.

## DevOps and next delivery gates
- Existing #195 merges only Phase7+Finance8A–8I, not 8K and not this draft. Global Flyway order, rollback/staging, tenant/authority config, supplied company chart, fiscal periods, legal/tax policies and actual audit Evidence require shared review.
- Test a REAL source change under concurrency: approved AP journal version P8K -> P8L pending approval -> another AP journal posting -> old close approval refused -> re-request current snapshot. Then a new approved closure must block subsequent direct-PG AP writes.
- Validate current native app shell role mapping and full Finance inbox approval decisions. KMP client only is **not** a mounted native screen.
- Complete verified customer AR/issued invoices, confirmed cash/payments, employee custody and reversal ledger and incorporate them into a holistic multi-currency period snapshot before enabling company close.
- Neither period close nor AP recognition is supplier payment, customer collection, bank reconciliation or tax reporting.

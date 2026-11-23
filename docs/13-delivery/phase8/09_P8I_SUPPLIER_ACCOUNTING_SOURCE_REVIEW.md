# Phase 8I — source-bound supplier invoice accounting review

Date: 2026-11-23
Status: **Draft** — real Accounting *documentation and source* review, **not posted AP or payment**.

## Real path
- Start with P7's matched supplier invoice with a READY private Evidence document, accepted 3-way match revision, company Finance handoff and immutable P8A `finance_payable_candidate`.
- Reuse P8A `FinancePayableCandidateService.detail(actor, candidateId)` to ensure current Finance reviewer authority + current P7 match; expose the authorized immutable source organization with `SupplierPayableCandidateView.organizationId` (nullable only for backwards-compatible test/mock values).
- `FinanceSupplierAccountingApprovalSubject` implements existing `ApprovalSubjectVersionSource` for `FINANCE_SUPPLIER_ACCOUNTING_REVIEW`. It **does not** accept caller-entered invoice, supplier, amount or beneficiary. It verifies candidate against exact organization, P7 handoff, invoice, purchase order, latest MATCHED revision, READY source document, currency, gross amount and evidence metadata. A policy/authority/evidence/match revision change changes the hash-derived version or makes the request unavailable.
- Company active SINGLE policy with a current distinct accounting reviewer must be preconfigured in existing ApprovalEngine. The candidate recorder cannot be the current policy reviewer and the actor submitting cannot be the accounting approver. No owner fallback, no bypass.
- `POST /v1/finance/payable-candidates/{id}/request-accounting-review`: authenticated idempotent request using existing `ApprovalEngine.requestException` and canonical source-version checks. Pending same-source request is shown, not duplicated; completed same-source review cannot be reopened.
- `GET /v1/finance/payable-candidates/{id}/accounting-review`: authorized Finance reviewer reads canonical current request outcome; `APPROVED` only means source evidence reviewed.
- Typed KMP native request/read API + mountable Compose Finance control with same operation ID for transient network retry and explicit review versus payout distinction.
- Unit negative tests for no company policy, invalid source/match, unauthorized self-review, idempotent request and no money rights, plus KMP API request/status route and typed decoding.

## Activation
- `hiltech.finance.supplier-accounting-review.enabled=false` default.
- `hiltech.finance.supplier-accounting-review.policy-key=` default unset.
- Requires existing `hiltech.finance.payable-candidates.enabled=false` to be explicitly enabled under source/role validation.
- Real company policy and reviewer authority must be active, distinct and current. Do not synthesize them.

## Accounting boundary (not implemented by 8I)
- No posted accounts payable, GL journal, VAT receivable, accrued tax, approved treasury payment, bank payout, supplier notification or reconciliation is generated.
- An `APPROVED` **accounting review** cannot be treated as an executed supplier payment, a signed payment approval, or proof that an obligation is legally payable.
- Separate lifecycle still needed: account assignment, period policy, taxes/exchange rates, recognized liability posting with unique invoice source, corrections/credit notes, payment authorization, bank integration and independent bank result/reconciliation.
- Reviewer outcome uses existing Phase2 ApprovalInbox decisions, not custom Finance approval storage. Optional native section still unmounted in authenticated shell.

## Engineering gates
- Exact-head CI for this PR and parents #190/#189/#186/#185; real PostgreSQL and native compiles.
- Canonical Finance8A/8B provisional migration ordering versus Phase7 #174 migration V0061 and shared package test remain DevOps-owned. No new migration in 8I.
- Integration proof must reverify full real P7 matching -> Finance candidate -> Finance request -> assigned reviewer approve/reject/change -> original Finance reviewer result; test source invalidation and cross-org denial on connected P7 stack. The standalone tests are **not** a full live integration.
- No production activation/merge until gates pass; all monetary actions remain off.

## Updated decision-time match guard
The subject source does **not** trust only the latest persisted P7 `MATCHED` revision. It also recomputes `SupplierInvoiceMatchingService.preview` under the original authorized Finance matcher identity on every source/current check, including ApprovalEngine decision-time checks. A changed warehouse receipt/discrepancy/source hash, missing source authority or mismatched PO/invoice causes the accounting review to become stale even when an old match event remains latest. The update needs real P7/warehouse integration evidence on staging before activation.

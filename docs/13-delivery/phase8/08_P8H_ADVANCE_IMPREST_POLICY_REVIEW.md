# Phase 8H — Employee Advance and Company Financial Imprest Policy Review

Date: 2026-11-23
Status: **Draft** — all funding, loan recognition, custody issuance, payroll deduction and settlement remain impossible through this change.

## Implemented, reusing canonical sources
- Two **separate existing immutable Phase8B tables** remain authoritative:
  - `finance_employee_advance_draft`: employee request for advance; *not yet a debt* and never a Payroll deduction
  - `finance_financial_imprest_draft`: custodian request to hold company cash; *not employee income or an advance*
- `FinanceMoneyDraftPolicyApprovalSubject` implements the existing `ApprovalSubjectVersionSource`. Separate subject types `FINANCE_EMPLOYEE_ADVANCE_POLICY_REVIEW` and `FINANCE_FINANCIAL_IMPREST_POLICY_REVIEW`, with independent current company approval policies and authority bindings.
- Exact source version is hash-derived from the current real immutable draft, employee/creator identity, amount/currency/purpose/project, active company policy revision and current assigned reviewer authority revision. Changed policy or authority invalidates old pending review through canonical ApprovalEngine decision-time source guard.
- Only authenticated current original employee with active employment and organization membership can request or read their review. No self-review if the original creator matches the company's current reviewer authority.
- Policy must exist and be ACTIVE, `approvalRequired=true`, `mode=SINGLE` and point to a real current authority binding. Neither policy nor reviewer is invented. **No default-to-owner or auto-approved path.**
- Uses existing `ApprovalEngine.requestException(...allowAuthorityBypass=false)` and its Inbox/decisions/audit. Existing same-version PENDING request is returned; finalized same-version requests cannot be submitted again.
- HTTP `POST /v1/finance/me/organizations/{orgId}/advances/{draftId}/request-policy-review`
- HTTP `GET /v1/finance/me/organizations/{orgId}/advances/{draftId}/policy-review`
- HTTP `POST /v1/finance/me/organizations/{orgId}/imprests/{draftId}/request-policy-review`
- HTTP `GET /v1/finance/me/organizations/{orgId}/imprests/{draftId}/policy-review`
- Typed online-only KMP API and optional Compose employee review/status control, both with distinct Advance vs Imprest labels. The employee UI uses the same operation ID when retrying an uncertain request.
- Negative and happy-path server tests + KMP MockEngine route/auth tests, dedicated PostgreSQL+native compilation CI.

## Activation controls
All OFF by default. Enable only on an actual verified company-specific approval policy:
- `hiltech.finance.employee-requests.enabled=false` (pre-existing draft source)
- `hiltech.finance.advances.policy-review.enabled=false`
- `hiltech.finance.advances.policy-review.policy-key=` (blank default)
- `hiltech.finance.imprests.policy-review.enabled=false`
- `hiltech.finance.imprests.policy-review.policy-key=` (blank default)

## Scope is NOT money movement
Both review outcomes explicitly keep:
`moneyIssued=false`, `personalLoanRecognized=false`, `cashCustodyRecorded=false`,
`payrollDeductionAuthorized=false`, `settlementRecorded=false`.

To actually fund an Advance, Finance must independently verify entitlement, amount, payee and approved payment source, record a confirmed payment-result and only then recognize a recoverable balance using real source lineage. Payroll can consume **only an immutable, independently approved deduction** — not a request review.

To actually fund an Imprest, Finance needs company treasury custody issuance, signed custodian acknowledgement, project link, append-only receipts/spend/returns, shortage/overage and settlement. Custody belongs to the company, not payroll. Do not coalesce financial imprest into loan logic.

## Integration dependencies
- Parent 8G #189 documentation review, 8F #186 Evidence upload and 8E #185 receipt readiness remain Draft until exact-head CI/device/staging.
- Native Compose section still must be mounted by Windows/Android shell owner after role routing tests.
- Phase7 #174 provisional V0061 and Finance8A #164 provisional V0061 clash; DevOps owns global Flyway ordering and general server package tests. This branch adds no migration and does not bypass collision.
- Never merge/activate before exact-head PostgreSQL, API, native and authority negative tests all pass.

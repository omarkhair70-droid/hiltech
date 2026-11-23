# Phase 8G — Real expense receipt-documentation review via existing ApprovalEngine

Date: 2026-11-23
**Status: Draft. No money movement, payable posting or payroll deduction.**

## Newly implemented
1. Employee's own actual Phase 8B expense draft can be read by exact ID, using the existing People employment+membership authority (not the previous latest-100 listing).
2. `FinanceExpenseDocumentationApprovalSubject` implements the Phase 2 `ApprovalSubjectVersionSource` contract for `FINANCE_EXPENSE_DOCUMENTATION_REVIEW`. It computes a positive, version-stable SHA256-derived `Long` over original expense identity/version, amount/currency, all private READY PDF/JPEG/PNG receipts and their Evidence hashes/sizes/versions, the **real active** document-review company policy version and assigned reviewer authority version.
3. Subject version **changes** when additional READY evidence is attached, a receipt/version changes, company policy/binding changes or the creator loses active membership. Existing ApprovalEngine decides only when current source and authority are still valid; old pending reviews get superseded, never mistaken for final expense/payment authorization.
4. `POST /v1/finance/me/organizations/{orgId}/expenses/{draftId}/request-documentation-review` delegates to the **existing audited and idempotent ApprovalEngine**, with an explicit SINGLE current policy and `allowAuthorityBypass=false`. It is guarded by original active employee, actual receipt-source and active company reviewer binding. No policy or approver is invented. No self-review through an active reviewer binding.
5. Typed KMP online-only POST API and native Compose employee review-request control, which only permits submission after a verified receipt and displays `PENDING` review (not an approved expense).
6. Unit negatives for feature OFF, missing company policy, NO_APPROVAL_REQUIRED, missing receipts, wrong employee creator, and no unexpected money action. PostgreSQL contract uses disposable real Finance draft/Evidence metadata to assert that adding an additional receipt changes the source version and makes prior pending approval invalid. KMP MockEngine verifies authenticated POST and strict expense/operation IDs.

## Company configuration — no invented rules
- `hiltech.finance.expenses.documentation-review.enabled=false` (default)
- `hiltech.finance.expenses.documentation-review.policy-key=` (blank default)
- Requires `hiltech.finance.expense-receipts.canonical-upload-ready=true` and `hiltech.finance.expense-receipts.readiness-enabled=true`, both currently OFF.
- The selected policy **must** exist in canonical `ApprovalPolicyVersion` and be ACTIVE, require approval, mode SINGLE with a *current* different-authority reviewer binding. Employee cannot be the assigned reviewer.
- This is a **DOCUMENTATION-ONLY** approval subject. Reviewing documents must never create a booked employee expense, payroll advance, recoverable loan, payment batch, GL journal, treasury cash movement, tax claim or reimbursement entitlement.

## Known limitations / integration gates
- The existing Evidence READY status verifies bytes, SHA-256, size and PDF/image signature, **not antivirus/ClamAV clearance**. Any additional malware clearance required by the company's policy must be independently implemented and evidenced.
- Review UI and actual Android SAF/Windows JFileChooser file-picker components exist on stacked #186 but aren't mounted into company shell navigation or on-device validated yet.
- Parent exact-head Finance8E/8F GitHub tests queued; this PR must have its own **PASS** PostgreSQL, KMP, Windows, Android builds and ApprovalEngine regression before merge.
- This is a separate child PR atop #186; no Finance migration and no procurement/people/Payroll/approval shared core modifications. Still subject to provisional Finance8A V0061 conflict with Phase7 S07 #174, Finance8B V0062 and DevOps integrated migration/provisioning tests. No production deployment.
- Source check intentionally rejects receipt lists beyond 100 until canonical paging/revision controls exist, rather than approving an incomplete snapshot.
- Extra source transactions may race while requesting approval; authoritative ApprovalEngine decision-time source guard will fail closed when new receipt/source or authority changes. Do not interpret creation of a PENDING request as an approved financial action.

## Next authoring path
Once the review request is valid under real company policy and source, the existing Approval Inbox/Decision paths handle review, rejection, change request and audit. The Finance lane must subsequently implement separately approved expense recognition and independently verified payment-result capture. Never infer those actions from receipt-documentation approval alone.

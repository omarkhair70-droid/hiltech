# Phase 8E — Expense Receipt Evidence Readiness (2026-11-23)

## Real deliverable
- Implemented `FinanceExpenseReceiptReadinessService` and two GET read-model routes:
  - `/v1/finance/me/organizations/{orgId}/expense-receipt-readiness` — actual current employee only, from Phase8B protected own-draft source.
  - `/v1/finance/organizations/{orgId}/expense-receipt-review` — explicit current Finance reviewer binding, from Phase8B protected reviewer source.
- For each visible expense draft, checks **canonical Evidence and upload session** metadata for exact org, expense draft target, original draft creator, required receipt type, `READY` finalized, format/SHA-verified private object, verified SHA-256 and size, and allowed PDF/image content type. No new file system/storage, no cross-organization Evidence, no download/public URLs.
- Separate OFF-by-default switches `hiltech.finance.expense-receipts.readiness-enabled=false` AND `hiltech.finance.expense-receipts.canonical-upload-ready=false`. Second switch MUST NOT be enabled before the Evidence owner implements and tests a real `FINANCE_EXPENSE_RECEIPT` target-specific reserve/finalize/format-validation/access lifecycle.
- Every response denies payment and approval, even if a receipt is verified, since company expense policy/approval/payable recognition are absent.
- Typed online-only KMP API, reusable native Windows/Android read-only review UI. Owned shell navigation is not modified on Finance's isolated branch.
- Dedicated JUnit tests for default-off no data access, unsupported canonical upload, independent policy/payment blockers, current employee-only and reviewer-only authorization delegation. CI includes existing 8A-8D source/security tests and Android/desktop compilation on disposable PostgreSQL.

## Explicit missing functionality
1. Canonical Evidence target was missing from this PR; child PR #186 now implements expense-specific reserve/finalize/access using the existing store. Neither the parent read model nor child upload component is wired into the released Windows/Android shells yet.
2. Expense owner needs real employer policies: supported expense categories/currencies, receipt/amount requirements and spend date, exact reviewer authority, separation of duties, rejection/return, and expense liability recognition.
3. A verified receipt does not automatically create a posted expense, employee receivable, payroll reimbursement, cash movement, or approved payable.
4. Document Evidence access/download must be restricted to original employee and specifically authorized Finance reviewers; general WorkOrder/HR/Supplier evidence is not interchangeable.
5. No real bank/provider or approval integration. Missing system reality remains a deliberate default-OFF gate.

## Integration
Stack #164 -> #175 -> #179 -> #180 -> this PR; Phase7 S07 PR #174 introduces V0061 conflicting with Finance8A provisional V0061, Finance8B provisional V0062. DevOps owns canonical sequence and native-shell navigation, not this domain branch. No modifications to Evidence service, migrations, Procurement, People, Payroll, Sales or general UI files.

## Accuracy correction
The canonical S3 Evidence finalizer currently verifies bytes, SHA-256, size and a PDF/image signature. `READY` does **not** mean an antivirus/ClamAV scan passed: no such scanner result is demonstrated by this Finance PR. Expense liability and payment approval remain blocked independently. Correct PDF class is `SIGNED_DOCUMENT`; PNG/JPEG class is `NATIVE_MEDIA` (never `ARBITRARY_FILE` for these supported formats). See exact-head query and PostgreSQL test.

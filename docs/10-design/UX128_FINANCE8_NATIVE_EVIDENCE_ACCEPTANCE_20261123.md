# UX-128 — Finance 8 native source-evidence workspace

**Candidate:** UX PR #239, targeting the combined V0100 Finance/Payroll/Commercial/Service integration PR #237. **Status: Draft / source-backed reader plus separately gated PREPARE+REQUEST REVIEW / NOT company accepted.**

## What is mounted now

The same Compose shared workspace is mounted in **Android** and **Windows** specialist workspaces. It is inside the independently bounded NativeOperationalWorkspaceHub, not nested in an unbounded root scroll. The user first passes a **current authenticated company-scoped GET** from either Finance posted-supplier-ledger or the existing original Finance supplier-invoice reader. These are route visibility checks, **not** authorization to other Finance domains: every detailed GET must independently succeed under its original server permission.

- Android default OFF: `hiltech.financeEvidenceReadEnabled=false`; BuildConfig `HILTECH_FINANCE_EVIDENCE_READ_ENABLED`.
- Windows default OFF: `HILTECH_FINANCE_EVIDENCE_READ_UI_ENABLED=true` explicitly required.
- **Separate source review preparation flag, default OFF:** Android `hiltech.financeReviewPrepareEnabled=false` (`HILTECH_FINANCE_REVIEW_PREPARE_ENABLED`); Windows `HILTECH_FINANCE_REVIEW_PREPARE_UI_ENABLED=true` is required in addition to the read flag.
- Other existing workspaces, API policy, Flyway, server and company data unchanged.

## Source and native UX coverage

| User journey | Source | Native behavior | Deliberately unavailable |
| --- | --- | --- | --- |
| Posted supplier AP evidence | `FinanceApLedgerEvidenceReportApiClient.get` | Automatic scoped read and explicit refresh, posted historic gross and count, incomplete/unknown source warnings | Fabricated outstanding/overdue/company trial balance |
| Bank statement proof | `FinanceBankStatementIntakeApiClient.status` | Current by original proposal UUID, explicit recording/reconciliation flags | Import/write or fake bank confirmation |
| Bank reconciliation | `FinanceBankReconciliationSignoffApiClient.status` | Current by original UUID, signed-internal versus bank-verified distinguished | Signoff or reconciliation mutation |
| Bank AP/AR cash allocation, correction | `FinanceCashAllocationApiClient.status`, `FinanceCashVoidApiClient.status` | Current original status and corrected source indicators | Payment initiation/record/void |
| Physical cash and witnessed count | `FinancePhysicalCashApiClient.balance/voucherStatus`, `FinanceCashCountApiClient.status` | Actual book balance/variance and independent custody indicator | Cash handover, voucher write, GL adjustment |
| Supplier and customer accounting | AP/AR journal, AR candidate and issuance typed GET `status` | Current original source evidence only | Prepare/post/legal issuance/send |
| Employee money | Funding, settlement and reimbursement typed GET `status` | Existing amount/state plus source flags | Payroll deduction, transfer |
| Financial period | `FinanceGlPeriodCloseApiClient.status` | Historic closure source | Period close action |

No assumption is made that an entered UUID exists or is visible. The server may return 404/403 or fail; a scope-mismatched result is rejected, and the old record is removed on text/type/account change. Known UUID lookup is necessary because **these sources do not expose a permission-scoped index for all proposal types**. This is not a newly invented finance search service.

## Preparation → independent review workflow (new)

The specialist Finance workspace now has an opt-in workflow section, visually separated from the source audit view. It is implemented once in Compose for both devices, not a substitute Finance backend or a fake demo. Current permitted flows:

- Read an **existing** bank statement intake proposal by original UUID, then `requestReview` only when the current source state is `PROPOSED`. The actual CSV Evidence upload and full import are still unsupported by this UI.
- Prepare a company bank reconciliation proposal from the real recorded intake UUID and a company reconciliation reference, then separately request independent review.
- Prepare a bank-backed AP/AR cash allocation proposal from an actual posted journal UUID, bank line UUID, positive amount and explanation, then separately request independent approval; **never record, disburse or claim collection** from this UI.
- Prepare a correction proposal against an original allocation record, then request review; no automatic reversal, bank refund or GL mutation.
- Prepare witnessed physical cash count against a currently readable cashbox ID; the UI preflights the original cashbox GET, shows counted/book amounts and variance from the authoritative receipt, then requests review; no cash movement or GL adjustment.
- Read an **existing** customer invoice issuance proposal by original UUID and request independent review; no invoice candidate creation, legal issuance or external send.

The original server re-checks action authority and current Finance policy on every POST. UI additionally re-reads the exact proposal immediately before review, refuses non-PROPOSED/pending authority, and verifies organization and proposal ID on response. A frozen operation UUID is reused on explicit retry; changing the form clears the previous status. All account/org/installation state is keyed and removed on change, and in-flight source requests are cancellable. No action is triggered merely by viewing a screen.

**These are actual PREPARE and REQUEST REVIEW network calls if the separate UI flag is explicitly enabled in a correctly authorized staging environment.** They are *not* approvals, applied allocations, bank transaction commands, signed reconciliations or production-ready workflows. Do not turn the flag on without source-owner approval and UAT.

## What **is not** done

This is **a real native audit/status surface**, not the final full Phase8 transactional interface. No bank movement, final settlement, fiscal posting, **cash count recording**, legal tax numbering or third-party communication is performed by this UI. Original protected operations will need source-bound, permission-reviewed workflows; enablement requires real operator acceptance and separate Finance owner policy. Developer UI flags remain OFF in real builds. The original Phase8 source-authenticated API contracts are unmodified.

## Exact-head quality gates

1. `UX-128 Phase8 Finance native source evidence` Actions: complete shared `desktopTest`, Windows Kotlin compile and Android debug APK with `--no-configuration-cache`. Unit tests cover wrong org, wrong ID, invalid amounts, current cashbox preflight, exact idempotency header, prior record status preventing re-review, explicit source GET then request-review POST.
2. Physical device with signed-in company Finance actor: actual ledger source, source denied, revoked actor, changed org, own/other proposal UUID, backend downtime, offline and retry.
3. RTL, small Android viewport, 200% font, keyboard Tab, Back dismissal, Windows resize; no clipped buttons, nested scroll or stale company status.
4. Enterprise staging: OIDC/OpenFGA, role revocation, private Evidence, current V0096+ migration, backup/restore, rollback and owner acceptance.

**Ownership:** UX-128 owns native read-route, separately gated prepare/review state/copy/accessibility and detected Compose issues; Phase8 owns accounting contracts/policies and write processes; DevOps owns integrated CI, approved staging, release/rollback. Release decision remains **HOLD** until company UAT.
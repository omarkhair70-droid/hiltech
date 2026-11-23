# UX-128 — Finance 8 native source-evidence workspace

**Candidate:** UX PR #239 based on Finance unified branch #235 (V0001–V0096). **Status: Draft / read-only / NOT company accepted.**

## What is mounted now

The same Compose shared workspace is mounted in **Android** and **Windows** specialist workspaces. It is inside the independently bounded NativeOperationalWorkspaceHub, not nested in an unbounded root scroll. The user first passes a **current authenticated company-scoped GET** from either Finance posted-supplier-ledger or the existing original Finance supplier-invoice reader. These are route visibility checks, **not** authorization to other Finance domains: every detailed GET must independently succeed under its original server permission.

- Android default OFF: `hiltech.financeEvidenceReadEnabled=false`; BuildConfig `HILTECH_FINANCE_EVIDENCE_READ_ENABLED`.
- Windows default OFF: `HILTECH_FINANCE_EVIDENCE_READ_UI_ENABLED=true` explicitly required.
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

## What **is not** done

This is **a real native audit/status surface**, not the final full Phase8 transactional interface. No bank movement, settlement, fiscal posting, cash counts, legal tax numbering or third-party communication is performed by this UI. Original protected operations will need source-bound, permission-reviewed workflows; enablement requires real operator acceptance and separate Finance owner policy. Developer UI flags remain OFF in real builds.

## Exact-head quality gates

1. `UX-128 Phase8 Finance native source evidence` Actions: complete shared `desktopTest`, Windows Kotlin compile and Android debug APK with `--no-configuration-cache`.
2. Physical device with signed-in company Finance actor: actual ledger source, source denied, revoked actor, changed org, own/other proposal UUID, backend downtime, offline and retry.
3. RTL, small Android viewport, 200% font, keyboard Tab, Back dismissal, Windows resize; no clipped buttons, nested scroll or stale company status.
4. Enterprise staging: OIDC/OpenFGA, role revocation, private Evidence, current V0096+ migration, backup/restore, rollback and owner acceptance.

**Ownership:** UX-128 owns native read-route, state/copy/accessibility and detected Compose issues; Phase8 owns accounting contracts/policies and write processes; DevOps owns integrated CI, approved staging, release/rollback. Release decision remains **HOLD** until company UAT.
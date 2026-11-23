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

## Non-waivable complete product acceptance (no deferred Finance journeys)

The draft read and review surfaces above are **not an accepted substitute** for the complete company Finance workflow. Every supported original Phase8 function must be accessible through its authorized Windows/Android journey (or a source-agreed desktop-only boundary with a documented operator rationale), including:
1. Original canonical bank CSV selection (strict UTF-8/header/row amount checks), exact SHA256, original bytes and protected Evidence reserve/upload/finalize, independent reviewed bank intake, and approved internal recording.
2. AP/AR journal sources, partial and concurrent cash allocations, corrections/void audit trail, approval and recorded accounting allocation as independently controlled states (not a bank transfer).
3. Actual physical cashbox opening/custodian, cash voucher preparation/review/custody proof, witnessed cash count, variance review and independently approved recording.
4. Customer invoice candidate and legally controlled issuance/correction review, source-linked AR journal, verification that invoice recording != external sending and recorded debt != received bank cash.
5. Employee advances/imprest, settlement and expense reimbursement; payroll review handoff; period close; source-backed ledgers, reconciliation and exception reports.
6. Role-specific home/navigation, scoped search/discovery rather than forcing operators to copy opaque UUIDs wherever server lists exist, validation, retries/idempotency, denied/wrong-org/revoked user, RTL/200%-font/keyboard/Back/small viewport and real Android+Windows role UAT.

**Verified backend integration contract issue (owner required):** the canonical `prepare-csv` endpoint already exists in `FinanceBankCsvParser.kt`; the blocker is that `EvidenceLifecycleApi.kt` has no reserve or finalize access case for `FINANCE_BANK_STATEMENT_INTAKE`, and `JdbcEvidencePersistence.insertRestrictedEvidenceReservation` does not allow that target. Finance owner #235 was given the exact gap. Bank Evidence requires a source/actor/org/account-authorized target bound to the **deterministic bank intake proposal UUID**, HIGHLY_RESTRICTED/INTERNAL_ONLY classification, private READY storage/session and original SHA, with **no invented bank provider action**. The canonical CSV source is limited to **2 MiB**, and the current private Evidence MIME/scan path must explicitly validate CSV bytes, not silently mark arbitrary `text/csv` as READY. Do not wire an unsupported target or display a false upload success.

**Completion rule:** Feature flags may remain OFF until operator-approved commissioning as an operational safety switch, but **they are not acceptance evidence and cannot excuse missing functional journeys**. All of the above must be implemented/tested or explicitly rejected by a source-owner policy with justification; do not call all Phase8 product journeys complete simply because shared/Android/Windows compile. No release while required workflows or company-role UAT are missing.

## Phase8 bank CSV end-to-end implementation delta — 2026-11-24

**New stacked owner source:** secure Evidence source PR #240; native Windows/Android implementation PR #241. This supersedes the earlier statement that `prepare-csv` is missing: that endpoint already existed. The defect was the unimplemented Finance-bank target inside canonical Evidence reserve/finalize.

**Actual native journey now authored** (one shared Compose source, two OS pickers): signed-in Finance preparer → independently authorized, masked approved bank-account selection → choose canonical UTF-8 CSV (1–2 MiB) → client SHA256 and immutable bank intake operation ID → deterministic target proposal UUID → original company-authorized private Evidence reserve → signed binary PUT → finalization with backend **restricted-object byte re-read** and canonical line/hash checks → READY → bank `prepare-csv` with exact file bytes and original operation ID → explicit independent review request → approval by the existing company approval service → independent operator opens the source proposal → status `APPROVED` preflight → original `record` command → source `RECORDED`. Neither a bank transfer nor bank-provider reconciliation is claimed.

**Bank-source route and identity isolation:** `FinanceBankStatementIntakeService.listApprovedAccounts` returns only approved name/masked-reference/currency, only for current bank preparer or operator authority. The operator may read the source status for subsequent authorized recording without inheriting preparer grants. The server checks every command independently. Finance bank UI has an independent flag default OFF: Android `hiltech.financeBankCsvEnabled` / `HILTECH_FINANCE_BANK_CSV_ENABLED`; Windows `HILTECH_FINANCE_BANK_CSV_UI_ENABLED`. Keeping a commissioning flag OFF does not remove the function from product completion.

### Exact device/UAT signoff still required

- Company preparer chooses a real **approved** register account; wrong org/revoked actor and nonreviewer/new bank/retired bank denied.
- Android `OpenDocument` and Windows `JFileChooser` pick a representative *synthetic* canonical CSV and block excessive size, wrong extension, wrong original SHA, duplicate external IDs, currency mismatch and inconsistent opening/closing balances; private Evidence never leaked to logs.
- Exercise reserve/PUT/finalize network failure and retry using **same** idempotency IDs; verify that invalid/untested evidence never reports `READY` and no duplicate intake is created.
- Finance preparer requests **independent** review; a different assigned reviewer approves through the canonical approval engine. Pending/rejected requests cannot record. Only authorized operator records after approval. Source status must update across both native apps.
- Verify mixed RTL with masked account refs, 200%-font, Windows keyboard/tab/window resize, Android Back/system document chooser cancellation and sign-in/sign-out source reset.
- Verify backend exact-head PostgreSQL migration and runtime with Evidence object storage and Keycloak/OpenFGA. Developer CI and mock transport tests alone are **not company approval**.
- Validate simultaneous sessions, duplicate `record`, and stale role/approval changes; do not mark `COMPLETE` before real source-backed acceptance.

**Engineering checkpoint:** #240 exact source head `aa3003f3` had 3/3 associated workflows PASS before this UX stack; #241 exact-head tests must be verified separately. The wider Finance8 cash allocation, invoice corrections, witnessed cashbox and payroll operator UAT in #238 **remain mandatory**; no final system-complete claim.

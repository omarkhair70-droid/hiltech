# UX-128 — Phase 7 service purchasing / native journey

**Scope:** Product and interaction layer on top of the actual Phase 7 service source
from PR #181, pinned start `35a301fab030e2a0fe89d3e5d64215f10312d364`.
This is a **stacked Draft PR**, not a merge to main or live rollout.

## What was implemented

1. `ServicePurchaseNativeJourney` presents **five Arabic-first jump targets** within one
   native source-connected long-form document: service need, supplier offers,
   approval/internal order, delivery acceptance, supplier invoice review.
   Each existing source-owned Pane stays composed; jumping does **not** unmount
   user-entered notes or replace server-side state. The parent authenticated
   shell remains the single vertical scroll owner. The jump row only scrolls
   horizontally for narrow Android viewports; it does not grant new authority.
2. Both actual Android and Windows service-purchasing mounts now use this
   shared Compose journey. The original Android `OpenDocument` and Windows
   `JFileChooser`, 16 MiB bounded bytes, SHA-256 and protected Evidence
   reserve/upload/finalize remain unchanged and must stay verified.
3. Supplier lookup is now tied to the exact nonblank search query **and**
   current source RFQ. An input edit, RFQ switch or new request clears
   displayed results and selected supplier. Generation checking prevents a
   delayed response from a previous input/source from repopulating the view.
   Starting a new RFQ clears the old selection **before** network access;
   a denied, stale or failed source load cannot leave the old supplier
   selectable. Presentation-only `serviceSupplierResultsBelongToCurrentQuery`
   and `serviceSupplierSearchResponseIsCurrent` have shared regression tests.
4. Delivery acceptance requires selecting one current server-listed order.
   Its notes and outcome are then associated with **that order alone**, and
   are reset on switching orders or refreshing the authorized list. Existing
   server-Evidence validation and approval/source checks are untouched.
5. Supplier invoice form is likewise bound to one selected service order;
   changing the selection clears the prior order's invoice values and any
   stale invoice-match preview. The original typed capture/review-only Finance
   handoff stays unchanged; no payment, payable or supplier dispatch is
   implied or executed.
6. Local Compose state is keyed to organization + installation on these
   native service panes. Both host mounts already key the full service
   section by authenticated identity + organization.

## Engineering boundaries

- **Phase 7 owns:** service need/RFQ, supplier quote API, real Evidence bytes,
  protected uploads, approval and issuer authority, delivery and invoice
  source checks, PostgreSQL migrations and business transaction tests.
- **UX owns:** navigation, drafts and cross-order presentation integrity,
  loading/error/denied distinction, accessible Arabic wording, keyboard,
  200% text, focus/scroll and native Back expectations.
- **DevOps owns:** combining latest #181 with #171, this stacked #199,
  assembled #193 and other phases; shared host-file reconciliation, unified
  DB migration numbering, final exact merged CI, staging and release flags.
- **Default feature switches remain OFF** until integrated real-account
  acceptance. An internal service order is **not** proof of external sending.
  Review-only Finance handoff is **not** posting or payment.

## Checkpoints to prove before rollout

- Exact-head CI: `:shared:core:desktopTest`, `:apps:desktopApp:compileKotlin`,
  `:apps:androidApp:assembleDebug` on this PR.
- Real Android: buyer (request → supplier lookup with edited query → quote
  document picker), independent reviewer, issuer, delivery reviewer, Finance
  reviewer; authorization denial/revocation during request; picker cancel and
  rotation; network recovery.
- Windows: 200% scaling, RTL, mouse and keyboard navigation, file chooser
  cancel/reopen, stale RFQ and previous-session source content.
- Integration: preserve latest P7 native host wiring when importing
  `ServicePurchaseNativeJourney`; verify newer shared shell from UX #171
  and preserve every module owner API/permission guard. Do not independently
  merge child PR to main.

## 2026-11-23 — Final source-state and action-safety refinement

The native source journey now also protects the **meaning of operation receipts**:

- Buyer source refresh immediately clears old eligible needs, RFQ quotes, selected candidate, issue queue and pending issuer confirmation. A denied/revoked refresh does not leave the prior list actionable. All three buyer states distinguish **not loaded**, **loaded empty**, and **server error**.
- **Internal service-order issuance** requires a separate, explicit amount/currency/supplier confirmation after clicking `Review issuance`. The confirmation is cleared on source refresh and before server mutation. An issued internal order never claims external supplier communication.
- Draft teams are revalidated when the authorized team list is refreshed, old source actions disappear immediately, and a different team clears previous team fields. Service needs distinguish loaded-empty from an unrequested list.
- The UI never represents a source mutation as failed just because its **subsequent reload failed**. Successful need creation, supplier quote recording, approval request, delivery review and invoice capture now present a distinct confirmed receipt and a separate refresh-warning path. This matters for preventing users from retrying a successfully recorded command with a new idempotency key.
- On quote file selection, mutable form fields are disabled; supplier, RFQ, generation and decimal/form values are frozen at the click. After a confirmed quote record, the draft is cleared to prevent accidental duplicate submission. A new RFQ erases stale supplier selection before the network request begins.
- Delivery proof and invoice capture use only the explicitly selected order from the currently authorized source list. `ServicePurchaseFormPresentationTest` covers revoked IDs and `NaN`, `Infinity`, negative, exponent, overly precise or malformed decimal invoice input. Invoice match result is hidden on failed preview, failed record, successful Finance handoff, or unsuccessful handoff; it cannot be mistaken for payment.
- Busy, loaded-empty and source-error feedback are visible in Arabic-first presentation. Technical source names/status codes are retained when they carry meaning.

### Acceptance matrix (not yet signed off)

| Scenario | Expected observation | Gate |
|---|---|---|
| Buyer opens a reviewed service request then changes source | Old supplier comparison and candidate disappear before fetch | Native role UAT |
| Supplier search is edited during slow response | Prior search and any previously selected supplier stay hidden | Pure regression + device |
| Different RFQ / user / organization after quote selection | Quote form cannot act on old source | Pure regression + real account |
| User cancels native PDF/image picker | No fake Evidence or supplier quote is recorded | Device |
| Quote registered; comparison refresh subsequently fails | Receipt still confirms recorded quote; warning requests refresh | Device/backend evidence |
| Reviewer chooses service A, then B | Notes and outcome of A do not become B's | Source-selection regression + device |
| Reviewer records service result; list refresh fails | Confirmation says recorded; no false save-failed result | Device |
| Issuer sees approved order | Explicit supplier + currency + total review before issuing | Device/role |
| Invoice entered for one order then order changes | Previous values and match preview reset | Pure regression + device |
| Finance match/hand-off fails | No stale matched action, no claim that money moved | Device |
| Employee with no grant or revoked grant | Source-empty/denied feedback; mutation remains server-blocked | Real account |
| RTL, 200% text, keyboard / Android System Back | Navigable, readable, no invisible controls | Manual visual QA |

**Automation boundary:** GitHub-hosted shared tests + native builds are authoritative only for source compilation/pure regressions. Remote physical Windows testing was attempted but **Desktop Commander usage is exhausted for the month**, so no actual Windows operator acceptance was performed. It must not be described as green. Android device, real actor and integrated staging gates are likewise open.

**DevOps preservation rule:** Do not replace current Phase7 service native source files with older shell variants. Use the authenticated default-OFF Android/Windows host mounts from #181 plus `ServicePurchaseNativeJourney.kt`, `ServicePurchaseOperationsSection.kt`, `ServiceSupplierQuoteCaptureSection.kt`, `ServicePurchaseNeedSection.kt` and pure guards/tests from this #199 child, then reconcile newer UX shell/workspace work from #171. Run merged-head Android/Windows/shared/server/PostgreSQL tests before enabling any flag.

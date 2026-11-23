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

# HILTECH UX-128 — Native Phase 8–13 integration gates

**Owner:** Product & Native UX (not the Finance/Payroll/Commercial/Service/Executive business-logic source owner).  
**Status:** Source-grounded coordination / pre-integration document. **No new
Phase 8–13 feature flag has been enabled and no stage has been declared
production-accepted by this document.**

Reference delivery order: `docs/13-delivery/IMPLEMENTATION_ORDER.md`.

## Ownership rule

- A domain phase owns its DTOs, source APIs, company authority and actor
  permissions, approval/revision rules, files, transactions and tests.
- Native UX owns how source-authorized users discover the correct surface,
  understand loading/denied/stale/empty/unknown states, navigate without
  losing drafts, open only source-allowlisted details and return to work.
- DevOps owns the final aligned source base, avoiding overlapping Android
  `MainActivity`, Windows `Main`, shared `HiltechShell` writes; DB/Flyway
  integrity, native/server/DB merged-head CI, staging and rollout.
- **Visible is not authorized:** a local feature switch, tab or Compose state
  does not grant finance, payroll, commercial or maintenance rights.

## Current grounded source handoff, 2026-11-23

| Phase | Pinned source PR and current observed role-sensitive surface | UX contract and open gates |
|---|---|---|
| P7, internal purchasing | #181 `35a301f`, operational native wrappers; UX #199 child and #171 base | Preserve real 16MiB protected PDF/image picker, Evidence SHA-256, source-bound quote/acceptance/invoice and approval guards. Exact latest UX child CI and real actor/device acceptance; DevOps merge remains separate |
| P8, Finance | DevOps Finance8A–8I candidate #195 `d616a82` (Draft); independent source review policies still gated | Finance workspaces must distinguish PREPARED / REVIEW / CONFIRMED / FAILED / UNKNOWN and actual receipt source, never claim payment or transfer without bank proof. Default OFF until company policy and native source available |
| P9, Payroll | #166 `0ec7c7a`, `Payroll9aAdminSection`, `PayrollRunReviewSection`, `OwnPayslipSection`, history, final pay and Finance review | Separate owner/admin, payroll reviewer, Finance review and employee **own-only** payslip entry. No fictitious PAID from a release/review or zero-net employee. Preserve confirmed/failed/returned/unknown and no-payment-required states. Nested scroll ownership unresolved |
| P10, Sales / tenders | #170 `332c67d`, `CommercialWorkbenchSection` and `CommercialDealWorkspaceSection` | Source-owned leads, scope/BOQ, costing, quote versions, tender and guarded award navigation. A tender win is not an automatically converted Project; conversion requires canonical PROJECT_ADMIN authority. Preserve exact source currency, amount and approval state |
| P11, Service | #167 `43207ba`, Handover, Service Desk, Support/Triage, Maintenance visits/findings/reviews | PM-only support desk and follow-up; technicians only see their currently assigned source WorkOrder findings if independently enabled. Do not show external warranty or SLA claims without source. Handover currently has nested scroll ownership issue |
| P12, Security / Facilities | `IMPLEMENTATION_ORDER.md` phase 12, no confirmed real hardware inventory for UI mounting in this review | **Deferred** until real hardware inventory and authorized adapter contracts exist. No mock CCTV, door controls, camera health or remote physical operations |
| P13, Executive | #197 `f8c4739` stacked on #168, `ExecutiveHomeRoute` / `ExecutiveHomeSection` / Operational Brief | Read-only, actor/org scoped; no snapshot cached across accounts or membership revocation. Treat missing/partial/unknown as unknown, **not zero or company-wide totals**. Navigation must pass source allowlist and original-module authority. Nested scroll ownership unresolved |

The pinned SHAs are **review snapshots**, not instructions to overwrite a newer
domain-owner commit. Refresh exact source before a merge. All rows above
describe code or planned contract boundaries rather than permission-tested
native mounts. Domain-source feature flags stay OFF unless an independently
approved deployment plan says otherwise.

## One vertical scroll owner — blocker found by native source audit

The authenticated `HiltechShell.kt` in UX #171 owns `.verticalScroll`.
Standalone P9 payroll sections (six Compose roots), P11
`HandoverSection`, and P13 `ExecutiveHomeSection` also own
`.verticalScroll(rememberScrollState())`. Directly mounting a nested
unbounded vertical scroller inside the shell's vertical Column can fail at
Compose measure time even when all source and Gradle builds pass.

Each source owner has been notified. Use an optional host-owned-scroll mode
(default preserving their standalone surface) or a measured, bounded nested
scroll container. No second unbounded vertical scroll in the integrated
window. Verify actual nested composition on Android narrow/large text and
Windows. This finding **does not claim** that the currently unmounted screens
have crashed in company production.

## Native UAT matrix before any activation

1. **Authentication:** current actor/organization/session source; denial and
   revoked assignment; sign-out/login with a different actor; no stale
   manager/finance/payroll data visible.
2. **Presentation:** RTL Arabic-first labels, 200% text, Android System Back,
   accessible focus/reordering, Windows keyboard/pointer, empty/loading/error/
   partial states. Every action has readable result and source provenance.
3. **Files:** actual user-selected private bytes and MIME/size/hash; cancel
   picker without an uploaded receipt; prevent cross-order Evidence links.
4. **Transactions:** source-owned idempotency/approval, accepted delivery and
   invoice are not bank payment; external supplier/customer send is separate;
   action success + later list-refresh failure does not claim the action failed.
5. **Compatibility:** latest parent domain source, latest signed-in UX shell,
   scoped responsive navigation and one vertical scroll, same actor test
   matrix on Android and Windows.
6. **Release:** full merged-head database/Flyway + server/Modulith +
   shared native tests + desktop compile + Android APK + staging smoke;
   no flag change or main merge by UX alone.

## Explicitly unresolved

Latest combined P8–P13 source is evolving in parallel, and Android/Windows
native host mounts for these modules require owner/DevOps reconciliation.
The current Desktop Commander connected Windows device could not be used for
physical QA because its monthly tool budget is exhausted; this is **not**
a device acceptance pass. No placeholder module UI is treated as a finished
operational experience.

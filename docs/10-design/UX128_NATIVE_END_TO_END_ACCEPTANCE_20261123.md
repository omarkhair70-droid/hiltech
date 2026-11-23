# UX-128 — HILTECH Windows + Android Native E2E Acceptance

Date: 2026-11-23  
Candidate: DevOps closeout PR #215 + additive UX PR #218  
Status: **ENGINEERING CANDIDATE / NOT RELEASE ACCEPTED**  
All financial execution, payroll bank transfer, supplier/client sending, company data migration and live enablement remain OFF.

## Exact source ownership

| Journey | Native host | Source of authority | UI scope today | E2E |
| --- | --- | --- | --- | --- |
| P7 service purchasing | Android + Windows, protected file picker | Original ServicePurchase needs/quotes/order/Evidence APIs; current source+approvals | Five-stage source-owned journey, bounded scroll under additional default-OFF workspace flag | Device role UAT OPEN |
| P8 supplier invoice finance | Android + Windows | SupplierInvoiceFinanceApiClient list/preview | Read-only source match preview. Mutation/handoff/payment hidden in this route | Finance policy UAT OPEN |
| P9 employee payslip | Android + Windows | `/v1/me/payroll/payslips` | Own-only approved payslip read, no salary cache | Employee/account UAT OPEN |
| P9 restricted Payroll review | Android + Windows | Payroll9aApiClient list/detail, server authorization | Read-only periods, source exceptions. No create/capture/approve/pay | HR current-role UAT OPEN |
| P10 commercial | Android + Windows | CommercialApiClient opportunities/detail | Source-owned Workbench/Deal read, no private cost, release, award or send | Company Sales UAT OPEN |
| P11 handover | Android + Windows | Projects list + per-project Handover readiness/latest | Read-only evidence/package, no invented acceptance or warranty | Project Manager UAT OPEN |
| P11 support/maintenance | Android + Windows bounded source-gated specialist workspace (default OFF) | Original Support tickets, warranty readiness, internal timing, proposed maintenance agenda APIs | Existing Phase11 Sections rendered read-only; no ticket mutation, contract SLA/warranty approval, visit dispatch or client send | Source/device role UAT OPEN |
| P13 Executive | Android + Windows | ExecutiveApiClient current actor/org echoed home, original Projects detail | Source-trusted Home/Brief. Windows original Project drill-through scroll focus; unsupported destinations hidden | Manager device UAT OPEN |

P8J–P8P, Payroll payment, statutory invoice issuance, banking, live contractual SLA/warranty activation and company-wide fiscal close are not implied by UI mounting. Phase12 hardware-dependent controls are outside this release.

- **P11 Support/maintenance native route is newly mounted in UX #218** behind `hiltech.serviceOperationsReadEnabled=false` (Android) / `HILTECH_SERVICE_OPERATIONS_READ_UI_ENABLED` (Windows, absent by default). Per-project support read preflight and per-ticket source GET must succeed. The UI consumes original Phase11 components but records no writes. Real source availability and signed-in actor acceptance remain unproven; this is not contractual warranty or SLA activation.

## CI contract (exact candidate SHA)

- `ux128-unified-native-routes.yml`: `:shared:core:desktopTest`, `:apps:desktopApp:compileKotlin`, `:apps:androidApp:assembleDebug`.
- `ux128-native-field-contract.yml`: native Field/Workspace source/revocation regression and both compilers.
- P9 payroll, P10 commercial, P11 service and P13 Executive owner contract workflows must remain green.
- DevOps #215 owns PostgreSQL/Flyway V0001–V0080, full Spring/Modulith integration, isolated staging and restore proof.
- Exact native CI uses `--no-configuration-cache` to bypass a Gradle 9.5/KSP serialization incompatibility in the previous DevOps candidate, without skipping tests.
- **Do not mark PASS while a run is queued, pending, cancelled or in progress.** Attach exact commit, run URL and job conclusion.

## Device acceptance: both platforms, real source policy

1. Sign in through OIDC PKCE as a real technician, warehouse, purchaser, Finance reviewer, Payroll reviewer, ordinary employee, Sales/PM and authorized executive. Capture *only sanitized* evidence (no salaries, tokens or customer private files). Confirm allowed routes from current API permissions, not title/role-label guesses.
2. Negative controls: same actor without privilege, revoked membership, expired session, changed organization, mismatched returned actor/org, 401/403 and source timeout. Routes must fail closed and must not retain prior company data. A successful tab display is never a write permission.
3. Nested scroll: at 100% and 200% scaling, exercise P7 five-stage source anchors, P9 read panels, P10 detail stages, P11 handover and P13 brief. No infinite-height exception, clipped controls, horizontal bleed or dead gesture. Android system Back returns to correct native shell without accidental financial command.
4. Accessibility: RTL Arabic, long labels, mouse wheel/touch, keyboard Tab/Enter/Escape, screen rotation, Windows resize, IME shown/hidden, empty/loading/error/partial/unknown states, delayed API response, refresh and retry.
5. P7 real private Evidence: choose supported PDF/image with original Windows chooser and Android OpenDocument, reject symlink/wrong MIME/too-large content, confirm original SHA-256 finalize and negative cross-tenant read, without sending to supplier.
6. Payroll separation: ordinary employee sees *only* `/v1/me/payroll/payslips` and cannot open Payroll Admin. Authorized Payroll reviewer sees current source periods under policy. No approval/release/bank execution UI.
7. Executive navigation: Windows source-linked Project/Project Health opens the real authenticated Projects area **and focuses it**; unsupported Approvals/Assets remain non-clickable. Android has no synthetic Project detail link. Mismatched/unknown/partial source must show truthful coverage limits, not fake zero or company totals.
8. P10 private costing, customer quotes, P11 warranty and service response: no hidden data or outward send via read-only route. A 404/unavailable source is an explicit unavailable state, never fabricated success.
9. Offline/process restoration: only the original Field offline-read surface is eligible; do not persist Payroll, Executive, Finance or Sales sensitive snapshots into Field caches. Sign-out and account switch reset composition scope.
10. Test an isolated staging snapshot with provisioned test actors, protected S3 Evidence, current OpenFGA policy, disposable PostgreSQL and successful backup/restore before any launch proposal.

## Ownership and release decision

- **UX PR #218:** Native route wiring, independent scroll, safe navigation, state handling and CI repair; repair any Kotlin/Compose/Android/Windows native regression shown by exact-head tests.
- **Domain owners:** Original API read/write permissions, workflow states, accounting, Payroll HR policy, Service contractual readiness.
- **DevOps PR #215:** Merge ordering, Flyway/SQL sequence, shared host conflict review, exact combined CI, staging/OIDC/OpenFGA/Evidence/backup/restore, release decision.
- **Company:** Real users/accounts, authorized policies, scenario sign-off and explicit activation decision.

**Release remains NO-GO** until exact integrated CI, all role/device UAT, private Evidence and staging/restore evidence, and explicit company approval are recorded. Do not turn default-OFF mutation/outbound flags on merely to make a screenshot.

# HILTECH — whole-company operational acceptance register (engineering checkpoint)

Date: 2026-11-23. Source: `omarkhair70-droid/hiltech`, isolated integration `integration/hiltech-finance-v0100-payroll-bridge-20261123`, baseline `1a9e3afd`. **Engineering candidate, NOT company accepted, NOT deployed.** This document is the compact acceptance plan for the actual company operating system, not a declaration that individual module completion implies company readiness.

## Meaning of done

Follow `docs/13-delivery/DEFINITION_OF_COMPLETE.md`: integrated → verified → operable → released → COMPLETE are **different** states. A compilation passing is not a real operator completing a process. Optimize for useful, fluid, recoverable work (one source of truth, clear next action, minimal duplicate entry), **not** unnecessary administrative gates or a visually finished demo.

Confirmed code/CI baseline:
- Finance8 owner RC #235 `27263ccd`: PostgreSQL/finance and Windows/Android compilation green, canonical Flyway V0001–V0100. Real signed bank provider confirmation, actual concurrency and correction/variance closure are not thereby shown.
- DevOps cross-domain bridge #237 `1a9e3afd`: [full Spring/Modulith/PG, immutable Flyway, native KMP/Windows/Android all green](https://github.com/omarkhair70-droid/hiltech/actions/runs/45262285967), [Payroll→Finance review and Commercial→Finance/Service source](https://github.com/omarkhair70-droid/hiltech/actions/runs/45262285296) green; finance payroll review remains **default OFF** with pending, unnumbered migration.
- Android Phase5 #231: [run 45262564460](https://github.com/omarkhair70-droid/hiltech/actions/runs/45262564460) proved Quick Scan + previous warehouse operator flows but did not pass the final revoked-authority closure screen because old UI test expected strings not emitted by the component. The exact visible denial assertion was corrected at `f6f521ba`, [new emulator run 45266641470](https://github.com/omarkhair70-droid/hiltech/actions/runs/45266641470) not yet accepted at document creation.
- Focused same-source replay on V0100 branch: [UX128 native route](https://github.com/omarkhair70-droid/hiltech/actions/runs/45266900146), [Phase11 service](https://github.com/omarkhair70-droid/hiltech/actions/runs/45266908175), [Phase13 executive](https://github.com/omarkhair70-droid/hiltech/actions/runs/45266914856), [Phase6 offline recovery](https://github.com/omarkhair70-droid/hiltech/actions/runs/45266919434). Each is **pending/in progress unless its exact jobs are subsequently recorded passed**.

## Company journeys: cross-domain truth before UI polish

| Journey / actor | Source chain and required real outcome | Engineering evidence already seen | Outstanding acceptance |
| --- | --- | --- | --- |
| Sales / tender officer → PM | P10 opportunity/quote/approved client PO/signed contract → one P4 Project/Work scope, no duplicate customer award | Commercial release view + Finance/Service joined source on PG in #237 | Create/change/cancel real-approved quote, client PO, scope revisions and project with real Sales/PM actors; compare Windows/Android |
| PM → buyer → warehouse | P4 work need → P7 RFQ/approval/PO → P5 actual receiving/serial/stock ledger; partial delivery, wrong quantity, discrepancy and return | P7/P5 backend tests and synthetic Android receiving & custody suite | UAT against the same PO/WorkOrder/warehouse, linked receipts, corrected stock, no silent overwrite or repeated manual entry |
| PM → technician → supervisor | P4 assigned Work → P5 asset/stock reservation & custody → P6 offline job bundle, drawing/evidence, durable replay → supervisor acceptance → P4 progress | Phase6 test suite, Room/KMP source tests; no operator E2E | Android cold start, airplane mode/process kill, reconnect, retries, 409/version conflict, denied/revoked user, Windows PM read-after-sync |
| Sales / PM → Finance AR | P10 approved award → P8 source candidate → approved invoice → balanced AR journal → bank and witnessed physical cash collection → zero/remaining balance | V0100 sequential mixed 100+50 on 150 approved receivable and negative overcollection tests | Two independent concurrent collectors on remaining balance; real correction/credit note; cashbox variance authority, signed bank exception resolution, customer-facing permissions |
| People / Payroll / Finance | P3 employee/time/HR approved input → P9 immutable run/approval → P8 review envelope → authorized payment result → own payslip | P9→P8 real PG review-intake #237; KMP/Windows/Android builds | Canonical numbered review migration, Finance owner agreement; company-approved bank/payment route and result/reconciliation; ordinary staff sees own only; neither review nor payslip means paid |
| PM → handover → support | P4 completion/Evidence + P10 signed contract → P11 handover internal review → coverage/warranty → support ticket/triage/maintenance visit & spare-part need | P11 signed-Source read, source-scoped service policies, V0072–V0080, native read routes | Document completeness, approved customer acceptance, true warranty/SLA terms, parts/PO/work issue, visit feedback and Finance billable service without speculative charges |
| Finance / executive | P7 source obligations, P8 GL/AR/AP/cash/receipts, P9 Payroll status, P10 commercial, P11 support → P13 role-scoped brief | P13 safe source Home/Brief tests and default-off route | Real manager current company accounts; no invented totals, partial/unknown coverage clearly labeled; drill down to source with correct org/actor |
| Store/field actor with revoked role | Identity/Organization P1 + OpenFGA P2 revocation → P5/P6 safe lookup possible where allowed, mutations denied; no stale company results | P5 quick scan fixture and synthetic denied gate, P1/field negative tests | Actual revoked session on Android + Windows, org switching, sign out/process reset; no unexpected mutation, no private data leak |
| Finance operations/edge states | P8 advance/imprest, expense evidence, AP/AR, bank and cash correction; one immutable fact per source, review not payment | Finance V0081–V0100 PostgreSQL contract suite | Company policies and operator authority for real correction, concurrency, partial allocation, network timeout and safe replay; no forced new modules |
| Employee daily usage | Me/sessions + assigned work + warehouse + evidence + payslip where authorized | Compose/KMP native tests and screenshot fixtures | Real device empty/loading/error/offline states, Arabic RTL, touch+keyboard/IME/Back, resizing/scaling 100–200%, no excessive taps, no dead menus |

No Phase12 hardware activation without actual camera/door/zone inventory. External supplier/client portals (P14–15) and optional NOC (P16) remain outside initial internal OS release.

## Flexibility and operator experience (test observations to record)

For each of the above journeys, a tester records: actor; organization/site/project; source record ID; starting state; steps; result/receipt ID; permission decisions; Windows and Android navigation; time and number of redundant entries; errors and recovery; evidence URI/hash (sanitized); **PASS / FAIL / NOT TESTED**. Test these changes to an in-flight job: amendment, cancel, partial receipt, substitute component, reversed adjustment, changed manager, offline retry, unavailable source and concurrent edits. A draft must remain editable where policy permits; an immutable approved accounting/stock fact receives a correction instead of stealth deletion. Do not conflate strict audit with confusing screen locks.

The UX source acceptance `docs/10-design/UX128_NATIVE_END_TO_END_ACCEPTANCE_20261123.md` remains authoritative: native integrated navigation currently mounts several read-only Finance/P9/P10/P11/P13 panels. Need actual company actor/device UAT for executable workflows and safe hidden destinations. Do not mark the full operator journey passing when only the read path works.

## Server, data and release

- Canonical PG18 test migrations V0001–V0100 green on #237. One pending Payroll→Finance review envelope is **not yet Flyway production migration**; align source owner and allocate version only after current Finance head is frozen.
- Spring Boot Java21 container and Flyway are packaged in CI by `devops-hiltech-server-package.yml`; image **not pushed or deployed**, native installers are separate. A passing bootJar/Dockerfile proof is not runtime sign-in, OpenFGA, object storage, monitoring or live operator access.
- `docs/13-delivery/devops/COOLIFY_HOST_AUDIT_AND_STAGING_GATE_2026-11-22.md` records prior shared Oracle host: 1 vCPU, ~5.8 GiB RAM, paging, two unhealthy containers, abnormal PID snapshot; **capacity/isolation/backup approvals remain open**. Do not build/deploy full stack onto that busy host just to call it done. Prefer a separate capacity-approved staging target connected to existing Coolify.
- Required staging loop: frozen exact SHA/digest → isolated PostgreSQL migration + upgrade/backup/restore → private Keycloak/OIDC + OpenFGA + Evidence → server startup/health/access checks → synthetic company users and real signed native clients → full cross-domain journeys → monitoring/error/rollback → company pilot sign-off.
- Keep actual bank execution, customer/supplier outbound send and company data migrations separate from CI fixtures and disabled until their own source/authority and result flows are verified.

## Release decision / next work order

1. **Finish automated exact-head regression**, record source SHA + successful/failed per job; resolve regressions in isolated integration branches, never blind main.
2. **Compose unified candidate** from Finance V0100 + #237 cross-domain bridge + #231 Android closure **after its full emulator regression succeeds**. Prove unified CI again with both sources on same SHA.
3. **Run source-based native cross-actor UAT**, prioritizing one Sales→Project→P7→P5→P6→P11→P8→P13 internal journey, plus separate Payroll→Finance→Me.
4. **Choose realistic staging capacity, configure services and rehearse recovery**; document exact outcome. Do not confuse available SSH or Docker with a deployed company system.
5. **Review PR cleanup as provenance**: there were 56 open PRs, 43 drafts, 10 Dependabot, 24 base=main, and 10 heads in current integrated commit ancestry; close only proven superseded branches with a specific replacement reference, not by count alone.
6. Mark the *bounded internal company pilot* READY only when required paths have PASS evidence, no critical correctness/access/duplicate-money issues, and someone from HILTECH accepts the actual workflows. Mark COMPLETE only after operable and released within its declared scope.

This checkpoint records engineering evidence, not an unobserved real-company decision.

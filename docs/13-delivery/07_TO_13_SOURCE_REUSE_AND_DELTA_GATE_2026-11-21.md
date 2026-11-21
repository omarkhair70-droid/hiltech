# Phase 7–13 — Source Reuse vs. Real Business Delta

Date: 2026-11-21
Status: **PRE-IMPLEMENTATION SOURCE AUDIT / NOT PHASE FREEZE**
Snapshot: `main` at `13c49c8b07060bd5495f6dcb8f219efe18ffb5c5`. Recheck against latest merged SHA before implementation.
Purpose: shorten the road to Phase 13 **without mistaking existing platform capabilities or design documents for finished business workflows**.

## Baseline correction — read before the historical implementation order

`docs/13-delivery/IMPLEMENTATION_ORDER.md` begins with a historical “PHASE 5 PLAN FROZEN / SLICE 01 NEXT” status. That is **not the current engineering position**. Phase 5 engineering was subsequently merged in [PR #111](https://github.com/omarkhair70-droid/hiltech/pull/111); its [Final Gap Review](phase5/15_PHASE5_FINAL_GAP_REVIEW_2026-11-21.md) still separates engineering from unverified real warehouse/production acceptance. Phase 6 S01/S02 merged; [S03 PR #115](https://github.com/omarkhair70-droid/hiltech/pull/115) is **Ready for Review, not merged**, with 15/15 exact-head GitHub Actions PASS; [S04 contract-only PR #118](https://github.com/omarkhair70-droid/hiltech/pull/118) is **Ready for Review, not merged**. Neither CI nor contract documents prove real technician/warehouse operational acceptance.

The historical order and its `Per-Slice Coding Order` remain the frozen architecture baseline. Do **not** rewrite phases 1–6 as if a new plan changed executed history; track current readiness and real acceptance separately.

## What main actually provides, and what it does NOT

| Existing source on main | Reusable capability | Not automatically supplied |
| --- | --- | --- |
| `server/.../identity`, `people`, `security` and Phase 1/3 migrations | identity/organization memberships, Employee/Employment, current-role checks, revocation | procurement supplier registry, payroll eligibility/legal engine, buyer/payment authority |
| `server/.../approval` and V0012, `inbox` and V0013 | version-bound approval engine, actionable inbox and assignment projection | Phase7 PO approval policy or Phase8 bank-signatory authority (need subject-specific source and policy) |
| `server/.../notifications` and V0014, `activity`, `audit` | safe notification intent/delivery, activity/audit | licensed external messaging channels, business-specific triggers, recipient/privacy policy |
| `server/.../projects`, `work` and V0021–V0025 | Project/Site/WorkOrder/Readiness/review/progress and current assignment | procurement demand approval, commercial Opportunity/BOQ/Quote/Contract, SLA |
| `server/.../warehouse` and V0026–V0033 | stock master, ledger, reservations, physical custody, receiving, discrepancy, stocktake, adjustment | PurchaseRequirement, RFQ, PO, PO amendment, supplier invoice, three-way match, finance payable |
| `ReceivingPersistence.kt` and `ReceivingApi.kt` | `ReceivingSourceType.PO`, a free `sourceRef: String?`, stock/asset receiving/discrepancy flows | an authoritative PO row / FK, approved PO-state or accepted-price comparison: **do not treat PO sourceRef as integrated Procurement** |
| `server/.../documents`, Evidence lifecycle, Ktor/Room native app (Phase 6 evolving) | classified private Evidence and authorized document refs; offline primitive | approved handover package, contractual client visibility, typed test forms, warranty obligations |
| `shared/core/...` existing Project/Warehouse command-center sections | domain-specific existing UI and source projections | cross-domain executive Company Brain, finance or payroll aggregates |

**Verification scope:** top-level tree and named source files/migrations were inspected. The absence of dedicated procurement/finance/payroll/sales implementation directories/migrations in this snapshot is an engineering reconnaissance finding, **not** a claim that every possible symbol or prototype has been exhaustively searched.

## Phase-by-phase delta matrix

| Phase | Reuse immediately | Actual new business verticals | Must verify before implementation |
| --- | --- | --- | --- |
| **7 Procurement** | Work resource demand; Stock/Receiving; Approval; Inbox; Evidence | PurchaseRequirement and validation/stock-before-buy; supplier/RFQ/quote versions; PO approval/amendments; delivery-to-PO matching; supplier invoice and finance handoff | buyer/procurement roles and limits, real supplier/PO/quote samples, spend approvals, 3-way-match rules; source binding must be stronger than arbitrary Receiving `sourceRef` |
| **8 Finance Operations** | Employee and project refs; Approval/Audit/Inbox, evidence storage | expenses, salary Advance vs financial Imprest, Payables/Receivables, invoice/payment instructions, external result/reconciliation, project finance views | finance owner/legal/currency/tax rules, approval authority, bank execution path, **unknown outcome** handling; no money transfer from a local intent |
| **9 Payroll** | Person/Employee/Employment and offboarding; Approval; future approved Finance advances | payroll period and snapshot, compensation rules, overtime/deductions, versioned draft/calculation, approvals, per-employee payment results, payslips/correction/final pay | Egypt statutory/tax/social-insurance/accountant approval, actual payment route, employee privacy and immutable historical eligibility |
| **10 Sales & Tenders** | Organization/Project/Site, safe documents, approval and later Phase8 financial model | Opportunity, Tender/RFQ, SiteVisit, Scope/BOQ, costing, quote revisions, contract and client PO, exact Won-to-Project handoff | actual commercial quote/BOQ/contract samples, restricted cost vs client price, signatures and authorized version chain |
| **11 Handover/Support/Maintenance** | WorkOrder/Evidence/reviews, Asset Passport/incident, Project/Site and safe documents | versioned HandoverPackage/completeness, acceptance, Warranty/Coverage/SLA, support ticket, maintenance schedule/visit, renewal | real warranty/coverage contracts, client evidence release, document retention, SLA breach/revision/rework semantics |
| **12 Security/Facilities integrations** | identity/role restrictions, Asset/Site, incident/activity, notifications | facility/zone inventory, device adapters, health/events and restricted incidents | actual CCTV/NVR and access vendor/protocol inventory, privacy/storage/consent, fail-safe physical command restrictions |
| **13 Executive Control** | matured Project/Work, Warehouse, People, future Finance and Sales, Inbox and Audit | cross-domain owner Home/Needs You, company pulse, risk and exception aggregation, verified drill-down, digest, safe cross-domain search | owner decision rubric, role/restricted figures, alert priority, freshness; **no second ledger and no invented business data** |

Source docs used: [Implementation Order](IMPLEMENTATION_ORDER.md); `docs/05-workflows/PROCUREMENT_FLOW.md`, `FINANCE_PAYMENT_LIFECYCLE.md`, `PAYROLL_FLOW.md`, `DOCUMENT_HANDOVER_LIFECYCLE.md`, `MAINTENANCE_MANAGED_SERVICE_LIFECYCLE.md`, `EMPLOYEE_ADVANCE_IMPREST_LIFECYCLE.md`; `docs/06-data/object-specs/PROCUREMENT_FINANCE_OBJECTS.md` and `SALES_COMMERCIAL_OBJECTS.md`. Most carry **v0.1/RESEARCHING/NOT SCHEMA FROZEN** labels: they are discovery input, not existing production domain.

## Fast-track protocol for every new phase

1. **Inspect merged main + outstanding PRs + schema + business proof**. Write a `REUSED / EXISTS-BUT-NOT-AUTHORIZED / MISSING / REALITY-BLOCKED` matrix. Compare code, tests and source data before taking old plans literally.
2. **Start with the smallest real new vertical**, not reimplementation of an existing screen, approval or DB. Reuse exact Work/Stock/People source IDs and current versions. Show explicit unavailable/blocked if authority is missing.
3. Freeze only the bounded domain contract for that vertical; cross-cutting foundations already exist. Confirm data ownership, authorization matrix, API, snapshot/version, reconciliation and idempotency.
4. Complete vertical PR: DB/constraints, server source/commands, authorization and audit, shared client, necessary UI, tests, and native/Windows/real-operator evidence. Distinguish synthetic CI green from field/finance/warehouse acceptance.
5. Check **actual merged main** for obsolete notes/dependencies again before next slice. If earlier implementation made a candidate slice redundant, retire/reduce it explicitly with source evidence. Never inflate a phase just to match an old count.

## Immediate queued work, separated by gate

- **Phase6 S03:** [#115](https://github.com/omarkhair70-droid/hiltech/pull/115) already 15/15 exact-head PASS; seek independent maintainer review, complete [#116](https://github.com/omarkhair70-droid/hiltech/issues/116) real-device / technician-supervisor acceptance or explicitly disposition its gating nature, then merge with correct release status and verify post-merge Bootstrap. No pretend signoff.
- **Phase6 S04:** [#118](https://github.com/omarkhair70-droid/hiltech/pull/118) contract reviewed for reuse: Phase3 actor→Employee via Person/organization, Phase4 WorkOrder assignment/current `can_view`, Phase5 own-employee `stock_issued_balance`. Only implement a *new* scoped read projection after S03 gate; do not rebuild the already existing Materials/Documents cards, fabricate issued vs consumed quantity, or call warehouse Consume as a technician.
- **Phase7 preparation:** [Phase7 narrow preflight](phase7/00_PROCUREMENT_DELTA_PREFLIGHT_2026-11-21.md) can be source-reviewed now without creating production objects or changing Phase6 code. Phase7 implementation follows the approved phase gate and real procurement samples.

`PHASE7_TO_13_REUSE_AUDIT = SOURCE_GROUNDED / CANDIDATE`
`PHASE7_TO_13_DOMAIN_CONTRACTS = NOT_FROZEN`
`PHASE7_TO_13_IMPLEMENTED_BY_THIS_DOC = NONE`

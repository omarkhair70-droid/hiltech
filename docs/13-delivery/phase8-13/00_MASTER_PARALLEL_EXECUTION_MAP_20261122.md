# HILTECH | 5 production workstreams across Phases 8–13
Date: 2026-11-22
State: research-grounded EXECUTION MAP, not product implementation, operational sign-off, or merged production plan.

## Scope
Active: P8 Finance, P9 Payroll, P10 Sales/Tenders/Commercial, P11 Handover/Warranty/Support/Maintenance, P13 Mohamed Executive Control. P12 camera/access-control hardware integration is deferred until real equipment/vendor inventory and approved privacy/access policy. No Phase 14–16/external client portal/NOC.

Current baseline: main fae1d7f36eb9adeaecfea6ccbd07baba6b2af646 through V0038. Existing Phase7 branch/PR stack #143 -> #146 -> #148 -> #153 -> #157 -> #158 remains unmerged and extends schema to V0059. PR #119 is an earlier source-based reuse audit (docs only). Check live heads before coding.

Source of truth: docs/13-delivery/IMPLEMENTATION_ORDER.md and DEFINITION_OF_COMPLETE.md; docs/05-workflows/FINANCE_PAYMENT_LIFECYCLE.md, ADVANCE_IMPREST_LIFECYCLE.md, PAYROLL_FLOW.md, CLIENT_SUPPORT_SERVICE_LIFECYCLE.md, DOCUMENT_HANDOVER_LIFECYCLE.md, MAINTENANCE_MANAGED_SERVICE_LIFECYCLE.md; docs/06-data/object-specs/PROCUREMENT_FINANCE_OBJECTS.md, PEOPLE_PAYROLL_OBJECTS.md, SALES_COMMERCIAL_OBJECTS.md, SUPPORT_DOCUMENT_SECURITY_OBJECTS.md; PR #119 document 07_TO_13_SOURCE_REUSE_AND_DELTA_GATE_2026-11-21.md.

## Reuse instead of repeat
Already implemented foundations: Identity/organization membership, Employee/Employment/WorkforceAssignment, Project/Site/WorkOrder, Warehouse/Asset/Stock ledger, ApprovalEngine/Inbox, classified Evidence/Object Storage, Audit/Activity, notifications abstraction, KMP/Compose Android/Windows, idempotent typed commands, migrations/CI. Reuse doesn't equal automatic authority or completed Finance, Payroll, Sales, Support or executive projections.

## Candidate vertical slices (subject to genuine safety split)
8: 8A source-bound payable/receivable/client+supplier invoice; 8B expenses vs employee Advance vs FinancialImprest settlement; 8C payment preparation & versioned approval, UNKNOWN state; 8D payment results/allocations/reconciliation/credits/project finance.
9: 9A employee eligibility/time/compensation frozen snapshot; 9B calculation/exception/review/adjustment/approval; 9C Finance payment-result linkage, private payslip/corrections/final pay.
10: 10A opportunity/tender/site visit; 10B Scope/BOQ/costing/quote/approval; 10C awarded contract/client PO -> Project + versioned billing-source handoff.
11: 11A versioned handover completeness/acceptance and warranty; 11B support/coverage/contractual SLA/reopen; 11C preventive schedule/visits/findings -> existing WorkOrders, P7 parts, commercial renewal.
13: 13A Needs You/owner decisions + Project/Warehouse exceptions from existing read models; 13B Finance/Payroll/Commercial/Support pulse, safe freshness/drill-through/digest as domains finish.
15 CANDIDATE VERTICALS, not 15 guaranteed delivery closures. Split one when separate cash/legal/approval/recovery risk requires an independent gate; retire redundant components only with current source evidence.

## Dependencies and safe parallelism
P7 verified supplier-match handoff -> P8 supplier Payable.
P8 approved employee Advance deduction input -> P9 Payroll; P8 payment-authoritative result -> P9 payroll settlement.
P10 authorized won/contract version -> Project creation and P8 client Receivable; P11 warranty/maintenance coverage consumes P10 commercial contracts.
Project/Work/Field and Evidence -> P11 Handover, Support and Maintenance.
Existing Approval/Inbox/Project/Warehouse -> P13 first dashboard; verified P8–P11 projections -> P13 complete.
Start now in separate chats: P7 remaining office/ad-hoc/service contract; P8 8A finance read/source and ledger design; P9 9A snapshot and inputs (no payout); P10 10A; P11 11A; P13 13A. Later work synchronizes only across versioned contracts.

## Branch and merge protocol
One WORKTREE/BRANCH/OWNER per lane. No phase chat edits another lane's branch. No separate lane modifies global Android MainActivity, Desktop Main, common Gradle, shared security, shared ApprovalEngine, Flyway numbering, or common API DTOs concurrently; make integration adapters/feature modules and a separate integrator wiring PR. One integration owner allocates migration versions based on actually merged main (do not skip lower Flyway versions or merge migration version out of order). No hardcoded employees, thresholds, wages, tax, SLAs, banking routes or special approvals. All new features default OFF until real policy. GitHub Actions exact-head tests then combined integration branch, real operator/device evidence, rollback rehearsal, then controlled merge and staged release.

Completion means DB constraints + API + authorization + audit + native role workflow + offline rules where applicable + negative same/foreign-org and revocation + actual source cases + deploy/rollback. A green compile is never a released or signed-off business vertical.

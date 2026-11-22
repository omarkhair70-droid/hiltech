# Phase 11 A2/B3/C — internal review, verified support resolution, field evidence

Owner branch phase11/handover-service-20261122, PR #167. All code remains DRAFT, OFF, unmerged and undeployed.

## Handover internal review (A2)
Frozen handover package versions use actual WorkOrder lifecycle AND version, required DOCUMENT satisfaction AND version, current approved Evidence revision/digest and Project version. Source changes invalidate further review work on a stale draft. An append-only review ledger allows CHECK_STARTED, FINDING_RECORDED, RECHECK_REQUESTED, with idempotent audited management-only transactions and strict sequence/FK/source constraints. It does not approve or issue to clients; client authority and sharing policy are still unavailable. Standalone KMP client and native section are included, not yet mounted in global shells.

## Support case lifecycle (B3)
Original service_support_ticket OPEN event is immutable; a separate source-bound event ledger tracks OPEN, INVESTIGATING, RESOLVED_INTERNAL and CLOSED_INTERNAL and permits later REOPENED. Work links point to canonical existing WorkOrders; internal resolution needs linked accepted/closed real WorkOrder and READY Evidence from that exact WorkOrder and organization, verified in service AND DB trigger. Source-project manager auth, DB ticket row lock, expected latest event number, idempotency, immutable reasons and audit. Internal closure is NOT client acceptance, warranty, SLA, Finance or external communication. Standalone shared KMP API + Compose UI are included.

## Maintenance source readback (C)
Visit proposals still point to original WorkOrder. Manager read endpoints expose current lifecycle/version, required checklist completion and READY Evidence count plus actual READY Evidence choices, without object storage keys or filenames. Field findings can be recorded during source IN_PROGRESS/BLOCKED/SUBMITTED_FOR_REVIEW/REWORK_REQUIRED/ACCEPTED/CLOSED; cannot record while only DRAFT/PLANNED/ASSIGNED/CANCELLED. Existing Evidence must be READY and work-scoped. No claim that the visit itself is completed, no auto-recurring plan, extra WorkOrder, new asset, procurement order or payment.

## Strict staged SQL ordering (DevOps assigns unique Flyway numbers)
- database/pending/phase11/11A_handover.sql
- database/pending/phase11/11A2_handover_internal_review.sql
- database/pending/phase11/11B_support_internal.sql
- database/pending/phase11/11B2_support_triage.sql
- database/pending/phase11/11B3_support_case_workflow.sql
- database/pending/phase11/11C_maintenance_visits.sql

## Dedicated CI contract
.github/workflows/phase11-source-service.yml: exact-head six focused server policy test classes; canonical database migration contract on disposable PostgreSQL; ordered staging DDL plus scripts/ci/phase11-schema-source-check.sql verifying FKs, source triggers and immutability; shared native Desktop tests/compile and Android debug assembly.
Successful earlier local focused Gradle run ONLY applied to 150e5989 before A2/B3/C changes. Treat new workflow as unverified until latest-head run completes. No staging/production flag activation.

## Full completion gates
Company-approved true handover completeness and client acceptance authority; verified and signed immutable P10 award evidence plus actual service/coverage/SLA/renewal terms; real field WorkOrder execution and signed-off technical checks; P7 parts procurement and P8 invoices linked through their own approved authorities; shared-shell Windows/Android authenticated role tests including revocation and cross-tenant; clean/upgrade PostgreSQL with invalid states, retries and concurrency; DevOps/Coolify restore/release gates. None is inferred from free text, invented prices/durations or a generated project document.

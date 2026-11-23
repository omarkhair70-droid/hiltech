# Service11 integrated candidate on HILTECH unified Phase7/8/9/10 + Native UX source

Date: 2026-11-23. Status: ISOLATED DRAFT. NO-GO for main or company staging/live.

## Pinned sources and authority

- Parent source PR #202 exact green head aaac5dc11b11d6fadd7aafa34107d020c189441e (includes P7 procurement, Finance8A-I, UX Windows/Android, Payroll9 and Commercial10). Exact-head all seven combined CI checks green, including full server/Modulith/Flyway/Windows/Android, P8/P9/P10 specialist and server bootJar.
- Added independently own-lane green Service11 PR #167 exact c4c15e07f65f46355786e986183dd88ff2a7ba20. Original source branch remains unchanged, not deployed, contracts/coverage/SLA intentionally unknown rather than fabricated.
- Git merge was clean in isolated worktree (no shared-file conflicts).

## Database migration order on integrator branch only

The source at PR #167 has eight, not seven, pending Phase11 scripts. Preserve exact source bytes and declared dependencies:

- V0072: 11A_handover.sql
- V0073: 11A2_handover_internal_review.sql
- V0074: 11B_support_internal.sql
- V0075: 11B2_support_triage.sql
- V0076: 11B3_support_case_workflow.sql
- V0077: 11C_maintenance_visits.sql
- V0078: 11C2_maintenance_ticket_followups.sql
- V0079: 11C3_maintenance_technical_review.sql

Main V0001-V0038 unchanged. P7 V0039-V0064, P8 V0065-V0066, P9 V0067-V0070, P10 V0071. Exact byte hash of each Phase11 pending file and numbered copy matches; 79 unique Flyway versions contiguous; the post-V0033 contract count now expects 46. No database scripts were executed on the company's server.

## Required exact merged-head verification

1. devops-p7-finance-payroll-sales-service11.yml: whole backend PostgreSQL 18/Modulith/Flyway history/bootJar/Android/Windows KMP.
2. phase11-source-service.yml modified ONLY on this integrator branch to use already-numbered Flyway V0072-V0079 and test independent schema/negative contracts without double applying source SQL. P11 source owner's own isolated CI continues unchanged.
3. Phase10 commercial real signed contract, Projects handoff and Phase11 commercial_support_contract_source must work on the same disposable DB; no fake client acceptance/SLA/warranty.
4. P9 restricted Payroll and P8 Finance current source and Evidence separation regressions all must pass on this combined head.
5. Actual signed-in multi-role UI, complete HTTPS/OIDC/OpenFGA, private Evidence upload/read, company policy, backups/restore and isolated approved hosting remain separate NO-GO gates.

No employee salary data, bank payment, supplier/customer outbound, company migration, Docker restart, feature enable, merge into main or release performed as part of this branch.
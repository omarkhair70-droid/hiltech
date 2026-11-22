# HILTECH — proposed non-conflicting Flyway integration order

**Checkpoint:** 2026-11-23 · **Status:** PROPOSAL FOR INTEGRATION, **not a reserved/merged migration allocation**. Do not rename files in the original domain PRs until the relevant domain owners and integrator have checked current exact heads, dependency SQL, fresh + existing PostgreSQL migrations and native source tests. This document changes no database.

## Why this is needed

`main@5696138f4f702c96c102676a397c6d5dbced50c1` contains V0001–V0038. The main branch now has the fail-closed per-candidate [#173](https://github.com/omarkhair70-droid/hiltech/pull/173) Flyway history guard. Active independent Engineering PRs *overlap*:

- P7 S01–S06: V0039–V0060; newer non-stock services S07: V0061–V0064 in [#174](https://github.com/omarkhair70-droid/hiltech/pull/174) → [#178](https://github.com/omarkhair70-droid/hiltech/pull/178) → [#181](https://github.com/omarkhair70-droid/hiltech/pull/181).
- P8 Finance 8A/8B: **different** SQL also called V0061/V0062 in [#164](https://github.com/omarkhair70-droid/hiltech/pull/164) → [#175](https://github.com/omarkhair70-droid/hiltech/pull/175) → [#179](https://github.com/omarkhair70-droid/hiltech/pull/179) → [#180](https://github.com/omarkhair70-droid/hiltech/pull/180).
- P9 Payroll [#166](https://github.com/omarkhair70-droid/hiltech/pull/166), **latest examined head `c65105e`**: V0070 **through V0073** (four SQL files, not three).
- P10 Sales [#170](https://github.com/omarkhair70-droid/hiltech/pull/170), examined head `c16054b`: one **pending commercial schema** plus two contract-check SQL files. Do NOT count the tests as production Flyway migrations.
- P11 Service [#167](https://github.com/omarkhair70-droid/hiltech/pull/167), examined head `08c854d`: **six** pending SQL scripts, not four.
- P13 Executive [#168](https://github.com/omarkhair70-droid/hiltech/pull/168): no new migration yet.

## Candidate sequence (subject to schema and source-owner validation)

| Proposed version | Owning phase | Original branch file or source |
| --- | --- | --- |
| V0039–V0060 | P7 S01–S06 | Original unchanged stock purchasing migration series through #162 |
| V0061 | P7 S07A | `V0061__procurement_nonstock_service_need__phase7_s07.sql` |
| V0062 | P7 S07B | `V0062__procurement_nonstock_service_sourcing__phase7_s07b.sql` |
| V0063 | P7 S07C | `V0063__procurement_governed_service_order__phase7_s07c.sql` |
| V0064 | P7 S07D/E | `V0064__procurement_service_delivery_invoice__phase7_s07d_e.sql` |
| **V0065** | P8 8A | currently provisional `V0061__phase8_finance_source_bound_payable_candidate.sql` |
| **V0066** | P8 8B | currently provisional `V0062__phase8_finance_employee_expense_advance_imprest_drafts.sql` |
| **V0067** | P9 9A | currently provisional `V0070__payroll_period_snapshot__phase9_9a.sql` |
| **V0068** | P9 9B | currently provisional `V0071__payroll_draft_run__phase9_9b.sql` |
| **V0069** | P9 9B release | currently provisional `V0072__payroll_run_release__phase9_9b.sql` |
| **V0070** | P9 9C | currently provisional `V0073__payroll_correction_link__phase9_9c.sql` |
| **V0071** | P10 10A–C | `database/phase10/pending_commercial_schema.sql`, **only if owner approves one coherent version** |
| **V0072** | P11 11A | `database/pending/phase11/11A_handover.sql` |
| **V0073** | P11 11A2 | `database/pending/phase11/11A2_handover_internal_review.sql` |
| **V0074** | P11 11B | `database/pending/phase11/11B_support_internal.sql` |
| **V0075** | P11 11B2 | `database/pending/phase11/11B2_support_triage.sql` |
| **V0076** | P11 11B3 | `database/pending/phase11/11B3_support_case_workflow.sql` |
| **V0077** | P11 11C | `database/pending/phase11/11C_maintenance_visits.sql` |

**Proposed combined candidate: 39 additive migrations V0039–V0077.** This is conditional on zero additional actual DDL files and P10 being safely represented by one script. If a domain adds a migration or needs to split P10, **shift every later provisional mapping** before SQL is applied anywhere; never leave holes or skip lower historical versions.

## How to implement without blocking parallel engineering

1. Keep every domain PR on its own branch. No one renames SQL inside a working domain source branch behind another engineer's back.
2. Make an **integrator-only staging merge branch from exact current main** with ordered P7 parent source; include the approved S06 Modulith repair [#176](https://github.com/omarkhair70-droid/hiltech/pull/176) and S07 Document/Warehouse repair [#184](https://github.com/omarkhair70-droid/hiltech/pull/184) **only after current source and tests pass**.
3. Add Finance 8A/8B source as a second reviewed merge, renaming only its **unmerged provisional** migrations to V0065/V0066 on the integration branch. Repair version references/count expectations in global DB tests, phase-specific SQL tests, any Flyway path manifest and server package build. Retain all Finance flags OFF, no money/approval changes.
4. Add Payroll after Finance, assign four newly numbered migrations as a single ordered block, then Sales/Service as inspected; **verify FK dependencies, migration checksum immutability, fresh DB + upgrade from V0038, savepoint/restart semantics, Spring Modulith + full KMP Android/Windows compile**. Never change the existing V0001–V0038 bytes or replay applied SQL.
5. Company-owned Approval, signed contract/Evidence and payroll source policies remain fail-closed. Staging pilot must prove negative foreign-org/revoked, offline, staff role and document access before activating features.
6. Publish source SHA and SQL ledger to [#161](https://github.com/omarkhair70-droid/hiltech/pull/161); merge parents in order only with green exact-head checks and review. Roll back a failed release by restoring a tested backup or deploying the prior image **without pretending irreversible Flyway schema commits are undone**.

**Current disposition: NO-GO.** This table is a proposed *collision-free order*, not a successful build, data migration, restoration, or permission to merge into production.

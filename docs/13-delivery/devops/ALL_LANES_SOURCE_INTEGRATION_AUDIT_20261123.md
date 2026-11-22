# HILTECH | Cross-phase implementation and DevOps integration audit

**Checkpoint:** 2026-11-23 (Egypt time) · **Scope:** repository and CI sources, actual observed Coolify staging, parallel engineering PRs · **Decision:** **NO PRODUCTION GO**

This reviews **what has actually been built** instead of equating a passing isolated test with deployment. Source refs are pinned in the companion audit workflow; new Engineering commits require a fresh exact-head re-audit. Do not use this document to bypass company authorization.

## 1. Source-of-truth and actual main

- Current `main`: `5696138f4f702c96c102676a397c6d5dbced50c1`, latest committed migration **V0038**. DevOps [#173](https://github.com/omarkhair70-droid/hiltech/pull/173) was merged and installs a source-only, append-only, gap-free Flyway premerge check.
- `docs/13-delivery/IMPLEMENTATION_ORDER.md` defines vertical-slice DB, server, permissions, audit, Android/Windows, offline rules and negative tests. Its frozen headings still describe older Phase 5 implementation status; do not use the heading text as live completion evidence. Latest Phase 8–13 parallel map in [#161](https://github.com/omarkhair70-droid/hiltech/pull/161) defers Phase 12 hardware until approved device evidence and excludes Phase 14–16 from first internal rollout.
- Already available foundations on main: People/Employment and organization membership, Project/Site/WorkOrder, Warehouse/Asset/stock provenance, ApprovalEngine/Inbox, classified Evidence and KMP/Compose native shells. **Reusable source code does not imply every native screen is globally mounted or authorized in staging.**

## 2. Phase 7 purchasing — full source chain, not just old S01–S06

| Child PR and base | Implemented source capability | Explicit release status |
|---|---|---|
| [#143](https://github.com/omarkhair70-droid/hiltech/pull/143) → [#146](https://github.com/omarkhair70-droid/hiltech/pull/146) → [#148](https://github.com/omarkhair70-droid/hiltech/pull/148) → [#153](https://github.com/omarkhair70-droid/hiltech/pull/153) → [#157](https://github.com/omarkhair70-droid/hiltech/pull/157) → [#158](https://github.com/omarkhair70-droid/hiltech/pull/158) → [#162](https://github.com/omarkhair70-droid/hiltech/pull/162) | S01–S06 stock-bound demand, RFQ, technical approval, PO, canonical receiving, supplier evidence, invoice match, company/team stock. Contiguous candidate **V0039–V0060** | Source candidate [#163](https://github.com/omarkhair70-droid/hiltech/pull/163) with corrected Spring Modulith boundaries; **NOT merged to main**; all company supplier/approval features OFF |
| [#174](https://github.com/omarkhair70-droid/hiltech/pull/174) on #162 | S07A non-stock service requirements, independent technical review; **candidate V0061** | Draft, no supplier financial operation |
| [#178](https://github.com/omarkhair70-droid/hiltech/pull/178) on #174 | S07B trusted supplier/quote Evidence, source-current RFQ; **candidate V0062** | Draft; protected quote access and Evidence need cross-lane review |
| [#181](https://github.com/omarkhair70-droid/hiltech/pull/181) on #178 | S07C/D/E governed internal service order, deliverable acceptance, supplier invoice **two-way** match and review-only handoff; **candidate V0063/V0064** | Draft; no outbound supplier send, no stock receiving or booked payable. Latest exact-head CI pending |
| [#169](https://github.com/omarkhair70-droid/hiltech/pull/169) / [#176](https://github.com/omarkhair70-droid/hiltech/pull/176) | Fix Warehouse ↔ Procurement cycle and non-exposed Documents storage reference via narrow public policy/evidence facades | #169 integrated into *test branch #163 only* and passed backend/Postgres; #176 backports to **original P7 source**; exact-head backport CI needs final confirmation |

**Do not merge #163 straight into main**: it is a test aggregation, not the ordered company-reviewed source branch. Do not call an internally marked service order externally dispatched. P7 service invoice two-way verification is not the same as stock PO three-way matching, and Finance must preserve this source distinction.

## 3. Five Phase 8–13 Engineering workstreams

| Domain | Latest reviewed PR | What the code supports now | Integration/release gap |
|---|---|---|---|
| Finance 8 | [#164](https://github.com/omarkhair70-droid/hiltech/pull/164), [#175](https://github.com/omarkhair70-droid/hiltech/pull/175), [#179](https://github.com/omarkhair70-droid/hiltech/pull/179), [#180](https://github.com/omarkhair70-droid/hiltech/pull/180) | Trusted invoice accounting-review candidate (not booked payable), separate employee expense/Advance/Imprest drafts, read-only payment preflight, and pure UNKNOWN/no-blind-retry result rules | **V0061/V0062 collide with P7 S07 V0061/V0062**; #175 specialist CI passed while DevOps package FAILED old 27-migration shared test. No accounting booking, validated beneficiary, company signatories, payment execution, authoritative bank result or Payroll deduction |
| Payroll 9 | [#166](https://github.com/omarkhair70-droid/hiltech/pull/166) | Period snapshots, calculation generations with sourced HR/approved-time/Finance inputs, independent ApprovalEngine release, typed restricted payslip/Finance result read model; provisional **V0070–V0072** | Needs final gap-free ordering; real verified compensation/attendance/statutory policy, accountant authority and Finance payout source, immutable bank result provenance; latest exact-head CI pending. No money movement |
| Commercial 10 | [#170](https://github.com/omarkhair70-droid/hiltech/pull/170) | Opportunity/tender/site visit → BOQ/cost vs client quote separation → quote version/approval → signed contract and client PO draft → guarded Project source and Finance/P11 read contracts | SQL in `database/phase10/pending_commercial_schema.sql`, **not a Flyway version**. Contract/PO Evidence destination adapter fails closed until Documents authority supports it; no actual approved external send/award; native host not wired |
| Service 11 | [#167](https://github.com/omarkhair70-droid/hiltech/pull/167) | Source-backed handover draft, private support triage, maintenance visit proposal/findings based on actual WorkOrders and READY Evidence | Four SQL migrations under `database/pending/phase11/` **not numbered**. Warranty/SLA/coverage/client acceptance and original 10C signed-contract authority unresolved; exact-head DB/native/live proof pending |
| Executive 13 | [#168](https://github.com/omarkhair70-droid/hiltech/pull/168) | Actor-source Needs You, bounded Project health and Warehouse/Asset exceptions, source timing, partial/unavailable truth, typed native Executive route | No schema; screen not mounted in global Windows/Android host. Current inbox feed is actor-wide **not selected-company total**. Actual Finance/Payroll/Commercial/Support sources only after domain release; company executive privacy approval and role/device proofs pending |

**UX/source integration:** [#171](https://github.com/omarkhair70-droid/hiltech/pull/171) builds on the pre-fix #163 base, brings source-connected native operational design language to the existing supplier-communication screen, but not complete role-host navigation. It must be reconciled with revised #163, S07 and P8–11/13 source routes. #156 is older, **not** the current UX release baseline.

## 4. Flyway collision and branch drift: immediate hard NO-GO

The current SQL version allocation is *provisional*:

- P7 S01–S06: **V0039–V0060** on an unmerged ancestor.
- P7 S07A/B/C–E: **V0061–V0064** on [#181](https://github.com/omarkhair70-droid/hiltech/pull/181).
- Finance 8A/8B: **another V0061/V0062**, different SQL contents, on [#180](https://github.com/omarkhair70-droid/hiltech/pull/180).
- Payroll: **V0070/V0071/V0072** directly from main V0038; cannot be merged into existing Flyway history with lower versions missing.
- Sales and Service: SQL **unnumbered**; no final allocation.

DevOps already merged [#173](https://github.com/omarkhair70-droid/hiltech/pull/173) to main to block gaps, rewrites and duplicate names in **a single candidate branch**. That does **not** spot two different unmerged branches each claiming V0061. This PR adds a **cross-branch** read-only scanner covering exact main and six domain heads; it accepts inherited identical SQL, reports distinct-content version collisions and missing lower versions, and inventories pending DDL. Its diagnostic success means the **scanner ran**, not that a blocked release is ready.

Do not silently renumber already-merged/apply-in-production SQL. **No P7/Finance/Payroll new version has been merged to main at this checkpoint**, so the integrator may coordinate a new consecutive allocation after reviewing source SHA, SQL dependencies, upgrade/fresh migration tests and ownership of global migration-count contracts. Do not choose a final V0065+ range until the pending Sales/Service SQL file count, ordering and source FKs have been inspected with their engineers.

## 5. DevOps, identity, authorization, runtime and recovery

- [#145](https://github.com/omarkhair70-droid/hiltech/pull/145), [#155](https://github.com/omarkhair70-droid/hiltech/pull/155), [#159](https://github.com/omarkhair70-droid/hiltech/pull/159), [#160](https://github.com/omarkhair70-droid/hiltech/pull/160): source/docs/tests on isolated branches; the newer OpenFGA bearer-tested multiarch **staging** server image is pinned on a **stopped** capped API candidate. This is not the up-to-date P7+P8–13 integrated image.
- On the prior direct Coolify check: Teswa `running:healthy`; three HILTECH PostgreSQL DBs `running:healthy`; Keycloak, persistent authenticated OpenFGA and RustFS privately routed with `running:unknown` status; **HILTECH API stopped**; no public HILTECH FQDN; production environment empty. Keycloak internal OIDC discovery, FGA allow/deny and private S3 signed PUT/GET/DELETE had passed focused staging tests; **native login on HTTPS/role E2E has not**.
- Coolify DB backup read-only query found no enabled HILTECH backup schedules nor active S3-saving schedule. That does not prove an independent non-Coolify backup doesn't exist. **No tested offsite encrypted restore** or approved recovery objective documented. [#177](https://github.com/omarkhair70-droid/hiltech/pull/177) is **disposable CI Postgres backup/restore rehearsal** only (exact-head result must be verified), not a company backup/restore.
- [#137](https://github.com/omarkhair70-droid/hiltech/issues/137) historical issue headline claimed missing Actuator. Current main **has** the dependency and tests 200/UP liveness/readiness with Postgres; real readiness on OIDC/FGA/S3 failure and 503 behavior remain unproved. Preserve no-store and no secret-bearing health.
- Old host audit [#135](https://github.com/omarkhair70-droid/hiltech/pull/135) conflicts with newer main code; cherry-pick reviewed docs/isolated tools only if still useful. **Never wholesale merge old security/identity or packaging changes.**
- One-shot OpenFGA bootstrap helper stopped. The copied secret was overwritten with an invalid sentinel, but Coolify's API denied deleting its *name* with HTTP 403. Privileged Coolify owner should delete the unused entry; do not claim removed.

## 6. Ordered integrator work and exact release gates

1. **Freeze observed heads, not business feature definitions.** Check current PR #176 source-backport and #163 full combined CI, then integrate P7 S07 parts #174 → #178 → #181 in a separate test branch after strict module boundary and Evidence adapter review.
2. **Coordinate a single contiguous migration ledger** across P7 S07, Finance 8A/8B, Payroll and pending Sales/Service; no live SQL edits, no skipped Flyway version, preserve byte-identical historical migrations. Fresh and upgrade PostgreSQL, rollback and idempotent replay proofs.
3. **Rebase each source lane intentionally** so old branch-specific fixed-count migration tests reflect actual merged source order; don't simply relax tests. The #175 current head package check is RED while its specialist check is GREEN.
4. **One integrator owns native entrypoints**: adopt UX #171 while preserving supplier source/stock/Warehouse, wire Finance/Payroll/Sales/Service/Executive authenticated routes, role/routing/logout/revocation, and prove Android/Windows via real hardware profiles and actual company source references.
5. **Stage the exact merged tested image** (not older SHA), private DNS/API/Keycloak/RustFS and company-approved HTTPS issuer/signing, then positive and negative authorization/evidence tests; keep external supplier send, Finance, payroll, Sales release switches **OFF** until real policy and multi-role acceptance.
6. **Protected data & recovery**: inventory DBs and object-store durability, configure approved encrypted off-host backup and retention, restore onto separate disposable infrastructure with integrity proof, verify monitoring/rollback and host capacity/Teswa isolation. Then signed native distribution and explicit production GO.

**Non-negotiable:** no automatic supplier dispatch, customer quotes, financial instructions, payroll disbursement, unauthorized executive access, or production migration because a package compiled. No UI prototype counts as a completed vertical without source-backed end-to-end tests.

This is a **source-backed review of examined PR metadata/docs, changed-file lists and observed CI/staging statuses**, not an exhaustive manual line-by-line security audit of every code file. Findings must be reevaluated as parallel branches move.

## 7. Integrator source corrections created during this review

- [#183](https://github.com/omarkhair70-droid/hiltech/pull/183) (child of Finance8B #175): fixes **only** the exact current shared PostgreSQL test expectation to 29 migration executions after V0033. This addresses the observed DevOps package failure for #175 while retaining the independent Phase8B domain source; no schema, salary, bank or company source mutation. Exact-head package CI [run #45081592913](https://github.com/omarkhair70-droid/hiltech/actions/runs/45081592913) **queued** at last inspection. It does **not** solve cross-branch SQL V0061/V0062 collision.
- [#184](https://github.com/omarkhair70-droid/hiltech/pull/184) (child of latest Phase7 S07 source #181): applies Warehouse-owned PoReceivingPolicyPort and guarded Documents public facade to **three** protected stock/service document readers; unlike older #176, retains newly-added S07 ServicePurchase Evidence lifecycle targets and JDBC storage source in place. Full backend/PostgreSQL/Android/Windows test workflow added, exact-head [package CI #45081803758](https://github.com/omarkhair70-droid/hiltech/actions/runs/45081803758) **queued**. No full PASS claimed, no supplier sends.
- [#176](https://github.com/omarkhair70-droid/hiltech/pull/176) earlier S06 source backport [package CI #45077990636](https://github.com/omarkhair70-droid/hiltech/actions/runs/45077990636) **passed**; source-owner review + full dedicated suite still required before parent merge.
- [#177](https://github.com/omarkhair70-droid/hiltech/pull/177) full disposable restore rehearsal [initial run #45078256791](https://github.com/omarkhair70-droid/hiltech/actions/runs/45078256791) **FAILED exact schema equality** after completing synthetically isolated dump/restore/data checks. Do not claim restore readiness. The script now emits a bounded synthetic-only schema diff on the next test run for evidence; updated [run #45081926939](https://github.com/omarkhair70-droid/hiltech/actions/runs/45081926939) **queued**.
- [#182](https://github.com/omarkhair70-droid/hiltech/pull/182) cross-lane audit workflow [run #45081510888](https://github.com/omarkhair70-droid/hiltech/actions/runs/45081510888) **queued** at this checkpoint. Independently observed source-file facts already establish collision V0061/V0062 and pending Sales/Service migrations; diagnostic CI cannot be marked PASS before executing.

All five changes are in isolated development/integration branches and have not enabled HILTECH business operations or impacted existing Coolify staging databases.

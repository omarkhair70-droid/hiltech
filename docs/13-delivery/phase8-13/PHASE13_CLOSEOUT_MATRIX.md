# Phase 13 — Executive Control / Mohamed Home: gate-based closeout

Checkpoint: 2026-11-23. Implementation lane: Draft PR #168. No merge, live enablement, or invented company finance/HR authority.

## Already implemented on the isolated Phase 13 branch

| Area | Source of truth | Current output | Status |
|---|---|---|---|
| Current company access | RoleTeamAuthorizationService, live organization membership and OpenFGA | Reject foreign/revoked org *before* other readers | CODE; unit + simulated source/FGA denial added; integrated role proof still required |
| Needs You | InboxService.workQueue with current action authority | Up to 20 currently actionable items, source cursor, version and as-of | CODE; actor-wide across authorized memberships, **NOT selected-org or company total** |
| Projects / work risk | ProjectsService.myProjects + ReviewProgressHealthService.health | Real project state, policy revision, health signals and source timestamps | CODE; bounded, always PARTIAL, CRITICAL-first presentation |
| Asset service exceptions | WarehouseReadService + AssetService.queue | Existing exception queue with availability, calibration and maintenance sources | CODE; bounded and partial when producer access fails |
| Provenance | Source IDs/versions and server timestamps | Verified paths to original modules, not executive commands | CODE; typed navigation allowlist |
| Native read experience | KMP API client and Compose screen / ExecutiveHomeRoute | Windows/Android common implementation, online reload, session/organization guard, stale data discard | CODE; **shell integration unverified / not mounted** |
| Read security | Controller and standard source permissions | Default-OFF flag and HTTP no-store, no outbound or write operation | CODE; test coverage added |
| Phase 13 dedicated CI | .github/workflows/phase13-executive-contract.yml | Architecture, server scope, native desktop tests, Android+Windows compilation | WORKFLOW PRESENT; exact current-head verdict must be checked |
| Finance / Payroll / Sales / Support / Security | Source-domain future approved contract | Pending domain marker; no invented totals, salaries, amounts, wages or bank actions | WAITING for producer contracts |

## Release gates — all are mandatory, not optional

1. **Exact-head CI PASS**, including native Android/Windows compilation, server unit and Modulith architecture.
2. **Real PostgreSQL + OpenFGA permission matrix**: owner only through company-approved authority; project manager, procurement/warehouse staff, revoked staff, foreign organization, missing FGA relation; no source data leakage.
3. **Company-approved executive visibility and field sensitivity policy**: current membership is a prerequisite, not a universal executive grant. Confirm who may see which project, asset, approval or eventual finance/payroll aggregate.
4. **DevOps integration PR**: mount ExecutiveHomeRoute on authenticated host shells, map allowed destinations to source-owned detail views, clear on logout/role/org change, no raw URLs or frozen user IDs.
5. **Real Windows / Android signed-in operator proof**: empty, partial, no authority, API fault, source unknown, cross-org switch, logout/revocation, returning via source detail.
6. **Data truth / operation proof**: priority state from sources, no stale health shown as current, live pagination constraints, bounded N+1 query latency, no-company-totals rhetoric.
7. **Approval to activate**: staged company acceptance, no-store and monitoring, DevOps controlled rollout/rollback, still OFF in all other environments.
8. **Source contracts for 13B**: Finance #164/#175, Payroll #166, Sales #170, Service #167 are independent Drafts and cannot be treated as released. No payroll person-level leaks. Phase12 Security requires real hardware inventory. Digest recipients and cadence need explicit approved privacy rules.

## Important known limitations

- Needs You is **actor-wide** across the user's currently authorized memberships. The canonical Inbox public work queue does not expose organizationId for each item. A company-filtered executive queue requires an owner-maintained Inbox API/pagination addition; do not filter using an untrusted client query or a guessed DB join.
- Projects are capped before some source access filtering. This page is **always PARTIAL** even when fewer than 30 records are visible.
- Producer timestamps are real source timestamps, but an HTTP fetch time is not proof that project/asset upstream information is fresh to any proposed SLA.
- No executive-level mutation, bank execution, payroll calculation, custom global ledger, financial approval, digest sending, or duplicate source records exist in Phase13A.

## Ownership boundary

Phase13 lane: source-bound projection, permission checks, KMP contracts/read route, tests, provenance.

DevOps integrator: merge sequencing, authentication shell wiring, exact Postgres/OpenFGA and device proof, feature activation and rollback. Preserve migration allocation for procurement/finance/payroll/service owners.

Design lane: real Compose visual improvements without losing risk priority, partial/unavailable states, privacy, source versions and trusted navigation.

Keep PR #168 Draft until all acceptance gates are demonstrated.

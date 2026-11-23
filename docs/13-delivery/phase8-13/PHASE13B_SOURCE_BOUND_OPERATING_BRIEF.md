# Phase 13B — on-demand operating brief, cross-domain source contract, closeout

**Implementation owner**: Phase 13 lane, stacked Draft PR #197 on top of verified Phase13A PR #168.
**Branch**: `phase13b/executive-brief-source-bound-20261123`.
**Safety**: no company-wide false totals, no permission widening, no backend replication, no scheduled digest and no write commands.

## Delivered first read-only Phase 13B slice

The brief is generated from the **same authenticated snapshot** returned by `GET /v1/executive/home?organizationId=...`. The existing server requires current organization membership and source authorization, protects against a revoked company between query and response, and sends `Cache-Control: no-store`. The shared native route checks echoed actor and organization before showing content, clears on refresh or auth-scope changes, and keeps no offline executive state.

`buildExecutiveOperationalBrief(home)` produces three *visible-window* lanes:

| Lane | Producer | Brief content | Read truth |
|---|---|---|---|
| Needs You | current actor's canonical Inbox WorkQueue | actionable decisions and source versions | Actor-wide across authorized memberships; **not selected-company total** |
| Project Risk | current permissioned Project list + Project Health | non-healthy or health-signal projects, source health state/reasons/version/time | Only visible projects, query bounded, **always partial** for present producer |
| Asset Service | current permitted warehouse service exception queues | calibration/maintenance/unavailability from actual source | Bounded, denied warehouse access becomes partial and never zero/company-clear |

- Each lane shows at most **three** records by default, expandable and navigable using only the existing allowlisted `ExecutiveDestination`.
- The brief computes **no global score or trend** and does not treat partial, unknown or unavailable as healthy. A critical project is prominently ordered using the **source's health state**, not a synthetic executive risk formula.
- If a producer returns `UNAVAILABLE`, even a malformed response including items must **not** show those items.
- All source detail routes reauthorize the active identity independently. For signals, the project destination is validated against its enclosing project, not the signal source UUID.
- `ExecutiveOperationalBriefSection` is a collapsible KMP Compose component inside `ExecutiveHomeSection`. It does not fetch again, create a new API, write a local database or send the content anywhere.
- All additions are **stacked** on Phase13A, so DevOps can merge Phase13A without this feature while 13B still receives tests.

## Domain-owner 13B integration rubric

To display any additional domain, the **owning domain** must first publish a stable, reviewed, permission-filtered read contract. Do not copy unfinished code into Phase13 or query raw domain tables.

For every new domain, require: exact authorization model (including organization, current actor and field-level sensitivity), source identity/version, as-of timestamp and quality/freshness semantics, bounded pagination (distinguish complete vs partial), explicit unknown/unavailable, original re-authorizing detail destination, redaction/aggregation policy and negative cross-tenant tests.

| Domain | Current draft/source dependency | Safe executive addition only AFTER owner approval | What MUST NOT be inferred |
|---|---|---|---|
| Phase8 Finance | #164 and #175 | genuine finance-owned obligation/exception/readiness projection with approval and aging semantics | Supplier invoice handoff candidate is not a booked payable, debt or executable payment |
| Phase9 Payroll | #166 | permitted **aggregate only** payroll-cycle readiness / reconciliation exceptions with approved audience | No per-person wages, payslip, bank details, individual advance or inferred paid status |
| Phase10 Sales | #170 | source-confirmed pipeline/award/tender exceptions tied to the proper org, with quote approval status | An open opportunity is not revenue, a quote is not a signed contract |
| Phase11 Service | #167 | source-owned open case, coverage and maintenance exception, with real SLA clock if contract defines one | A drafted SLA/visit/contract is not necessarily a breach or active warranty |
| Phase12 Security/Hardware | awaiting validated hardware inventory and canonical feed | permissioned actual device/site exception, time and evidence | Missing offline sensor data is not a security incident |

Digest delivery / scheduled mail / escalation is **not enabled**. Before implementing, approve recipients, source sensitivity, allowed fields, acknowledgement and cadence. Avoid external delivery through executive preview alone.

## Required release evidence

- Phase13B exact-head GitHub CI: server/Architecture + `shared:core:desktopTest --tests com.hiltech.shared.core.executive.*` + Android debug and Windows build.
- At least: bounded overflow, critical project priority and detail navigation, absent source, partial source, unknown health, spoofed destination, actor/org invalidation on native route.
- DevOps to mount the existing route in real authenticated shells and validate active, revoked and foreign actors with PostgreSQL/OpenFGA; merged app integration is separate from source PR compile.
- Feature `hiltech.executive.home-enabled` remains **false** until company-approved executive visibility policy, staged smoke, rollback and source acceptance.

## Ownership

**Phase13 lane**: pure read projection/brief, source-safe Compose and tests; fix regressions in these files.

**Module owners**: publish accurate approved source contracts, no other lane is permitted to invent their finance/payroll/legal/support semantics.

**DevOps**: merge sequencing, database migration ownership, native shell integration, real-user/role tests, feature flag, stage rollout/rollback.

**Design lane**: styling of existing Compose components without removing provenance, unknown/partial states, original-source navigation or required checks.

## 13B trust/coverage review — 23 November 2026

- `executiveReadCoverage(home)` exposes **only** what the authenticated, bounded source reply proves: source name/status, visible-window item count, explicit partial/cursor, source as-of when actually supplied, and the reason for unknown/unavailable.
- Unknown producer status and unexpected source identity are treated as unavailable, without displaying counts or timestamps from the mismatched response. `CURRENT` means the producer returned a current window, **not that every underlying record meets a freshness SLA**.
- Actor-wide Inbox scope is flagged even when the window is complete; Project Health always has a portfolio-completeness warning because the producer limits records before source access filtering. Unknown/unavailable project health and health records without observation time are flagged.
- P8/9/10/11/12 appear as **AWAITING_SOURCE_CONTRACT**, not zero, outage or implemented. Their actual draft operations are not financial/payment/HR executive read authority.
- The operational brief exposes **ما الذي لا نعرفه عن البيانات؟** on demand, without another API request or persistent cache. All details are reset with the current executive snapshot and subject to the same actor/org/session guard and feature flag.
- `ExecutiveReadCoverageTest` proves partial false with a cursor, unknown producer, mismatched source name, actor-wide scope, source unavailability, unknown health and missing health-as-of. This is a provenance audit, **not** a company-wide count or a real-time event feed.

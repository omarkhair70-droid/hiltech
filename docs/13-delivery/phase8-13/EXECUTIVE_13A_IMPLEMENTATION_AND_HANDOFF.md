# Phase 13A — Mohamed Home: source-bound first vertical

Status: **IMPLEMENTED ON ISOLATED BRANCH / NOT MERGED / NOT PRODUCTION-VERIFIED**  
Reference: PR #161, \`06_PHASE13_EXECUTIVE.md\`; canonical \`docs/13-delivery/IMPLEMENTATION_ORDER.md\`.

## What actually exists in this branch

- \`GET /v1/executive/home?organizationId=<UUID>\` is **OFF by default** (hiltech.executive.home-enabled=false) and requires the authenticated actor, current source organization membership AND matching OpenFGA membership before any source reads.
- **Needs You** delegates to \`InboxService.workQueue\` with its current assignment/decision authorization. This section is intentionally **ACTOR-wide across authorized memberships**, not a fabricated org-wide approval total.
- **Projects** delegates to \`ProjectsService.myProjects\` (source authorization), then \`ReviewProgressHealthService.health\` (independent health authorization). It displays project lifecycle, exact source version and timestamp, health state, policy revision, current reason signals and their provenance. \`UNKNOWN\` and \`UNAVAILABLE\` must never be rendered as healthy.
- **Asset exceptions** enumerates only visible warehouses through \`WarehouseReadService\`, then invokes \`AssetService.queue\` which rechecks warehouse access. It shows exact availability/calibration/maintenance producer results, reason codes, source versions and evaluation times. No fictional inventory totals.
- A session-scoped online \`ExecutiveHomeRoute\` mounts the shared Compose \`ExecutiveHomeSection\` in Windows and Android with the shared authenticated client \`ExecutiveApiClient\`. The shell supplies refresh, current organization/identity and typed navigation into the source-owning module; this branch deliberately does not edit concurrently owned Desktop Main, Android MainActivity, shared authorization, common API or Flyway.
- No executive write path, ledger, migration, approval configuration or magic Mohamed user/role is created.

## Read-model and UX semantics

- \`asOf\` is the **executive composition time**, not the change time of every producer.
- \`sourceAsOf\` is from the source when available; otherwise each project or asset carries its own precise source timestamps. Do not label a successful GET as proof of source event freshness.
- \`CURRENT\` means the queried window responded, **not** guaranteed company completeness. \`PARTIAL\` means bounded or degraded; \`UNAVAILABLE\` means a producer failed/denied the read, **not zero**.
- Project queries are windowed (30 visible cards from a bounded 31-item source read); asset exceptions use up to eight authorized warehouses and up to 30 cards (each producer query itself caps at 100); Needs You uses the source's 20-row signed cursor. No aggregate totals from these windows.
- Every card has a real read-only source path and source version. The UI callback opens a **source-owned** detail; all commands, reauthorization, revocation, approve/reject and auditing remain in the original module.
- No offline executive persistence or cross-user/organization cache. The shell must clear view state on sign out, org switch or permission revocation and refetch online.
- \`FINANCE\`, \`PAYROLL\`, \`SALES\`, \`HANDOVER_SUPPORT_MAINTENANCE\` and \`SECURITY_HARDWARE\` remain explicitly **AWAITING_AUTHORIZED_SOURCE_CONTRACT**. Do not connect partial schema guesses or financial/employee detail fields.

## Integration contract for DevOps + Design

**DevOps integration owner** must wire the feature into both native shells on an integration branch, map only allowlisted \`ExecutiveDestination\` to existing source-owned authenticated detail routes, and revalidate the session/org before applying results. Do not open arbitrary server-supplied URLs or bypass source authorization. Allocate no database migration: 13A creates none. Rebase after PR #161/P7 merge contract reconciliation; test both roles and the combined application before merge.

**Design owner** should refine the source-first editorial hierarchy (Needs You, source health, exceptions, pending sources), preserving provenance, unknown/partial/unavailable states and explicit no-totals. Never add synthetic charts or fake KPIs. The native Compose screen here is a working baseline rather than a final visual sign-off.

**Verification still required**:
1. Compile/server unit + common test on exact head.
2. Existing Project / Inbox / Warehouse authorization contract suites.
3. Postgres + OpenFGA role matrix: actual configured Mohamed identity vs project manager, warehouse operator, a foreign-org user, revoked user, and missing authority; assert no cross-tenant fields.
4. Audit the fact that all executive actions navigate to authoritative commands; rejection, stale version and re-auth must be handled by sources.
5. Native Windows/Android signed-in refresh + empty/partial/unknown/unavailable proof and device evidence.
6. Profile bounded-window queries and review rate limiting, observability and rollout; keep feature off until accepted.

**No slice is closed or operable until these gates pass.**

## Security and native route hardening — 23 November 2026

- Access first: `SourceExecutiveOrganizationAccess` reuses `RoleTeamAuthorizationService.isOrganizationMember`, which checks the current live source membership and the matching OpenFGA relationship. A foreign or revoked organization fails as not found **before** the Inbox or any other producer runs. All sources independently enforce finer ACL.
- The current actor's Work Queue may include items from other *currently authorized memberships*: it is **explicitly actor-wide**, not a selected-organization approval count. The canonical Inbox public response has no organization ID, so this lane will not guess one. A selected-org queue later requires an Inbox-owner pagination/filter contract.
- Native `ExecutiveHomeRoute` manages online refresh, cancellation on actor/org change, no cross-session persistence, no previous snapshot on failure, and a safe error without raw server diagnostics.
- `ExecutiveApiClient.home(... expectedIdentityId)` validates the actor and company echoed by the API and rejects a stale response. `ExecutiveHomeSection` also validates both inputs. No stale state during active refresh.
- `ExecutiveDestination` is an allowlisted typed navigator: approvals, projects, project health, asset availability. A route may only be opened through its source module, which repeats current authorization. Never open raw source paths from JSON or hardcode a Mohamed identity.
- Project presentation is source-health-first (CRITICAL, ON_HOLD, ATTENTION, UNKNOWN/UNAVAILABLE, HEALTHY); this is UI ordering, **not** a computed executive risk score.
- Executive assets with denied source queues must be PARTIAL, never CURRENT.
- Verification: `.github/workflows/phase13-executive-contract.yml` and local Gradle source tests. These are engineering checks only, not signed-in company device UAT, real PostgreSQL/OpenFGA matrix or release approval.
- Native shells remain **owned by DevOps integration**. Pass current identity, organization, installation, and `onOpenSource: (ExecutiveDestination) -> Unit` to `ExecutiveHomeRoute`. Revoke/organization switch must unmount or re-key the route. Keep the feature OFF until acceptance.

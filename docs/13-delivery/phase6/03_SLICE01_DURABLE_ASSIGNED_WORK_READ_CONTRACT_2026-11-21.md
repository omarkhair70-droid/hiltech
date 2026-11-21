# Phase 6 / Slice 01 — Durable Assigned Work Read-Only Cache
Date: 2026-11-21
Status: **SOURCE-BOUND SAFE BUILD CONTRACT / OPERATOR ACCEPTANCE OPEN**
Base `main`: `9cae5702d7e84145a1cff4d698b28caf66a420a1`; Phase-6 planning PR #112 merged.

## Bounded outcome
Existing Phase-4 authenticated `/v1/field/today` and `/v1/work-orders/{id}/job-bundle` keep server current-source truth. An already authenticated Android user can read previously fetched assigned work after a network outage. **No offline StartWork, Block, Resume, material consumption, Evidence capture or SubmitCompletion is enabled by Slice 01.**

## Persistence and security
- Reuse the existing app-private `HiltechLocalDatabase` Room3 store; add two keyed read caches (Today keyed by authenticated identity; JobBundle keyed by identity + WorkOrder).
- Room schema forward migration **1→2** must be generated/verified; do not reset or destroy pending commands/evidence. Use Room's automatic migration for adding only new tables. The schema export and upgrade test are required before merge.
- Persist only the existing server-authorized, field-safe DTO; no raw binary, secrets, Finance data, or arbitrary new Project/Employee query. WorkOrder/assignment/policy/instruction versions and server asOf are preserved.
- A cache is never read outside the signed-in identity; cache records from another account never become visible.
- Server 401/403/404/revocation must not fall back. Fallback is **only** for an actual network-unavailable error and an already authenticated session; a fresh online Today result controls which WorkOrders can be selected.
- Cached Today items forcibly clear `currentlyActionable`; cached bundles forcibly clear every execution capability, regardless of the previous online result.
- On stale/unknown assignment while disconnected, only read a **clearly stale** snapshot, not execution authority. Old local notes do not prove a current assignment.
- Document links are metadata only; do not describe them as downloaded offline drawings. Local storage security/retention must receive device policy and human review before company rollout.

## Verification and exit
1. Room v1→v2 non-destructive migration, with pending command retention.
2. True process close/reopen durable cache, strict identity isolation.
3. Network-only fallback vs 401/403/404 (no offline authority bypass).
4. Exact WorkOrder, assignment, binding and instruction revisions retained, but cached actionability and execution capabilities denied.
5. Android Arabic-first Today + detail identify cached read/fetch source and no command UI. CI and synthetic mobile proof are not actual operator acceptance.
6. Post-merge Bootstrap and real field operator/device review are separate gates.

`PHASE6_S01_READ_ONLY_ENGINEERING_SCOPE = BUILD_READY`
`PHASE6_S01_LIVE_OPERATION = NOT_AUTHORIZED`

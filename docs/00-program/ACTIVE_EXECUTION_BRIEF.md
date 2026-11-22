# HILTECH — Live Execution Brief / Fresh-Session Entry

As-of: **2026-11-22** (snapshot; re-check GitHub before action)
Status: **CONTROL-PLANE RECONCILIATION PR / NOT MERGED**

> Important: this brief supersedes stale *execution-position* text in AGENTS.md and the
> 2026-09-21 CURRENT_PROGRAM_STATUS.md / IMPLEMENTATION_ORDER.md. Historical plans and
> contracts remain relevant, but are **not current PR/CI status**. Never assume this
> snapshot is live after its date. Run `scripts/devops/start-session.ps1` and inspect
> GitHub main, branches, PRs, checks and source-specific closure documents.

## Owner-approved target
One **internal** HILTECH OS: Android + Windows native Compose clients, shared core,
Spring/PostgreSQL/OpenFGA authoritative backend. Finish through **Phase 13**.
Phase 12 physical CCTV/NVR/access-control integration deferred pending real vendors,
hardware facts and express authorization. External supplier, client and NOC phases
14/15/16 are **out of scope**. Master: #103. Delivery control tower: #130.

## Evidence-based baseline

| Area | Engineering in GitHub | Human/operational boundary |
| --- | --- | --- |
| Phase 0–4 | Historical merged verified baseline | Real-user operation separate from CI |
| Phase 5, S01–S09 | Merged; synthetic engineering integration PASS, source of truth Warehouse/Stock/Asset | Authentic UTP/FIBER/ACTIVE/Accessories + assets + named warehouse operator + staged cutover **not verified** |
| Phase 6 S01–S03 | Merged: durable offline assigned bundle, Start/Block/Resume queue, private local Evidence → READY | Real technician/supervisor physical device acceptance OPEN: #116 |
| Phase 6 S04A | Merged #120: personal issued-stock read, default OFF | Human custody-read policy and real device acceptance OPEN |
| Phase 6 S04B base | Merged #121: immutable pending material-usage report, default OFF | No claim of automatic consumption/operational acceptance |
| Phase 6 S04 continued | **OPEN, stacked** #122 review-read → #123 canonical Consume reconcile → #124 scoped AssetTag lookup → #125 source gate → #126 configurable tests; #127 document revisions **and #129 offline engineering test replay are sibling PRs based on #126** | Feature flags OFF, exact-head reviews/CI, real OTDR/Fluke sample and publication/reviewer authority not yet accepted; #117 |
| Phase 6 S05–S06 | Not implemented/closed on this inspected main | Completion/rework, recovery, end-to-end real native testing |
| Phases 7–11, 13 | Scoped/planned; no false production completion claim | Contracts and approvals by domain; implement complete vertical slices |
| Phase 12 | Internal register/incident subset only if grounded | Hardware not required for internal OS completion |
| Product quality | Existing interactive 37-frame FIRST-SLICE proof; current Compose shells remain substantially utilitarian | Full craft, native screenshots, role-aware UX, Arabic, accessibility, performance and user acceptance: #128 |

**Inspected main SHA**: `c30fb707186cc0bbb38ba95b924737bd1bc56997`
(#121 merge; 2026-11-21). **This SHA is not a release identifier.**

**PR #127**: GitHub Actions controlled-document dedicated workflow **PASS** on
`a0a38ee83176a2af075922b8a98eed1fcdf6c359`
(run 44962175722), after earlier V0037 migration failures; still **draft/unmerged**
at inspection. Verify exact head and all inherited gates before review/merge.

## Workstreams and sequencing

**A — Domain delivery**: do not shortcut #122→#126 stacked dependency/CI/review; #127 and #129 branch separately from #126 and require a reviewed integration path before closure.
Then Phase 6 S05 completion, supervisor review/rework, PM authoritative status.
Then S06 offline/recovery/security tests and scoped engineering gap review.
Phase 7 procurement, Phase 8 finance, Phase 9 payroll, Phase 10 commercial,
Phase 11 handover/support, Phase 12 limited registry, Phase 13 executive Company Brain.
Reuse Phases 0–6 instead of recreating auth/approval/work/movement/audit modules.

**B — Product experience**: functional UX/state/role safety is part of every slice.
Research/compare actual Android and Windows output, not browser low-fi alone.
Research list/detail and adaptive behavior (Material 3 Adaptive), Fluent 2 spatial
principles, Carbon dense tables/accessibility, field-scanning ergonomics. Translate
into original HILTECH *Industrial. Precise. Alive.* design; do not clone references.
After core Phase 13 functionality, run whole-product visual/animation/copy polish
plus regression. Track #128 and preserve testable prototypes.

**C — DevOps/reliability**: exact-head CI, real PostgreSQL/OpenFGA, Room migrations,
Android/Windows artifacts with SHA and sanitized screenshot evidence, branch protection,
least-privilege GitHub Actions, signed apps, staging/cutover, backup/restore,
migration rollback, real operator acceptance, telemetry/runbooks. CI green
does NOT mean production released. Audit real CI files and deployed environments
rather than assuming infrastructure is live.

## Every fresh agent/chat MUST
1. Read `AGENTS.md`, `docs/00-program/AGENT_EXECUTION_PROTOCOL.md`, this live
   brief, `docs/13-delivery/DEFINITION_OF_COMPLETE.md`, current phase contract.
2. Run the read-only session report: `powershell -NoProfile -File scripts/devops/start-session.ps1`
   (works in a clone; PowerShell + Git required; `gh` optional). Check **fresh** main,
   open PR heads/checks/stack bases, local dirty state and required toolchain.
3. Choose **one** lane + bounded slice. Verify owner-approved scope, exact
   branch/contract/permissions; separate engineering vs operational status.
4. Implement only on a scoped branch. Run tests; record exact SHA and evidence;
   open a reviewed PR. No force push, unsafe merge, hidden production changes.
5. At exit, document HEAD, changed behavior/files, commands/results/runs, blocked
   reality, next permitted action, and whether anything was deployed.
6. Never store secret values, client company files, private payroll/financial data,
   unredacted evidence or credentials in GitHub issues, screenshots or logs.
7. A chat has **no inherent machine privileges**. Remote terminal requires a
   separately connected, authorized Desktop Commander device and scoped workspace.
   CI secrets, deploy, production data, destructive actions and financial commands
   remain human-gated; no unapproved autonomous production operation.

## Session handoff format
```text
LANE / PHASE / SLICE:
MAIN (SHA / checked UTC):
BRANCH / PR (exact head/base):
IMPLEMENTED:
VERIFIED (commands, CI run URLs, native screenshots):
NOT VERIFIED (device, operators, real data, release):
BLOCKER / DECISION OWNER:
NEXT SAFE ACTION (exact bounded task):
```

## Key links
- Master program: https://github.com/omarkhair70-droid/hiltech/issues/103
- Phase 6 S04: https://github.com/omarkhair70-droid/hiltech/issues/117
- Real device Evidence acceptance: https://github.com/omarkhair70-droid/hiltech/issues/116
- Design/experience: https://github.com/omarkhair70-droid/hiltech/issues/128
- Delivery control tower: https://github.com/omarkhair70-droid/hiltech/issues/130

## Suggested next PR after this control-plane PR
Inspect current stacked bases and exact checks for #122–#127, and record the first
safe merge candidate. In parallel, capture **actual native Compose** Android and
Windows screenshots and file a prioritised usability/visual discrepancy list,
not a free-standing UI mockup.

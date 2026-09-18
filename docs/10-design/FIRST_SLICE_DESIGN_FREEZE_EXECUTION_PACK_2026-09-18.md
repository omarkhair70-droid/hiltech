# HILTECH First-Slice Design Freeze Execution Pack

Date: 2026-09-18
Status: **INTERACTIVE RENDERED PROOF ACTIVE / FIGMA OPTIONAL**

Figma file:
`HILTECH OS — Low Fidelity Product Prototypes`

File key:
`1l92hyJyOzwaudkdlUBbBt`

Current MCP account:
- plan: Starter
- seat: View
- role: admin
- MCP quota: exhausted after metadata access
- write/metadata expansion cannot currently continue

The first-slice visual gate is now **tool-neutral**.

Primary active proof engine:
`prototypes/first-slice-design/`

Automated rendered proof:
- browser-executed with Playwright,
- 36 required screenshots,
- English + Arabic RTL,
- phone + tablet + desktop,
- conflict/error/authoritative/local states.

Figma remains useful for later visual-system craft and may continue when quota returns, but Figma itself is no longer a hard pre-code dependency.

This file remains the acceptance/spec queue regardless of rendering tool.

---

# 1. Existing frames to preserve

Mobile:
- M01 — Mohamed Home — `1:3`
- M02 — Owner Approval — `1:5`
- M03 — Technician Today — `1:7`
- M04 — Warehouse Scan — `1:9`
- M05 — Client My HILTECH — `1:118`
- M06 — Technician Job Detail — frame shell `2:2`
- M07 — Warehouse Checkout — frame shell `2:4`

Previously reported Desktop:
- D01 — Ahmed Payroll — `1:144`
- D02 — Project Command Center — `1:146`

Do not recreate an existing frame under a new ID if it still exists.
Inspect current canvas before mutation.

---

# 2. First-slice visual Freeze priority

Only first-slice blocking visuals are Priority A.

## Priority A1 — M06 Technician Job Detail

Required variants:

### M06-A — READY / ONLINE
Must show:
- WorkOrder code.
- WorkType.
- Project / Site / Area.
- sync state.
- readiness.
- instruction revision.
- readiness requirements.
- tools/material requirements.
- EvidencePolicy-derived checklist.
- map/navigation when available.
- START JOB.

### M06-B — IN_PROGRESS / OFFLINE
Must show:
- clear offline state.
- local queued changes count.
- local evidence status.
- current instruction/policy revision.
- Block / Submit actions as allowed.
- no false authoritative success.

### M06-C — BLOCKED
Must show:
- blocker.
- Waiting on.
- linked object/responsible party.
- safe resolve/retry path.

### M06-D — OFFLINE CONFLICT
Scenario:
PM reassigned/cancelled or instruction/version changed while technician was offline.

Must show:
- authoritative current state.
- local captured work/evidence preserved.
- CONFLICT.
- dependent commands blocked.
- allowed recovery actions.
- no silent overwrite/discard.

### M06-E — SUBMITTED
- exact submitted version.
- Awaiting Review.
- evidence summary.
- no Start/Submit duplicate action.

### M06-F — REWORK
- reviewer reason.
- exact revision.
- required correction.
- preserved prior evidence/history.

---

# 3. M07 Warehouse Checkout

## M07-A — AVAILABLE
- Asset Passport.
- condition.
- calibration.
- current location.
- reservation context.
- recipient.
- Project/Site/Work.
- expected return.
- CHECK OUT.

## M07-B — RESERVED SAME WORK
- reservation visible.
- context aligned.
- checkout allowed.

## M07-C — CALIBRATION BLOCKED
- normal checkout unavailable.
- reason clear.
- no generic override in first slice.

## M07-D — ALREADY CHECKED OUT
- current custodian/context if permitted.
- no checkout action.

## M07-E — STALE CACHED AVAILABLE → SERVER COLLISION
- CHECKOUT NOT APPLIED.
- authoritative current state/version.
- local cached state identified as stale.
- next allowed action.

## M07-F — SUCCESS
- movement ref.
- new custodian.
- context.
- timestamp.
- expected return.
- Next Scan.

---

# 4. D06 — Configuration Center

New Desktop screen required for first-slice configurable operating model.

Primary layout:
- left: configuration families.
- center: selected family/revision editor/list.
- right: validation / dependency / history inspector.

## D06-A — Configuration Center Home
Families:
- Work Types
- Assignment Policies
- Readiness Policies
- Evidence Policies
- Review Policies
- Tracking Policies
- Approval Policies
- Teams / Roles / Delegations
- Warehouses / Site Storage
- Asset / Stock Master Data
- Code Policies
- Project Health Policies
- Templates / Checklists

Each row/card:
- code/name.
- lifecycle.
- revision.
- effective date.
- last activation.
- validation warning.
- dependency/usage count.

## D06-B — WorkType Draft Editor
Show:
- identity/code/name.
- AssignmentPolicy.
- ReadinessPolicy.
- EvidencePolicy.
- ReviewPolicy.
- optional TrackingPolicy.
- default progress weight.
- templates.
- effective dates.

Actions:
- Save Draft
- Validate
- Compare Active
- Activate

## D06-C — Invalid Draft
- field errors.
- dependency errors.
- activation blocked.

## D06-D — Activation Review
- diff.
- usage impact.
- future WorkOrders affected.
- historical WorkOrders unchanged.
- re-auth/approval state.

## D06-E — Version Conflict
- current revision.
- stale draft base version.
- rebase/clone current path.
- no silent overwrite.

## D06-F — Superseded History
- revision timeline.
- effective window.
- actor/reason.
- current active revision.

---

# 5. D07 — Supervisor / Engineer Review

Primary:
review exact submitted WorkOrder version.

Must show:
- WorkOrder / Project / Site.
- WorkType.
- submitted version.
- instruction revision.
- evidence requirements/results.
- measurements/tests.
- drawings/revisions.
- assignment.
- prior rework.
- review step/policy.
- ACCEPT / REWORK / REJECT only where allowed.

## D07-A — Clean submitted review
## D07-B — Missing acceptance evidence
## D07-C — Rework decision
## D07-D — Stale version
Must force refresh/re-review.

---

# 6. D02 Project Command Center — Contract Update

Existing frame should be revised if needed to reflect current contracts.

Must include:
- Project state.
- ProjectHealth state + contributing signals.
- accepted-weight progress.
- baseline version.
- Waiting On.
- blocked/rework/submitted work.
- resource readiness.
- Site/ProjectSite context.
- activity/audit.
- selected object inspector.

No editable manual project-health percentage.
No arbitrary manual progress override.

---

# 7. Conflict visual set

Must visibly prototype these as first-class state patterns:

## C01 — Offline Work conflict
M06-D.

## C02 — Asset checkout collision
M07-E.

## C03 — Configuration version conflict
D06-E.

## C04 — Review stale version
D07-D.

Shared conflict anatomy:
- what action failed.
- authoritative truth.
- local work/data safety.
- current version.
- allowed recovery actions.
- correlation/support path where useful.

Do not reduce them to one generic red error banner.

---

# 8. Arabic RTL required renders

Priority A:

## R02 — Technician Job Arabic RTL
Include:
- Arabic labels.
- `WO-42`.
- `Fluke-03`.
- drawing revision.
- English/Latin code.
- number/time.
- offline/conflict copy.

## R04 — Project Command Center Arabic RTL
Test:
- project nav.
- workspace.
- inspector.
- Waiting On.
- health signals.
- mixed codes.

## R06 — Warehouse Checkout Arabic RTL
Test:
- Asset code/serial.
- recipient.
- Project/Site.
- calibration.
- primary action order.

## R07 — Configuration Center Arabic RTL
Test:
- policy codes.
- revision.
- diff/validation.
- mixed English configuration identifiers.
- inspector placement.

Rules:
- logical reading/task order over mechanical mirroring.
- codes/IPs/serials/versions remain legible LTR inside RTL context.
- dense tables preserve numeric scanning.

---

# 9. Tablet adaptive proof

## T01 — Technician tablet
Landscape candidate:
- work list/context left.
- job detail right.
- local sync state always visible.

Portrait:
- same hierarchy as phone with more spacious grouped sections.

## T02 — Warehouse tablet
- rapid scan/list left.
- Asset Passport/Checkout inspector right.
- Next Scan efficient.

Do not create a separate tablet product.
Same product/state/read models, adaptive presentation.

---

# 10. Navigation comparison

## Mobile candidate

Primary:
- Home
- Work
- Explore
- Inbox
- Me

Scan:
- contextual/global floating/action entry where role supports it.
- not necessarily permanent bottom tab for all roles.

## Desktop candidate

- global product rail/nav.
- context/workspace navigation.
- global Search/Command.
- Work Queue.
- inspector.

Configuration Center:
- permissioned product area, not separate admin app.

Freeze decision must compare:
- Technician.
- Warehouse.
- PM.
- Finance.
- Admin/configuration.
- external Client.

---

# 11. Low-fi acceptance criteria

Design Freeze proof does NOT require:
- final colors.
- final brand identity.
- final radius.
- final icon art.
- final typography visual polish.
- final motion implementation.

It DOES require:
- task hierarchy understandable.
- state differences visible.
- authoritative vs local/queued visible.
- permissions/actions plausible.
- conflict recovery understandable.
- RTL structurally sound.
- phone/tablet/desktop adaptation plausible.
- one-product navigation coherent.
- Configuration Center can actually control operating model.

---

# 12. Rendered proof execution order

Interactive prototype implementation now covers:
1. M06 Technician Job variants.
2. M07 Warehouse Checkout variants.
3. D06 Configuration Center variants.
4. D07 Supervisor Review variants.
5. D02 Project Command Center states.
6. all four required conflict patterns.
7. Arabic RTL representative renders.
8. Technician/Warehouse tablet proof.
9. navigation comparison.

Automated next steps:
10. render 36 screenshots in Chromium.
11. inspect the actual images.
12. correct visual/interaction issues.
13. rerun proof until accepted.
14. update first-slice `07_UI_FLOW_CONTRACTS.md`.
15. mark Design blocker closed only after actual rendered review.

---

# 13. Current tooling blocker

Figma MCP check on 2026-09-18:
- top-level metadata call succeeded.
- next metadata call returned Starter-plan MCP rate-limit error.
- `whoami`:
  - team: Omar Khair's team
  - tier: Starter
  - seat: View
  - role: admin

Therefore:
- do not spam Figma MCP calls.
- product/design ambiguity is already removed by this pack.
- Figma quota is no longer the proof blocker because the interactive prototype provides the rendering path.
- actual Design Freeze still requires review of the rendered proof artifact.

Production UI still must not begin before that rendered review passes.

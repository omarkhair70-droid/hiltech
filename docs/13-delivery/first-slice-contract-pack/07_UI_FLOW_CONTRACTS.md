# 07 — First-Slice UI Flow Contracts

Status: **FIRST-SLICE UI FLOW CONTRACT v0.2 / RENDERED VALIDATION PASS**
Date: 2026-09-18

## Rule

UI contracts freeze tasks/state/action behavior before final visual polish.

Every surface defines:
- user/job,
- authoritative vs local state,
- allowed actions,
- permission projection,
- loading/empty/offline/error/conflict,
- audit/history access,
- adaptive/RTL behavior.

Backend policy remains authority.

---

# 1. Configuration Center — Desktop Primary

## Primary question

**How does an authorized HILTECH administrator change operating policy safely without code/database edits?**

## Entry

Permissioned global area:
Configuration / System / Operating Model.

Navigation should expose only authorized config families.

## Configuration Center home

Sections:
- Work Types
- Readiness Policies
- Evidence Policies
- Review / Acceptance Policies
- Approval Policies
- People / Teams / Roles / Delegations
- Warehouses / Project / Site Storage
- Asset / Stock Master Data
- Field Tracking Policies
- Notifications / Escalations
- Templates / Checklists
- Integration settings where applicable

Each card/list item shows:
- code/name.
- lifecycle: DRAFT / ACTIVE / SUPERSEDED / RETIRED.
- revision.
- effective dates.
- last changed/activated.
- dependencies/usage count.
- warning/validation state.

## Draft editor pattern

Desktop multi-pane:
- left/list or family context.
- main typed editor.
- right inspector for validation/dependencies/history.

No raw JSON/script editor as the normal product UX.

Actions:
- Save Draft
- Validate
- Compare with Active
- Activate
- Supersede
- Retire
- Clone Revision
- View History

Activation screen must show:
- what changes.
- dependent WorkTypes/policies.
- validation result.
- effective time.
- approval/re-auth requirement.
- whether existing WorkOrders remain bound to old revision.

## Configuration safety states

Must prototype:
1. clean draft.
2. invalid draft with field/dependency errors.
3. valid draft ready to activate.
4. activation requires re-auth/approval.
5. revision activated.
6. superseded historical revision.
7. unauthorized user read-only/no-access.
8. dependency prevents retirement.
9. future-effective activation.
10. concurrent edit/version conflict.

---

# 2. Work Type editor

Fields/surfaces:
- identity/code/name.
- AssignmentPolicy.
- ReadinessPolicy.
- EvidencePolicy.
- ReviewPolicy.
- optional TrackingPolicy.
- templates/checklists.
- material/asset requirement templates.
- effective date/version.

Must allow creating a new WorkType without deployment.

Impact preview:
- future work affected.
- existing historical work not rewritten.

---

# 3. Evidence Policy editor

Need typed requirement rows:
- evidence type.
- stage.
- count.
- capture source.
- allowed content.
- offline allowed.
- classification/retention.
- client visibility.

Preview:
“What technician will see before Submit/Accept.”

No global “always require photo” switch hidden outside policy.

---

# 4. Review / Approval policy editor

Show:
- selector type: relationship/role/team/user/client relationship.
- sequence/parallel/quorum.
- re-auth/reason obligations.
- escalation.
- exact-version binding.

Specific user allowed as config value, never code assumption.

---

# 5. Storage / master-data configuration

Support:
- Main Warehouse.
- future Warehouse.
- Project Storage.
- Site Storage.
- nested operational locations where useful.
- AssetType.
- Stock category/unit.
- calibration requirement.
- restricted/high-value flag.

Adding a new project/site store must not require code deployment.

---

# 6. Field Tracking policy editor

Show explicitly:
- enabled.
- mode.
- trigger.
- stop condition.
- sampling/accuracy.
- offline buffering.
- viewers.
- retention.
- user notice requirement.

Avoid ambiguous permanent employee tracking.

---

# 7. PM Desktop — Project Command Center

Primary task:
see project truth, exceptions and next actions.

Contract:
- selected Project/Site context.
- work readiness/Waiting On.
- assignment.
- state/version.
- configured WorkType label.
- policy-derived readiness/evidence signals.
- asset/material readiness.
- audit/activity.
- no hidden finance fields without permission.

States:
- loading.
- no work.
- ready.
- blocked.
- submitted waiting review.
- rework.
- stale/conflict.
- partial service failure.

---

# 8. Warehouse — Scan / Checkout

Primary task:
identify physical asset and perform safe custody action.

Contract:
- scan/manual lookup.
- Asset Passport.
- current authoritative/cached state indicator.
- condition/calibration.
- reservation/project/site/work.
- recipient user/crew/team.
- checkout confirmation consequence.
- success + next scan.
- collision/current custodian.
- return/condition.
- project/site storage transfer when configured.

Final Checkout is OA by default.

Never:
- silent override.
- duplicate custody.
- show acquisition cost without permission.

Required visual states:
AVAILABLE / RESERVED / CHECKED_OUT / MAINTENANCE / CALIBRATION_BLOCKED / STALE / CONFLICT.

---

# 9. Technician Android — Today / Job

Primary task:
understand and execute assigned work with poor/no connectivity.

Today:
- jobs ordered by operational need/time.
- sync freshness.
- Project/Site.
- WorkType.
- readiness.
- key tool/material blockers.
- map/navigation where configured.

Job:
- state/version.
- instruction revision.
- readiness requirements.
- tools/materials.
- drawings/docs.
- evidence checklist derived from bound EvidencePolicy.
- tracking status when enabled.
- Start / Block / Resume / Submit.
- queued/sync state.

Offline:
- clearly local vs server-confirmed.
- preserve local evidence.
- no false completion.

Required states:
READY online.
IN_PROGRESS offline.
BLOCKED.
PENDING_SYNC.
SUBMITTED.
REWORK.
CONFLICT after reassignment/cancel.
PERMISSION_REVOKED/needs support.
STALE document/bundle.

---

# 10. Supervisor / Engineer — Review

Contract:
- submitted exact version.
- WorkType.
- ReviewPolicy step/position.
- evidence/test set.
- instruction/drawing revision used.
- accept / rework / reject only as allowed.
- reason requirements.
- stale version detection.
- history/audit.

Never approve an unseen newer version.

---

# 11. Conflict UI contract

Every conflict surface answers:
- what local action could not apply.
- why.
- current authoritative truth.
- whether local work/evidence is safe.
- permitted next actions.

First-slice required:

## stale work after PM change
- preserve local evidence.
- show new assignment/state safely.
- dependent commands blocked.

## asset checkout collision
- checkout not applied.
- current custodian/context if permitted.
- no override unless explicit privileged process exists.

## policy/config conflict
- draft based on stale revision.
- show current revision/diff.
- save as new draft/rebase path.

## review stale version
- force refresh/re-review.

No generic “Sync error” for known conflict classes.

---

# 12. RTL / adaptive contract

Required before visual freeze:
- Arabic RTL technician Job.
- Arabic RTL Project Command Center.
- Arabic RTL Warehouse Checkout.
- Configuration Center mixed Arabic/English codes.
- phone.
- field tablet.
- desktop.
- mixed serials/codes/IPs/amounts.

Do not mechanically mirror pane order; preserve task/reading logic.

---

# 13. Navigation contract

HILTECH remains one product.

Mobile conceptual areas:
- Home.
- Work.
- Explore/Search.
- Inbox.
- Me.
- contextual/global Scan where appropriate.

Desktop:
- global product nav.
- context/workspace nav.
- global Search/Command.
- Work Queue.
- inspector/detail.

Configuration Center is a permissioned product area, not a separate admin application.

---

# 14. Rendered design validation

First-slice rendered proof is complete.

Prototype:
`prototypes/first-slice-design/`

Final tested prototype commit:
`c910901333f137e14edf5bb7d115f54cd287ec68`

GitHub Actions:
- workflow: Design Prototype Render
- run: **35396169606**
- result: **PASS**
- captures: **37 / 37**

Markers:
- `HILTECH_DESIGN_RENDER_PASS captures=37`
- `HILTECH_DESIGN_PROOF_COUNT_PASS captures=37`

Artifact digest:
`sha256:6988eb2f01613a0e89c72a74f90e5304ddc349b1d8bc9e49819ec5f0056ac1b3`

Validated:
- Technician READY / OFFLINE / BLOCKED / SUBMITTED / CONFLICT / REWORK.
- Warehouse AVAILABLE / RESERVED / CALIBRATION BLOCKED / ALREADY CHECKED OUT / COLLISION / SUCCESS.
- Configuration ACTIVE / DRAFT / INVALID / ACTIVATION / VERSION CONFLICT / HISTORY.
- Supervisor Review CLEAN / MISSING EVIDENCE / REWORK / STALE VERSION.
- Project HEALTHY / ATTENTION / CRITICAL / ON HOLD.
- Arabic RTL representative states.
- phone / tablet / desktop.
- one-product navigation comparison.

Visual review confirmed:
- task hierarchy understandable.
- local vs authoritative state visible.
- no false authoritative success.
- conflict recovery visible.
- Arabic-first human labels with stable technical IDs/codes.
- adaptive layouts coherent.
- Configuration Center expresses the operating model.
- Project progress and health remain distinct.
- HILTECH remains one product across roles.

Figma remains optional for later high-fidelity visual craft.

Current:
**FIRST-SLICE UI FLOW / LOW-FI RENDERED VALIDATION = PASS.**

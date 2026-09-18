# HILTECH Low-Fidelity Screen Specifications — Pending Canvas Build

Date: 2026-09-18
Status: **SCREEN-SPEC READY / CANVAS VALIDATION PENDING**

Purpose:
Remove product/interaction ambiguity from the remaining low-fidelity screens while Figma MCP is unavailable.

These specifications are not final visual design.
They define:
- information priority,
- authoritative actions,
- visible state,
- conflict/error behavior,
- cross-screen continuity,
- what must be tested once the screens are rendered.

They intentionally do **not** freeze:
- color,
- typography,
- icon style,
- exact spacing,
- radius,
- motion,
- final navigation labels.

---

# M06 — Technician Job Detail

Target:
Mobile / field technician.

Existing Figma frame:
`2:2`

## Primary question

**Can I safely start and finish this assigned job here, including when the network is unreliable?**

## Header

Show:
- back/context return,
- Work Order human code,
- short job type/title,
- sync state,
- assignment/readiness status.

Representative:
- `WO-42`
- Fiber test
- READY
- Offline / 3 changes pending when relevant.

Do not hide offline state in Settings.

## Context strip

Compact:
- Project
- Site
- Area/Room/Zone
- assigned crew/supervisor when relevant.

Mixed Arabic/LTR identifiers must remain readable.

## Readiness

Before START JOB, show the small set of prerequisites that can actually block work:

- approved drawing/version,
- required asset/tool,
- required material reservation,
- site/access approval,
- prerequisite work.

Each row:
- item,
- state,
- exact linked reference where useful.

Example:
`Drawing rev 7 — READY`
`Fluke-03 — RESERVED`
`Patch cord — READY`

If blocked:
show blocker and **Waiting on** owner, not a generic red error.

## Primary action

`START JOB`

Behavior:
- locally durable first when command is offline-capable,
- visible queued/sync state,
- never fake authoritative acceptance.

After start:
primary action changes with state rather than keeping a permanently enabled Start button.

## Execution sections

### Instructions / Drawing
- latest approved drawing/version.
- cached/offline state.
- revision warning if local version is stale.

### Asset / Tool
- Scan Asset.
- current assigned/reserved tool.
- calibration validity.

### Test / Measurement
- result entry/import appropriate to job.
- test equipment context.

### Evidence
- Required evidence count.
- captured/local/uploaded state separately.

Example:
`Evidence 0 / 3`

Each evidence item may show:
- local ready,
- pending upload,
- uploaded/finalized,
- failed/retry.

### Material usage
Record explicit usage/consumption, not silent inventory mutation.

### Notes
Text/voice note entry where allowed.

## Secondary actions

- BLOCK JOB
- Report issue
- Pause where workflow permits.

Block flow requires:
- reason,
- optional linked blocker/material/access issue,
- safe note/evidence,
- expected responsible party if known.

## Completion

`SUBMIT COMPLETION`

Before enabled:
show missing required evidence/tests/material confirmations.

Submission creates a reviewable exact version/bundle.

Next:
Supervisor review -> Accepted or Rework.

## Offline conflict state

Representative:
Technician worked offline.
PM reassigned/cancelled/changed authoritative version while technician was disconnected.

Screen must:
- preserve captured local work,
- show authoritative current assignment/state,
- mark command CONFLICT,
- block unsafe dependent replay,
- offer allowed recovery actions.

Never:
- silently discard evidence,
- silently overwrite server assignment,
- claim completion succeeded.

## Required prototype variants

1. READY online.
2. IN_PROGRESS offline with queued changes.
3. BLOCKED waiting on Warehouse.
4. CONFLICT after reassignment while offline.
5. COMPLETION submitted / awaiting supervisor.
6. REWORK requested.

## Feature / object links

- FIELD-002..019
- WORK / WorkOrder
- ENG evidence/test/drawing
- ASSET
- OFF
- DOC

---

# M07 — Warehouse Checkout

Target:
Warehouse operator / phone.

Existing Figma frame:
`2:4`

## Primary question

**What did I scan, is it safe/available to issue, and exactly who becomes custodian?**

## Entry

Scan-first.

After scan:
show Asset Passport summary immediately.

Representative:
`Fluke-03`

## Asset summary

Show:
- asset code,
- model/serial,
- current status,
- current location,
- current custodian if any,
- condition,
- calibration validity/due date,
- reservation/project context.

Primary status must be unambiguous:
AVAILABLE / RESERVED / CHECKED_OUT / MAINTENANCE / CALIBRATION_BLOCKED.

## Checkout form

Required/conditional:
- recipient employee/crew,
- project/site/work context,
- expected return,
- current condition,
- purpose/comment only where policy needs it.

Use scanner/search selectors rather than free text for authoritative references.

## Primary action

`CHECK OUT`

Confirmation must state the custody consequence:

`Fluke-03 will become the custody of Ahmed Hassan for Project A.`

## Success

Show:
- new custodian,
- movement reference,
- timestamp,
- project/site,
- expected return.

Support immediate next scan.

## Collision

Representative:
Asset was AVAILABLE locally, but another authoritative checkout won first.

Show:
- CHECKOUT NOT APPLIED,
- authoritative current custodian,
- current version/state,
- movement time if safe,
- next allowed action.

Never:
- last-write-wins,
- silent override,
- duplicate custody.

## Offline policy visualization

Read/scan may use cached passport.

Final custody transfer is collision-sensitive.

Prototype must distinguish:
- cached asset identity,
- locally captured intent,
- authoritative checkout acceptance.

Exact offline execution policy remains tied to concurrency/server validation.

## Calibration block

If calibration expired:
- no normal CHECK OUT,
- explain block,
- route to calibration/authorized exception only if policy permits.

## Required prototype variants

1. AVAILABLE.
2. RESERVED for same job.
3. Calibration blocked.
4. Already checked out.
5. Stale cached AVAILABLE -> server collision.
6. Successful checkout -> next scan.

## Feature / object links

- ASSET-004..020
- WH issue context
- FIELD Scan
- OFF
- APR where exception requires approval.

---

# D03 — Global Search / Command

Target:
Desktop internal users.

Pending Figma creation.

## Primary question

**Take me to the exact authorized thing or action without navigating department trees.**

Keyboard-first overlay/workspace.

## Entry

Global shortcut + top search box.

Search should work for:
- exact human codes,
- IDs/serial numbers,
- names,
- client/project context,
- safe authorized text.

Representative object groups:
- Project
- Site
- Work Order
- Asset
- Employee
- PO
- Invoice
- Ticket
- Document.

## Result grouping

Group by object type instead of one flat relevance soup.

Each result:
- icon/type label,
- primary title,
- exact code/ID,
- safe context,
- state/status,
- optional matching snippet,
- permitted quick actions only.

Example:

`Asset · Fluke-03`
`HT-A-000184 · Main Warehouse · AVAILABLE`

## Authorization

Search is server/read-model permission safe.

Forbidden:
- returning sensitive fields then hiding them visually,
- leaking object existence to unauthorized external users,
- indexing salary/bank/identity fields into generic search.

## Mixed-language stress

Must test:
- Arabic person/project name,
- English project code,
- serial,
- IP,
- invoice number,
- mixed Arabic + Latin in one result.

## Command actions

Command palette may expose contextual safe actions such as:
- Open
- Scan/open asset
- New request
- Create work order where permitted
- Request approval

It must not become an unrestricted hidden admin console.

Every command respects same backend permission/workflow rules as normal UI.

## Recents

Optional section:
- recent authorized contexts,
- recent objects,
- never stale permission bypass.

## Required prototype variants

1. Empty/focus state.
2. Exact code hit.
3. Mixed grouped results.
4. No authorized results.
5. Permission-restricted result set.
6. Command action list.

---

# D04 — Shared Work Queue

Target:
Internal desktop roles.

Pending Figma creation.

## Primary question

**What actionable work is mine/ours now, why is it waiting, and what should happen next?**

This is **not** a generic task table.

It aggregates authorized actionable items across domains through purpose-built read models.

## Queue item families

Depending on role:
- approvals,
- field jobs,
- project blockers,
- procurement actions,
- finance exceptions,
- payroll exceptions,
- support items,
- client actions,
- warehouse exceptions,
- HR/onboarding actions.

## Row anatomy

Each row needs:
- object/action type,
- exact subject,
- state,
- priority/urgency,
- due/overdue,
- project/client/site context,
- current owner,
- **Waiting on**,
- offline availability where relevant,
- safe next action.

## Filters

- Mine
- My team
- role/context
- overdue
- due soon
- waiting on
- project
- client
- urgency
- object/action type.

## Saved views

Useful for dense operators:
- Ahmed: Finance Exceptions
- PM: Project A blockers
- Warehouse: Returns due
- Supervisor: Completions to review.

Saved view never broadens permission.

## Inspector

Selecting row keeps queue position and opens:
- object summary,
- evidence/context,
- history,
- next authorized actions.

Do not force open/close navigation for every row.

## Empty state

`Nothing needs your action in this view.`

Avoid fake productivity metrics.

## Required prototype variants

1. Mixed personal queue.
2. Team queue.
3. Heavy finance queue.
4. Waiting-on filtered view.
5. No-action empty state.
6. Selected row + inspector.

---

# D05 — Ahmed Finance — Advances & Imprest

Target:
Ahmed / Finance-Admin desktop.

Pending Figma creation.

Reason:
Employee Advance and Financial Imprest/Cash Custody are distinct first-class financial obligations and must not disappear inside generic Expenses.

## Primary question

**What company money is currently advanced or in employee custody, what is unresolved, and what needs Finance action?**

## Workspace tabs / views

Low-fi candidate:
- Advances
- Imprest / Cash Custody
- Settlements
- Exceptions

Do not combine Advance and Imprest into one object.

## Summary strip

Exception-oriented:
- Advances outstanding
- Advances due/overdue
- Active imprests
- Settlement due
- Shortage/overage review
- replenishments waiting approval/funding.

No vanity totals without drill-down.

## Advances table

Columns candidate:
- advance code,
- employee,
- requested/approved amount,
- issued amount,
- settlement mode,
- settled,
- outstanding,
- due date,
- state,
- waiting on.

Inspector:
- purpose,
- request/version,
- exact approval,
- issue/payment reference,
- settlement history,
- approved payroll-deduction plan if any,
- audit.

Critical rule visible:
**Advance existence does not automatically create a payroll deduction.**

## Imprest table

Columns candidate:
- imprest code,
- custodian,
- project/site/cost context,
- funded,
- accepted spend,
- cash returned,
- current custody balance,
- settlement due,
- state,
- waiting on.

Inspector:
- approved purpose/limit,
- append-only ledger,
- receipt evidence,
- replenishments,
- submitted settlement version,
- finance review,
- shortage/overage,
- funding/payment references,
- audit.

Critical rule visible:
**Custody balance is derived from ledger; it is not an editable number.**

## Settlement review

Three-pane candidate:
- settlement/exception queue,
- selected settlement lines/evidence,
- finance decision inspector/actions.

Review must not edit the custodian's submitted entries in place.

Outcomes:
- accept line,
- reject/request correction,
- request evidence,
- accept cash return,
- explicit shortage/overage path.

## Shortage / overage

Must be an explicit exception state.

Never:
- silently change balance,
- silently deduct shortage from payroll,
- hide overage through adjustment.

Any recovery/deduction is a separate authorized/legal process.

## Employee-facing counterpart

Mobile Self-Service:
- Own Advance request/status/settlement.
- Own assigned FinancialImprest only when custodian.
- receipt/spend capture where allowed.
- submit settlement.
- replenishment request where policy permits.

No visibility into another employee's obligation.

## Required prototype variants

1. Advances normal/outstanding.
2. Advance due + payroll-deduction plan.
3. Active imprest.
4. Settlement submitted.
5. Shortage review.
6. Replenishment pending.
7. Selected ledger entry/evidence.
8. Permission-redacted non-finance view concept.

## Feature / object links

- PEOPLE-016, 022, 023
- FIN-016, 021..025
- PAY-009
- Advance
- FinancialImprest
- ImprestLedgerEntry
- ImprestSettlement
- PaymentInstruction
- ApprovalRequest

---

# RTL Stress Pass

Required rendered clones/variants once Figma access returns:

## R01 — Mohamed Home Arabic RTL
Test:
- Needs You order,
- numeric values,
- project codes,
- mixed Arabic/English.

## R02 — M06 Technician Job Arabic RTL
Test:
- `WO-42`, drawing revisions, serials/IP/test codes,
- start/end alignment,
- action order,
- conflict copy.

## R03 — D01 Ahmed Payroll Arabic RTL
Test:
- dense columns,
- money,
- employee codes,
- persistent inspector side/order,
- keyboard semantics.

## R04 — D02 Project Command Center Arabic RTL
Test:
- nav/workspace/inspector physical vs logical ordering,
- Waiting on,
- mixed identifiers.

## R05 — D05 Advances & Imprest Arabic RTL
Test:
- money/currency,
- transaction refs,
- employee codes,
- ledger chronology,
- finance decision actions.

Rule:
Do not mirror every desktop pane mechanically.
Test reading/task order, then freeze logical pane direction.

---

# Conflict / Exception Prototype Set

## C01 — Stale Payroll Approval
Subject exact version changed after approver opened it.

Show:
- approval no longer applies,
- old/new version,
- safe diff,
- superseded state,
- no stale Approve action.

## C02 — Offline Work Conflict
Technician local work vs authoritative reassignment/cancel.

Preserve local evidence and explain recovery.

## C03 — Asset Checkout Collision
Cached AVAILABLE vs authoritative current custodian.

No override.

## C04 — Advance Changed After Approval
Material amount/settlement terms changed.

Old approval superseded; require exact-version reapproval.

## C05 — Imprest Settlement Changed
Submitted settlement version must be immutable while under review.
Correction creates new version/outcome, not silent mutation.

---

# Adaptive Tablet Prototype Set

## T01 — Technician List / Job
Candidate:
list + selected job detail.

Must remain touch-first and not become desktop compressed.

## T02 — Warehouse Asset List / Passport
Candidate:
scan/search context + asset passport + action pane.

Support rapid repeated scanning and custody actions.

## T03 — Finance Advance / Imprest Review
Only if finance tablet use is validated in reality.
Do not build merely for symmetry.

---

# Navigation Comparison Gate

After all remaining low-fi frames/variants exist, compare:

## Mobile
Candidates:
- Home
- Work
- Search/Explore
- Inbox
- Me

Scan remains contextual/global for field/warehouse rather than universal tab unless canvas testing proves otherwise.

## Desktop
Need to compare:
- global product navigation,
- context/workspace navigation,
- global Search/Command,
- Shared Work Queue,
- inspector behavior.

Do not freeze labels before this comparison.

---

# Figma Build Order When MCP Access Returns

1. Fill M06 existing frame `2:2`.
2. Fill M07 existing frame `2:4`.
3. Create/build D03.
4. Create/build D04.
5. Create/build D05.
6. R01/R02/R03/R04/R05 RTL stress variants.
7. C01..C05 conflict/exception variants.
8. T01/T02 tablet variants.
9. final navigation comparison board/screen set.
10. update decision log only from observed canvas results.

## Gate

This document makes the remaining screens **spec-ready**.

Low-fi phase remains NOT COMPLETE until the screens are actually rendered, reviewed and the open navigation/RTL/adaptive questions are tested on canvas.

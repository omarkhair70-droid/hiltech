# 07 — First-Slice UI Flow Contracts

Status: **PRE-FREEZE / DESIGN VALIDATION REQUIRED**

## Rule

UI contracts describe:
- user task,
- authoritative/local state shown,
- allowed actions,
- loading/offline/error/conflict behavior,
- permission differences,
- adaptive/RTL behavior.

They do not prescribe final visual polish until design freeze.

---

# PM Desktop — Project Command Center

Must freeze:
- selected project context,
- Waiting On / exceptions,
- work queue/readiness,
- assignment action,
- current authoritative work state/version,
- selected inspector,
- conflict/reassignment implications,
- asset/material readiness signal,
- audit/activity visibility.

Pending:
- exact navigation,
- final table/inspector contract,
- Arabic RTL,
- loading/empty/error.

---

# Warehouse — Scan / Checkout

Existing low-fi: Warehouse Scan.
Pending visual validation: Warehouse Checkout.

Must freeze:
- scan/manual lookup,
- asset identity/condition/calibration,
- current custody,
- reservation/work/project context,
- recipient identity,
- checkout confirmation,
- collision: already checked out,
- return/condition flow,
- rapid multi-asset behavior if reality requires,
- tablet/adaptive use.

Never show acquisition cost unless permission requires.

---

# Technician Android — Today / Job

Existing low-fi: Technician Today.
Pending visual validation: Technician Job Detail.

Must freeze:
- sync/offline status,
- assignment/readiness,
- job instruction version,
- site/access minimum required context,
- required tools/materials,
- drawing/docs,
- evidence checklist,
- Start / Block / Resume / Submit,
- queued state,
- retryable state,
- stale/conflict state,
- local evidence preservation,
- rework.

Need Arabic RTL and real-device/tablet pass.

---

# Supervisor / Engineer — Review

Must freeze:
- submitted exact version,
- evidence/test review,
- accept vs rework,
- rework reason,
- current authoritative assignment/state,
- stale review behavior,
- audit trail.

Authority differs by real work type; do not hard-code reviewer from title alone.

---

# Conflict UI — Required

At least:

## Offline work stale after PM change
Show:
- local work preserved,
- server state changed,
- what changed,
- allowed recovery actions,
- no false success.

## Asset already checked out
Show:
- authoritative unavailability,
- safe current context/custodian if permitted,
- no silent override.

## Review stale version
Show:
- submitted/current version mismatch,
- refresh/review requirement,
- no acceptance of unseen version.

---

# Design Gate

First-slice UI contract is ready only when:
- Technician Job Detail visually tested,
- Warehouse Checkout visually tested,
- Project Command Center updated as needed,
- Supervisor review represented,
- Arabic RTL variants exist,
- conflict states exist,
- mobile/tablet/desktop adaptation is compared,
- navigation hypothesis is selected.

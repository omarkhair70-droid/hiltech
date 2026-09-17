# HILTECH Cross-Device Continuity

Status: PRODUCT / EXPERIENCE MODEL v0.1

## Thesis
Mobile and Desktop are not synchronized copies. They are two interaction surfaces over the same live HILTECH objects, permissions, states, approvals, activity, and workflows.

## Shared truth
The following must remain identical across devices:
- identity
- permission result
- object ID
- object state
- version
- approval state
- activity/audit
- workflow state
- unread/action status
- authoritative financial state

## Device-shaped interaction
Same object, different interaction.

### Example — Payroll
Desktop Ahmed:
prepare/review dense payroll.

Mobile Mohamed:
review exact approved version and decide.

Desktop Ahmed:
state updates to APPROVED immediately.

### Example — Project
Desktop PM:
plan work and reserve resources.

Mobile Supervisor:
receives today's site plan.

Mobile Technician:
executes task/evidence.

Desktop PM:
sees accepted work/progress.

### Example — Warehouse
Desktop:
manage reservations/inventory.

Mobile:
scan checkout/return.

Desktop:
movement appears instantly.

## Recent context
Where safe/useful:
recently opened objects can appear across devices.

Do not implement "remote desktop resume".
Resume object/context, not screen pixels.

## Drafts
Need explicit rules by object:
- local draft only
- cloud draft
- collaborative draft
- authoritative saved object

Critical finance/approval drafts require stronger version semantics than a note.

## Notifications
Push on mobile can open object.
If user already handled action on desktop, mobile notification should resolve/update rather than create stale work.

## Offline
Mobile may have local queued changes.
Desktop/server authoritative state may advance meanwhile.

Sync engine must handle:
- stale command
- superseded object
- conflicting assignment
- duplicate evidence
- already-completed action

## Security
Device-specific:
- session trust
- re-auth
- local encrypted sensitive data
- remote revocation
- lost device recovery

## Presence
Potential later:
show that another authorized user is currently editing/reviewing sensitive record.

Not a V1 requirement.

## Continuity success criteria
- no file/email/WhatsApp handoff required between Mohamed and Ahmed for system workflow.
- no duplicate re-entry.
- no device-specific independent truth.
- safe conflict behavior when one device is offline.

# Wireflow — Technician Field / Offline

Status: EXPERIENCE WIREFLOW v0.1

## Goal
Technician completes valid work even with no connectivity, without losing evidence or lying about sync state.

---

# Before Site

```text
HILTECH background prefetch
   ↓
Tomorrow / Today job bundle
   ↓
Task
Site
Latest allowed drawing
Required evidence
Tools/materials
Contacts/access
```

UI indicates downloaded/ready.

---

# Start Job

```text
Today
 ↓
WO-118
 ↓
Job Detail
 ↓
[Start]
 ↓
Network?
 ├─ online → server validates → IN_PROGRESS
 └─ offline → local command queued
                ↓
          "Started on this device"
```

Never show authoritative synced checkmark while offline.

---

# Work Context

Job screen:
- task.
- location/site/area.
- drawing.
- material.
- tool.
- evidence checklist.
- issues.
- sync state.

---

# Scan Asset

```text
[Scan]
  ↓
QR recognized
  ↓
Asset Passport cached
  ↓
Is asset expected for job?
  ├─ yes → continue
  └─ no → warn / allow report / require confirmation
```

If asset state stale:
show freshness.

---

# Capture Evidence

```text
Photo
 ↓
Local durable file
 ↓
Evidence metadata
 ↓
Operation ID
 ↓
Upload queue
```

If app crashes:
file/evidence remains.

---

# Record Test

Example:
OTDR / measurement.

```text
Test type
Value/file
Asset/tool used
Result
Notes
```

Calibration check can warn/block depending policy and cached certainty.

---

# Material Used

```text
Fiber
Issued: 220m
Used: 213m
Return: 7m
```

Offline command stores intent.
Server validates authoritative balance on sync.

---

# Blocker

```text
[I'm Blocked]
  ↓
Type
Photo/Note
Severity
  ↓
Local queue
  ↓
Visible immediately on device
```

When online:
supervisor/PM receives according policy.

---

# Complete

Before submit:
system checks local requirements.

```text
Required:
✓ before photo
✓ after photo
✓ OTDR
✓ material usage
✓ note

[Submit completion]
```

Offline:
```text
Completed on this device
Waiting to sync
```

not:
```text
Completed ✓
```

---

# Reconnect

```text
Connectivity returns
 ↓
Upload binaries
 ↓
Evidence registrations accepted
 ↓
Start/material/test commands
 ↓
SubmitCompletion
 ↓
Server validation
```

---

# Conflict — Job Reassigned/Cancelled

```text
Technician submitted offline
        ↓
Server:
Job was cancelled/reassigned
        ↓
SYNC_CONFLICT
        ↓
UI:
"Your work and photos are safe, but this job changed while you were offline."
        ↓
[Review]
[Send to supervisor]
```

Evidence is preserved.
State is not silently overwritten.

---

# Conflict — Asset Already Checked Out

Scan/use recorded offline.
Server finds different custodian.

UI:
- current authoritative custodian.
- local evidence safe.
- supervisor/warehouse resolution.

---

# Drawing Revision Conflict

Technician used rev 7.
Rev 8 approved during offline period.

Store:
usedRevision=7.

Policy:
review/rework decision.

Never rewrite history to pretend rev 8 was used.

---

# Sync UI

Global:
```text
2 waiting
1 uploading
1 needs attention
```

Per item:
- On device.
- Waiting.
- Uploading.
- Synced.
- Needs attention.

---

# Device Loss

When replacement device logs in:
only server-confirmed state returns.
Unsynced lost-device data may be unrecoverable unless device/cloud-safe strategy later added.

This risk must be visible in operational policy.

---

# Success Criteria

- no lost field evidence.
- no duplicate command.
- no false success.
- conflicts preserve human work.
- technician can understand sync without technical jargon.

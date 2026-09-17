# Wireflow — Engineering / Test / Technical Acceptance

Status: EXPERIENCE WIREFLOW v0.1

## Goal
Tie test evidence to correct work/site/asset/revision and make technical acceptance traceable.

---

# Assignment

```text
Engineering Today
 ↓
Technical Review / Test Due
 ↓
Project > Site > Work/Link/Asset
```

---

# Drawing Check

Before work:
```text
Required drawing
 ↓
Latest approved revision?
 ├─ yes
 └─ newer revision exists → warning/update
```

Offline:
show cached revision + freshness.

---

# Equipment Check

Example:
Fluke 03.

```text
Scan
 ↓
Calibration valid?
 ├─ yes
 └─ expired → block/warn by test policy
```

---

# Test Capture

```text
Test type
 ↓
Target link/asset
 ↓
Measurement/file
 ↓
Tool/serial
 ↓
Drawing/revision context
 ↓
Result
 ↓
Evidence
```

---

# Review

```text
Submitted Test
 ↓
Engineer review
 ↓
PASS / FAIL / NEEDS_RETEST
```

If fail:
create issue/rework.

---

# Handover Link

Accepted test:
automatically becomes eligible for handover artifact set.

No one should search old folders later.

---

# Wrong Revision Edge

Field evidence used rev 7.
Rev 8 approved before sync.

System:
preserve actual used revision.
Review decides impact.

---

# Offline

Capture/test evidence can queue offline.
Final technical acceptance normally online-authoritative.

---

# Success Criteria

Every technical result answers:
What was tested?
Where?
Using what tool?
Which revision?
Who accepted it?

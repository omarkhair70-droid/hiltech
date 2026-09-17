# Wireflow — Warehouse Scan & Custody

Status: EXPERIENCE WIREFLOW v0.1

## Goal
Turn warehouse control from memory/key/paper into scan-first accountable movement.

---

# Mobile Home

Primary:
- Quick Scan
- Issue Today
- Returns Due
- Receive Delivery
- Count
- Exceptions

---

# Checkout Asset

```text
Quick Scan
   ↓
Scan Asset QR
   ↓
Asset Passport
   ↓
Current state = AVAILABLE
   ↓
[Checkout]
   ↓
Select/Scan Recipient
   ↓
Project/Site/Work context
   ↓
Expected return
   ↓
Condition confirmation
   ↓
Confirm
   ↓
Server checks:
  version
  availability
  calibration
  permission
  reservation
   ↓
CHECKED_OUT
   ↓
Custody movement created
```

---

# Recipient Selection

Prefer:
- recent assigned people.
- project team.
- scan employee badge if adopted.
- search.

Do not type employee name freeform.

---

# Conflict

Asset card opened as AVAILABLE.
Another user checks it out first.

```text
Confirm
 ↓
ASSET_ALREADY_CHECKED_OUT
 ↓
"Fluke 03 is now with Ahmed Hassan — Project X."
 ↓
[View current custody]
[Choose another asset]
```

---

# Return

```text
Scan Asset
 ↓
Current custodian shown
 ↓
[Return]
 ↓
Inspect
  - good
  - damaged
  - missing accessory
 ↓
Good → AVAILABLE
Problem → UNDER_INSPECTION / DAMAGED
 ↓
Movement history updated
```

---

# Transfer

```text
Scan Asset
 ↓
Transfer
 ↓
From current custody
 ↓
To:
  Person
  Site
  Warehouse Location
 ↓
Accept/confirm where policy
 ↓
Movement
```

No direct edit of currentCustodian.

---

# Receive Purchase Delivery

```text
Deliveries
 ↓
PO-0081
 ↓
Expected lines
 ↓
Scan/Count
 ↓
Serial capture
 ↓
Condition
 ↓
Discrepancy?
 ├─ no → receive
 └─ yes → discrepancy
              ↓
          procurement notified
```

---

# Stocktake

```text
Start Cycle Count
 ↓
Zone A
 ↓
Scan/count
 ↓
Local counts possible
 ↓
Submit
 ↓
Variance
 ↓
Adjustment Request
 ↓
Separate approval
```

Count does not directly rewrite stock.

---

# Calibration

Asset scan:
```text
Fluke 03
Calibration due yesterday

Status:
NOT AVAILABLE FOR NORMAL ISSUE

[Send for calibration]
```

---

# Camera / Access Correlation

If a discrepancy exists:
authorized security/warehouse user can open:
- recent warehouse access events.
- relevant camera deep link.

Not shown on every routine checkout.

No automatic accusation.

---

# Offline

Default checkout final authority = online.

Possible future controlled mode:
offline local warehouse lane if business reality demands it.

Must solve:
- collision,
- authority,
- local shared device,
- later reconciliation.

Not enabled merely for convenience.

---

# Success Criteria

At any time:
- asset location/custodian known from movement history.
- warehouse does not depend on hidden key-holder memory.
- stock corrections are auditable.
- scan reduces typing.

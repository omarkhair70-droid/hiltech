# Wireflows — Supplier, Security, Search

Status: EXPERIENCE WIREFLOW v0.1

---

# A. Supplier

```text
Supplier Inbox
 ↓
RFQ
 ↓
Submit Quote
 ↓
HILTECH review
 ↓
PO issued
 ↓
Supplier Confirm
 ↓
Delivery date/status
 ↓
Delivery
 ↓
Invoice
 ↓
Status
```

Supplier only sees own commercial context.

## Amendment
PO v1 -> HILTECH amendment -> supplier sees v2 difference -> acknowledge.

## Partial Delivery
Supplier records backorder/partial.
HILTECH receiving confirms actual.

---

# B. Security Incident

```text
Critical event
  ↓
Security Inbox
  ↓
Event Detail
  ↓
Door / Zone / Camera / Time
  ↓
Related warehouse/asset events
  ↓
Create/Link Security Incident
  ↓
Assign owner
  ↓
Investigate
  ↓
Resolution
```

No automated blame.

## Camera
Authorized user may open live/event view through NVR integration.

Provider unavailable:
show external-system degraded state, not generic HILTECH failure.

---

# C. Global Search

Desktop:
```text
Ctrl+K
 ↓
"Fluke 03"
 ↓
Results:
Asset: Fluke 03
Movement
Related project
Allowed command(s)
 ↓
Open Asset Passport
```

---

# Exact ID

```text
PO-2026-0081
 ↓
Exact result
 ↓
Open directly
```

---

# Contextual Command

While Asset selected:
```text
Ctrl+K
 ↓
Commands:
View History
Reserve
Checkout
Report Damage
```

Only allowed commands for current role/state.

---

# Search Security

User searches client/project they cannot access:
no result / safe not-visible behavior.

Never leak object existence through search.

---

# AI Search — Later

```text
"Why is Project A late?"
 ↓
Authorized query
 ↓
Answer:
Material delivery + client access blocker
 ↓
Links to source objects
```

AI never gets broader access than user.

---

# Success Criteria

Supplier:
knows exact order/delivery/invoice status.

Security:
can correlate events without jumping systems blindly.

Search:
becomes a fast path into HILTECH, not a data-leak path.

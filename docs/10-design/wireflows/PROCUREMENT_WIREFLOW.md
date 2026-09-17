# Wireflow — Procurement End to End

Status: EXPERIENCE WIREFLOW v0.1

## Goal
Move from real business need to received/matched payable without chat-driven purchasing.

---

# Requirement

```text
Project/Work/Warehouse
   ↓
Purchase Requirement
   ↓
Stock check
   ├─ available → reserve/use existing stock
   └─ shortage
        ↓
     Procurement
```

---

# Sourcing

```text
Requirement
  ↓
RFQ suppliers
  ↓
Quotes received
  ↓
Versioned
  ↓
Comparison
```

Comparison:
- price.
- delivery.
- technical compliance.
- warranty.
- terms.
- supplier history facts.

---

# Approval

```text
Recommended choice
  ↓
Policy evaluation
  ↓
Approval?
  ├─ no → create PO
  └─ yes → Approval Engine
```

No hidden AI winner.

---

# PO

```text
PO Draft
 ↓
Review
 ↓
Issue exact version
 ↓
Supplier receives
 ↓
Confirm / Exception
```

Edit after issue:
new PO version/amendment.

---

# Delivery

```text
Expected Delivery
   ↓
Warehouse/Site receives
   ↓
Count / Scan / Condition
   ↓
Exact match?
   ├─ yes → Receipt
   └─ no → Discrepancy
              ↓
          Procurement action
```

---

# Invoice Match

```text
Supplier Invoice
      +
PO version
      +
Receipt
      ↓
Three-way Match
      ↓
MATCHED / MISMATCH
```

Matched:
finance payable.

Mismatch:
price / quantity / tax / missing receipt.

---

# Payable Handoff

Procurement sees:
"Ready for Finance"

Finance owns payment.

Supplier sees allowed invoice/payment status only.

---

# Emergency Purchase

Explicit path:
```text
Emergency reason
 ↓
Emergency authority
 ↓
Purchase
 ↓
Mandatory retrospective audit/review
```

No invisible bypass.

---

# Mobile

Procurement mobile:
- urgent requirement.
- supplier quote/PO context.
- delivery issue.
- approval state.

Desktop:
comparison, PO, matching.

---

# Success Criteria

Every purchase answers:
Why did we buy it?
Who approved?
Which quote?
Which PO?
Was it received?
Did invoice match?

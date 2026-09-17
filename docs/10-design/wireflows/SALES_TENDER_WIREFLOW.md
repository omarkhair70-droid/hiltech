# Wireflow — Sales / Tender to Project

Status: EXPERIENCE WIREFLOW v0.1

## Goal
Move from incoming opportunity to delivery-ready project without duplicate re-entry or losing commercial assumptions.

---

# Opportunity Intake

```text
Lead / Tender / RFQ
  ↓
Opportunity
  ↓
Client organization
  ↓
Owner
  ↓
Deadline
  ↓
Initial scope
```

If organization/contact already exists:
reuse canonical object.

---

# Qualification

```text
Opportunity
  ↓
Discovery
  ↓
Site Visit / Docs / Clarifications
  ↓
Qualified?
  ├─ No → Lost/Disqualified
  └─ Yes
       ↓
   Solution / BOQ
```

---

# BOQ / Costing

Desktop:
```text
Scope v3
  ↓
BOQ
  ↓
Material / Labor / Subcontract
  ↓
Internal Cost
  ↓
Sell Price / Margin
  ↓
Commercial Exceptions
```

Internal cost restricted.

---

# Quote

```text
Quote v1
  ↓
Internal review
  ↓
Approval required?
  ├─ No → Ready
  └─ Yes → Approval Engine
              ↓
          Approved exact version
```

Edit after approval:
Quote v2 -> new approval if policy.

---

# Submission

```text
Ready Quote
  ↓
Submit
  ↓
Submitted timestamp
  ↓
Client clarification
  ↓
Revision if needed
```

Never overwrite submitted version.

---

# Award

```text
Opportunity WON
  ↓
Contract / Client PO / Award evidence
  ↓
CreateProjectFromAward
  ↓
Carries:
Client
Scope version
BOQ version
Price/terms
Sites
Documents
Dates
Warranty/maintenance
  ↓
Project Kickoff
```

No manual project re-entry.

---

# Lost

Capture:
- reason.
- competitor only if legitimately known.
- price/technical/timing cause.
- future follow-up.

Do not delete opportunity.

---

# Mobile

Sales mobile:
- meeting/site visit.
- client history.
- capture note/photo.
- follow-up.
- deadline.
- approval status.

BOQ/costing/quote deep work:
Desktop.

---

# Errors

- tender deadline passed.
- quote approval superseded.
- client scope changed.
- duplicate organization.
- award before final contract.
- site data missing.
- invalid margin/price permission.

---

# Success Criteria

Won work arrives to PM with exact commercial/technical truth and no second spreadsheet reconstruction.

# Master Workflow — Documents, Evidence & Handover

Status: RESEARCHING / SYSTEM MODEL v0.1

## Objective
Make documents/evidence part of business objects and lifecycle, not disconnected folders.

---

# 1. Document Identity

Document has:
- type
- business context
- owner/creator
- classification
- version
- status
- storage object
- checksum
- visibility
- approval/acknowledgement requirements if any

Examples:
- drawing
- BOQ
- contract
- quote
- PO
- delivery note
- invoice
- test report
- warranty
- manual
- handover pack

---

# 2. Versioning

Important document types require explicit versions.

States candidate:
DRAFT -> REVIEW -> APPROVED -> SUPERSEDED -> ARCHIVED

Client-visible only when policy allows.

Old approved versions remain traceable.

---

# 3. Latest Approved

For field-critical documents:
system must know latest approved revision.

Technician/engineer should never have to guess which file is latest.

---

# 4. Evidence

Evidence is not generic document.

It links to:
- work
- site
- asset
- test
- issue
- maintenance visit

Can include:
- photo
- video
- measurement
- test file
- signature
- scan

Evidence carries source/time/device/context.

---

# 5. Client Sharing

Internal document/evidence can be:
- internal only
- client visible
- client action required
- public/shareable if explicitly allowed

Sharing is permission/policy action, not merely "copy URL".

---

# 6. Acknowledgement / Approval

Some documents require:
- internal approval
- client acknowledgement
- client approval
- signature

Use shared Approval where appropriate.

---

# 7. Handover Assembly

Handover should pull from structured truth.

Potential sections:
- project summary
- as-built drawings
- asset register
- test reports
- certificates
- warranties
- manuals
- photos/evidence
- acceptance
- snags
- training records
- support/warranty contacts

System detects missing required artifacts before ready state.

---

# 8. Handover Review

Internal:
- completeness
- document versions
- technical acceptance
- commercial/contract requirements

Client:
- receives exact handover package/version
- comments/accepts/rejects according to contract

---

# 9. Post-Handover

Documents remain attached to:
- project
- site
- asset
- maintenance/warranty history

Do not archive them into a dead folder disconnected from future support.

---

# 10. Retention / Classification

Need later policy for:
- employee docs
- financial docs
- client confidential docs
- security footage links
- technical evidence
- legal contracts

Object storage lifecycle must respect retention.

---

# Major Objects

- Document
- Document Version
- Attachment
- Evidence
- Evidence Requirement
- Document Approval
- Acknowledgement
- Handover Package
- Handover Package Version
- Signature/Acceptance Record
- Retention Rule

---

# Completion Gate

Requires:
- real HILTECH project folders,
- actual handover packs,
- document naming/versioning reality,
- client sharing rules,
- retention/legal requirements,
- storage design,
- upload/version/conflict tests.

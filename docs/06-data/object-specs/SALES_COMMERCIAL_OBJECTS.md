# Exact Object Specs — Sales, Tenders & Commercial

Status: DOMAIN DATA MODEL v0.1 / NOT COMMERCIAL-SCHEMA-FROZEN

---

# Opportunity

## Fields
- id
- opportunityCode
- clientOrganizationId
- source
- title
- description
- state
- ownerUserId
- estimatedValue — O — RESTRICTED
- currency — O
- probability — O
- expectedDecisionDate — O
- createdAt
- version

Important:
Probability is user/business estimate, not predictive model by default.

---

# TenderRFQ

## Fields
- id
- opportunityId
- type: TENDER/RFQ/RFP/OTHER
- referenceNumber — O
- receivedAt
- submissionDeadline
- sourceChannel
- documentRefs
- clarificationDeadline — O
- state
- version

---

# SiteVisit

## Fields
- id
- opportunityId/projectId — O
- siteId — O
- scheduledAt
- attendees
- purpose
- notes
- evidenceRefs
- completedAt — O
- version

---

# Scope

## Fields
- id
- opportunityId/projectId
- versionNumber
- description
- inclusions
- exclusions
- assumptions
- technicalConstraints
- clientResponsibilities
- createdBy
- approvedState
- version

Classification: RESTRICTED.

---

# BOQ

## Fields
- id
- contextType: OPPORTUNITY/PROJECT
- contextId
- versionNumber
- currency
- state
- createdAt/by
- documentRef — O
- version

Child BOQLine:
- lineCode
- description
- category
- quantity
- unit
- internalUnitCost — RESTRICTED
- sellUnitPrice — RESTRICTED
- totalCost — RESTRICTED
- totalSell — RESTRICTED
- technicalSpecRef — O
- supplier/item refs — O

## Invariants
Approved commercial version immutable.
Project actuals do not overwrite awarded BOQ.

---

# Costing

## Fields
- id
- opportunityId
- boqVersionId
- materialCost
- laborCost
- subcontractCost
- overheadAllocation — O
- contingency — O
- totalInternalCost
- targetMargin
- proposedSellValue
- currency
- versionNumber
- state
- version

Classification: RESTRICTED.

---

# Quote

## Fields
- id
- opportunityId
- quoteCode
- versionNumber
- clientOrganizationId
- scopeVersionId
- boqVersionId — O
- totalPrice
- currency
- paymentTerms
- validityDate
- deliveryTerms
- exclusions
- state
- approvalRef
- documentRef
- createdAt/by
- version

## Invariants
- submitted/approved version immutable.
- new commercial edit creates new version.
- internal costing remains separate from client-visible quote.

---

# Contract

## Fields
- id
- clientOrganizationId
- opportunityId
- contractCode
- effectiveDate
- endDate — O
- currency
- contractValue — RESTRICTED
- paymentTerms
- warrantyTerms
- maintenanceTerms — O
- documentRef
- state
- version

---

# ClientPurchaseOrder

## Fields
- id
- clientOrganizationId
- contractId — O
- opportunityId/projectId — O
- clientPoNumber
- issueDate
- totalAmount
- currency
- documentRef
- state
- version

---

# CommercialApprovalContext

Not a replacement for ApprovalRequest.

Fields:
- subjectRef
- internalCost
- sellValue
- margin
- discount
- paymentTermsRisk
- unusualCommitments
- strategicFlags

Classification: RESTRICTED.

---

# Invariants

- client-visible values never expose internal cost/margin.
- quote/contract revisions are versioned.
- won opportunity handoff preserves exact awarded version.
- lost opportunity does not destroy history.
- commercial documents tied to organization/context.

## Next
Reality validation with Sales/Tenders and sample BOQ/quotes.

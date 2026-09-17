# HILTECH Reality Validation Plan

Status: REQUIRED / NOT STARTED

## Goal
Replace design hypotheses with verified HILTECH reality before product/domain/stack freeze.

We do not need perfect corporate documentation.
We need enough real evidence to understand how work actually happens.

---

# 1. Mohamed — Owner / Executive Session

## Understand
- What Mohamed personally handles every day/week.
- What only he is allowed/trusted to decide.
- What people interrupt him for.
- What he wishes he could stop doing.
- How he knows project health today.
- How he knows money in/out today.
- How he tracks clients.
- How he knows who is working where.
- What risks keep him checking manually.
- What reports/files/messages he receives.
- What decisions he delegates already.
- What he wants to see from outside office.
- Existing camera/security access.
- Existing company systems/tools.

## Evidence to inspect
Where safe:
- sample daily/weekly reports.
- project WhatsApp/report flow screenshots with private data redacted.
- approval examples.
- project status files.
- org chart or practical hierarchy.
- examples of client escalations.

## Output
Update:
- owner persona.
- owner product experience.
- approval policies.
- company pulse.
- automation opportunities.
- notification policy.

---

# 2. Ahmed — Finance/Admin Session

## Understand
- Payroll process step by step.
- Employee list/source of truth.
- Salary/overtime/deduction process.
- Who reviews/approves payroll.
- How salaries are paid.
- Bank(s)/portals/files/APIs used.
- Client invoices/collections.
- Supplier invoices/payments.
- Petty cash.
- Advances.
- Expenses.
- Taxes/e-invoice/accounting software.
- Reconciliation.
- What Ahmed re-enters manually.
- What Ahmed asks Mohamed for.
- What Ahmed asks project managers/warehouse for.

## Evidence
Redacted samples:
- payroll sheet.
- employee master.
- supplier invoice.
- client invoice.
- payment/bank batch format if any.
- expense/advance form.
- accounting software screenshots or name/version.
- recurring finance reports.

## Output
Validate:
- finance objects.
- payroll workflow.
- bank integration strategy.
- finance permissions.
- tables/surfaces.
- legal/accounting research needs.

---

# 3. Project Manager Session

Observe one real project.

## Map
- how won project arrives.
- documents received.
- project setup.
- team planning.
- site planning.
- BOQ usage.
- materials.
- tools.
- reporting.
- progress calculation.
- changes.
- client approvals.
- subcontractors.
- billing milestones.
- handover.

## Evidence
- project folder structure.
- BOQ.
- schedule.
- report.
- drawings.
- handover checklist.
- variation/change example.

---

# 4. Field Session — Engineer / Supervisor / Technician

Prefer observation of one actual workday/site.

## Understand
- how job assignment arrives.
- what they take to site.
- how they know latest drawing.
- site access.
- network availability.
- phone usage/camera restrictions.
- tools/materials.
- tests.
- evidence.
- daily reporting.
- blockers.
- overtime/attendance.
- return to warehouse.

## Capture
No client secrets unless authorized.
Document the flow, not sensitive project data.

---

# 5. Warehouse Walkthrough

High priority.

## Record
- physical layout.
- doors/access.
- cameras.
- responsible people.
- key/control reality.
- storage zones.
- high-value assets.
- consumable classes.
- labels/serials.
- current Excel/paper records.
- checkout.
- return.
- damage/loss.
- calibration.
- stocktake.
- procurement receiving.
- direct-to-site delivery.

## Build sample dataset
At least:
- 10 trackable assets.
- 10 stock items.
- 3 checkout examples.
- 2 return examples.
- 1 damaged/calibration case.

This becomes the first realistic test fixture.

---

# 6. Procurement Session

Map:
requirement -> supplier quote -> comparison -> approval -> PO -> delivery -> invoice -> payment.

Collect redacted:
- purchase request.
- quote.
- PO.
- delivery note.
- supplier invoice.

---

# 7. HR/Admin Session

Map:
hire -> paperwork -> onboarding -> attendance -> leave -> role changes -> offboarding.

Need:
- employee categories.
- required documents.
- attendance method.
- leave policy.
- contracts.
- certifications.
- asset/access clearance.

---

# 8. Sales/Tenders Session

Map:
lead/tender -> discovery -> BOQ/costing -> quote -> approval -> submission -> award -> handoff.

Evidence:
- tender/RFQ.
- quote version.
- costing sheet.
- award/PO/contract handoff.

---

# 9. Client Reality

Do not interview client until Mohamed approves.

Initially learn internally:
- what clients request repeatedly.
- what reports/status they receive.
- what approval points exist.
- support after handover.
- maintenance contracts.
- client portal value.

Later validate with selected trusted client if appropriate.

---

# 10. Systems Inventory

Record every existing system/tool:

Category examples:
- accounting.
- payroll.
- bank portal.
- Excel.
- Google/Microsoft.
- WhatsApp.
- CCTV/NVR.
- access control.
- biometric attendance.
- project tools.
- test-equipment software.
- email.
- domain/hosting.
- cloud/storage.
- antivirus/security.
- printer/scanner/label equipment.

For each:
- vendor/name.
- purpose.
- users.
- authoritative data.
- export/API.
- pain.
- replacement vs integration decision.

---

# 11. Device Inventory

Sample:
- Mohamed phone/desktop.
- Ahmed PC.
- PM laptop/phone.
- field technician Android.
- warehouse phone/PC.
- office Windows versions.

Need:
- Android versions.
- RAM/storage.
- Windows versions.
- company-owned/BYOD.
- connectivity.
- camera restrictions.

---

# 12. Evidence Handling

Never commit:
- passwords.
- bank credentials.
- client confidential raw documents.
- national IDs.
- private personal data.
- secret pricing not needed for design.

Use:
- redacted examples.
- structural notes.
- synthetic samples.

Private evidence should live outside repo under controlled storage if later required.

---

# 13. Validation Status Per Statement

Use:
- VERIFIED_DOCUMENT
- VERIFIED_OBSERVATION
- VERIFIED_INTERVIEW
- INTERNAL_REPORTED
- PUBLIC_VERIFIED
- ASSUMPTION
- UNKNOWN

Architecture/product freeze should not depend on important UNKNOWN/ASSUMPTION facts without explicit risk acceptance.

---

# 14. Definition of Reality Pass Complete

Reality validation pass is complete as a planning artifact when:
- Mohamed workflow captured.
- Ahmed/finance captured.
- one real project mapped.
- one field flow mapped.
- warehouse walked.
- procurement mapped.
- HR mapped.
- sales/tender mapped.
- systems/devices inventoried.
- major contradictions resolved.
- all affected docs updated.

This still does not make product capabilities COMPLETE.

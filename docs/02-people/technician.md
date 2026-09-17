# Technician — Persona & Work Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Product principle
This must be one of the simplest experiences in HILTECH. A technician should not learn an ERP. HILTECH should tell them what they need to do now.

## Primary home
# Today
Each work item should answer:
- Where?
- When?
- What exactly?
- Who is with me / who supervises me?
- What drawings/instructions matter?
- What tools/materials are required?
- What evidence must I submit?

## Typical job flow
Open job -> navigate/context -> start -> perform -> scan relevant asset/material if needed -> capture required evidence -> record issue if blocked -> complete -> supervisor review.

## Mobile-first
- Offline work.
- Camera.
- QR/barcode scan.
- Measurements/test attachment.
- Voice/text note where useful.
- Work timer/status.
- Material used.
- Tool custody.
- Damage/problem report.
- Site contact.
- Updated drawing notification.

## Employee self-service visible to technician
- Upcoming jobs.
- Salary/payslip.
- Expense status.
- Leave request/status.
- Assigned assets/tools.
- Required training/certification.
- Company announcements relevant to them.

## Deliberately hidden by permission
- Peer salary.
- Company margins.
- Unrelated client information.
- Unrelated projects.
- Restricted finance/security information.

## Offline model
A technician must be able to complete a site visit with no connection after required job context is downloaded. Evidence queues locally and syncs later with visible sync state.

## Events
- job.started
- asset.scanned
- material.consumed
- evidence.captured
- field.issue.created
- job.completed
- equipment.return_requested

## Questions to validate
- Current job assignment method.
- Current proof/evidence requirements.
- Whether company phones are provided.
- Typical Android versions/devices in field.
- Which sites prohibit cameras/phones.
- Existing attendance rules.

# HR / Admin — Persona & Work Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Core responsibility
Maintain the human operating layer of HILTECH: employee records, onboarding/offboarding, attendance/time inputs where applicable, leave, documents, policies, role changes, and coordination with finance and operations.

## Primary home
- New hires / onboarding status.
- Missing employee documents.
- Leave requests.
- Attendance/time exceptions.
- Contracts/IDs/certifications expiring.
- Role/team changes.
- Employees requiring action.
- Offboarding checklist.
- Company announcements / policy acknowledgements.

## Employee record
One source of truth for:
- identity and contact data
- employment status
- role/team/manager
- hire date
- job/site eligibility
- documents
- certifications
- assigned company assets
- payroll configuration references
- leave/attendance information
- access status
- lifecycle history

Sensitive fields require strict permissions.

## Employee lifecycle
CANDIDATE -> OFFER/APPROVAL -> HIRED -> PREBOARDING -> ACTIVE -> ROLE/TEAM CHANGES -> LEAVE/SUSPENSION where applicable -> OFFBOARDING -> FORMER

Exact state model must be validated.

## Onboarding
Should orchestrate rather than just display a checklist:
- profile completion
- required documents
- contract/admin steps
- manager/team assignment
- system account
- permissions
- safety/policy material
- bank/payroll info
- tools/assets/PPE assignment
- first project/site
- required training/certifications

## Role change
One authorized change should propagate to:
- organizational role
- manager/team
- permissions policy
- work visibility
- required training
- relevant approvals
- payroll reference if applicable

## Offboarding
Must coordinate:
- access revocation
- assigned assets return
- pending expenses/advances
- project transfer
- final payroll inputs
- documents
- account/session closure
- audit preservation

## Employee self-service
- profile
- payslips
- leave
- expense status
- assigned equipment
- training/certifications
- announcements
- employment documents allowed for self-access

## Events
- employee.invited
- employee.onboarding_started
- employee.activated
- employee.role_changed
- employee.team_changed
- leave.requested
- leave.approved
- certification.expiring
- employee.offboarding_started
- access.revocation_requested
- asset.return_required
- employee.offboarded

## Questions to validate
- Who currently owns HR/admin work?
- Formal vs informal attendance method.
- Leave policy.
- Employment document types.
- Existing payroll inputs from HR.
- Whether field workers use company devices.
- Employee categories/contracts.
- Certifications HILTECH tracks today.

# Client — Experience Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Product principle
The client uses the same HILTECH product but sees only the client-owned truth and allowed project/service information. HILTECH's internal payroll, internal margins, unrelated clients, restricted staff data, and sensitive operations remain invisible by permission.

## Client goals
- Know what HILTECH is doing.
- Know current project/service status.
- Find documents without chasing people.
- Approve what requires client action.
- Report issues.
- See service/maintenance history.
- Understand sites/assets HILTECH manages for them.
- Request new work.

## Client home
### My HILTECH
- Active projects.
- Sites.
- Actions waiting for me.
- Recent progress.
- Open issues/support.
- Maintenance/service status.
- Documents/handover.
- Invoices/commercial items allowed to role.
- New request / RFQ.

## Project experience
- agreed scope
- progress
- milestones
- client-visible schedule
- approved photos/evidence
- tests/certifications allowed for sharing
- documents
- variation/change approvals
- issues requiring client input
- handover

## Client roles
A client organization may have its own permissions:
- executive/owner
- project manager
- technical engineer
- finance/accounts
- viewer

Do not model every client employee as having identical access.

## Approval examples
- approve variation/change
- approve milestone/inspection
- acknowledge document
- approve access/site schedule where applicable

## Support / maintenance
After project delivery:
Site/Asset -> Report issue -> contract/SLA context -> ticket -> assignment -> updates -> resolution -> service history.

## Relationship continuity
A delivered project remains a live digital object. It can later become:
- maintenance contract
- managed service
- upgrade
- new site template
- expansion opportunity

## Mobile emphasis
- progress
- approvals
- documents
- support
- asset/site context
- notifications

## Desktop emphasis
- multi-site/project view
- reports
- documents
- project history
- commercial/admin role views
- handover and asset inventories

## Events
- client.invited
- client.approval_requested
- client.approved
- client.rejected_or_requested_change
- client.issue_reported
- client.document_viewed_or_acknowledged
- client.new_work_requested

## Questions to validate
- Typical client stakeholders.
- What project data HILTECH currently shares.
- Existing approval/handover procedures.
- Whether clients should invite their own users or HILTECH controls invites.
- Support and maintenance contract reality.

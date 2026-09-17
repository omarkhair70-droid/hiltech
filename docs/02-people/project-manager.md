# Project Manager — Persona & Work Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Purpose
Define the complete HILTECH experience for a Project Manager before implementation. This file is not a final specification yet; it is the working product model to validate against HILTECH reality.

## Core responsibility
Own delivery across assigned projects and sites: scope, schedule, people, materials, field progress, quality, issues, client coordination, and handover.

## What the Project Manager should NOT spend time doing
- Re-entering information already captured in field work.
- Chasing technicians on WhatsApp for progress that the system can know.
- Manually reconstructing material usage from messages.
- Asking finance whether an approved purchase was paid.
- Asking the warehouse who has a tool when HILTECH already has custody data.
- Sending the same project update separately to owner, client, finance, and field teams.

## Primary HILTECH home
### My Projects
Each project should expose at minimum:
- Current status and health.
- Progress against plan.
- Sites and work areas.
- Current blockers.
- Work due today / overdue.
- Team availability.
- Materials required / missing.
- Assets and equipment assigned.
- Open approvals.
- Client actions waiting.
- Financial visibility allowed by permission.
- Recent project activity.

## Main workflows
1. Receive / take ownership of project.
2. Review contract/scope/BOQ/drawings.
3. Break project into sites, milestones, work packages, and work orders.
4. Assign supervisors/engineers/technicians.
5. Reserve required materials and equipment.
6. Follow field execution.
7. Review evidence, tests, photos, and completion.
8. Handle blockers / variations / rework.
9. Trigger approvals when thresholds or policy require them.
10. Publish client-visible progress.
11. Prepare milestone / partial / final handover.
12. Close project while preserving complete project history.

## Key events emitted by this persona
- project.plan.updated
- work.assigned
- material.requested
- equipment.reserved
- issue.escalated
- work.reviewed
- milestone.completed
- variation.requested
- client.approval.requested
- handover.started
- handover.completed

## Cross-domain connections
### Warehouse
Work plan -> material/equipment reservation -> issue/checkout -> usage -> return -> cost.

### Finance
Project change -> budget impact -> approval -> procurement/payment -> project cost/margin.

### Field
Assignment -> execution -> evidence -> supervisor/PM review -> project progress.

### Client
Milestone -> visible update -> approval/feedback -> project state.

### Owner
Only exceptions, risk, threshold approvals, strategic changes, major client escalations.

## Mobile emphasis
- Today.
- Approvals.
- Site view.
- Team status.
- Work orders.
- Issues.
- Quick project activity.
- Client actions.
- Scan asset/QR when needed.

## Desktop emphasis
- Project command center.
- Multi-site view.
- Schedule / work breakdown.
- Team and dependency planning.
- BOQ / materials / procurement visibility.
- Bulk work assignment.
- Document comparison.
- Progress review.
- Project financial view where authorized.
- Handover preparation.

## Offline behavior
Project-critical field information must be cacheable before visiting a site. A PM should be able to review site work, drawings, assigned tasks, and captured field evidence with degraded or absent connectivity where policy allows.

## Permission questions to validate
- Which financial values can PMs see today?
- Can PMs approve purchases or only request them?
- Can PMs approve overtime?
- Can PMs communicate directly with clients?
- Who approves variations?
- Who is allowed to close a work order or milestone?

## Reality validation needed
Interview/observe at least one real HILTECH project manager and map one recent project from award to handover before freezing this persona.

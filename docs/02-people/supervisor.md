# Supervisor — Persona & Work Map

Status: RESEARCHING / DESIGN HYPOTHESIS

## Core responsibility
Control execution on the ground: people, sequence, quality, materials, tool custody, evidence completeness, site blockers, and daily reporting.

## Primary home
### My Site Today
- Crew present.
- Work planned.
- Work completed.
- Blocked work.
- Tools/equipment checked out to team.
- Materials issued/used/returned.
- Open technical issues.
- Evidence awaiting review.
- Tomorrow's requirements.

## Main flow
Receive plan -> confirm crew/resources -> start site -> distribute work -> monitor execution -> capture/escalate blockers -> review completed tasks/evidence -> confirm quantities/materials -> close day.

## Key value
A supervisor should replace the informal “what happened at the site today?” phone call with a structured, live site state.

## Mobile-first capabilities
- Crew attendance/check-in context.
- Assign/reassign field task.
- Scan equipment.
- Confirm material receipt.
- Review technician evidence.
- Site photos.
- Report shortage/damage/blocker.
- Sign off daily completion.
- Request next-day resources.

## Desktop/tablet capabilities
- Daily planning.
- Crew/load view.
- Work package breakdown.
- Evidence review.
- Resource plan.
- Daily report.

## Events
- site.shift.started
- crew.assignment.changed
- material.received_on_site
- field.work.reviewed
- site.blocker.created
- daily.site.report.closed

## Questions to validate
- Does HILTECH use a formal supervisor title or equivalent role?
- Who records attendance on remote sites?
- Who accepts materials delivered to site?
- Who can mark tools damaged/lost?
- Who signs daily/weekly reports?

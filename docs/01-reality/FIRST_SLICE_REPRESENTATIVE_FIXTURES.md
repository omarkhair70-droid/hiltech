# HILTECH First-Slice Representative Reality Fixtures

Status: **INTERNAL_REPORTED / REDACTED MODEL-VALIDATION FIXTURES**
Date: 2026-09-18

## Purpose

Use real-shape HILTECH operating examples to validate that the first-slice configuration/domain contracts cover materially different work patterns.

These are:
- internal product-design fixtures,
- redacted/synthetic where client naming is unnecessary,
- not public case-study claims,
- not permanent WorkType definitions,
- not evidence that every HILTECH project follows the same rules.

They validate model shape and provide safe development/test seed examples.

---

# Fixture A — Bank HQ Data Center / Rack Installation

Source status:
**INTERNAL_REPORTED**

Reported shape:
- work at a bank head-office data-center environment,
- rack installation work,
- compact field team,
- limited tool set because the job/site did not require a large material/tool footprint,
- sensitive/restricted site context is plausible for this type of environment.

## Model coverage

Project:
- one client organization.
- one durable Site.
- ProjectSite association.
- data-center Area/Zone/Rack-room hierarchy if useful.

WorkType seed candidate:
`RACK_INSTALLATION`

AssignmentPolicy:
- small crew/user assignment supported.
- exact current crew members are seed data.

Readiness dimensions potentially expressible:
- assigned people.
- site access.
- tools.
- drawing/instruction revision.
- client/security permission.

EvidencePolicy:
Not assumed.
Exact required photos/tests/signoff remain configurable seed after validation.

Asset:
- individually tracked tool custody fits Asset model.
- checkout from Main Warehouse to crew/project/site context.

Security:
- site access/location notes can be field-restricted.

Offline:
- Job Bundle can contain safe minimum context without exposing broader client/internal data.

Result:
**PASS — current Project/Site/Work/Asset/security structure can express this work shape without a new domain type.**

---

# Fixture B — Alamein Building Cable Pulling

Source status:
**INTERNAL_REPORTED**

Reported shape:
- remote project in Alamein,
- technicians entering buildings and pulling cables,
- representative output around multiple buildings/units per day,
- reported crew can be two technicians,
- cable cartons/material can remain temporarily at the remote project/site instead of returning to Maadi daily.

## Model coverage

Project:
- one Project.
- one or more durable Sites depending actual development structure.
- Areas can represent buildings/floors/zones if useful.

ProjectSite:
- carries project-specific access/context.

WorkType seed candidate:
`CABLE_PULLING`

AssignmentPolicy:
- technician pair/crew.
- model also supports larger future team without schema change.

Work decomposition:
- repeated WorkOrders per building/area/unit/day are supported.
- not one giant project task.

Readiness:
- people.
- cable/material availability.
- access.
- drawings/instructions if applicable.
- tools.

Storage:
- MAIN_WAREHOUSE = Maadi seed location.
- PROJECT_STORAGE or SITE_STORAGE = temporary cable/material location in Alamein.
- temporary storage is not forced to become a full Warehouse.

Stock:
- cable/material can be quantity tracked.
- issue -> site storage -> consume -> return/adjust path fits stock ledger.

Field:
- site map/navigation capability.
- offline Job Bundle.
- Start/Block/Resume/Evidence/Submit queue.
- site tracking only if configured.

Result:
**PASS — current configuration + ProjectSite + SiteStorage + WorkOrder + Stock/offline model expresses the reported remote repeated-work pattern.**

---

# Fixture C — Factory Infrastructure Visit / Execution

Source status:
**INTERNAL_REPORTED**

Reported shape:
- infrastructure work at a factory/site,
- representative crew shape can include one engineer + two technicians,
- exact task/evidence specifics not yet recorded.

## Model coverage

Work assignment:
- crew/team assignment.
- lead/engineer + technicians can be represented through AssignmentPolicy and assignment records.

Review:
- engineer/supervisor/PM acceptance can be ReviewPolicy configuration rather than code.

Evidence:
- exact requirement intentionally left unseeded.
- EvidencePolicy can vary independently from Rack Installation / Cable Pulling.

Assets/material:
- same Asset/Stock/Storage model can express required tools/material.

Result:
**PASS — crew hierarchy variation does not require a new schema. Evidence/readiness details remain configuration, not architectural blockers.**

---

# Cross-fixture validation

The three examples together exercise:

- one-site sensitive infrastructure work,
- remote multi-building repeated field work,
- temporary Project/Site material storage,
- individually tracked tools,
- quantity stock/material,
- technician pair,
- engineer + technician crew,
- WorkType variability,
- Project/Site/Area context,
- offline field work,
- map/site context,
- configurable evidence/review policy.

No example requires:
- a separate app per role,
- a separate architecture,
- hard-coded staff count,
- hard-coded reviewer,
- hard-coded evidence checklist,
- a different warehouse system.

---

# Structural gaps NOT revealed by these examples

These fixtures do not prove:
- exact finance/payroll behavior,
- exact client-site legal/contract identifiers,
- exact test/evidence files for each WorkType,
- final device restrictions at sensitive sites,
- exact asset/stock master list,
- production retention/legal rules.

Those remain separate seed/policy/ops validation items.

---

# First-slice consequence

These internal examples are sufficient to treat the current first-slice domain/configuration model as **representatively covered** for:
- Project/Site/Work,
- small/variable crews,
- Main Warehouse + Project/Site storage,
- Asset/Stock,
- offline field execution.

A future reality walkthrough can still correct terminology/seed values or reveal a missing structure.

It no longer makes sense to block contract work waiting for a generic “one real project” before continuing.

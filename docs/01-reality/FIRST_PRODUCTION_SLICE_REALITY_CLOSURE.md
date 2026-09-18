# HILTECH First Production Slice — Reality Closure

Date: 2026-09-18
Status: **READY FOR VALIDATION / BLOCKING FIRST-SLICE CONTRACT FREEZE**

## Purpose

Close only the real-company facts that block the first production vertical:

PM Desktop
→ Project / Site / Work Order
→ Warehouse Asset Reservation / Checkout
→ Technician Android
→ Offline Execution / Evidence
→ Reconnect / Sync
→ Supervisor Acceptance
→ PM Authoritative Read / Audit

This does not replace the full Reality Validation Plan.

It is the **minimum reality bridge** between the now-closed technical-spike phase and exact first-slice DB/API/local/auth/file contracts.

Source material already exists in:
- `REALITY_VALIDATION_PLAN.md`
- `REALITY_VALIDATION_INTERVIEW_PACK.md`
- `interview-packs/MOHAMED_OWNER_EXECUTIVE_SESSION.md`
- `REMAINING_EXTERNAL_FACTS.md`
- `../13-delivery/FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md`

Do not turn this into a form.
Walk real examples and record evidence status.

# Configuration Rule — Important

Most answers in this document are **not code-freeze values**.

Where a real answer is legitimate operating policy, HILTECH OS must expose it as versioned configuration:
- approver/reviewer relationships,
- assignment modes,
- WorkTypes,
- readiness requirements,
- evidence requirements,
- tracking policy,
- warehouse/site storage locations,
- asset/stock categories,
- notification/escalation rules,
- delegation.

Reality validation is used to prove our configuration model can represent HILTECH and to create sensible initial seed data.

It must not hard-code today's organization into production code.

See:
`../03-product/CONFIGURABLE_OPERATING_MODEL.md`.

---

# Closure Group A — Authority / Organization

Primary source:
Mohamed + actual PM/Supervisor/Warehouse practice.

Must establish:

1. Who can create a Project?
2. Who can create a Work Order?
3. Who can assign/reassign technician work?
4. Who can cancel work?
5. Who can accept completed work?
6. When is Engineer acceptance required vs Supervisor vs PM?
7. Who may reserve an Asset?
8. Who has final authority to CheckoutAsset?
9. Who may override custody/return exceptions?
10. What can be delegated, to whom, and for how long?
11. Which of these actions require Mohamed?
12. Which actions require re-auth/high-risk confirmation?
13. What team/reporting relationships are real enough to seed first release?

Evidence level required:
- VERIFIED_INTERVIEW minimum,
- VERIFIED_DOCUMENT where a written policy/approval artifact actually exists.

Output updates:
- Object × Action Permission Matrix.
- OpenFGA first-slice relationship model.
- field-level obligations.
- Organization/Team/Delegation exact subset.
- authorization tests.

Blocking rule:
Do not freeze production authorization tuples from assumed titles such as "PM" or "Supervisor" alone.

---

# Closure Group B — One Real Project / Site

Primary source:
Project Manager + one recent/active representative project.

Walk one project from:
award/handoff
→ project setup
→ site
→ work planning
→ assignment
→ field execution
→ testing/evidence
→ acceptance
→ handover/close context.

Must establish:

1. Real Project identifier/code convention.
2. Required Project fields at creation.
3. Where project truth currently comes from.
4. Whether Project starts from Sales award/PO/contract/manual creation.
5. Real Site hierarchy:
   - one site?
   - multiple sites?
   - floors/rooms/racks/zones?
6. Whether a client site may be reused across projects.
7. Required site/access/contact fields.
8. Who becomes PM and when.
9. Real project lifecycle milestones relevant to first release.
10. What "project active/ready" actually means.
11. Which project/site fields technician needs offline.
12. Which project/site fields must never leave authorized context.

Evidence:
- one redacted project folder/index,
- project code,
- site list,
- representative report/schedule/BOQ headings,
- safe screenshot or structural notes.

Output updates:
- Project exact field subset.
- Site/Area exact field subset.
- Project/Site read models.
- first Flyway tables.
- Room job-bundle context.
- PM/Technician field-level access tests.

Blocking rule:
Do not freeze a generic Project schema from the current v0.1 object spec without one real project mapping.

---

# Closure Group C — Real Work Order / Field Day

Primary source:
PM + Engineer/Supervisor/Technician observation/interview.

Choose one real repeatable field activity.

Must establish:

## Creation / Assignment
1. What real thing maps to a WorkOrder today?
2. Who creates it?
3. What minimum instruction is required?
4. How assignment reaches technician now.
5. Single user vs team vs subcontractor assignment.
6. Priority/schedule semantics.

## Readiness
7. Which checks truly block starting:
   - people,
   - materials,
   - tool/equipment,
   - drawing/revision,
   - access,
   - dependency,
   - client permission,
   - safety/PPE,
   - other.
8. Which checks are automatic vs human confirmation.
9. Who may override/waive a blocker.

## Offline bundle
10. What must exist on device before entering weak/no-signal site?
11. How long can realistic offline periods last?
12. What Android devices/versions are actually used?
13. Company-owned vs BYOD.
14. Camera restrictions.
15. Client/site security restrictions.

## Evidence / Completion
16. Required proof by this work type:
   - photos,
   - test measurement,
   - OTDR/Fluke artifact,
   - signature,
   - checklist,
   - drawing mark-up,
   - material consumption,
   - timestamp/location,
   - other.
17. What makes submission incomplete.
18. Who reviews.
19. What rejection/rework reason must be recorded.
20. When work is actually accepted/closed.

## Conflict
21. What should technician see if PM reassigns/cancels while technician was offline?
22. Is local evidence retained for review?
23. Who decides whether any local work can still be credited/transferred?
24. What must never auto-merge?

Evidence:
- one real assignment example,
- current WhatsApp/paper/report structure,
- one redacted completion/evidence example,
- one test/report format if applicable.

Output updates:
- exact WorkOrder fields.
- exact WorkOrder states/transitions.
- exact command DTOs.
- TechnicianJobBundle schema.
- Room cache/command/evidence schema.
- conflict codes/recovery actions.
- WorkOrder OpenFGA relations.
- PM/Technician/Supervisor read models.
- representative UI contract.

Blocking rule:
SPIKE-15 proved the mechanism.
It did **not** prove which fields/evidence/readiness rules HILTECH really needs.

---

# Closure Group D — Warehouse / Asset Custody

Primary source:
physical warehouse walkthrough + person who actually issues/receives tools.

Must establish:

## Physical structure
1. Number of warehouses/storage places.
2. Zones/shelves/bins actually useful to model.
3. Who has physical access/key.
4. Whether checkout can happen outside normal warehouse process.

## Asset identity
5. Top individually tracked asset classes.
6. Existing asset codes/labels.
7. Serial-number quality.
8. Ownership types actually used:
   company/client/rented/etc.
9. Calibration-required classes.
10. Repair/warranty practices.

## Custody
11. Exact checkout steps today.
12. Required recipient/project/site/work context.
13. What proves physical handover.
14. Whether receiver confirms.
15. Return steps.
16. Condition/accessories check.
17. Missing/damaged/late process.
18. Can custody ever be transferred technician-to-technician directly?
19. Who can correct a wrong custody record?
20. Is any offline final checkout truly needed?

## Stock
21. Which items are quantity stock vs serialized assets.
22. Units of measure.
23. Existing item codes.
24. Issue/return/consumption practice.
25. Direct-to-site receiving.
26. Stocktake/adjustment authority.

Minimum sample fixture:
- 10 real-shape synthetic/redacted assets,
- 10 stock items,
- 3 checkout examples,
- 2 return examples,
- 1 calibration/damage case.

Output updates:
- exact Asset subset.
- Warehouse/StorageLocation subset.
- AssetMovement fields.
- Reservation/Checkout/Return commands.
- custody invariants.
- first-slice OpenFGA/policy tests.
- WarehouseCheckout UI contract.
- realistic test fixture.

Blocking rule:
Do not freeze Warehouse tables from guessed categories or units.

---

# Closure Group E — Device / Operational Reality

Primary source:
one PM/office Windows machine + one field Android + warehouse device.

Record only non-secret operational facts:

- Windows version / hardware class.
- Android version / hardware class.
- storage/RAM constraints if relevant.
- camera/QR capability.
- company-owned/BYOD.
- connectivity pattern.
- VPN/proxy/firewall restrictions if any.
- warehouse scanner/label printer availability.
- expected app update/distribution method.
- whether office IT can centrally install/update Windows software.

Output:
- production min/device support matrix.
- Windows distribution choice input.
- offline cache/evidence size budget.
- camera/QR fallback rules.
- deployment runbook inputs.

This validates operations; it does not reopen KMP/WorkManager/Windows packaging feasibility.

---

# Closure Group F — First-Slice Data Import / Existing Source

For Project, Site, Work, Asset and Warehouse, identify:

- current source file/system,
- current owner,
- export format,
- unique/human codes,
- duplicates/missing data,
- whether import is required for pilot,
- whether first pilot can start from clean manually verified seed.

Output:
- external ID map.
- migration/import plan.
- synthetic fixture structure.
- data-cleaning backlog.

Do not design a migration before knowing the source.

---

# Evidence Status Rules

Every captured fact must be one of:
- VERIFIED_DOCUMENT
- VERIFIED_OBSERVATION
- VERIFIED_INTERVIEW
- INTERNAL_REPORTED
- PUBLIC_VERIFIED
- ASSUMPTION
- UNKNOWN

First-slice schema/authorization policy may depend on VERIFIED_* facts.

INTERNAL_REPORTED can guide preparation but must not silently become frozen truth where a contradiction would change:
- data shape,
- authority,
- lifecycle,
- offline behavior,
- integration,
- security.

---

# Minimum Pass To Start Exact Contract Freeze

The first-slice reality pass is sufficient when all are true:

- [ ] authority for Work/Asset actions is known,
- [ ] one real Project/Site is mapped,
- [ ] one representative field WorkOrder is mapped,
- [ ] readiness/evidence/acceptance rules for that work type are known,
- [ ] field offline/device restrictions are known,
- [ ] warehouse physical/custody process is observed,
- [ ] real asset/stock categories needed by pilot are known,
- [ ] first pilot data source/import path is known,
- [ ] contradictions against current docs are recorded,
- [ ] affected object/transition/permission specs are updated.

Then freeze, in order:
1. identity/authority subset,
2. Project/Site subset,
3. WorkOrder contract,
4. Asset/Warehouse custody subset,
5. evidence metadata/policy,
6. authorization obligations,
7. API/read models,
8. PostgreSQL/Flyway/jOOQ schema,
9. Room/local schema,
10. UI contracts,
11. contract/security/offline tests.

---

# Not Required For First-Slice Contract Freeze

Unless the selected pilot directly depends on them, the following do not block this first vertical contract pass:

- full payroll design,
- full Finance schema,
- bank integration,
- full Sales/Tender implementation,
- Client Portal,
- managed service/NOC,
- CCTV remote control.

They still remain blockers for their own affected domains and for any broader company-wide freeze claims.

---

# Current State

Technical feasibility: **CLOSED / PROVEN**.

Reality closure: **PARTIALLY CLOSED BY INTERNAL REPORT**.

Already known enough to stop treating as unknown:
- HILTECH is a small field-oriented organization, not a deep enterprise hierarchy.
- main tool storage is at the Maadi HQ.
- tool count is moderate/small relative to enterprise warehouse scale, but custody value is high.
- remote projects can hold temporary Project/Site stock such as cable cartons.
- field crews are small and may be engineer + technicians or technicians directly.
- Work can repeat by building/unit/day.
- first-slice design should support Main Warehouse + Project/Site temporary storage + Field Crew + WorkOrder.

Still required before first-slice freeze:
- the **configuration schemas** must be able to express authority/exception rules safely,
- one representative real job must prove the Project/WorkOrder model is not missing structural data,
- WorkType/Readiness/Evidence/Review policies must be typed/versioned/configurable,
- Asset/Stock/Storage configuration must support the pilot without hard-coded categories,
- device/site restrictions that materially change offline/security architecture must be known.

Initial values such as who currently approves, which evidence is required for a particular WorkType, current staff count, and current storage locations are seed/configuration data unless they expose a missing structural capability.

Exact first-slice contracts: **CAN ADVANCE NOW. REALITY IS A MODEL-VALIDATION + SEEDING INPUT, NOT A REQUIREMENT TO HARD-CODE TODAY'S COMPANY.**

Production code: **DO NOT START YET**.

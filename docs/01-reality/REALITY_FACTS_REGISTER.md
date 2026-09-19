# HILTECH Reality Facts Register

Status: **ACTIVE / PHASED REALITY REGISTER**
Updated: 2026-09-19

## Purpose

Keep real-company facts separate from assumptions.

Phase-queued learnings extracted from private operational files are tracked separately in:
`REALITY_EVIDENCE_REGISTER.md`.

Raw source handling policy:
`RAW_EVIDENCE_POLICY.md`.

Every important product/architecture statement that depends on HILTECH reality should be traceable to one of:

- VERIFIED_DOCUMENT
- VERIFIED_OBSERVATION
- VERIFIED_INTERVIEW
- INTERNAL_REPORTED
- PUBLIC_VERIFIED
- ASSUMPTION
- UNKNOWN

INTERNAL_REPORTED means a trusted internal report was relayed to the product work, but the team has not independently inspected the source/process yet.

---

# Confirmed / Reported Facts

## RF-001 — Internal operating system is the priority
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
Mohamed prioritizes HILTECH's internal operating system — Mohamed/Ahmed/warehouse/field/company operations — over an external client-facing portal.

Product consequence:
- implementation order remains internal-first,
- external Client Portal remains last,
- client business objects may exist earlier because internal workflows need them,
- client-facing polish must not delay Ahmed/warehouse/field/company core.

Repository effects:
- docs/13-delivery/IMPLEMENTATION_ORDER.md
- docs/13-delivery/PRE_CODE_ENDGAME.md

Validation still useful:
Direct Mohamed session should confirm whether any external client action is operationally mandatory earlier than the full Client Portal.

---

## RF-002 — Employee advances must be first-class
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
Employee financial advances / سلف are a real internal finance requirement and must not be omitted.

Product consequence:
Advance is a first-class object/workflow, not a payroll free-text deduction.

Current model now covers:
- request,
- approval,
- issue/funding,
- settlement,
- payroll deduction linkage when explicitly approved,
- cash return / expense offset / mixed settlement,
- outstanding balance,
- closure.

Validation required with Ahmed:
- actual request source,
- approval chain,
- deduction rules,
- settlement deadlines,
- legal/accounting treatment,
- whether multiple concurrent advances are permitted.

---

## RF-003 — Financial imprest / cash custody must be first-class
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
Financial imprest / عهدة مالية is a real internal finance requirement and must not be omitted.

Product consequence:
FinancialImprest is modeled separately from:
- salary/payroll,
- employee Advance,
- expense reimbursement,
- physical Asset custody.

Current model now covers:
- custodian,
- funding,
- append-only spend ledger,
- receipts/evidence,
- replenishment,
- settlement version,
- cash return,
- shortage/overage,
- clearance/closure.

Validation required with Ahmed:
- fixed vs temporary vs revolving imprest,
- who can hold one,
- concurrent imprests,
- funding method,
- receipt requirements,
- replenishment process,
- settlement cadence,
- shortage/overage handling,
- offboarding clearance.

---

## RF-004 — Current HILTECH staffing is a compact field-oriented operating snapshot
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported current snapshot:
- Mohamed and Ahmed are central office/management figures,
- there is another Mohamed in the company,
- the engineering layer is currently reported at approximately two engineers,
- field execution includes technicians and small crews.

Important interpretation:
This is **current operating data, not a product-size constraint**.

Product consequence:
- the system must work cleanly for today's compact team,
- but Organization / Team / Role / Project / Work / Warehouse models must scale without redesign as HILTECH hires, wins more projects, adds partners/agencies, creates more teams/locations, or changes reporting structure,
- Work/Project assignment supports individuals, crews, teams, subcontractors and future structures through data/configuration,
- authorization models relationships and authority rather than assuming either a tiny flat company or a deep enterprise hierarchy.

Validation note:
Exact current staff count/reporting lines are seed/configuration data and may change without changing the core architecture.

---

## RF-005 — Main warehouse/storage is at the Maadi headquarters
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
- the company headquarters in Maadi contains the main tool/equipment storage area,
- the storage area is neither tiny nor a large distribution warehouse,
- the number of tools is not extremely large,
- many tools are high-value and therefore custody/history matters more than inventory scale.

Product consequence:
- the initial Warehouse experience should make high-value custody and fast issue/return excellent for current operations,
- the domain model must still support additional warehouses, project/site stores, locations, stock classes and higher volume without redesign,
- serialized Asset identity and custody are first-class; larger stock/logistics scale remains supported by the same warehouse/location/stock model.

---

## RF-006 — Remote projects can have temporary site stock/storage
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported reality:
For remote work such as Alamein, cable cartons/material may be stored temporarily inside/near the project buildings rather than returning to Maadi every day.

Product consequence:
- distinguish Main Warehouse from temporary Project/Site storage,
- SiteStock/ProjectStorage must be representable without pretending every temporary location is a full Warehouse,
- material location/custody must remain tied to Project/Site context,
- direct project consumption/return must be possible.

---

## RF-007 — Current field work uses compact crews; assignment model must scale
Status: **INTERNAL_REPORTED**
Date: 2026-09-18

Reported examples:
- data-center work at a bank head-office site included rack installation with a small field team and a limited tool set,
- Alamein work included cable pulling through residential/building areas with approximately two technicians working through multiple buildings per day,
- another reported job involved a factory/site for an energy-drink manufacturer,
- a common field shape can be one engineer plus two technicians, while some work can be handled by technicians directly.

Product consequence:
- WorkOrder supports individual, crew, team and subcontractor assignment rather than assuming one permanent crew size,
- current compact crews are initial operating data,
- Project/Site/Area/Building context matters,
- daily production can be expressed as repeated WorkOrders/units of work rather than one giant task,
- offline/site navigation and field execution are first-class,
- evidence/readiness rules vary by WorkType,
- future growth in crew count, project count or organizational structure must not require domain redesign.

---


## RF-008 — Reducing routine dependence on Mohamed is a core operating outcome
Status: **INTERNAL_REPORTED**
Date: 2026-09-19

Reported reality:
- Mohamed is the owner and a central company decision-maker,
- a large amount of follow-up, calls, people/site coordination and status chasing currently concentrates on him,
- one core purpose of HILTECH OS is to reduce the routine coordination that requires Mohamed personally while preserving the decisions/authority that genuinely belong to him.

Product consequence:
- work/status/exception/approval surfaces should make pending state and ownership visible without requiring repeated calls,
- automation/configuration should remove chasing, not silently transfer Mohamed's decision authority,
- this is an internal operating-system objective, not a reason to prioritize a Client Portal.

Validation still useful:
- which decisions Mohamed must personally retain,
- which follow-ups can be delegated/configured,
- which summaries/exceptions he wants surfaced proactively.

---

## RF-009 — Daily tool issue/return is currently person-mediated and WhatsApp/photo-assisted
Status: **INTERNAL_REPORTED**
Date: 2026-09-19

Reported reality:
- Osama is the reported day-to-day handler of the Maadi tool/equipment storage movement,
- a common flow is: person arrives for tools → Osama photographs the item(s) → update/photo is sent through WhatsApp / to Ahmed → item leaves → it is expected back after the work/day according to the real case,
- the storage space itself is modest, but some equipment is high-value, so custody/history matters more than warehouse scale.

Product consequence:
- issue/return must answer who took what, for which Project/Site/Work context, when it left, whether/when it returned, and condition/evidence,
- photos remain useful Evidence,
- WhatsApp must not remain the authoritative custody database,
- initial UX should be fast enough for a small real store rather than a distribution-center workflow.

Validation still useful:
- exact return timing rules/exceptions,
- whether Ahmed must acknowledge every issue/return,
- damaged/missing/tool-service handling,
- Osama's exact role/authority and backup coverage.

---

## RF-015 — Finance/admin disbursement reality involves Ahmed Fawzy and Dr. Mohamed, but terminology/authority is phase-local
Status: **INTERNAL_REPORTED / PARTIALLY OPEN**
Date: 2026-09-19

Reported reality:
- Ahmed Fawzy is central to accounts/admin work, bank errands, worker payments and work-related cash movement,
- Dr. Mohamed also participates with Ahmed in some employee payment/disbursement and administrative/financial operations,
- operational money concepts reported include salary, employee advance, financial imprest/custody, Pocket Money/site allowance/expense terminology, and employee-paid expense reimbursement.

Product consequence:
- do not collapse these concepts into one generic payment,
- do not assume accounting/legal meaning from colloquial terminology,
- current Excel/files and the actual Ahmed/Mohamed process are evidence to reconcile when Finance/Payroll phases arrive,
- exact authority, naming, settlement and accounting boundaries remain phase-local validation work.

---

## RF-016 — Field documentation must support fast contextual capture before formal transitions
Status: **INTERNAL_REPORTED**
Date: 2026-09-19

Reported reality:
- technicians/engineers need to report what happened at a site quickly,
- useful capture includes text, photo, file, voice note, problem/update/completion and mentions linked to the relevant context,
- not every field observation should require a heavy form.

Product consequence:
- preserve low-friction contextual notes/evidence,
- use structured/versioned/audited commands when a formal state changes: custody, issue/return, acceptance, approval, payment, completion, etc.,
- do not infer a full person-to-person chat product from this requirement; current scope remains Inbox/comments/mentions/contextual voice-text notes/evidence unless a separate chat product is later approved.

---

## RF-017 — Arabic-first is an operating reality, not only a visual preference
Status: **INTERNAL_REPORTED**
Date: 2026-09-19

Reported reality:
Internal HILTECH use is primarily Egyptian Arabic / Arabic.

Product consequence:
- the already-frozen `Arabic Is Native` design doctrine remains operationally justified,
- Arabic/RTL/Bidi must be tested in real product flows,
- codes, serials, IPs, model names and other Latin technical tokens must remain readable inside Arabic UI.

---

## RF-018 — Location/tracking may help active field work but is not an all-day surveillance assumption
Status: **INTERNAL_REPORTED / CONDITIONAL**
Date: 2026-09-19

Reported reality:
Map/navigation/check-in or active-task tracking may have value for remote/field work if the real workflow needs it.

Product consequence:
- tracking remains policy/configuration controlled,
- do not assume continuous employee monitoring,
- validate purpose, time window, consent/policy, device reality and client/site restrictions before enabling any location mode.

---

## RF-019 — Roadmap integrations are not evidence of current HILTECH deployment
Status: **INTERNAL_REPORTED / CONDITIONAL**
Date: 2026-09-19

Not confirmed as current operating reality:
- CCTV/NVR integration,
- access-control/door integration,
- NOC / Managed Service operation,
- GPS/IoT integration,
- any specific bank API,
- any specific accounting-system integration.

Product consequence:
Keep these as conditional/future capability points until the relevant phase validates an actual HILTECH need and authoritative system boundary. Roadmap presence must never be interpreted as a current implementation commitment.

---

## RF-020 — Current approval/operational authority baseline is owner-final with Finance/Admin preparation, but detailed policy is still open
Status: **INTERNAL_REPORTED / PARTIALLY OPEN**
Date: 2026-09-19

User-confirmed current operating facts:
- Mohamed is the company owner and current final decision-maker.
- Ahmed Fawzy is responsible for accounts / finance-admin operational work.
- Ahmed coordinates employee/technician operational administration including work-related money, custody/equipment and related handoffs.
- field staff document execution, expenses and materials, then return that information/state to Ahmed for review/settlement.
- Mohamed sees the broader company picture and intervenes where a decision requires owner authority.
- the product must keep these rules configurable/relationship-driven rather than hard-coding named people because HILTECH is growing and authority/personnel can change.

Not yet user-confirmed:
- exact subject-by-subject approval chains,
- numeric thresholds or an explicit statement that a subject has no threshold,
- requester/preparer self-approval rules,
- delegation/absence rules,
- emergency approval paths,
- exact reject/request-change/cancel/expiry semantics,
- bank/payment execution authority.

Product consequence:
- this baseline can seed the Approval reality map,
- it does **not** authorize production routing such as "Ahmed then Mohamed",
- owner-final and Finance/Admin preparation must be converted to typed relationships/policy only after subject-specific reality is validated,
- payment execution remains a separate unknown authority boundary.

Consume in:
- Phase 2 Slice 04 Approval Engine Authority Reality Closure,
- later Finance/Payroll/Procurement slices for subject-specific policy.

---

# High-Priority Unknowns

## RF-010 — Payroll source of truth
Status: **UNKNOWN**
Need:
- current employee master source,
- payroll sheet/system,
- component calculation path,
- approval path,
- bank/payment handoff.

Owner for validation: Ahmed.

---

## RF-011 — Accounting / e-invoice system
Status: **UNKNOWN**
Need:
- accounting software name/version,
- e-invoice/e-receipt tools,
- export/import/API capability,
- authoritative accounting boundary.

Owner for validation: Ahmed/accountant.

---

## RF-012 — Bank execution reality
Status: **UNKNOWN**
Need:
- bank(s),
- portal/manual/bulk file/API path,
- maker/checker flow,
- payment result/reconciliation sources.

Do not store credentials or account secrets in GitHub.

Owner for validation: Ahmed/Mohamed.

---

## RF-013 — Warehouse physical-control reality
Status: **INTERNAL_REPORTED / PARTIALLY OPEN**
Known:
- main storage is at the Maadi headquarters,
- inventory is not huge by count,
- tools/equipment can be high-value,
- remote projects may hold temporary project/site stock.

Still useful to validate:
- Osama's exact authority/backup coverage,
- exact issue/return acknowledgement and exception rules,
- calibration-required tool classes,
- current records/labels,
- physical access/cameras,
- whether any final checkout needs offline capability.

Owner for validation: Warehouse + Mohamed.

---

## RF-014 — Real field device/site restrictions
Status: **UNKNOWN**
Need:
- Android versions/devices,
- BYOD/company-owned,
- camera restrictions,
- connectivity,
- offline periods,
- client/security restrictions.

Owner for validation: Engineer/supervisor/technician.

---

# Register Rule

If a freeze decision materially depends on an UNKNOWN or ASSUMPTION, either:
1. validate it,
2. scope around it explicitly,
3. or record formal risk acceptance.

Never silently convert INTERNAL_REPORTED into VERIFIED_DOCUMENT/OBSERVATION.
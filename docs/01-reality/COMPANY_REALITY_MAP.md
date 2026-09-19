# HILTECH — Company Reality Map

Status: `RESEARCHING`

This document describes HILTECH as it actually operates today. It must eventually be grounded in internal company documents, staff interviews, current systems, project evidence, and approved external references.

## Evidence labels

- `INTERNAL-VERIFIED` — supported by internal company evidence.
- `EXTERNAL-VERIFIED` — supported by reliable public evidence.
- `INTERNAL-UNVERIFIED` — reported by owner/family/staff but evidence not yet added to the repository.
- `HYPOTHESIS` — product/research assumption to test.

## Current high-level reality

### Existing company, not a startup

`INTERNAL-UNVERIFIED`

HILTECH is an existing operating infrastructure/telecommunications company with a physical office in Maadi and real field operations.

The digital transformation project is therefore not about inventing company credibility from scratch. It is about making the company’s actual operational capability visible, controllable, scalable, repeatable, and less dependent on manual coordination.

### Owner

`INTERNAL-UNVERIFIED`

Mohamed is the owner and a central operational/management decision-maker. The company has been built through hands-on effort, field execution, relationships, and direct management.

A major transformation objective is to reduce the amount of routine work and information-chasing that requires Mohamed personally.

### Finance / administration

`INTERNAL-UNVERIFIED`

Ahmed Fawzy works closely under/with Mohamed and is responsible for important finance/admin/accounting work. Exact responsibility boundaries still need direct mapping.

### Operational footprint

`INTERNAL-UNVERIFIED`

Reported company experience includes work across multiple Egyptian governorates and significant infrastructure projects.

Reported examples that require internal documentation before being used as public claims:

- projects involving courts / cybercrime-related court infrastructure across Egypt,
- data-center / infrastructure work involving major Egyptian banks,
- work at National Bank of Egypt-related locations,
- work at Banque Misr headquarters,
- subcontracted execution under larger companies,
- past/current work involving companies such as SABA and other contractors/integrators.

These examples must be separated into:

- direct HILTECH contract,
- subcontracted scope,
- employee/field participation,
- client name usage permitted or restricted,
- public case-study eligibility.

### Subcontracting reality

`INTERNAL-UNVERIFIED`

HILTECH may execute substantial real work as a subcontractor under larger firms. This is strategically important.

Research questions:

- What share of revenue/projects are direct vs subcontracted?
- Which scopes does HILTECH perform better than its public brand currently communicates?
- Where can HILTECH move from hidden execution partner to direct prime technology partner?
- Which partner relationships should remain protected rather than displaced?

## Physical operations

### Warehouse

`INTERNAL-UNVERIFIED`

HILTECH has a main tool/equipment storage area at the Maadi headquarters. Reported inventory is not extremely large by item count, but individual tools/equipment can be expensive enough that custody and history matter materially.

Reported current pain:

- access depends heavily on physical key custody,
- warehouse entry/exit may not be systematically logged,
- people can enter/leave with weak digital accountability,
- asset custody can rely on memory/manual coordination,
- equipment responsibility/history is not sufficiently visible.

This makes warehouse/asset custody a core transformation area. The initial UX should fit today's reported inventory and high-value custody needs, while the underlying Warehouse / StorageLocation / Stock / Asset model remains capable of supporting more locations, inventory and operational volume as HILTECH grows.

Remote projects may also create temporary Project/Site storage for material such as cable cartons. These locations should be modeled as project/site stock or temporary storage, not automatically as full warehouses.

### Field operations

`INTERNAL-UNVERIFIED`

HILTECH performs real on-site infrastructure work. Product design must therefore account for:

- unreliable site connectivity,
- physical evidence,
- tools/material movement,
- field teams and supervisors,
- project/site context,
- testing/certification outputs,
- handover,
- customer approvals,
- travel between sites/governorates.

### Current field-crew operating snapshot

`INTERNAL-UNVERIFIED`

Reported current operating shape:
- a compact management/engineering group,
- approximately two engineers at the current snapshot,
- technician-heavy field execution,
- common crews such as one engineer plus two technicians,
- some work performed directly by technician pairs,
- daily field output can be repeated building/unit work rather than one large monolithic task.

This is not a scale assumption. HILTECH is growing and the product must support additional people, teams, projects, partners, agencies/authorized relationships, warehouses/storage locations and clients without redesign.

Reported examples include:
- data-center rack installation,
- cable pulling across buildings in Alamein,
- factory/site infrastructure work.

Product consequence:
Work assignment, map/site context, offline execution, evidence, tools and temporary site material must work well for today's crews while remaining data/configuration-driven for larger future teams and structures.

## Current digital gap

`HYPOTHESIS based on internal report`

The company’s real-world execution capability is materially stronger than its current unified digital operating capability.

Likely symptoms to verify:

- operational knowledge concentrated in people,
- fragmented communication,
- WhatsApp/phone dependence,
- spreadsheets/paper/manual follow-up,
- limited unified asset/warehouse history,
- limited unified project/client visibility,
- duplicated data entry,
- management time spent asking for status,
- digital brand/site not fully representing operational depth.

## Transformation target

HILTECH OS should turn real company operations into a connected operating system across:

- company control,
- people,
- projects/sites/field work,
- finance/payroll,
- warehouse/assets,
- procurement,
- clients/commercial work,
- documents/knowledge,
- security/facilities,
- integrations and automation.

The target is not “ERP installation.” The target is a HILTECH-native operating model.

## Evidence required next

- legal/company identity documents relevant to operations,
- current organization chart or staff list,
- role/responsibility list,
- service list,
- project list and project classifications,
- sample BOQ,
- sample quotation,
- sample purchase request/order,
- sample invoice flow,
- sample payroll flow,
- sample handover/test report,
- warehouse inventory sample,
- current asset/tool list,
- current accounting/payroll tools,
- current bank/payment process,
- current CCTV/NVR/access systems,
- current project communication/reporting tools.

Sensitive values/client information can be redacted. We need workflow shape before confidential content.


---

## 2026-09-19 internal operating alignment

Evidence level: `INTERNAL-UNVERIFIED` / `INTERNAL_REPORTED`

This alignment does not replace the phased architecture. It clarifies how later business slices should be grounded:

- Mohamed remains a central owner/decision-maker; a primary OS outcome is reducing routine chasing/calls/follow-up that depend on him personally.
- Ahmed Fawzy is central to accounts/admin/bank errands and reported work-related cash movement; Dr. Mohamed also participates in some payment/admin operations. Exact authority and accounting terminology remain validation work.
- Osama is reported as the daily tool-storage handler. Current issue/return can involve photographing equipment and sending updates via WhatsApp/Ahmed. The future system should retain useful photo Evidence but own custody truth itself.
- Field staff need low-friction contextual text/photo/file/voice updates as well as formal structured transitions when an official business state changes.
- Internal usage is Arabic-first; technical Latin tokens must remain correctly isolated/readable in RTL UI.
- Location/tracking is conditional and policy-controlled around active work, not assumed continuous surveillance.
- CCTV/NVR, access control, NOC, GPS/IoT, a specific bank API, and a specific accounting system remain conditional/unknown capabilities rather than current HILTECH facts.

Operating rule for every later business slice:
1. identify who actually performs the work today,
2. identify the current substitute/process,
3. use reality to validate terminology/configuration/flow,
4. preserve conditional capabilities as conditional,
5. record new evidence/facts rather than silently turning hypotheses into mandatory features.

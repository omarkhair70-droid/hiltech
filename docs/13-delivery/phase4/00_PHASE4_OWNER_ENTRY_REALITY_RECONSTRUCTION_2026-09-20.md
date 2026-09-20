# Phase 4 — Projects / Sites / Work Core — Owner-Facing Reality Reconstruction

Date: 2026-09-20  
Status: **OWNER REVIEWED / ACCEPTED / ENTRY REALITY CLOSED**

## Purpose

Reconstruct Phase 4 from the repository itself before asking the owner to restate HILTECH from memory.

This is intentionally **not** a blank-sheet discovery exercise.

The repository already contains:
- HILTECH reality evidence;
- Project/Site/Work lifecycle and object contracts;
- People/Identity/Team/authorization truth from Phase 1–3;
- Approval, Work Queue, Inbox, Notification and Evidence foundations from Phase 2;
- accepted first-slice Project/Site/Work contracts and architecture spikes.

The purpose of this review is to answer:

1. what the system already knows;
2. what the system should decide automatically;
3. what should be routed to an authorized human;
4. what is merely a later-domain dependency;
5. what is a genuine remaining HILTECH reality unknown.

No Phase 4 production code is authorized by this draft.

---

# 1. Canonical repository inputs reviewed

Program / ownership:
- `AGENTS.md`
- `docs/00-program/HILTECH_PRODUCT_OWNERSHIP_PRINCIPLES.md`
- `docs/00-program/CURRENT_PROGRAM_STATUS.md`
- `docs/13-delivery/IMPLEMENTATION_ORDER.md`
- `docs/13-delivery/phase3/07_PHASE3_FINAL_GAP_REVIEW_2026-09-20.md`

Project / Work:
- `docs/05-workflows/PROJECT_LIFECYCLE.md`
- `docs/06-data/object-specs/PROJECT_SITE_WORK_OBJECTS.md`
- `docs/06-data/transition-tables/PROJECT_AND_WORK_TRANSITIONS.md`
- `docs/03-product/role-experiences/PROJECT_MANAGER_EXPERIENCE.md`
- `docs/10-design/wireflows/PROJECT_CONTROL_WIREFLOW.md`
- `docs/13-delivery/first-slice-contract-pack/11_PROJECT_SITE_WORK_CONTRACT.md`
- `docs/13-delivery/FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md`
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`

Relevant durable reality:
- RE-001 — work/day context can later feed Pocket Money eligibility.
- RE-004 — measured/commercial progress is distinct from operational Project progress.
- RE-006 — owner coordination load is a primary problem; management surfaces should expose exceptions/ownership instead of requiring Mohamed to chase status.
- RE-009 — field capture must feel fast and contextual while formal transitions remain typed/audited.
- RE-010 — Arabic-first; active-task tracking is conditional, not all-day surveillance.

External reference check:
- Microsoft Dynamics 365 Field Service work-order lifecycle / architecture / mobile scheduling.
- Oracle Fusion Cloud Project Management project tasks, resources, dependencies and work-plan templates.

These references support the existing HILTECH separation between project planning, executable work, assignment/resource booking, field execution and review. They are references, not product requirements.

Official references:
- https://learn.microsoft.com/en-us/dynamics365/field-service/work-order-status-booking-status
- https://learn.microsoft.com/en-us/dynamics365/field-service/field-service-architecture
- https://learn.microsoft.com/en-us/dynamics365/field-service/create-work-order
- https://docs.oracle.com/en/cloud/saas/project-management/26b/oapem/using-project-execution-management.pdf
- https://docs.oracle.com/en/cloud/saas/project-management/26b/fapap/api-project-plans-tasks.html

---

# 2. What Phase 4 already means in HILTECH

Phase 4 is the first phase where HILTECH moves from:

`who works here / who can do what`

to:

`what work exists / where / for which project / who owns the next action / what is blocking delivery`.

The current repository model is:

`Project -> ProjectSite/Site -> Milestone / WorkPackage -> WorkOrder -> optional Task`

with first-class:
- WorkAssignment;
- Readiness requirements;
- Blockers;
- Review/rework history;
- policy/configuration binding;
- derived Project progress/health.

This is not a generic task manager.

It is the operational truth that later Warehouse, Field/Offline, Procurement, Finance and Client surfaces consume.

---

# 3. Canonical object meaning

## Project

Project is the canonical delivery engagement.

It may eventually be created from won commercial work without manual re-entry, or through an authorized internal-project path where policy allows.

Project owns:
- project identity/code;
- client relationship;
- lifecycle;
- PM relationship;
- planned dates;
- baseline version;
- sites/work;
- derived health/progress;
- restricted commercial references where permitted.

The Project does **not** own raw employee role truth, warehouse custody or payroll truth.

---

## Site

Site is a durable client/physical location.

It survives one project.

A later Project, Warranty, Support or Maintenance relationship may reuse the same Site.

Therefore:

`Site != Project`

and

`Site != one temporary project address row`.

---

## ProjectSite

ProjectSite is the Project-specific relationship to a durable Site.

It owns project-specific:
- access instructions;
- project contacts;
- project notes;
- participation lifecycle.

Closing ProjectSite does not delete Site.

---

## Area / Room / Zone

Optional physical hierarchy below Site.

Examples may later include:
- floor;
- room;
- rack room;
- zone.

It is optional context, not a mandatory enterprise hierarchy for every job.

---

## Milestone

Milestone is a project checkpoint/date/acceptance concept.

It is not the executable field job.

Milestone may group or measure delivery state and may later relate to commercial/billing milestones through explicit boundaries.

Operational project progress remains derived from accepted included WorkOrders rather than arbitrary manual percent.

---

## WorkPackage

WorkPackage is a planning/grouping unit.

It may group work by:
- technical scope;
- site;
- milestone;
- delivery package;
- owner/team.

It is not itself the technician job unless a future policy explicitly collapses a simple case.

---

## WorkOrder

WorkOrder is the primary executable unit.

It says:
- what needs to be done;
- where;
- under which Project/Site/Area;
- with which instructions/policy revision;
- what readiness requirements exist;
- who is assigned;
- what evidence/review is required;
- what lifecycle state the work is in.

This is the object that eventually becomes the technician's job bundle.

---

## Task

Task is optional finer-grained work under a WorkOrder.

It must not be mandatory bureaucracy for every WorkOrder.

A simple job can remain one WorkOrder.

A complex job can materialize checklist/task instances when the bound WorkType/Checklist policy requires them.

---

# 4. The central correction: the system should route work, not hard-code people

HILTECH must not encode:

`if project -> ask Mohamed`

or:

`if work -> send to Ahmed`.

Mohamed/Ahmed/current staff names are current company reality, not architecture.

The system operates from:
- People / Employment / WorkforceAssignment truth from Phase 3;
- Team membership / OpenFGA authority from Phase 1;
- Project relationships;
- WorkAssignment records;
- AssignmentPolicy;
- ReadinessPolicy;
- ReviewPolicy;
- Approval policy;
- Work Queue / Inbox;
- explicit delegation.

The durable product question is:

`which current authority/eligible target owns the next action?`

not:

`which named person did Omar tell us about in 2026?`

---

# 5. Reconstructed end-to-end operational flow

## Step A — Project enters delivery

Long-term preferred path:

`WON commercial truth -> CreateProjectFromAward`

without retyping client/scope/sites/commitments.

Phase 10 owns the full commercial source later.

For Phase 4 itself, an authorized `CreateInternalProject` / project seed path may exist only if needed to operate before Phase 10.

The Project receives:
- client;
- code/name;
- known Site relationships;
- PM/owner relation;
- initial baseline version;
- planned dates/context.

No system code assumes Mohamed is always the PM.

---

## Step B — Project kickoff and planning

Project lifecycle already models:

`DRAFT -> KICKOFF -> PLANNING -> READY -> ACTIVE`

Kickoff confirms enough structured truth to plan:
- scope;
- sites;
- responsible authority;
- major constraints;
- team context;
- work decomposition.

The system should create/rout pending kickoff actions through the existing Work Queue where a real actionable source object exists.

Routine completion follows policy.

Exceptions/approval-required decisions route to current authority.

---

## Step C — Work decomposition

The planning surface creates the delivery tree:

`Project -> Site/ProjectSite -> WorkPackage -> WorkOrder`

Milestone can provide schedule/acceptance checkpoints.

Task/checklist remains optional under WorkOrder.

The system should make templates/configuration reusable through WorkTypeDefinition and versioned policy bindings.

A repeated type of HILTECH job should not require a PM to rebuild the same checklist/readiness/evidence/review logic from zero.

---

## Step D — Readiness is calculated, not remembered

A WorkOrder has two independent dimensions:

### lifecycle
`DRAFT / PLANNED / ASSIGNED / IN_PROGRESS / BLOCKED / SUBMITTED_FOR_REVIEW / REWORK_REQUIRED / ACCEPTED / CLOSED / CANCELLED`

### readiness
`NOT_EVALUATED / READY / BLOCKED`

Readiness is derived from requirement state.

Candidate requirement families already exist:
- people/eligibility;
- material;
- equipment;
- document/drawing revision;
- access;
- dependency;
- configured work-type extensions.

A PM should not need to call five people merely to discover why work cannot start.

The Project/Work view should say:

`BLOCKED — MATERIAL`

or:

`BLOCKED — ACCESS + DRAWING`

and each reason opens the authoritative source/action.

---

# 6. Assignment: what the system decides vs what a human decides

## System responsibility

The system can evaluate:
- whether an Employee is current/active;
- Team/relationship context;
- WorkAssignment eligibility rules;
- required certification/skills when configured;
- current authorization;
- Project/Site context;
- readiness blockers;
- assignment conflicts when the relevant scheduling policy exists.

The system should produce an **eligible target set** and explain why someone is or is not eligible.

## Policy responsibility

AssignmentPolicy decides whether a WorkType permits:
- direct automatic assignment;
- auto-suggest + authorized human confirmation;
- manual selection from eligible targets;
- Team/Crew assignment;
- subcontractor assignment.

The mechanism should be configurable.

The repository does **not** currently prove that HILTECH wants a global auto-dispatch algorithm for all work.

Therefore Phase 4 must not invent one.

## Human responsibility

A human acts only where policy requires judgment:
- choosing among several equally eligible people/teams;
- approving an override/waiver;
- accepting risk;
- handling a conflict/exception.

That action routes to the current authorized user/relationship, not a hard-coded name.

---

# 7. What Mohamed should see

RE-006 says owner coordination load is a real HILTECH problem.

Mohamed's useful surface is therefore exception-driven:

- projects needing owner/management decision;
- severe blockers;
- overdue decisions;
- client action stuck;
- unusual risk;
- delegated approval awaiting action;
- Project health reasons;
- work with no eligible owner/assignee.

He should not be forced to:
- manually redistribute every normal task;
- call employees for routine status;
- maintain a shadow spreadsheet;
- enter progress percentages already derivable from accepted work.

If Mohamed is not the authority for an action, it should not appear merely because he is the owner.

---

# 8. What PM / supervisor should see

Canonical PM thesis already exists:

**PM can answer “what is blocking delivery?” without calling five people.**

Desktop Command Center should prioritize:

- Project health + exact reason;
- accepted-work progress;
- next milestone;
- Waiting On;
- open blockers;
- work readiness;
- assignment gaps;
- review/rework queue;
- client action;
- later Phase 5 material/equipment signals;
- later commercial/finance signals only when authorized.

Supervisor/reviewer should receive Work Queue actions generated from source-domain work:

`WorkOrder SUBMITTED_FOR_REVIEW -> review action`

The Work Queue is not a second WorkOrder truth.

---

# 9. What technician should see

Phase 4 can establish the assignment/read boundary:

- Today / Assigned Work;
- Project/Site safe context;
- WorkOrder title/instructions;
- current assignment;
- readiness/basic blocker state;
- safe documents/context.

Phase 6 owns the full field/offline execution machine:
- durable offline Job Bundle;
- StartWork offline;
- Block/Resume offline;
- Evidence capture/upload/finalize;
- SubmitCompletion offline;
- sync/conflict resolution;
- process-death recovery;
- configured field tracking.

Therefore Phase 4 must not duplicate Phase 6 just because the full first-slice contract already describes those eventual commands.

The Phase 4 product should make the future technician path visible without prematurely building the full offline execution engine.

---

# 10. What Phase 5 owns

Phase 4 may say:

`this WorkOrder requires material/equipment`.

Phase 5 owns the authoritative answers:

- stock identity;
- availability;
- reservation;
- custody;
- checkout;
- return;
- consumption;
- warehouse/storage location;
- tool/asset state.

Until Phase 5 exists, Phase 4 must not fake a stock answer.

The readiness model should have an honest integration boundary for later material/equipment truth.

---

# 11. Progress and health

## Operational progress

First-slice contract already closes the formula:

`accepted included WorkOrder weight / total included baseline WorkOrder weight`.

Consequences:
- IN_PROGRESS is not arbitrary 50%.
- SUBMITTED is not accepted progress.
- REWORK is not accepted progress.
- accepted work contributes full snapshotted weight.
- baseline-changing scope uses controlled version/change path.

This is operational delivery progress.

RE-004 explicitly says measured/commercial claim progress is a different later concept.

Do not merge the two.

## Project health

Health is derived/explainable:

`UNKNOWN / HEALTHY / ATTENTION / CRITICAL / ON_HOLD`

A red Project must expose the contributing objects/signals.

No unexplained manager red/green opinion as authoritative truth.

---

# 12. Existing Phase 2 foundations that Phase 4 must reuse

## Work Queue

Source domain remains authoritative.

Examples:
- WorkOrder needs review -> source WorkOrder creates/updates current action.
- severe blocker needs owner -> blocker/action projects into Work Queue.
- approval needed -> Approval source projects current actionable state.

Do not create a second task tracker.

## Inbox / Notifications

Inbox is durable attention/read state.

Notification is delivery.

No provider is required for the core workflow to exist.

## Approval

A Project/Work command requiring approval uses the existing Approval foundation.

Do not implement a second project-specific approval engine.

## Evidence

Phase 2 Evidence remains the one binary evidence lifecycle.

Phase 4 may reference evidence policy/metadata requirements.

Phase 6 owns the field capture/offline execution experience.

---

# 13. External reference comparison

The current HILTECH model is directionally consistent with mature systems without copying them.

Microsoft Dynamics 365 Field Service separates:
- Work Order;
- resource booking/assignment;
- field technician execution;
- supervisor review.

It also supports Work Order Types, tasks/checklists and resource characteristics.

This supports HILTECH's existing decision to keep WorkOrder separate from WorkAssignment and to bind work-type policy/configuration.

Oracle Project Management separates:
- Project;
- project tasks;
- resources;
- dependencies;
- milestones;
- reusable work-plan templates.

This supports HILTECH's separation between project planning structure and executable/resource-assigned work.

HILTECH should remain simpler and more exception-driven than those enterprise products where company reality does not justify their breadth.

---

# 14. What is already structurally closed

The following should **not** be reopened casually:

- Site is durable and reusable across projects.
- ProjectSite carries project-specific site context.
- WorkOrder lifecycle != readiness.
- WorkAssignment is first-class history.
- assignment target can be USER / CREW / TEAM / SUBCONTRACTOR_ORGANIZATION.
- WorkType/policies are versioned/configurable.
- checklist runtime instances are materialized, not parsed from mutable template JSON.
- WorkOrder instructions are versioned.
- progress derives from accepted weighted work.
- health is explainable/derived.
- baseline changes are controlled/versioned.
- no direct `if role == PM` authorization.
- stale offline work can never overwrite newer authoritative work.
- Project/Work core has its own module ownership and does not mutate Warehouse/Finance tables directly.

Change only through explicit contradiction/change-control evidence.

---

# 15. Owner decisions recorded

The owner review did **not** identify a structural contradiction.

The main clarification is governance:

HILTECH is flexible, so business choices that are legitimately flexible must remain policy/configuration rather than being frozen as one permanent company behavior.

This is not indecision. It is the intended product architecture.

## A. Project creation before Phase 10

Phase 4 may support an authorized Project creation path now.

It is not a throwaway hack.

The Project keeps typed optional source references so that Phase 10 can later make `CreateProjectFromAward` the preferred commercial path without replacing Project identity or history.

Creation authority remains policy-controlled.

## B. Project responsibility / PM requirement

The repository rule remains:

ACTIVE normally requires a responsible PM/Project authority, unless active policy explicitly allows a temporary unassigned/team/owner-managed state.

Do not hard-code Mohamed or any current employee.

The important invariant is that every required Project decision/action has an authoritative current owner/authority, not that one named field must always contain the same kind of person.

## C. Assignment mode

Confirmed as configuration.

`AssignmentPolicy` / WorkType configuration may permit:
- automatic assignment;
- suggestion + authorized confirmation;
- manual choice from eligible targets;
- Team/Crew/Subcontractor assignment where allowed.

Eligibility and authorization remain system truth.

The current operating mode can change without schema redesign.

## D. Initial WorkType seed

Representative/synthetic seed values are allowed for implementation and validation.

They must be clearly marked as seed/configuration, not claimed as the final HILTECH WorkType catalog.

Real company WorkTypes can be added/configured later without redesigning the domain.

## E. Phase boundaries remain intentional

Do not compress phases merely to make the product appear usable sooner.

Phase 4 remains Project / Site / Work Core.

Phase 5 remains Assets / Warehouse / Materials / Tools truth.

Phase 6 remains the planned Field / Offline / Evidence / Sync execution phase.

Phase 4 may expose the safe assigned-work read/context needed to prove the Project/Work vertical, but it must not absorb the Phase 6 offline execution engine merely for speed.

If later implementation proves a genuine overlap or contradiction, use explicit change control. Do not pre-emptively collapse the phase plan.

## F. Definition of Green

A phase is not Green because screens exist or CI happened to pass.

Green means:
- reality/research reviewed;
- contract frozen;
- authoritative data ownership correct;
- security/authorization/audit/idempotency preserved;
- cross-phase boundaries honest;
- relevant Windows/Android human flow rendered;
- regression suites green;
- exact-head CI green;
- unknown/deferred items explicit;
- repository status/handoff updated.

A later change in configurable company policy does not invalidate a correctly closed phase.

---

# 16. Phase 4 vertical slice plan — OWNER ACCEPTED / FREEZE CANDIDATE

## Slice 01 — Project + Site / ProjectSite Core

Visible result:
- create/open Project;
- reusable Site;
- ProjectSite relationship;
- lifecycle through planning/active boundary;
- PM relation;
- Windows My Projects + Project header.

Reuse:
- Phase 3 People;
- Phase 1 OpenFGA;
- audit/idempotency/activity.

## Slice 02 — Project Planning Structure

Visible result:
- Areas where needed;
- Milestones;
- WorkPackages;
- baseline/version-safe planning;
- Project tree.

No Warehouse/Field execution.

## Slice 03 — WorkOrder + WorkType/Policy Binding

Visible result:
- create/plan WorkOrder;
- versioned instructions;
- checklist instances;
- requirement instances;
- lifecycle/readiness separation.

## Slice 04 — Readiness + WorkAssignment

Visible result:
- eligible-target evaluation from Phase 3 People/Team truth;
- assignment history;
- assign/reassign;
- Waiting On / blocker explanation;
- Work Queue projection for current actions.

Material/equipment remains an honest Phase 5 boundary.

## Slice 05 — Review / Accepted Progress / Project Health

Visible result:
- work review/rework authority;
- accepted-work progress;
- explainable Project health;
- PM Command Center;
- management-by-exception surface.

The exact field execution actions included here must remain within the Phase 4/Phase 6 boundary approved in owner review.

## Slice 06 — Basic Assigned Work Mobile Read / Phase 5–6 Handoff

Visible result:
- Android Today / assigned WorkOrder;
- safe Project/Site context;
- instructions/current readiness;
- explicit “execution/evidence offline engine comes in Phase 6” boundary;
- no fake offline capture.

Keep this as a distinct final Phase 4 slice unless implementation evidence shows that merging it preserves the same proof quality without pulling Phase 6 forward.

---

# 17. Owner-facing reconstructed scenario

Without naming current employees as architecture:

1. A Project exists for Client X.
2. One or more durable Sites are linked through ProjectSite.
3. Authorized project authority creates planning structure / WorkOrders.
4. Each WorkOrder binds a WorkType + Assignment/Readiness/Evidence/Review policy revision.
5. The system evaluates readiness from available authoritative dependencies.
6. If something is missing, the WorkOrder remains PLANNED and says exactly what is blocking it.
7. AssignmentPolicy evaluates eligible current People/Teams from Phase 3 truth.
8. If policy can decide automatically, it assigns.
9. If judgment is required, the correct authorized user's Work Queue receives the decision/action.
10. The assigned employee sees the work in the appropriate mobile/read surface.
11. Phase 6 later provides the full offline Start/Block/Evidence/Submit execution loop.
12. ReviewPolicy routes submitted work to the correct reviewer.
13. ACCEPTED work changes Project progress automatically.
14. Blockers, overdue work, rework and client actions produce explainable Project health.
15. Mohamed/management sees only decisions/exceptions within current authority, not every routine handoff.

This is the intended HILTECH operational machine already implied by the repository.

---

# 18. Current entry decision

The old question:

`“Who does Mohamed call?”`

is not the Phase 4 architecture question.

The correct Phase 4 question is:

`“Given current Project/People/Team/policy state, which object or authority owns the next action, and can HILTECH decide it without human judgment?”`

The repository already answers most structural parts.

The owner should review only the narrow policy/rollout decisions in Section 15.

---

# Owner review conclusion

`PHASE_4_REALITY_RECONSTRUCTION = PASS`

`OWNER_REVIEW = ACCEPTED`

`STRUCTURAL_CONTRADICTION_FOUND = NO`

`PHASE_BOUNDARIES = PRESERVE_ORIGINAL_PLAN`

`FLEXIBLE_BUSINESS_CHOICES = CONFIGURATION_POLICY`

`NEXT = FREEZE_PHASE_4_SCOPE_AND_SLICE_PLAN`

Production code remains gated until the Phase 4 scope/slice closure is frozen in the repository.

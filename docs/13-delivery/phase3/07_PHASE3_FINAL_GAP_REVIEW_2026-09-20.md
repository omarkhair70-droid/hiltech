# Phase 3 — People / Internal Workforce Core — Final Gap Review

Date: 2026-09-20  
Status: **PASS / PHASE 3 VERIFIED / COMPLETE / NO ADDITIONAL PHASE 3 SLICE REQUIRED**

## Purpose

Close Phase 3 against the frozen Phase 3 scope, all six slice contracts, actual merged code, human-flow proofs and post-merge regression evidence.

This review is a closure gate, not permission to start Phase 4 production code without an owner-facing Phase 4 entry review.

Canonical Phase 3 scope:

`docs/13-delivery/phase3/00_PHASE3_PEOPLE_CORE_SCOPE_CLOSURE_2026-09-20.md`

---

# 1. Final merged state

Phase 3 slices:

1. Employee / Employment Core — **VERIFIED / MERGED**
2. Workforce Assignment / Reporting Structure — **VERIFIED / MERGED**
3. HR Documents / Certifications — **VERIFIED / MERGED**
4. Onboarding + Basic Self-Service — **VERIFIED / MERGED**
5. Workforce Assignment Change Workflow — **VERIFIED / MERGED**
6. Offboarding Skeleton — **VERIFIED / MERGED**

Slice 06 PR #51 merged at:

`bc6b61cca744c17814e58d5e212812339cd96409`

Final Slice 06 closure head:

`4d941970563b84245061d5ab80101550243b6000`

Exact-head verification on that closure lineage:

- Bootstrap Phase 0 `35495373713` attempt 2 — **PASS**
- Phase 2 Shared Command Runtime `35495373785` — **PASS**
- Phase 1 Native OIDC Production Smoke `35495373737` — **PASS**
- Phase 3 Onboarding Human Proof `35495373800` — **PASS**
- Phase 3 Assignment Change Human Proof `35495373780` — **PASS**
- Phase 3 Offboarding Human Proof `35495373723` — **PASS**

Post-merge Bootstrap on `main`:

- `35495676983` — **PASS**
  - foundation — PASS
  - database-contract — PASS
  - local-platform-contract — PASS
  - evidence-storage-contract — PASS
  - terraform-contract — PASS
  - supply-chain-contract — PASS
  - dependency-review intentionally skipped on main push; exact-head PR review already passed.

The first Bootstrap attempt on the final Slice 06 closure head exposed the already-inherited intermittent ApprovalEngine contract flake. The exact same head passed the rerun, and post-merge Bootstrap also passed. This is recorded as cross-cutting test-stability follow-up, not as a Phase 3 product/data/security gap.

---

# 2. Frozen Phase 3 gate review

## Gate 1 — Person / Employee / Employment are canonical and organization-scoped

**PASS**

Slice 01 established separate canonical Person, Employee and Employment truth with organization-scoped Employee identity and versioned lifecycle state.

No later Phase 3 slice created a competing employee master.

---

## Gate 2 — Identity and Employee truth remain distinct

**PASS**

Phase 1 Identity remains authentication/access truth.

Phase 3 People remains workforce truth.

The Person link is the bridge; Employee is not the authentication record.

Onboarding identity provisioning and Offboarding access revocation consume an Identity-owned boundary rather than moving Keycloak/session/provider logic into People.

---

## Gate 3 — existing Team foundation is reused, not duplicated

**PASS**

Slice 02 and Slice 05 reuse Phase 1 Team and Team authorization projection.

No People-owned duplicate Team master was introduced.

WorkforceAssignment owns the business relationship to Team; security projection remains derived authority.

---

## Gate 4 — WorkforceAssignment is the single effective-dated role/team/manager source

**PASS**

Slice 02 created WorkforceAssignment.

Slice 05 changes Team / role / reporting manager through history-preserving assignment revisions rather than mutable Employee fields or a second transfer model.

Slice 06 closes current assignment authority for offboarding without creating a fake replacement revision.

---

## Gate 5 — workforce changes preserve history and authorization correctness

**PASS**

Slice 05 proves:

- old assignment -> ENDED;
- new assignment -> ACTIVE;
- revision chain via `supersedes_assignment_id`;
- exact-version / idempotent change;
- Team and reporting-cycle validation;
- People-owned Team membership resynchronization;
- OpenFGA aggregate correctness;
- same-Team role change does not accidentally remove Team authority;
- reporting manager does not become Team-manager security authority by implication.

Windows change/history human proof passed.

---

## Gate 6 — HR documents / certifications remain restricted

**PASS**

Slice 03 reuses the existing Evidence lifecycle instead of inventing a second binary-storage path.

Employee document / certification reads are authorization-scoped.

Self-service later exposes only own allowed records.

No unrestricted HR-document URL/list surface was introduced.

---

## Gate 7 — onboarding activation is typed, versioned and audited

**PASS**

Slice 04 provides:

- configured onboarding policy/revision;
- durable OnboardingCase;
- source-backed blocker evaluation;
- exact-version activation;
- idempotent commands;
- identity invitation/provisioning boundary;
- audit/events;
- rendered Android/Windows representative flow.

Real HILTECH checklist values remain configuration/activation data rather than guessed code policy.

---

## Gate 8 — self-service is own-record scoped

**PASS**

Employee self-service is limited to own permitted profile/contact, current assignment/onboarding, own allowed documents and certifications.

People/Admin management remains separately authorized.

No peer HR-record browsing was introduced through employee self-service.

---

## Gate 9 — offboarding safely revokes HILTECH access/session authority

**PASS**

Slice 06 proves:

- ACTIVE -> OFFBOARDING -> FORMER;
- Start Offboarding does not prematurely end Employment;
- organization membership is ended at access revocation;
- active HILTECH sessions are revoked;
- current WorkforceAssignment and People-owned Team authority are closed;
- OpenFGA organization/Team projection is reconciled;
- ACCESS clearance is derived from authoritative state and cannot be manually spoofed CLEAR;
- Keycloak/provider identity is not blindly deleted;
- device history is not blindly wiped;
- Employment is ended and Employee becomes FORMER only after completion gates.

Dedicated PostgreSQL contract + Windows offboarding human proof passed.

---

## Gate 10 — later-domain truth is not duplicated inside People

**PASS**

Phase 3 deliberately does not implement:

- Project/Site/Work reassignment;
- Warehouse custody/return transactions;
- final payroll calculation;
- Finance settlement;
- attendance/leave/overtime;
- bank/payment execution;
- ATS depth;
- field tracking.

Offboarding Project / Asset / Finance / Payroll entries are coordination clearances only and explicitly say their business transactions remain in their authoritative future modules.

Slice 05 emits only a Project-impact hook; Phase 4 owns Project/Site/Work truth.

---

## Gate 11 — shared client contracts compile and inherited foundations remain green

**PASS**

Phase 3 has shared KMP contracts/clients for the required People surfaces.

Representative human proof exists for onboarding, assignment change and offboarding.

Final exact-head regression and post-merge Bootstrap are green across:

- database/migrations;
- server contracts;
- shared runtime;
- Android/Windows compile paths;
- OIDC;
- OpenFGA;
- Evidence storage;
- supply chain;
- Terraform;
- inherited Phase 0–2 contract suites.

---

# 3. Phase 3 product result

Phase 3 now gives HILTECH a coherent People foundation rather than isolated HR CRUD.

The system can represent:

`Person -> Employee -> Employment -> WorkforceAssignment -> HR records/certifications -> Onboarding -> own self-service -> assignment history/change -> Offboarding -> Former`

and can keep that lifecycle connected to:

- Phase 1 Identity / organization membership / sessions;
- Team/OpenFGA authority;
- Phase 2 Evidence;
- audit/events/idempotency;
- Windows/Android shared product contracts.

This is sufficient People truth for Project / Site / Work to start consuming real employees and assignments.

---

# 4. Deliberate unresolved items — not Phase 3 gaps

The following remain intentionally unresolved because the repository does not yet contain enough real HILTECH policy/legal/later-domain evidence:

- actual legacy employee-master/import source and migration reconciliation;
- exact HILTECH onboarding checklist values by employee category;
- exact required HR document/certification catalogs;
- Egyptian legal employment/termination classifications and statutory rules;
- future-effective workforce-change scheduler;
- bulk workforce transfers;
- compensation/payroll effects of role changes;
- final-pay calculation;
- Project reassignment on role change/offboarding;
- Warehouse asset-return truth;
- attendance, leave, overtime and field-time policy;
- rehire workflow.

These must be validated in the phase/domain that owns them. They do not justify another Phase 3 slice now.

---

# 5. Cross-cutting follow-up

## ApprovalEngine contract flake

An inherited ApprovalEngine PostgreSQL/OpenFGA contract has shown intermittent CI failure while unrelated Phase 3 heads were green on rerun.

Closure evidence demonstrates:

- no deterministic Slice 06 failure on the same exact head;
- exact-head rerun PASS;
- post-merge Bootstrap PASS.

Disposition:

**NON-BLOCKING CROSS-CUTTING TEST-STABILITY FOLLOW-UP.**

Do not normalize or ignore the flake.

When touched, fix the test/runtime race at its actual ownership boundary rather than weakening assertions or changing People behavior to make CI green.

---

# 6. Phase 4 entry boundary

Phase 3 is complete.

The next program phase is:

**PHASE 4 — Projects / Sites / Work Core**

But production code must not start merely because Phase 3 closed.

Before Phase 4 implementation, perform an owner-facing Reality + Research + Product Entry Review over the existing frozen pre-code material.

Mandatory Phase 4 entry reading starts with:

- `docs/05-workflows/PROJECT_LIFECYCLE.md`
- `docs/06-data/object-specs/PROJECT_SITE_WORK_OBJECTS.md`
- `docs/06-data/transition-tables/PROJECT_AND_WORK_TRANSITIONS.md`
- `docs/03-product/role-experiences/PROJECT_MANAGER_EXPERIENCE.md`
- `docs/10-design/wireflows/PROJECT_CONTROL_WIREFLOW.md`
- `docs/13-delivery/first-slice-contract-pack/11_PROJECT_SITE_WORK_CONTRACT.md`
- `docs/13-delivery/FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md`
- `docs/01-reality/REALITY_EVIDENCE_REGISTER.md`
- relevant first-slice Freeze pack and accepted spikes.

The review must answer in human terms before code:

- what a real HILTECH Project is;
- what Site vs Area means in HILTECH;
- how work decomposes Project -> WorkPackage -> WorkOrder -> Task;
- who creates, assigns, blocks, resumes and closes work;
- what Mohamed / PM / supervisor / technician actually see;
- which Project state is authoritative;
- what assignment relationship consumes Phase 3 People truth;
- what belongs now vs Phase 5 Warehouse vs Phase 6 Field/Offline;
- what representative Windows and Android flow the owner will touch;
- which existing pre-code contracts still fit real HILTECH reality and which require controlled amendment.

No Phase 4 production code is authorized by this Phase 3 closure alone.

---

# Final decision

`PHASE_3_FINAL_GAP_REVIEW = PASS`

`ADDITIONAL_PHASE_3_SLICE_REQUIRED = NO`

`PHASE_3 = VERIFIED / COMPLETE`

`NEXT = PHASE_4_OWNER_FACING_ENTRY_REVIEW`

Phase 4 implementation starts only after that entry review is discussed with the owner and frozen into the repository.

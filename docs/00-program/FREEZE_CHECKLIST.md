# HILTECH Freeze Checklist

Status: ACTIVE / NOT READY TO FREEZE

## Rule
Freeze does not mean "never change".
It means implementation should no longer require discovering fundamental product/architecture decisions.

## Scope distinction — Whole Program vs First Production Slice

This document contains whole-program readiness items across all HILTECH domains.

Unchecked Finance / Payroll / Sales / Security / Client Portal / later-integration items do not automatically block the first production vertical when those domains are outside that slice.

For the first production slice, the authoritative Freeze controls are:
- `docs/13-delivery/first-slice-contract-pack/09_FREEZE_RECORD.md`
- `docs/13-delivery/first-slice-contract-pack/14_FREEZE_GAP_REGISTER.md`
- `docs/13-delivery/first-slice-contract-pack/21_FINAL_PRE_FREEZE_CONSISTENCY_REVIEW.md`
- `docs/13-delivery/first-slice-contract-pack/23_FIRST_SLICE_FREEZE_REVIEW_PROCEDURE.md`

Current first-slice state:
- contracts: PASS,
- final stack: PASS,
- provider architecture: PASS at contract level,
- rendered design proof: OPEN / sole current pre-code blocker.

Whole-program checklist remains valuable for later slices and eventual company-wide maturity.

Any intentional unknown at freeze must be documented as:
- deferred,
- bounded,
- non-blocking,
- with an owner/review trigger.

---

# A. Company Reality

- [ ] Mohamed workflow validated.
- [ ] Ahmed finance/payroll reality validated.
- [ ] One real project lifecycle validated.
- [ ] Field workflow observed/validated.
- [ ] Warehouse reality validated.
- [ ] Procurement reality validated.
- [ ] HR/admin reality validated.
- [ ] Sales/tenders reality validated.
- [ ] Existing systems inventory.
- [ ] Device inventory.
- [ ] Regulatory/legal/accounting unknowns identified.

BLOCKING: YES

---

# B. Product

- [x] Human Map first pass.
- [x] Product Map first pass.
- [x] Role Experience first pass.
- [x] Feature Catalog seeded.
- [ ] Feature Catalog reviewed against reality.
- [ ] Feature dependencies mapped fully.
- [ ] Deferred features explicitly marked.
- [ ] Final implementation sequence defined after freeze.

BLOCKING: YES

---

# C. Workflows / Domain

- [x] Project lifecycle v0.1.
- [x] Employee lifecycle v0.1.
- [x] Warehouse flow v0.1.
- [x] Procurement flow v0.1.
- [x] Payroll flow v0.1.
- [x] Approval model v0.1.
- [x] Automation map v0.1.
- [x] Client support/service lifecycle v0.1.
- [x] Finance/payment lifecycle v0.1.
- [x] Maintenance/managed-service lifecycle v0.1.
- [x] Document/handover lifecycle v0.1.
- [x] Security/facilities lifecycle v0.1.
- [ ] All important workflows reality-validated.

BLOCKING: YES

---

# D. Data

- [x] Object model seed.
- [x] State machines seed.
- [x] Event registry seed.
- [x] Transition tables first pass for high-risk objects.
- [ ] Exact object field definitions.
- [ ] Object ownership frozen.
- [ ] Transition tables complete for implementation scope.
- [ ] Invariants complete for implementation scope.
- [ ] Data classification.
- [ ] Retention/privacy rules.
- [ ] Ledger models validated.
- [ ] Database schema strategy frozen.

BLOCKING: YES

---

# E. Security / Permissions

- [x] Permission model v0.1.
- [x] Authorization matrix v0.1.
- [x] Object × Action permission matrix v0.1.
- [ ] Actual company authority validated.
- [ ] External org boundaries validated.
- [ ] Critical re-auth rules.
- [ ] Device trust rules.
- [x] OpenFGA/application policy spike decision — SPIKE-09 / ADR-009 accepted.
- [ ] Permission test matrix.

BLOCKING: YES

---

# F. UX / Design

- [x] IA v0.1.
- [x] Mobile surface map v0.1.
- [x] Desktop surface map v0.1.
- [x] Cross-device model.
- [x] UI reference pass 01.
- [x] UI reference pass 02.
- [x] Design thesis.
- [x] Design system architecture.
- [x] Typography/Bidi research.
- [x] Motion research.
- [x] Data visualization requirements.
- [x] Accessibility requirements.
- [ ] Final navigation model.
- [ ] Representative workflow wireframes.
- [ ] Arabic/English prototypes.
- [ ] Design tokens visual values.
- [ ] Typography choice.
- [ ] Base icon library + HILTECH custom set.
- [ ] Motion tokens.
- [ ] Component inventory/spec.
- [ ] Representative visual prototypes validated.

BLOCKING: YES before production UI.

---

# G. Offline / Sync

- [x] Offline-first model.
- [x] Sync model.
- [x] Conflict classes.
- [x] Capability-level offline classification first pass.
- [ ] Per-feature-ID offline classification for implementation scope.
- [x] Sync protocol semantic contract frozen for first slice.
- [x] Local schema/entity contract defined for first slice.
- [x] Upload/evidence strategy defined.
- [x] Local cache/evidence security baseline defined; stronger encryption has explicit revisit triggers.
- [x] Core offline/background spike path passed — SPIKE-03/04/13 accepted.

BLOCKING: YES for field foundation.

---

# H. Integrations / Hardware

- [x] Integration map.
- [x] Hardware strategy.
- [ ] Bank reality/provider.
- [ ] CCTV/NVR inventory.
- [ ] Access-control inventory.
- [ ] Attendance system.
- [ ] Test-equipment integrations.
- [ ] Push/email/SMS provider research.
- [ ] Tag/label pilot.
- [ ] Integration contracts for implementation scope.

BLOCKING: PARTIAL — only for included integrations.

---

# I. Stack

- [x] Stack research pass 01.
- [x] Candidate version matrix.
- [x] Technical architecture candidates.
- [x] Technical spikes completed — SPIKE-01 through SPIKE-15 accepted; final vertical run 35323209954.
- [x] KMP client decision — SPIKE-01/02/06 / ADR-001 accepted.
- [x] Desktop viability decision — 50k dense-data render proof passed.
- [x] Auth decision — Keycloak native OIDC / ADR-008 accepted.
- [x] Authz decision — OpenFGA / ADR-009 accepted.
- [x] SQL/persistence decision — PostgreSQL + jOOQ accepted; exact schemas/codegen freeze later.
- [x] Object storage/provider baseline — OCI Object Storage + KMS / ADR-014; cutover validation pending.
- [x] Infra provider baseline — OCI / ADR-014; Jeddah candidate + Container Instances/Compute fallback; tenancy/quota/latency validation pending.
- [x] CI/CD control plane — GitHub Actions accepted; final action pinning/version re-check remains.
- [x] Windows packaging/update/rollback decision — SPIKE-07 / ADR-012 accepted.
- [x] Windows production signing/distribution architecture — DigiCert OV + KeyLocker + HILTECH Update Service / ADR-019; certificate issuance/staging proof remains operational activation.
- [x] Ktor/shared networking decision — SPIKE-15 / ADR-007 accepted.
- [x] FINAL_STACK.md created for the first production slice.
- [x] First-slice version review completed — AGP 9.3.3 final pin; run 35389326629 PASS.
- [x] Production CI action immutable-SHA policy defined.

BLOCKING: YES.

---

# J. Architecture

- [x] System architecture v0.1.
- [x] Client architecture v0.1.
- [x] Backend architecture v0.1.
- [x] Database architecture v0.1.
- [x] Infrastructure v0.1.
- [x] Observability v0.1.
- [x] Module ownership v0.1.
- [x] Dependency graph v0.1.
- [x] Monorepo structure proposal.
- [ ] ADRs accepted.
- [x] API conventions / first-slice HTTP semantics v0.3.
- [x] first-slice exact object/API contract candidates defined in contract pack.
- [x] first-slice module/public contract boundaries defined at pre-code level; production interfaces generated after Freeze.
- [x] accepted spike evidence reflected in canonical plan/status/ADRs; technical-spike gate closed with SPIKE-15 / ADR-007.
- [ ] final monorepo structure.

BLOCKING: YES.

---

# K. Delivery

- [x] Definition of COMPLETE.
- [x] Dependency graph v0.1.
- [x] Technical spike plan.
- [ ] Implementation order.
- [x] Test strategy artifact exists.
- [x] CI gates artifact exists.
- [x] Release strategy artifact exists.
- [x] migration/rollback policy artifact exists.
- [x] operational runbooks index/artifacts exist.

BLOCKING: YES before production build sprint.

---

# Freeze Decision

Current: NOT READY.

Freeze review can only be called when every blocking section is either:
- complete as a planning/architecture artifact, or
- explicitly deferred with no hidden dependency.


---

## Configurable operating model

- [x] Typed/versioned configuration schemas structurally defined for WorkType, readiness, evidence, review, tracking, storage, roles/teams/delegation and first-slice policy.
- [x] Normal operating-policy changes are modeled through authorized product configuration without code/database edits.
- [x] Configuration changes are versioned, permissioned and audited by contract.
- [x] Hard invariants remain non-configurable.
- [x] Representative internal HILTECH fixtures prove current Project/Field/Storage patterns fit the configuration/domain model.
- [x] Current names/thresholds/work-type lists are treated as seed data, not compile-time architecture.
- [ ] Configuration Center representative visual proof.

Canonical model:
`docs/03-product/CONFIGURABLE_OPERATING_MODEL.md`

Freeze artifact:
`docs/13-delivery/first-slice-contract-pack/00_CONFIGURATION_POLICY_SCHEMAS.md`

---

## First Production Slice Closure Pack

Canonical bridge to repository bootstrap:
- reality gate: `docs/01-reality/FIRST_PRODUCTION_SLICE_REALITY_CLOSURE.md`
- readiness map: `docs/13-delivery/FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md`
- contract pack: `docs/13-delivery/first-slice-contract-pack/README.md`
- final slice freeze record: `docs/13-delivery/first-slice-contract-pack/09_FREEZE_RECORD.md`

The first production slice is not BUILD-READY until that freeze record can be marked PASS without inventing HILTECH-specific facts.


---

## Freeze / Bootstrap interpretation

Canonical:
`docs/13-delivery/first-slice-contract-pack/17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

Pre-code Freeze requires exact contracts/generation rules/test specifications.

Final Flyway SQL, Spring/Ktor/Room production code, generated jOOQ, and implementation integration tests are post-Freeze Bootstrap artifacts and must not be circular pre-code blockers.


---

## Current First-Slice Freeze Status

`FIRST_SLICE_CONTRACT_CONSISTENCY = PASS`

`FINAL_STACK_REVIEW = PASS`

`FIRST_SLICE_FREEZE = NOT YET PASS`

Only current pre-code blocker:
- actual rendered first-slice design proof in Figma.

The following are not current first-slice pre-code blockers:
- exact Finance/payroll schemas,
- bank integration,
- full Procurement/HR/Sales reality,
- Client Portal,
- NOC/managed service,
- OCI tenancy/quota/latency activation,
- DigiCert certificate issuance,
- production DR rehearsal,
- actual pilot seed data.

They are later-domain or activation gates unless they reveal a contradiction in the frozen first-slice contracts.

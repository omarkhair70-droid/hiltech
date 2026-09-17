# HILTECH Freeze Checklist

Status: ACTIVE / NOT READY TO FREEZE

## Rule
Freeze does not mean "never change".
It means implementation should no longer require discovering fundamental product/architecture decisions.

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
- [ ] OpenFGA/application policy spike decision.
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
- [ ] Sync protocol frozen.
- [ ] Local schema.
- [ ] Upload strategy.
- [ ] Security/encryption policy.
- [ ] Spike passed.

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
- [ ] Technical spikes completed.
- [ ] KMP client decision.
- [ ] Desktop viability decision.
- [ ] Auth decision.
- [ ] Authz decision.
- [ ] SQL/persistence decision.
- [ ] Object storage/provider.
- [ ] Infra provider.
- [ ] CI/CD toolchain.
- [ ] FINAL_STACK.md.
- [ ] VERSION_MATRIX.md frozen.

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
- [ ] API conventions.
- [ ] exact object/API schemas.
- [ ] module public contracts.
- [ ] technical spikes reflected.
- [ ] final monorepo structure.

BLOCKING: YES.

---

# K. Delivery

- [x] Definition of COMPLETE.
- [x] Dependency graph v0.1.
- [x] Technical spike plan.
- [ ] Implementation order.
- [ ] Test strategy.
- [ ] CI gates.
- [ ] Release strategy.
- [ ] migration/rollback policy.
- [ ] operational runbooks.

BLOCKING: YES before production build sprint.

---

# Freeze Decision

Current: NOT READY.

Freeze review can only be called when every blocking section is either:
- complete as a planning/architecture artifact, or
- explicitly deferred with no hidden dependency.

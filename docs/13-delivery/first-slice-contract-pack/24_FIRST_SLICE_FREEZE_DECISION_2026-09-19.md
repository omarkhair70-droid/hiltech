# 24 — First Production Slice Freeze Decision

Date: **2026-09-19**
Status: **PASS / FROZEN FOR REPOSITORY BOOTSTRAP**

## Decision

`FIRST_SLICE_FREEZE = PASS`

The first HILTECH production vertical is now frozen at the pre-code contract/design/stack level.

Scope:

**Project / Site / Work / Warehouse Asset Custody / Technician Offline / Evidence / Supervisor Acceptance / PM Read & Audit**

This decision means repository bootstrap and production implementation may begin from the frozen contracts.

It does **not** mean:
- the entire future HILTECH OS is frozen,
- all Finance/Payroll/Sales/Client/NOC domains are implementation-ready,
- production cloud/certificate accounts are already activated,
- final visual identity is complete,
- future evidence cannot trigger controlled contract change.

---

# 1. Freeze source snapshot

Review source on `main`:

`d1c7fefd87ab460abca66c42980a5641e468c170`

That snapshot includes the accepted interactive rendered design proof merged through PR #16.

Key evidence already accepted before this decision:

- SPIKE-15 architectural vertical:
  - run `35323209954`
  - PASS.
- OpenFGA first-slice model:
  - run `35331537375`
  - PASS.
- AGP 9.3.3 focused final-stack validation:
  - run `35389326629`
  - PASS.
- final rendered design proof:
  - run `35396169606`
  - PASS.
  - captures: `37 / 37`
  - artifact id: `10567646954`
  - artifact digest:
    `sha256:6988eb2f01613a0e89c72a74f90e5304ddc349b1d8bc9e49819ec5f0056ac1b3`

Canonical stack:
`docs/12-stack/FINAL_STACK.md`

Canonical rendered-design manifest:
`docs/10-design/INTERACTIVE_FIRST_SLICE_RENDERED_PROOF_MANIFEST_2026-09-18.md`

Freeze / Bootstrap boundary:
`17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

---

# 2. Review Rule A — No unknown structural semantics

**PASS**

Implementation no longer needs to invent first-slice answers for:

- aggregate ownership,
- Project/Site/ProjectSite identity,
- Work lifecycle,
- readiness,
- assignments,
- policy binding,
- instruction/checklist history,
- Project progress/health,
- Asset identity/custody/calibration,
- Stock movement/balance,
- evidence lifecycle,
- offline command semantics,
- conflict semantics,
- idempotency/baseVersion,
- authorization relationship projection,
- API error meaning,
- database integrity rules.

Remaining staff/project/asset names and current codes are seed/configuration data, not missing architecture.

---

# 3. Review Rule B — Configurable vs invariant separation

**PASS**

Company operating variation is typed/versioned configuration.

Hard product invariants remain non-configurable.

Examples:

Configurable:
- Work Types,
- readiness/evidence/review policies,
- teams/roles/delegations,
- storage/master data,
- CodePolicy,
- ProjectHealthPolicy,
- templates/checklists.

Hard invariants:
- server authorization,
- auditability,
- idempotency,
- optimistic concurrency,
- one current Asset custody,
- stock conservation,
- exact-version review,
- checksum/finalize,
- replay re-authorization.

No current HILTECH employee/title/warehouse count is compiled into product architecture.

---

# 4. Review Rule C — One-truth ownership

**PASS**

Authoritative ownership is explicit for:

- Project/Site/ProjectSite,
- Work lifecycle and assignments,
- Work policy/instruction history,
- Asset movement/custody,
- Stock movement/balance,
- Evidence metadata,
- Configuration revisions,
- identity provider boundary,
- OpenFGA projection,
- audit/activity.

No editable duplicate screen/client truth is accepted as authoritative.

---

# 5. Review Rule D — Offline safety

**PASS**

Evidence:

- accepted offline/background spikes,
- SPIKE-15 end-to-end proof,
- Room/offline contract,
- rendered Technician offline/conflict proof.

Verified contract behavior:

- local intent/evidence preserved,
- process death recoverable,
- same operationId replay,
- replay re-authenticates/re-authorizes,
- stale baseVersion cannot overwrite server truth,
- dependent commands can block on conflict,
- UI distinguishes local/queued/server-confirmed state,
- no fake completion.

---

# 6. Review Rule E — Physical custody safety

**PASS**

Asset checkout/return/collision semantics are explicit.

Rendered proof includes distinct:

- AVAILABLE,
- RESERVED SAME WORK,
- CALIBRATION BLOCKED,
- ALREADY CHECKED OUT,
- STALE CHECKOUT COLLISION,
- SUCCESS.

One current authoritative custody remains an invariant.

No silent custody overwrite is permitted.

---

# 7. Review Rule F — Authorization safety

**PASS**

OpenFGA first-slice model is executable and CI-green.

Security boundary is explicit:

PostgreSQL business relationship truth
→ transactional projection intent/outbox
→ fail-closed application guard
→ OpenFGA projection.

Verified/contracted:

- pending grant does not optimistically allow,
- pending revoke denies immediately,
- stale projection revision cannot re-grant,
- model ID is pinned,
- offline replay is re-authorized,
- field-level filtering remains server-side,
- object-hidden policy exists,
- config edit/activation authority is distinct where required.

---

# 8. Review Rule G — Evidence safety

**PASS**

Contracted first-slice behavior:

- app-private durable local evidence,
- reserve/upload/finalize flow,
- private signed upload,
- SHA-256 + size verification,
- quarantine/scanner class for arbitrary files,
- 16 MiB first-slice object maximum,
- no multipart first slice,
- no permanent public restricted URL,
- HIGHLY_RESTRICTED download through authenticated API proxy,
- no automatic business-evidence deletion without active RetentionPolicy.

If `ARBITRARY_FILE` is not enabled in the pilot, selecting the exact malware-scanner service is non-blocking.

---

# 9. Review Rule H — Database generation completeness

**PASS**

Pre-code database contract includes:

- table shapes,
- ownership,
- UUID/time/decimal rules,
- keys,
- FKs,
- CHECK constraints,
- uniqueness,
- indexes,
- optimistic update semantics,
- Flyway global ordering/naming,
- jOOQ generation/type mapping,
- migration/test obligations.

Production SQL/jOOQ generated code remains correctly classified as post-Freeze Bootstrap work.

---

# 10. Review Rule I — HTTP / local generation completeness

**PASS**

First-slice pre-code contracts define:

- `/v1` route/action grammar,
- DTO/error/conflict/re-auth envelopes,
- idempotency,
- baseVersion,
- visibility policy,
- cursor semantics,
- native client metadata,
- serialization approach,
- Room local entities,
- PendingCommand/Conflict/Evidence semantics,
- retry/storage/migration policies.

Spring/Ktor/Room implementation code is Bootstrap work, not a missing product decision.

---

# 11. Review Rule J — Rendered design proof

**PASS**

The prior Figma-only blocker was replaced with a tool-neutral interactive rendered proof.

Final run:

`35396169606`

Result:

`PASS`

Markers:

`HILTECH_DESIGN_RENDER_PASS captures=37`

`HILTECH_DESIGN_PROOF_COUNT_PASS captures=37`

Actual rendered review covered:

- Technician Job states,
- Warehouse custody states,
- Configuration Center,
- Supervisor Review,
- Project Command Center,
- all required conflict classes,
- Arabic RTL,
- phone/tablet/desktop adaptation,
- one-product navigation comparison.

Review corrections were made after inspecting the actual rendered images, especially Arabic-first human labels.

Low-fi Design Freeze does **not** require final:
- brand/color system,
- final font,
- iconography,
- motion,
- polished radius/spacing.

Those remain later visual-craft work.

---

# 12. Review Rule K — Stack reproducibility

**PASS**

`FINAL_STACK_REVIEW = PASS`

`FIRST_SLICE_STACK = FROZEN`

Final Android build pin:

**AGP 9.3.3**

Focused run:

`35389326629` — PASS.

Production GitHub Actions immutable SHA policy is defined.

Deliberate non-upgrades are documented.

No preview/EAP dependency is accidentally required by the first slice.

---

# 13. Review Rule L — Provider architecture

**PASS AT CONTRACT LEVEL**

Accepted baseline:

- OCI,
- Jeddah primary-region candidate,
- Riyadh DR candidate,
- Container Instances preferred / Compute fallback,
- OCI Database with PostgreSQL,
- OCI Object Storage + KMS,
- Secret Management,
- OCI Registry,
- Load Balancer,
- OpenTelemetry Collector → OCI observability,
- Terraform + OCI Provider / Resource Manager,
- DigiCert OV + KeyLocker,
- HILTECH Update Service,
- staged PILOT/STABLE DR contract.

The provider contract is sufficient to generate IaC/adapters during Bootstrap.

---

# 14. Deferred non-blocking items

These do **not** block this contract Freeze.

## Pilot seed/setup

Still to instantiate before pilot use:
- actual current staff/team rows,
- authority assignments,
- asset/stock/storage master seed,
- current Project/Client/Site codes,
- selected first pilot WorkType/policy seed,
- representative device/site configuration,
- import vs clean-seed decision.

Reason non-blocking:
their schema/configuration model is already defined.

Revisit:
before pilot data load / user acceptance.

## Tracking

Persisted `ARRIVAL_PROOF` / `ACTIVE_SITE_PRESENCE` stays disabled until:
- retention,
- notice,
- viewers,
- stop conditions

are configured/approved.

Safe default:
disabled.

## Arbitrary file evidence

Exact malware scanner service may remain unselected if `ARBITRARY_FILE` is not enabled.

Safe default:
arbitrary-file evidence disabled until scanner path exists.

## OCI production activation

Still required before production cutover:
- tenancy/subscription,
- quotas/capacity,
- measured Egypt latency,
- final shapes/cost,
- staging IaC deployment,
- secret/KMS setup,
- PITR/DR rehearsal,
- telemetry operational tuning,
- domain/TLS ownership.

Reason non-blocking:
provider architecture/interfaces are already selected.

## Windows production activation

Still required before release:
- DigiCert organization validation,
- certificate issuance,
- KeyLocker credentials,
- signed-MSI staging test.

Reason non-blocking:
signing/update architecture is already selected.

---

# 15. Whole HILTECH vs first-slice Freeze

This PASS applies to the starting vertical only.

It does **not** declare exact later-domain contracts frozen for:

- Payroll,
- bank execution,
- full Finance,
- Sales/Tender,
- Client Portal,
- NOC,
- CCTV/access-control integrations,
- every future WorkType.

Each later implementation phase receives its own scoped contract/reality/design closure before production work reaches it.

The shared foundation can now be bootstrapped because the cross-cutting architecture needed by the first slice is frozen.

---

# 16. Change control after Freeze

Implementation must not silently redesign a frozen first-slice contract.

If a real contradiction appears:

1. stop the affected implementation decision,
2. record the evidence,
3. identify exact frozen contract/ADR affected,
4. propose the smallest contract change,
5. run focused compatibility/security/data review,
6. update the decision record,
7. then continue implementation.

Normal seed/configuration changes do not require architecture reopening.

---

# 17. Next phase

The next phase is now:

`REPOSITORY_BOOTSTRAP`

Bootstrap may generate/implement:

- monorepo/Gradle structure,
- convention plugins/version catalog/wrapper,
- Android/Desktop/server modules,
- Flyway V0001… migrations,
- jOOQ generation,
- shared DTO/error envelopes,
- Ktor boundary,
- Spring module skeletons,
- Room entities/DAOs/migrations,
- WorkManager sync foundation,
- Keycloak/OpenFGA adapters,
- authorization projection/outbox processor,
- evidence storage adapter,
- OCI IaC skeleton,
- CI/security/contract test foundation.

Bootstrap Verification must prove generated implementation matches the frozen contracts before the vertical expands.

---

# Final decision

**CONTRACT REVIEW = PASS**

**RENDERED DESIGN REVIEW = PASS**

**FINAL STACK REVIEW = PASS**

**PROVIDER CONTRACT REVIEW = PASS**

**FIRST_SLICE_FREEZE = PASS**

Next:
**REPOSITORY_BOOTSTRAP**

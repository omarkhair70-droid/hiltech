# 09 — First Production Slice Freeze Record

Status: **NOT READY / ACTIVE CLOSURE RECORD**

Freeze date: TBD
Freeze owner/reviewers: TBD

## Scope

First production vertical:
Project / Site / Work / Warehouse Asset Custody / Technician Offline / Evidence / Supervisor Acceptance / PM Read/Audit.

---

# Evidence Checklist

## Reality
- [x] Representative internal reality fixtures cover Data Center / remote cable pulling / temporary site storage / variable crew shapes.
- [x] Generic Project/Site/Work structural model coverage is no longer an open blocker.
- [ ] Initial real staff/team/authority seed reviewed for pilot.
- [ ] Representative asset/stock/storage seed reviewed for pilot.
- [ ] field device/site restrictions that materially affect security/offline behavior recorded.
- [ ] pilot data source/import path known or clean-seed pilot explicitly chosen.

## Domain
- [ ] Configuration/policy schemas frozen.
- [ ] Data dictionary frozen.
- [ ] Project/Site/Work contract frozen.
- [ ] WorkOrder lifecycle and readiness are explicitly separated.
- [ ] Work policy-binding/version semantics frozen.
- [ ] Asset/Warehouse/Stock/Custody contract frozen.
- [ ] evidence policy/file contract frozen.
- [ ] cross-contract consistency review has no unresolved contradiction.

## Security
- [x] OpenFGA first-slice model candidate is executable/tested — run 35331537375; final freeze awaits ops consistency constants/model pin deployment config.
- [ ] application obligations frozen.
- [ ] field-level access frozen.
- [ ] re-auth/device obligations frozen.
- [ ] negative authorization tests defined.

## API / Data
- [ ] routes/DTOs/errors frozen.
- [ ] read models frozen.
- [x] PostgreSQL table/constraint/index contract defined; production SQL generation waits for Freeze.
- [x] Flyway convention frozen.
- [x] jOOQ convention frozen.
- [x] Room/local schema + retry/storage policy contract defined.
- [x] sync/conflict contracts frozen at semantic level.
- [ ] file/evidence metadata frozen.

## Design
- [ ] PM flow validated.
- [ ] Warehouse Checkout validated.
- [ ] Technician Job validated.
- [ ] Supervisor review validated.
- [ ] RTL validated.
- [ ] conflict states validated.
- [ ] adaptive device states validated.

## Ops
- [ ] infrastructure decision sufficient for bootstrap.
- [ ] object-storage provider selected or bounded non-blocking plan accepted.
- [ ] Windows signing/distribution decision sufficient for first release.
- [ ] backup/RPO/RTO baseline.
- [ ] version matrix re-checked.
- [ ] CI action pins reviewed.

---

# Accepted Technical Baseline

Already accepted before this freeze:
- SPIKE-01…15.
- KMP/Compose.
- Spring Boot/Modulith.
- PostgreSQL/jOOQ.
- Room3.
- Ktor.
- Keycloak.
- OpenFGA.
- WorkManager.
- S3-compatible evidence protocol.
- OpenTelemetry correlation.
- Windows MSI lifecycle.
- GitHub Actions.

These are not reopened without a concrete contradiction/revisit trigger.

---

# Contract Candidate Inputs

- `00_CONFIGURATION_POLICY_SCHEMAS.md`
- `01_DATA_DICTIONARY.md`
- `02_API_AND_READ_MODELS.md`
- `03_POSTGRES_FLYWAY_JOOQ.md`
- `04_ROOM_OFFLINE_SYNC.md`
- `05_AUTHORIZATION_POLICY_TESTS.md`
- `06_FILES_EVIDENCE.md`
- `07_UI_FLOW_CONTRACTS.md`
- `08_FIRST_SLICE_TEST_MATRIX.md`
- `10_CROSS_CONTRACT_CONSISTENCY.md`
- `11_PROJECT_SITE_WORK_CONTRACT.md`
- `12_ASSET_WAREHOUSE_CONTRACT.md`
- `13_OPENFGA_MODEL_CANDIDATE.md`
- `14_FREEZE_GAP_REGISTER.md`
- `15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`
- `16_DATABASE_DDL_CONSTRAINT_CONTRACT.md`
- `17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

---

# Contradictions / Deferred Items

Every deferred item must record:
- exact item,
- why non-blocking,
- owner,
- revisit trigger,
- maximum acceptable scope/risk.

No unnamed "later" bucket.

---

# Freeze Decision

Current: **NOT READY**

When every blocking item above is complete or explicitly bounded/deferred:

Decision:
`FIRST_SLICE_FREEZE = PASS`

Then:
1. finalize monorepo/module structure,
2. create repository bootstrap branch,
3. generate/build Phase 0 foundation,
4. implement the first vertical slice from these contracts,
5. do not redesign fundamentals during implementation without formal change control.


---

## Freeze / Bootstrap Boundary

Use:
`17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`

Pre-code Freeze requires exact contracts + test specifications.

It does not require production Flyway SQL, Spring/Ktor/Room implementation, production UI code, or implementation tests that only exist after repository bootstrap.

Those become immediate Bootstrap Verification gates.

# HILTECH First Production Slice — Build Contract Freeze Pack

Status: **TEMPLATE PACK / PRE-FREEZE**
Date: 2026-09-18

## Purpose

These files are the direct bridge from validated HILTECH reality to production bootstrap.

They are intentionally structured so that after reality validation the team does not need to rediscover:
- what must be frozen,
- where each decision belongs,
- what tests prove it,
- what code generation/implementation consumes it.

First production vertical:

PM Desktop
→ Project / Site / Work Order
→ Warehouse Asset Reservation / Checkout
→ Technician Android
→ Offline Execution / Evidence
→ Reconnect / Sync
→ Supervisor Acceptance
→ PM Read / Audit

Technical feasibility is already accepted through SPIKE-01…15.

This pack is therefore **not a spike plan**.

---

# Contract Pack

0. `00_CONFIGURATION_POLICY_SCHEMAS.md`
   - typed/versioned WorkType, readiness, evidence, review, tracking, storage, role/team and other configurable policy schemas.

1. `01_DATA_DICTIONARY.md`
   - exact fields/types/nullability/ownership/classification.

2. `02_API_AND_READ_MODELS.md`
   - exact commands, queries, routes, payloads, errors, versions.

3. `03_POSTGRES_FLYWAY_JOOQ.md`
   - authoritative tables, keys, constraints, indexes, migrations, codegen.

4. `04_ROOM_OFFLINE_SYNC.md`
   - local cache, job bundle, command queue, evidence queue, migrations, conflicts.

5. `05_AUTHORIZATION_POLICY_TESTS.md`
   - Keycloak subject mapping, OpenFGA tuples/relations, obligations, field-level tests.

6. `06_FILES_EVIDENCE.md`
   - evidence metadata, object-key policy, reservation/finalization, retention.

7. `07_UI_FLOW_CONTRACTS.md`
   - PM/Warehouse/Technician/Supervisor surfaces and state behavior.

8. `08_FIRST_SLICE_TEST_MATRIX.md`
   - unit/contract/integration/security/offline/UI/operational acceptance.

9. `09_FREEZE_RECORD.md`
   - one final evidence-backed record that allows repository bootstrap.

10. `10_CROSS_CONTRACT_CONSISTENCY.md`
   - verifies Configuration → Domain → DB → API → Room → Auth → UI → Tests remain coherent.

11. `11_PROJECT_SITE_WORK_CONTRACT.md`
   - implementation-facing Project/Site/Work lifecycle, policy-binding, assignment and offline contract.

12. `12_ASSET_WAREHOUSE_CONTRACT.md`
   - implementation-facing Asset/Stock/Storage/Custody contract.

13. `13_OPENFGA_MODEL_CANDIDATE.md`
   - first-slice relationship model + application-obligation boundary.

14. `14_FREEZE_GAP_REGISTER.md`
   - narrow remaining blockers required before FIRST_SLICE_FREEZE = PASS.

---

# Editing Rule

Every unresolved row must be explicitly one of:

- `LOCKED_TECHNICAL`
- `VERIFIED_REALITY`
- `PROPOSED_FOR_REVIEW`
- `REALITY_REQUIRED`
- `DESIGN_REQUIRED`
- `PROVIDER_REQUIRED`
- `DEFERRED_NON_BLOCKING`

Never leave a production-significant field as an unlabeled guess.

---

# Freeze Rule

This pack is FROZEN only when:

- no starting-slice table/command/read model depends on an unknown company fact,
- authority rules are verified,
- offline/conflict/error behavior is explicit,
- representative UI states are validated,
- schema/API/local/auth/file contracts agree with each other,
- tests can be generated directly from the contracts,
- the monorepo/module plan can consume the contracts without redesign.

Then:

`FREEZE PASSED → REPOSITORY BOOTSTRAP → PRODUCTION CODE`

---

# Source Documents

Use, do not duplicate:
- `../../01-reality/FIRST_PRODUCTION_SLICE_REALITY_CLOSURE.md`
- `../FIRST_PRODUCTION_SLICE_CONTRACT_READINESS_2026-09-18.md`
- `../../06-data/object-specs/PROJECT_SITE_WORK_OBJECTS.md`
- `../../06-data/object-specs/ASSET_STOCK_WAREHOUSE_OBJECTS.md`
- `../../06-data/object-specs/IDENTITY_ORGANIZATION_OBJECTS.md`
- `../../06-data/COMMAND_CATALOG.md`
- `../../06-data/transition-tables/PROJECT_AND_WORK_TRANSITIONS.md`
- `../../06-data/transition-tables/ASSET_AND_WAREHOUSE_TRANSITIONS.md`
- `../../07-security/OBJECT_ACTION_PERMISSION_MATRIX.md`
- `../../07-security/FIELD_LEVEL_ACCESS_MATRIX.md`
- `../../11-architecture/CROSS_CUTTING_BUILD_CONTRACTS.md`
- ADR-007 / 008 / 009 / 010 / 011.

15. `15_AUTHORIZATION_CONSISTENCY_CONTRACT.md`
   - fail-closed PostgreSQL business-truth → OpenFGA projection consistency, pinned model/versioning and recovery contract.

16. `16_DATABASE_DDL_CONSTRAINT_CONTRACT.md`
   - exact first-slice PostgreSQL keys/FKs/checks/uniques/indexes and generation gate for Flyway/jOOQ.

17. `17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`
   - canonical line between pre-code contract Freeze and post-Freeze repository/bootstrap implementation verification.

18. `18_PRODUCTION_INFRASTRUCTURE_CONTRACT.md`
   - OCI production topology, environments, network, PostgreSQL/Object Storage/KMS/Secrets, IaC, deployment and cutover validation contract.

19. `19_WINDOWS_RELEASE_UPDATE_CONTRACT.md`
   - signed MSI release objects/API, private artifact distribution, client verification, rollout and rollback contract.

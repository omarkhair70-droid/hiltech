# 09 — First Production Slice Freeze Record

Status: **NOT READY / TEMPLATE**

Freeze date: TBD
Freeze owner/reviewers: TBD

## Scope

First production vertical:
Project / Site / Work / Warehouse Asset Custody / Technician Offline / Evidence / Supervisor Acceptance / PM Read/Audit.

---

# Evidence Checklist

## Reality
- [ ] Authority map verified.
- [ ] One real Project/Site mapped.
- [ ] One representative field WorkOrder mapped.
- [ ] Warehouse walkthrough complete.
- [ ] field device/site restrictions recorded.
- [ ] pilot source/import path known.

## Domain
- [ ] Data dictionary frozen.
- [ ] Project/Site subset frozen.
- [ ] WorkOrder states/commands/invariants frozen.
- [ ] Asset/Warehouse custody subset frozen.
- [ ] evidence policy frozen.

## Security
- [ ] OpenFGA first-slice model frozen.
- [ ] application obligations frozen.
- [ ] field-level access frozen.
- [ ] re-auth/device obligations frozen.
- [ ] negative authorization tests defined.

## API / Data
- [ ] routes/DTOs/errors frozen.
- [ ] read models frozen.
- [ ] PostgreSQL tables/constraints/indexes frozen.
- [ ] Flyway convention frozen.
- [ ] jOOQ convention frozen.
- [ ] Room/local schema frozen.
- [ ] sync/conflict contracts frozen.
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

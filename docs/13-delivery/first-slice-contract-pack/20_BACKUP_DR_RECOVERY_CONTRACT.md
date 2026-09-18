# 20 — Backup / Disaster Recovery / Recovery Contract

Status: **CONTRACT CANDIDATE v0.1**
Date: 2026-09-18

Provider:
OCI — ADR-014.

Primary candidate:
`me-jeddah-1`

Secondary DR candidate:
`me-riyadh-1`

## Purpose

Define engineering recovery behavior without pretending an unmeasured business SLA already exists.

Two maturity levels:

- PILOT recovery baseline.
- STABLE production recovery baseline.

A stricter contractual/client SLA can later tighten these values through operations/configuration without changing application architecture.

---

# 1. Recovery classes

## PILOT

For controlled initial production/pilot use:

PostgreSQL:
- automated backups.
- PITR enabled.
- minimum 10-day PITR window.
- automatic backup copies to secondary region.
- restore runbook tested before real pilot approval.

Engineering targets:
- regional-backup RPO target: <=24 hours worst-case until backup-copy schedule is tuned/measured.
- restore RTO target: <=4 hours in drill.

These are acceptance targets, not external SLA.

## STABLE

Before HILTECH declares the platform fully stable for broader critical daily operations:

PostgreSQL:
- OCI Database with PostgreSQL Warm Standby in secondary region.
- primary: Jeddah candidate.
- standby: Riyadh candidate.
- asynchronous replication.
- RPO enforcement enabled.
- enforcement threshold: **300 seconds / 5 minutes**.
- failover/failback runbook tested.

Engineering targets:
- cross-region DB RPO: <=5 minutes while enforcement/standby healthy.
- regional failover service-restoration RTO target: <=60 minutes in rehearsed runbook.

If the provider reports standby lag beyond threshold, preserving data integrity takes priority over continuing writes.

---

# 2. Same-region database availability

Use OCI managed PostgreSQL service capabilities.

Production sizing can add read-replica/failover nodes where cost/availability validation supports it.

Do not hard-code node count into application architecture.

Connection endpoint is environment configuration.

Application retries transient DB failover failures according server transaction safety; it never retries an ambiguous business command by creating a new operationId.

---

# 3. Backup policy

For application PostgreSQL:

- automated provider backup/PITR.
- pre-high-risk-change manual backup when operations runbook requires it.
- backup-copy policy to DR region in PILOT.
- Warm Standby in STABLE.

Backups must include or be paired with:
- schema/migration version.
- application release reference.
- environment reference.
- restore test history.

A backup that has never been restored in a rehearsal is not considered proven recovery.

---

# 4. Keycloak / OpenFGA recovery

Keycloak and OpenFGA persistent state lives in PostgreSQL logical databases/owners.

Recovery includes:
- Keycloak DB.
- OpenFGA DB.
- HILTECH app DB.

Safe version-controlled configuration also includes:
- Keycloak realm/client non-secret configuration.
- OpenFGA model source.
- pinned model ID deployment record.
- IaC.

After restore:
- verify Keycloak issuer/client behavior.
- verify OpenFGA model/tuple consistency.
- run authorization reconciliation against PostgreSQL source relationships.

---

# 5. Object Storage recovery

Evidence/release buckets:

- private.
- versioning enabled where provider configuration supports it.
- production KMS encryption.
- accidental deletion/overwrite protection configured.

PILOT:
- no automated business evidence deletion.
- provider durability/versioning + documented export/replication recovery path.

STABLE:
- configure cross-region replication/copy strategy to DR region if validated for the selected OCI Object Storage setup.
- periodically test restoration/read of representative encrypted object.

Database metadata and object bytes must be reconciled after recovery:
- DB READY evidence pointing to missing object is operational incident.
- orphan object does not become trusted Evidence automatically.

---

# 6. KMS / Secret recovery

Document:
- KMS key OCIDs/aliases through IaC/config references.
- key backup/replication capability where selected.
- Secret Management versions/rotation runbook.

Never make recovery depend on a secret existing only on one engineer laptop.

Break-glass credentials:
- minimal.
- protected separately.
- access logged.
- tested without exposing values in docs/Git.

---

# 7. Application runtime recovery

Runtime containers are disposable.

Recovery source:
- OCI Registry image digests.
- IaC.
- environment config.
- Secret/KMS references.
- database.
- object storage.

No unique production business state may live only:
- inside Container Instance filesystem,
- in a local Compute disk not covered by recovery contract,
- in GitHub Actions workspace.

---

# 8. Regional failover runbook

Candidate steps:

1. declare incident / freeze unsafe deployments.
2. determine DB primary/standby/backup status.
3. promote/restore secondary DB according OCI runbook.
4. apply/verify secondary-region IaC application runtime.
5. restore/verify secrets/KMS/object-storage access.
6. update controlled DNS/load-balancer endpoint.
7. boot Keycloak/OpenFGA/HILTECH API/OTel.
8. verify Flyway schema compatibility.
9. run auth model/tuple reconciliation.
10. run read-only health checks.
11. run synthetic/safe write smoke.
12. re-enable normal writes/users.
13. monitor.
14. document actual RPO/RTO.
15. plan failback separately.

Never improvise bi-directional writes during failover.

---

# 9. Failback

Failback is a separate controlled operation.

Do not simply point traffic back at an old stale primary.

Must establish:
- authoritative current DB.
- object-store consistency.
- latest secrets/config.
- app release compatibility.
- OpenFGA reconciliation.

Then restore/rebuild original region from authoritative current state.

---

# 10. Recovery drills

Before PILOT:
- PITR restore to isolated staging recovery environment.
- copied-backup restore.
- Keycloak login after restore.
- OpenFGA allow/deny after restore.
- one Evidence object recovery/verification.

Before STABLE:
- full warm-standby regional failover drill.
- measure DB replication lag/RPO.
- measure app service RTO.
- failback rehearsal.
- secret/KMS access validation.
- Windows/native client reconnect after endpoint recovery.

Quarterly/periodic frequency is operations policy; first production runbook must assign it.

---

# 11. Recovery data safety

During recovery:

- idempotency operation history preserved.
- audit history preserved.
- asset/stock/finance append-ledger history preserved.
- pending authorization projection reconciled.
- no queue row "replayed from backup" blindly creates duplicated external action.
- Windows/Android clients may retry only with original operationId.

If backup restore rolls server state behind a client-confirmed operation:
- reconciliation/support workflow is required.
- do not assume client or server copy wins globally.

---

# 12. Monitoring

Alert before disaster where possible:

- backup failure.
- PITR policy disabled.
- warm standby lag.
- RPO threshold/read-only transition.
- DB node failover.
- Object Storage replication/copy failure.
- authorization projection backlog.
- registry/image deployment failure.
- KMS/Secret access failure.

---

# 13. Business SLA boundary

Engineering baseline:

PILOT:
- RPO <=24h cross-region backup worst-case target.
- RTO <=4h recovery-drill target.

STABLE:
- DB cross-region RPO <=5m when Warm Standby healthy/enforced.
- full service RTO <=60m recovery-drill target.

These are internal engineering acceptance objectives.

A signed client/business SLA:
- is a separate management/commercial decision.
- may require tighter infrastructure/cost.
- must never be promised merely because an engineering target exists.

---

# 14. Revisit triggers

Revisit DR topology if:
- HILTECH signs stricter SLA.
- client requires data location outside/inside a specific country.
- Jeddah/Riyadh service availability changes.
- actual workload/cost makes Warm Standby inappropriate.
- recovery drill misses target.
- multi-region object recovery is insufficient.

---

# Decision

Recovery architecture is no longer open-ended.

PILOT can start on PITR + copied backups once restore rehearsal passes.

STABLE production requires cross-region Warm Standby with 5-minute enforced RPO and a rehearsed <=60-minute service recovery target, unless an explicitly approved replacement DR plan meets or exceeds it.

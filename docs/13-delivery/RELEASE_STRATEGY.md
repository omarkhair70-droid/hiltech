# HILTECH Release Strategy

Status: PRE-CODE DELIVERY SPEC v0.1

## Principle

HILTECH is an internal operating system first.

Release strategy prioritizes:
- controlled internal rollout,
- fast learning,
- no data loss,
- backward compatibility,
- supportability.

---

# Environments

## Local
Developer machine.

## CI/Test
Automated ephemeral/integration.

## Staging
Production-like test environment.

## Production
Real HILTECH data/work.

No production data casually copied to lower environments.

---

# Server Release

Preferred:
- immutable build artifact/container.
- migrations reviewed and rehearsed.
- deploy with health checks.
- backward-compatible transition where client versions overlap.
- observe before full rollout.

---

# Android

Stages:
1. internal engineering.
2. controlled HILTECH employee testers.
3. broader internal workforce.
4. production distribution.

Google Play/internal distribution decision finalized later.

Staged rollout where supported.

---

# Windows

Stages:
1. test machines.
2. Ahmed/office pilot.
3. selected PM/warehouse.
4. broader office rollout.

Need:
- signed installer.
- update mechanism.
- rollback/previous installer availability.
- local DB preservation.

---

# Rollout by Company Function

Do not turn on every module company-wide on day one.

Suggested operational adoption:

1. Identity/People.
2. Projects/Work.
3. Warehouse/Assets.
4. Field execution.
5. Procurement.
6. Finance/Imprest.
7. Payroll.
8. Sales.
9. Maintenance/Security.
10. external portals later.

The full architecture exists before this; rollout is operational adoption sequencing.

---

# Feature Flags

Use only where valuable:
- controlled pilot.
- risky integration.
- staged role enablement.

Do not use feature flags to hide permanently unfinished architecture.

---

# Backward Compatibility

Server must account for temporarily older Android/Windows clients.

For breaking contract:
- add compatible path first,
- release clients,
- observe adoption,
- remove old path later.

---

# Release Health

Watch:
- crashes.
- sync backlog.
- API errors.
- DB latency.
- event failures.
- auth failures.
- integration health.
- business error spikes.

---

# Stop / Rollback Criteria

Examples:
- data corruption risk.
- permission leak.
- duplicated financial/custody actions.
- migration failure.
- widespread sync loss.
- authentication outage.

UI cosmetic bug alone normally does not justify database rollback.

---

# Release Notes

Internal release note:
- user-visible changes.
- workflow changes.
- known limitations.
- migration/operational notes.
- support path.

---

# Client Portal

External client-facing release is intentionally last after internal system stability.

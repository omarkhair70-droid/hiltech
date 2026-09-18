# ADR-015 — CI/CD Control Plane

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH needs one reproducible engineering control plane across:
- Kotlin/Gradle,
- Android,
- Windows packaging,
- JVM backend,
- PostgreSQL integration tests,
- browser/identity tests,
- architecture/security checks.

The production deployment provider is not yet frozen, but CI must be decided before repository bootstrap.

## Decision

Use **GitHub Actions** as the primary HILTECH CI and delivery-orchestration control plane.

Baseline:
- pull-request validation,
- branch protection / required checks,
- Linux and Windows runners,
- Android build/emulator jobs,
- backend/database integration jobs,
- architecture/security tests,
- versioned release workflows,
- environment-scoped secrets,
- deployment jobs implemented as explicit adapters to the selected infrastructure provider.

GitHub Actions does not own domain deployment logic. Deployment scripts/modules must remain reproducible outside the web UI.

## Evidence

The pre-code spike program has already executed representative HILTECH workloads in GitHub Actions, including:
- Android + Windows KMP builds,
- Windows EXE/MSI packaging,
- real PostgreSQL concurrency,
- Room/SQLite Linux + Windows,
- OpenFGA,
- Spring Modulith,
- Keycloak + Chromium,
- binary S3 protocol,
- Compose Desktop rendering,
- RTL/adaptive rendering,
- OpenTelemetry tests.

This is substantially stronger evidence than selecting CI from a feature comparison alone.

## Consequences

Positive:
- source, review and CI stay together,
- Linux/Windows matrix is already proven,
- PR checks map naturally to the single source-of-truth repository,
- Codex/agent changes can be forced through the same gates as human changes.

Costs:
- hosted-runner concurrency/minutes require monitoring,
- Android emulator jobs need deliberate runner orchestration/caching,
- production deployment credentials must be tightly environment-scoped,
- GitHub outage must not be the only location of operational deployment knowledge.

## Production CD

Exact deployment target remains conditional on ADR-014 Infrastructure.

Once provider is frozen:
- deployment workflow calls versioned infra/deploy code,
- staging before production,
- smoke/health gates,
- rollback route,
- audit of who approved production deployment.

## Revisit Triggers

Revisit if:
- organization requires on-prem CI,
- regulated environment forbids hosted runners,
- runner economics become materially worse than self-hosted,
- deployment environment cannot integrate safely with GitHub Actions.

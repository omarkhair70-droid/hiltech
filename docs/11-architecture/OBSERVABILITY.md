# HILTECH Observability Architecture

Status: ARCHITECTURE MODEL v0.1

## Thesis
If HILTECH OS runs the company, failures must be diagnosable without guessing.

Observability is not the same as audit.

Audit answers:
Who did what to business data?

Observability answers:
What is the system doing and why is it failing/slow?

---

# 1. Server Telemetry

OpenTelemetry candidate.

Capture:
- traces,
- metrics,
- logs.

Every request/workflow should carry correlation/trace context.

---

# 2. Business Operational Metrics

Examples:
- queued sync commands.
- failed sync commands.
- evidence upload backlog.
- approval latency.
- payroll batch state.
- failed payment count.
- notification failure.
- integration health.
- background event publication failures.
- object storage upload errors.

These are more useful than CPU alone.

---

# 3. Infrastructure Metrics

- CPU/memory.
- DB connections/latency.
- query latency.
- disk/storage.
- object storage latency/errors.
- HTTP latency/error.
- auth latency.
- queue/backlog if any.

---

# 4. Client Telemetry

Need internal abstraction:
- app version.
- platform/device class.
- crash.
- startup.
- screen/action performance.
- sync failure.
- offline queue.
- upload failure.
- permission/auth error.

Avoid transmitting sensitive business payloads unnecessarily.

---

# 5. Structured Logs

Logs should have:
- timestamp.
- service/module.
- environment.
- trace/request ID.
- actor/user ID only where lawful/needed and preferably pseudonymous internal identifier.
- object ID where useful.
- error code.

No:
- passwords.
- access tokens.
- salary details.
- personal IDs.
- raw bank secrets.

---

# 6. Integration Observability

Each adapter:
- success/failure.
- latency.
- last success.
- auth status.
- retry.
- unknown outcome.
- provider correlation ID.

Dashboard can distinguish:
HILTECH healthy / Bank unavailable.

---

# 7. Alerts

Alert on actionable symptoms.

Examples:
- payment result unknown too long.
- DB unavailable.
- sync backlog growing.
- failed event publication.
- notification provider failure.
- object storage upload failure.
- auth outage.

Avoid alerting humans on every individual user validation error.

---

# 8. SLOs

Define later by capability:
- API availability.
- sync freshness.
- notification latency.
- finance consistency.
- client portal.

No arbitrary 99.99 before cost/business justification.

---

# 9. Privacy / Retention

Telemetry retention shorter/different from business records.

Need:
- access controls.
- redaction.
- sampling.
- retention.
- incident investigation access.

---

# 10. Operational Dashboards

Separate from Mohamed business dashboard.

Engineering operations dashboard:
- system health.
- error budget/SLO.
- release version.
- integration health.
- queue/backlog.
- database.
- client crash/sync.

## Completion gate
Requires telemetry backend/provider, dashboards, alert routes, runbooks, privacy review and production load validation.

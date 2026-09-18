# ADR-013 — Observability Contract

Status: **ACCEPTED**
Date: 2026-09-18

## Context

HILTECH needs failures to be diagnosable across:
- native client,
- API boundary,
- command/application layer,
- database transaction,
- internal event listeners,
- background/offline sync.

Telemetry must not become a second sensitive-data database.

## Decision

Use **OpenTelemetry-compatible trace/correlation semantics** as the HILTECH observability contract.

Baseline:
- W3C trace context.
- correlationId + operationId for user/business workflow diagnosis.
- structured error codes.
- client telemetry behind an internal abstraction.
- explicit allow-listing of telemetry attributes.
- no raw business payloads, salary values, tokens, personal IDs or evidence content in telemetry by default.

The telemetry backend/vendor remains an infrastructure decision.

## Evidence

SPIKE-14 / GitHub Actions run 35305996737.

A representative failed workflow produced one connected trace:

`client.sync → api.command → command.complete_work → db.transaction / event.listener.audit`

Proven:
- one W3C trace ID across boundaries.
- verified parent/child structure.
- listener failure exported ERROR.
- client failure diagnostic preserved correlationId/operationId/error code.
- tested employee email/phone, salary, bearer token and payload secrets were absent from exported attributes.

Tested OpenTelemetry Java: 1.66.0.

## Consequences

Positive:
- end-to-end diagnosis without guessing.
- vendor-neutral telemetry contract.
- correlation across offline/sync and backend paths.
- privacy boundary is explicit.

Costs:
- instrumentation discipline is required.
- high-cardinality identifiers require retention/access review.
- production sampling, collector and alerting still require design.

## Not Decided Here

- OTLP collector topology.
- metrics/log backend vendor.
- sampling rates.
- retention periods.
- alert thresholds/routes.
- crash-reporting vendor.

## Revisit Triggers

Revisit the contract only if a production requirement cannot be represented safely with W3C/OpenTelemetry semantics. Backend/vendor may change without changing this ADR.

# SPIKE-14 Result — Observability

Date: 2026-09-18
Decision: **ACCEPT — END-TO-END TRACE/CORRELATION CONTRACT PASSED**

## Environment

- OpenTelemetry Java 1.66.0
- Kotlin 2.4.20
- Java 21

GitHub Actions run: 35305996737.

## Trace Proven

Representative failed workflow:

`client.sync → api.command → command.complete_work → db.transaction`

and

`command.complete_work → event.listener.audit`

The event listener intentionally failed.

Proven:
- W3C traceparent propagation,
- one shared trace ID,
- correct parent/child relationships,
- listener span ERROR status,
- correlationId and operationId across boundaries.

## Client Diagnostic Proven

A representative sync failure retained:
- correlation ID,
- operation ID,
- error code,
- object type,
- command type.

## Sensitive-Data Boundary

The test intentionally supplied:
- employee email,
- employee phone,
- salary/net-pay value,
- bearer token,
- national-ID-like payload.

None appeared in exported telemetry attributes.

## Accepted Direction

HILTECH telemetry uses a safe allowlist contract.

Good telemetry dimensions:
- correlation ID,
- operation ID,
- object type,
- command type,
- module,
- error code.

Business payloads and sensitive fields are not telemetry by default.

## Still Open

- production OTLP collector/provider,
- metrics/log storage backend,
- trace sampling,
- retention,
- alert thresholds,
- client crash-reporting vendor,
- external integration trace correlation.

## Production Status

Disposable observability-contract evidence only.

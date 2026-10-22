# SPIKE-14 — Observability

Status: RUNNING

## Exact line

- OpenTelemetry Java 1.66.0
- Kotlin 2.4.20
- Java 21

OpenTelemetry Java traces/metrics/logs are stable at the platform level. This spike focuses on HILTECH's trace/correlation contract.

## Scenario

A field sync command flows through:

`client.sync → api.command → command.complete_work → db.transaction → event.listener.audit`

The audit listener fails.

## Required proof

- W3C `traceparent` propagates from client boundary to server.
- API span is child of client span.
- command span is child of API.
- DB and event-listener spans stay connected to the command.
- failed event listener records ERROR.
- correlationId + operationId survive every stage.
- client sync failure diagnostic carries the same identifiers.
- sensitive employee/payroll/auth/payload values never become telemetry attributes.

## Safe telemetry rule

High-cardinality or sensitive business data is not telemetry by default.

Allowed representative dimensions:
- correlation ID,
- operation ID,
- object type,
- command type,
- error code,
- module.

Examples intentionally excluded:
- employee email/phone,
- national ID,
- salary/net pay,
- auth bearer token,
- command payload/evidence content.

## Pass

ACCEPT if one failed workflow can be reconstructed from exported spans and client diagnostics without sensitive-data leakage.

## Still Open

- OTLP collector/provider deployment.
- production sampling.
- log backend.
- metrics backend.
- retention.
- alert thresholds.
- client crash provider.
- correlation into external bank/storage integrations.

## Production status

Disposable observability contract evidence only.

# 36 — Bootstrap Observability

Date: 2026-09-19
Status: **PASS / BOOTSTRAP VERIFIED**

## Purpose

Materialize the accepted ADR-013 / SPIKE-14 telemetry contract in production server code while keeping application instrumentation vendor-neutral.

## Implementation

Primary commit:
`4cb1363e92eeb3d962a9b9d55eaed320973ea9f8`

Baseline:
- OpenTelemetry Java 1.66.0,
- W3C Trace Context,
- OTLP/gRPC exporter boundary,
- OpenTelemetry Collector as the infrastructure seam,
- OCI exporter/backend concerns kept outside business/domain code.

## Safe telemetry contract

Only the explicit allowlist can become HILTECH telemetry attributes:
- `hiltech.correlation_id`,
- `hiltech.operation_id`,
- `hiltech.object_type`,
- `hiltech.command_type`,
- `hiltech.error_code`,
- `hiltech.module`.

Values are bounded to 160 characters.

Unknown context keys are discarded rather than generically serialized.
Raw payloads, authorization values and sensitive business fields therefore do not enter telemetry by default.

## Runtime behavior

Observability is disabled safely by default and uses a no-op OpenTelemetry implementation without requiring a collector.

When enabled:
- service name is explicit,
- OTLP endpoint is environment-backed,
- W3C propagation is installed,
- exporter timeout is bounded.

## Verification

Canonical run:
`35410739837` (#31) — **SUCCESS**

Tests prove:
- client → server W3C parent/trace continuity,
- same trace ID across the boundary,
- safe correlation/operation attributes survive,
- unknown private context is absent,
- value length is capped,
- disabled observability needs no collector configuration.

The full existing Bootstrap regression matrix also remained green.

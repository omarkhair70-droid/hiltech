# ADR-013 — Observability Contract

Status: **ACCEPTED**
Date: 2026-09-18

## Decision

Use **OpenTelemetry-compatible tracing/correlation** as the HILTECH observability foundation.

Baseline:
- W3C trace context,
- correlationId and operationId preserved across client/server/business-command boundaries,
- server spans cover API → command → DB → internal-event handling,
- failure status recorded on the responsible span,
- client diagnostics use an abstraction rather than vendor-specific calls,
- telemetry attributes use a strict safe allowlist.

## Evidence

SPIKE-14 / GitHub Actions run 35305996737.

One representative failed workflow was reconstructed end-to-end while tested payroll/PII/auth/payload values remained absent from telemetry attributes.

## Sensitive Data

Do not record business payloads by default.

Sensitive values such as:
- salary,
- payroll details,
- national ID,
- email/phone where unnecessary,
- auth tokens,
- raw document/evidence content

must not enter telemetry through generic context maps.

## Vendor Position

This ADR accepts the **telemetry contract**, not a collector/storage vendor.

Collector, metrics/log backend, sampling and retention remain infrastructure decisions.

## Revisit Triggers

Revisit only if a verified production diagnostic requirement cannot be met safely with this contract.

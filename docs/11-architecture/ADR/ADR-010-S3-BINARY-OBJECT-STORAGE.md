# ADR-010 — Binary Object Storage Protocol

Status: **ACCEPTED**
Date: 2026-09-18

## Decision

Use an **S3-compatible object-storage protocol** for HILTECH binary evidence.

Large binaries must not flow through ordinary JSON command/query APIs.

Baseline protocol:
- server-issued short-lived pre-signed PUT,
- expected evidence identity/content metadata,
- expected SHA-256,
- direct client upload,
- explicit server-side finalization,
- finalization verifies actual stored bytes/size/digest,
- authorized short-lived pre-signed GET,
- private bucket/object policy by default.

## Evidence

SPIKE-12 / GitHub Actions run 35305216130.

Proven:
- pre-signed upload,
- retry,
- checksum metadata,
- provider-independent integrity validation,
- pre-signed download,
- unsigned-read denial.

## Provider Position

This ADR accepts the **protocol**, not a vendor.

Production provider remains open until infrastructure/provider comparison is complete.

## Consequences

- evidence upload can scale independently of JSON APIs,
- incomplete/corrupt uploads remain unfinalized,
- local/offline queue may reference durable local binary until upload completes,
- object-storage permissions and signed-URL lifetime become security-sensitive,
- evidence metadata must distinguish RESERVED / UPLOADING / READY / FAILED states.

## Revisit Triggers

Revisit only if a verified evidence requirement cannot be met using S3-compatible semantics.

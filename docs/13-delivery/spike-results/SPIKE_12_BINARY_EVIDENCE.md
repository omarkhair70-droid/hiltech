# SPIKE-12 Result — Binary Evidence Pipeline

Date: 2026-09-18
Decision: **ACCEPT — S3-PROTOCOL BINARY EVIDENCE PATH PASSED**

## Environment

- Kotlin 2.4.20
- Java 21
- AWS SDK for Java 2.55.0
- Moto S3 server 5.2.3 as disposable S3-protocol test endpoint

GitHub Actions run: 35305216130.

Moto is not the production storage-provider decision.

## Proven Flow

1. server creates evidence reservation metadata,
2. server computes/records expected SHA-256,
3. short-lived pre-signed PUT is generated,
4. client uploads binary directly to S3-compatible storage,
5. server finalization reads/verifies stored bytes,
6. size + SHA-256 must match before evidence is READY,
7. client receives short-lived pre-signed GET for authorized download.

## Integrity Failure

A truncated payload was submitted using the upload contract for the full payload.

Moto accepted the raw PUT, demonstrating why HILTECH must not trust provider ingest behavior alone.

HILTECH finalization recomputed the stored bytes and rejected the corrupt object.

Retrying the same evidence with the correct bytes succeeded and finalized.

## Privacy

Unsigned direct object read returned 403.

Authorized pre-signed GET returned the correct bytes.

## Accepted Direction

- large evidence bypasses normal JSON API bodies,
- object storage carries binary bytes,
- PostgreSQL/domain state carries evidence metadata/status,
- expected checksum belongs to the reservation,
- finalization is the authoritative integrity boundary,
- provider checksum enforcement is additional defense-in-depth.

## Still Open

- production S3-compatible provider,
- multipart thresholds,
- retention/versioning/legal hold,
- final evidence metadata schema,
- malware/content validation if required,
- mobile background upload scheduling.

## Production Status

Disposable protocol evidence only.

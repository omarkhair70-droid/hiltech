# SPIKE-12 — Binary Evidence Pipeline

Status: RUNNING

## Goal

Prove HILTECH can keep large field evidence out of normal JSON API bodies.

Representative evidence:
- site photos,
- OTDR/Fluke exports,
- handover files,
- test evidence.

## Architecture under test

1. Server computes/records expected evidence metadata.
2. Server generates a short-lived pre-signed S3 PUT.
3. Client uploads binary directly to S3-compatible storage.
4. SHA-256 is part of the signed upload contract.
5. Evidence metadata carries the expected digest.
6. Finalization validates stored bytes/digest/size before evidence is considered ready.
7. Downloads use short-lived pre-signed GET URLs.
8. Direct unsigned read is denied.

## Failure scenario

A truncated/corrupt body is sent using a URL signed for the full expected SHA-256.

Required:
- the corrupt/truncated upload is rejected either by storage **or by HILTECH finalization**,
- finalization recomputes the stored bytes and never trusts metadata alone,
- the same evidence can be retried with the correct bytes,
- final object passes checksum and size validation.

## Exact test line

- Kotlin 2.4.20
- Java 21
- AWS SDK for Java 2.55.0
- Moto S3 server 5.2.3 as a disposable S3-protocol test endpoint

Moto is **not** the selected HILTECH production object-storage provider.

## Pass

ACCEPT if:
- presigned PUT works,
- checksum header is signed,
- correct upload finalizes,
- corrupt/truncated upload cannot finalize successfully,
- retry succeeds,
- final bytes match expected SHA-256,
- presigned GET works,
- unsigned direct read is denied.

## Production decisions still open

- actual S3-compatible provider.
- retention/legal hold/versioning.
- multipart thresholds.
- malware/content validation where required.
- final metadata DB schema.
- upload reservation/finalize API shapes.

## Production status

Disposable protocol evidence only.


## Moto checksum note

Moto 5.2.3 correctly exercises the S3 signing/private-object protocol but does not enforce the signed SHA-256 checksum on every PUT the way a production provider may.

Therefore the accepted HILTECH invariant is stronger than provider-only validation:

**Evidence is not READY until server-side finalization reads/verifies the stored bytes against the expected SHA-256 and size.**

Provider checksum enforcement is defense-in-depth, not the sole integrity boundary.

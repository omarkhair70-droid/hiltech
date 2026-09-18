# SPIKE-05 Result — Android Camera / QR / Evidence

Date: 2026-09-18
Decision: **ACCEPT — ANDROID CAMERA / QR / LOCAL EVIDENCE PATH PASSED**

## Evidence

GitHub Actions run: 35313483156

Real Android emulator:
- API 36
- CameraX path
- virtual camera scene
- same KMP/Android application line used by the other client spikes

## QR / Asset Context

Representative QR asset-lookup and upload-retry unit paths passed before the device lifecycle test.

The product architecture remains:
scan identity -> authorized Asset Passport/context -> permitted action.

## Camera Permission Denied

With CAMERA permission revoked:

`state=PERMISSION_DENIED`

The screen exposed:
`fallback=MANUAL_ASSET_ID_ALLOWED`

No false capture success was reported.

## Site Camera Prohibited

With camera permission available but the HILTECH site/policy mode explicitly prohibiting capture:

`state=CAMERA_PROHIBITED`

Manual asset identity remained available and no JPEG evidence file was created.

This proves OS permission and HILTECH site policy are separate controls.

## Real CameraX Capture

After enabling capture:

`state=CAPTURED`

Evidence metadata included:
- `state=LOCAL_READY`
- `uploadState=PENDING_UPLOAD`
- SHA-256 digest

Proof-run JPEG size:
190,035 bytes.

The local file is therefore a durable pre-upload artifact rather than an in-memory-only photo.

## Accepted Direction

Android platform adapter:
- CameraX for image capture,
- QR/barcode adapter for physical identity,
- explicit permission state,
- explicit camera-prohibited policy state,
- manual asset-ID fallback,
- durable local evidence first,
- upload is a later sync/storage operation.

This composes with:
- ADR-011 offline command/sync semantics,
- ADR-010 S3-compatible binary evidence protocol.

## Still Open

- exact scanner library/ML implementation,
- final camera/evidence UX,
- physical HILTECH device tests,
- site-specific camera restrictions from reality validation,
- EXIF/location policy,
- image compression/resolution limits,
- malware/content validation where applicable.

## Production Status

Disposable platform evidence only.

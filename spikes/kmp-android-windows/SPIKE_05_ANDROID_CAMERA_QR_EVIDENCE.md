# SPIKE-05 — Android Camera / QR / Evidence

Status: RUNNING

## Candidate line

- CameraX 1.6.2 stable.
- ZXing Core 3.5.4.
- Android API 36 emulator for automated camera lifecycle proof.

## HILTECH Scenarios

### QR → Asset lookup

A QR encodes:

`hiltech://asset/ASSET-FLUKE-03`

The decoder must:
- decode the QR,
- reject unrelated URL schemes/resources,
- return the exact HILTECH asset ID.

### Camera permission denied

If Android CAMERA permission is denied:
- the flow must not crash,
- state is PERMISSION_DENIED,
- manual asset/evidence fallback remains available.

### Camera-prohibited site mode

Some secure sites may prohibit photography.

When site policy says camera prohibited:
- the app must not initialize CameraX,
- state is CAMERA_PROHIBITED,
- manual asset ID flow remains usable,
- no evidence image is created.

### Evidence capture

With camera permission:
- CameraX binds to a real emulator camera provider,
- JPEG is captured directly into app-private storage,
- local file survives the Activity,
- metadata records evidence ID, size, SHA-256 and local path,
- evidence starts LOCAL_READY / PENDING_UPLOAD.

### Upload retry handoff

A local evidence record preserves:
- evidence ID,
- local path,
- SHA-256

through:
LOCAL_READY → UPLOADING → RETRYABLE → UPLOADING → UPLOADED.

SPIKE-12 already proves the actual pre-signed S3 retry/integrity protocol. This spike proves the Android capture-to-upload handoff.

## Pass

ACCEPT if:
- QR decode / asset lookup unit tests pass,
- upload-retry state test passes,
- permission denial remains recoverable,
- camera-prohibited mode remains usable without creating image evidence,
- real API 36 emulator CameraX capture succeeds,
- captured JPEG is non-empty,
- metadata includes a 64-character SHA-256 and PENDING_UPLOAD state.

## Still Open

- real HILTECH field-device camera matrix,
- low-light/focus/flash UX,
- real printed QR scan through live ImageAnalysis,
- secure-site policy source,
- final evidence UI,
- production upload scheduling (SPIKE-13 covers WorkManager feasibility).

## Production status

Disposable Android camera/QR evidence proof only.

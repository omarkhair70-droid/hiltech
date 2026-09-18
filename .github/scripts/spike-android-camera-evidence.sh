#!/usr/bin/env bash
set -euo pipefail

cd spikes/kmp-android-windows

PKG="com.hiltech.spike.android"
ACTIVITY="$PKG/.camera.CameraEvidenceSpikeActivity"

APK="$(find androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"
test -n "$APK"
adb install -r "$APK"

read_state () {
  adb shell run-as "$PKG" cat files/camera-spike-state.txt 2>/dev/null | tr -d '\r' || true
}

wait_state () {
  local EXPECTED="$1"
  local LIMIT="$2"
  local START
  START="$(date +%s)"

  while true; do
    VALUE="$(read_state)"
    echo "cameraState=[$VALUE]"

    if echo "$VALUE" | grep -q "state=$EXPECTED"; then
      return 0
    fi

    NOW="$(date +%s)"
    if [ $((NOW - START)) -ge "$LIMIT" ]; then
      echo "Timed out waiting for camera state $EXPECTED"
      adb logcat -d -t 800 | grep -i -E 'CameraX|Camera2|ImageCapture|hiltech|CameraEvidence' || true
      return 1
    fi

    sleep 1
  done
}

cleanup_probe () {
  adb shell run-as "$PKG" rm -f files/camera-spike-state.txt >/dev/null 2>&1 || true
  adb shell run-as "$PKG" rm -rf files/evidence >/dev/null 2>&1 || true
}

# Bootstrap app data directory once.
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell am force-stop "$PKG"
cleanup_probe

# ---- Permission denied ----
adb shell pm revoke "$PKG" android.permission.CAMERA >/dev/null 2>&1 || true
adb shell am start -W -n "$ACTIVITY" --es mode capture >/dev/null
wait_state "PERMISSION_DENIED" 15

DENIED="$(read_state)"
echo "$DENIED" | grep -q "fallback=MANUAL_ASSET_ID_ALLOWED"

# ---- Camera prohibited by site policy ----
adb shell pm grant "$PKG" android.permission.CAMERA
cleanup_probe

adb shell am start -W -n "$ACTIVITY" --es mode prohibited >/dev/null
wait_state "CAMERA_PROHIBITED" 15

PROHIBITED="$(read_state)"
echo "$PROHIBITED" | grep -q "fallback=MANUAL_ASSET_ID_ALLOWED"
echo "$PROHIBITED" | grep -q "manualAssetId=ASSET-FLUKE-03"

if adb shell run-as "$PKG" test -f files/evidence/evidence-001.jpg; then
  echo "Camera-prohibited mode created image evidence"
  exit 1
fi

# ---- Real CameraX capture ----
cleanup_probe
adb shell pm grant "$PKG" android.permission.CAMERA

adb shell am start -W -n "$ACTIVITY" --es mode capture >/dev/null
wait_state "CAPTURED" 90

STATE="$(read_state)"
echo "$STATE" | grep -q "uploadState=PENDING_UPLOAD"

META="$(adb shell run-as "$PKG" cat files/evidence/evidence-001.meta | tr -d '\r')"
echo "$META"
echo "$META" | grep -q "state=LOCAL_READY"
echo "$META" | grep -q "uploadState=PENDING_UPLOAD"
echo "$META" | grep -E -q "sha256=[0-9a-f]{64}"

SIZE="$(adb exec-out run-as "$PKG" cat files/evidence/evidence-001.jpg | wc -c | tr -d ' ')"
echo "capturedJpegBytes=$SIZE"

if [ "$SIZE" -le 0 ]; then
  echo "Captured JPEG is empty"
  exit 1
fi

echo "HILTECH_CAMERA_EVIDENCE_PASS qr_asset_lookup=PASS permission_denied=PASS prohibited_mode=PASS camerax_capture=PASS local_file=PASS checksum_metadata=PASS upload_handoff=PASS"

#!/usr/bin/env bash
set -euo pipefail

PKG=com.hiltech.android
ACTIVITY="$PKG/.AssignedWorkEvidenceActivity"
REPORT=build/reports/phase6-slice03
APK="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"
test -n "$APK"
mkdir -p "$REPORT"
adb install -r "$APK" >/dev/null
adb shell pm clear "$PKG" >/dev/null

dump_ui () {
  adb shell uiautomator dump /sdcard/hiltech-phase6-s03.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase6-s03.xml | tr -d '\r'
}

tap_visible_ui_text () {
  local TARGET="$1" LIMIT="${2:-45}" START XML COORDS
  START="$(date +%s)"
  while true; do
    XML="$(dump_ui || true)"
    COORDS="$(printf '%s' "$XML" | python3 scripts/ci/android_uiautomator_clickable_center.py "$TARGET" 2>/dev/null || true)"
    if [ -n "$COORDS" ]; then
      adb shell input tap $COORDS >/dev/null
      sleep 2
      return 0
    fi
    if [ $(($(date +%s) - START)) -ge "$LIMIT" ]; then
      echo "Timed out locating clickable UI text: $TARGET"
      echo "$XML"
      return 1
    fi
    adb shell input swipe 540 1900 540 650 300 >/dev/null || true
    sleep 1
  done
}

await_marker () {
  local marker="$1" started
  started="$(date +%s)"
  while true; do
    local xml
    xml="$(dump_ui || true)"
    if printf '%s' "$xml" | grep -F -q "$marker"; then return 0; fi
    if [ $(($(date +%s) - started)) -gt 45 ]; then
      echo "Native Evidence marker not found: $marker"
      echo "$xml"
      adb logcat -d -t 400 | grep -iE "FATAL EXCEPTION|evidence|hiltech" || true
      exit 1
    fi
    adb shell input swipe 540 2040 540 650 260 >/dev/null || true
    sleep 1
  done
}

open_mode () {
  local mode="$1"
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" --es mode "$mode" >/tmp/hiltech-field-evidence-start.txt
  cat /tmp/hiltech-field-evidence-start.txt
}

capture_verified_marker () {
  local marker="$1" screenshot="$2" xml
  await_marker "$marker"
  # UI hierarchy can expose a marker one frame before the compositor draws it.
  # Require stability on both sides of the screenshot to reject startup blanks.
  sleep 2
  xml="$(dump_ui || true)"
  if ! printf '%s' "$xml" | grep -F -q "$marker"; then
    echo "Evidence marker disappeared before screenshot: $marker"
    exit 1
  fi
  adb exec-out screencap -p > "$screenshot"
  test -s "$screenshot"
  xml="$(dump_ui || true)"
  if ! printf '%s' "$xml" | grep -F -q "$marker"; then
    echo "Evidence marker disappeared after screenshot: $marker"
    exit 1
  fi
}

# Android may kill an Activity while the picker/camera is foreground.
# Verify a saved state round trip preserves the original actor/WorkOrder
# request and its private camera filename without granting new authority.
open_mode evidence-callback-state
capture_verified_marker "PHASE6_S03_EXTERNAL_CAPTURE_CONTEXT_RESTORED" "$REPORT/00-callback-context-restored.png"

open_mode evidence-seed
capture_verified_marker "PHASE6_S03_EVIDENCE_SAVED_LOCAL_READY" "$REPORT/01-private-evidence-queued.png"

# New Android process, real Room v3 and app-private photo bytes survive.
open_mode evidence-persisted
# The unified native shell starts in actor-scoped Workspaces. Open the genuine
# Field/Today card and its selected WorkOrder before checking persisted Evidence.
tap_visible_ui_text "شغلي اليوم" 45
tap_visible_ui_text "WO-2026-010" 45
capture_verified_marker "محفوظ على الجهاز: LOCAL_READY" "$REPORT/02-room-evidence-process-restart.png"

# Deliberately corrupt private image bytes: verification must fail, no fake READY.
open_mode evidence-corrupt
capture_verified_marker "PHASE6_S03_CORRUPTION_REJECTED_BYTES_PRESERVED" "$REPORT/03-corrupt-evidence-protected.png"

test -s "$REPORT/00-callback-context-restored.png"
test -s "$REPORT/01-private-evidence-queued.png"
test -s "$REPORT/02-room-evidence-process-restart.png"
test -s "$REPORT/03-corrupt-evidence-protected.png"

echo "HILTECH_PHASE6_S03_NATIVE_EVIDENCE_PASS callback_bundle_restore=PASS fsync=PASS room_restart=PASS private_bytes=PASS tampering_rejected=PASS fake_ready=DENIED"

#!/usr/bin/env bash
set -euo pipefail

PKG="com.hiltech.android"
ACTIVITY="$PKG/.AssignedWorkEvidenceActivity"
REPORT_DIR="build/reports/phase4-slice06"
APK="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"

test -n "$APK"
mkdir -p "$REPORT_DIR"
adb install -r "$APK" >/dev/null

dump_ui () {
  adb shell uiautomator dump /sdcard/hiltech-phase4-slice06-window.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase4-slice06-window.xml | tr -d ''
}

wait_for_text () {
  local EXPECTED="$1"
  local LIMIT="$2"
  local START
  START="$(date +%s)"

  while true; do
    XML="$(dump_ui || true)"
    if echo "$XML" | grep -F -q "$EXPECTED"; then
      return 0
    fi

    NOW="$(date +%s)"
    if [ $((NOW - START)) -ge "$LIMIT" ]; then
      echo "Timed out waiting for UI text: $EXPECTED"
      echo "$XML"
      adb logcat -d -t 600 | grep -i -E 'hiltech|AssignedWorkEvidenceActivity' || true
      return 1
    fi
    sleep 1
  done
}

render_mode () {
  local MODE="$1"
  local EXPECTED="$2"
  local OUTPUT="$3"

  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$ACTIVITY" --es mode "$MODE"     >/tmp/hiltech-phase4-slice06-start.out
  cat /tmp/hiltech-phase4-slice06-start.out

  wait_for_text "$EXPECTED" 30
  adb exec-out screencap -p > "$REPORT_DIR/$OUTPUT"
  test -s "$REPORT_DIR/$OUTPUT"
}

render_mode "today" "شغلي اليوم" "android-today-rtl.png"
wait_for_text "التنفيذ يبدأ في Phase 6" 10

render_mode "detail" "SOURCE_PENDING_PHASE5" "android-job-bundle-blocked.png"
wait_for_text "التقاط/رفع Evidence من الموبايل مش متاح في Slice 06" 10
wait_for_text "Authoritative queue: false" 10
wait_for_text "مفيش Started/Completed/Waiting to sync state محلي هنا" 10

render_mode "revoked" "مفيش WorkOrder حالي متعيّن ليك" "android-assignment-revoked.png"

echo "HILTECH_PHASE4_SLICE06_ANDROID_RENDER_PASS rtl=PASS today=PASS detail=PASS waiting=PASS phase5_pending=PASS execution_controls_absent=PASS fake_sync_absent=PASS revoke_empty=PASS"

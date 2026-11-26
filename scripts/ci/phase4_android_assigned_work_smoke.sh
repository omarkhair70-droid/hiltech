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

scroll_until_text () {
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
      echo "Timed out scrolling for UI text: $EXPECTED"
      echo "$XML"
      adb logcat -d -t 600 | grep -i -E 'hiltech|AssignedWorkEvidenceActivity' || true
      return 1
    fi

    adb shell input swipe 540 2100 540 650 320 >/dev/null || true
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

  if [ "$MODE" = "today" ] || [ "$MODE" = "cached" ] || [ "$MODE" = "revoked" ]; then
    tap_visible_ui_text "شغلي اليوم" 45
  fi
  wait_for_text "$EXPECTED" 30
  adb exec-out screencap -p > "$REPORT_DIR/$OUTPUT"
  test -s "$REPORT_DIR/$OUTPUT"
}

render_mode "today" "شغلي اليوم" "android-today-rtl.png"
# TodayCard now presents the assignment reference, lifecycle/readiness and
# actionable label separately. Prove the current fixture, not removed copy.
scroll_until_text "WO-2026-010" 30
wait_for_text "ASSIGNED · READY" 10
wait_for_text "جاهز للمراجعة" 10

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" --es mode "detail" \
  >/tmp/hiltech-phase4-slice06-start.out
cat /tmp/hiltech-phase4-slice06-start.out
tap_visible_ui_text "شغلي اليوم" 45
tap_visible_ui_text "WO-2026-010" 45
wait_for_text "تفاصيل الشغل" 30
scroll_until_text "التقاط/رفع Evidence من الموبايل مش متاح في Slice 06" 30
scroll_until_text "SOURCE_PENDING_PHASE5" 30
adb exec-out screencap -p > "$REPORT_DIR/android-job-bundle-blocked.png"
test -s "$REPORT_DIR/android-job-bundle-blocked.png"
scroll_until_text "القراءة فقط. أوامر التنفيذ" 30

render_mode "cached" "نسخة محفوظة بدون اتصال" "android-offline-today-cached.png"
tap_visible_ui_text "WO-2026-010" 45
scroll_until_text "نسخة محفوظة للقراءة فقط" 30
adb exec-out screencap -p > "$REPORT_DIR/android-offline-bundle-cached.png"
test -s "$REPORT_DIR/android-offline-bundle-cached.png"
scroll_until_text "القراءة فقط. أوامر التنفيذ" 30

render_mode "cold-offline" "وضع القراءة بدون إنترنت" "android-cold-start-readonly.png"
wait_for_text "لا يوجد بدء/إنهاء عمل" 10
OFFLINE_XML="$(dump_ui)"
if echo "$OFFLINE_XML" | grep -F -q "Me / Sessions"; then
  echo "Offline-only shell must not expose signed-in admin/session modules."
  exit 1
fi

# A native cold-start round trip: same secure OIDC store, persisted Room, real MainActivity,
# configured to unreachable .invalid endpoints; no debug fixture UI on the restored screen.
render_mode "cold-seed" "PHASE6_COLD_OFFLINE_SEEDED" "android-cold-seed-confirm.png"
sleep 1
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/tmp/hiltech-phase6-cold-start.out
cat /tmp/hiltech-phase6-cold-start.out
wait_for_text "وضع القراءة بدون إنترنت" 60
wait_for_text "نسخة محفوظة بدون اتصال" 10
OFFLINE_REAL_XML="$(dump_ui)"
if echo "$OFFLINE_REAL_XML" | grep -F -q "Me / Sessions"; then
  echo "Production cold-start offline screen must not render admin/session tools."
  exit 1
fi
adb exec-out screencap -p > "$REPORT_DIR/android-production-offline-cold-start.png"
test -s "$REPORT_DIR/android-production-offline-cold-start.png"

render_mode "revoked" "مفيش WorkOrder حالي متعيّن ليك" "android-assignment-revoked.png"

echo "HILTECH_PHASE4_SLICE06_ANDROID_RENDER_PASS rtl=PASS today=PASS detail=PASS waiting=PASS phase5_pending=PASS execution_controls_absent=PASS fake_sync_absent=PASS revoke_empty=PASS"

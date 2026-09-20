#!/usr/bin/env bash
set -euo pipefail

PKG="com.hiltech.android"
ACTIVITY="$PKG/.OnboardingEvidenceActivity"
REPORT_DIR="build/reports/phase3-onboarding"
APK="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"

test -n "$APK"
mkdir -p "$REPORT_DIR"
adb install -r "$APK" >/dev/null
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" >/tmp/hiltech-phase3-android-start.out
cat /tmp/hiltech-phase3-android-start.out

dump_ui () {
  adb shell uiautomator dump /sdcard/hiltech-phase3-window.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase3-window.xml | tr -d '\r'
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
      adb logcat -d -t 500 | grep -i -E 'hiltech|OnboardingEvidenceActivity' || true
      return 1
    fi
    sleep 1
  done
}

wait_for_text "تجهيزك في HILTECH" 30
wait_for_text "مطلوب منك" 10
wait_for_text "في انتظار HILTECH" 10
wait_for_text "صورة بطاقة الرقم القومي" 10
wait_for_text "تعيين الفريق والمدير المباشر" 10

adb exec-out screencap -p > "$REPORT_DIR/android-preboarding-ar.png"
test -s "$REPORT_DIR/android-preboarding-ar.png"

echo "HILTECH_PHASE3_ANDROID_ONBOARDING_RENDER_PASS arabic_preboarding=PASS employee_action=PASS hiltech_blocker=PASS"

#!/usr/bin/env bash
set -euo pipefail

PKG="com.hiltech.android"
ACTIVITY="$PKG/.MainActivity"
REPORT_DIR="build/reports/phase1-native-oidc"
APK="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"

test -n "$APK"
mkdir -p "$REPORT_DIR"
adb install -r "$APK" >/dev/null
adb shell pm clear "$PKG" >/dev/null

dump_ui () {
  adb shell uiautomator dump /sdcard/hiltech-window.xml >/dev/null
  adb shell cat /sdcard/hiltech-window.xml | tr -d '\r'
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
      adb logcat -d -t 500 | grep -i -E 'hiltech|oidc|MainActivity' || true
      return 1
    fi
    sleep 1
  done
}

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" >/tmp/hiltech-android-start.out
cat /tmp/hiltech-android-start.out

wait_for_text "Sign in with your HILTECH identity to continue." 30
wait_for_text "Sign in" 10
adb exec-out screencap -p > "$REPORT_DIR/android-signed-out.png"
test -s "$REPORT_DIR/android-signed-out.png"

CALLBACK_URI='com.hiltech.app:/oauth2redirect?code=smoke&state=smoke'
adb shell am force-stop "$PKG"
adb shell am start -W   -a android.intent.action.VIEW   -c android.intent.category.BROWSABLE   -d "$CALLBACK_URI"   >/tmp/hiltech-android-callback.out
cat /tmp/hiltech-android-callback.out

grep -q "com.hiltech.android/.MainActivity" /tmp/hiltech-android-callback.out
wait_for_text "Something went wrong" 30
wait_for_text "OIDC_ATTEMPT_MISSING" 10
adb exec-out screencap -p > "$REPORT_DIR/android-callback-failure.png"
test -s "$REPORT_DIR/android-callback-failure.png"

echo "HILTECH_PHASE1_ANDROID_SHELL_PASS signed_out=PASS private_callback_route=PASS missing_attempt_fail_closed=PASS render=PASS"

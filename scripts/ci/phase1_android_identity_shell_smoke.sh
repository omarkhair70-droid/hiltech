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
  local LIMIT="$1"
  shift
  local START
  START="$(date +%s)"

  while true; do
    XML="$(dump_ui || true)"
    if printf '%s' "$XML" | python3 scripts/ci/phase1_android_ui_text.py "$@"; then
      return 0
    fi

    NOW="$(date +%s)"
    if [ $((NOW - START)) -ge "$LIMIT" ]; then
      printf 'Timed out waiting for exact UI text (supported translations): %s\n' "$*"
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

# The production shell is Arabic-first and also supports English. Assert the
# actual localized labels; an unrelated screen or metadata text must not pass.
wait_for_text 30 "سجّل الدخول بحساب HILTECH للمتابعة." "Sign in with your HILTECH identity to continue."
wait_for_text 10 "تسجيل الدخول" "Sign in"
adb exec-out screencap -p > "$REPORT_DIR/android-signed-out.png"
test -s "$REPORT_DIR/android-signed-out.png"

CALLBACK_URI='com.hiltech.app:/oauth2redirect?code=smoke&state=smoke'
adb shell am force-stop "$PKG"
adb shell am start -W   -a android.intent.action.VIEW   -c android.intent.category.BROWSABLE   -d "$CALLBACK_URI"   >/tmp/hiltech-android-callback.out
cat /tmp/hiltech-android-callback.out

grep -q "com.hiltech.android/.MainActivity" /tmp/hiltech-android-callback.out
wait_for_text 30 "حدث خطأ" "Something went wrong"
wait_for_text 10 "المرجع: OIDC_ATTEMPT_MISSING" "Reference: OIDC_ATTEMPT_MISSING"
adb exec-out screencap -p > "$REPORT_DIR/android-callback-failure.png"
test -s "$REPORT_DIR/android-callback-failure.png"

echo "HILTECH_PHASE1_ANDROID_SHELL_PASS signed_out=PASS private_callback_route=PASS missing_attempt_fail_closed=PASS render=PASS"

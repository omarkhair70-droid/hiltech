#!/usr/bin/env bash
set -euo pipefail

PKG="com.hiltech.android"
ACTIVITY="$PKG/.MainActivity"
REPORT_DIR="build/reports/phase1-native-oidc"
UI_CHECK="scripts/ci/phase1_android_ui_text.py"
REMOTE_UI="/sdcard/hiltech-window.xml"
APK="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"

test -n "$APK"
mkdir -p "$REPORT_DIR"
adb install -r "$APK" >/dev/null
adb shell pm clear "$PKG" >/dev/null

dump_ui () {
  # A failed dump must not reuse XML from the preceding language or Activity.
  adb shell rm -f "$REMOTE_UI" >/dev/null || return 1
  adb shell uiautomator dump "$REMOTE_UI" >/dev/null || return 1
  adb shell cat "$REMOTE_UI" | tr -d '\r'
}

wait_for_shell () {
  local LIMIT="$1"
  local STATE="$2"
  local LANGUAGE="$3"
  local START NOW FAILURE
  START="$(date +%s)"

  while true; do
    XML="$(dump_ui || true)"
    if FAILURE="$(printf '%s' "$XML" | python3 "$UI_CHECK" --state "$STATE" --language "$LANGUAGE" 2>&1)"; then
      return 0
    fi

    NOW="$(date +%s)"
    if [ $((NOW - START)) -ge "$LIMIT" ]; then
      printf 'Timed out waiting for protected Android shell: state=%s language=%s\n%s\n' "$STATE" "$LANGUAGE" "$FAILURE"
      printf '%s\n' "$XML" > "$REPORT_DIR/android-shell-timeout.xml"
      adb logcat -d -t 500 | grep -i -E 'hiltech|oidc|MainActivity' || true
      return 1
    fi
    sleep 1
  done
}

capture_shell () {
  local NAME="$1"
  wait_for_shell 30 "$2" "$3"
  printf '%s\n' "$XML" > "$REPORT_DIR/$NAME.xml"
  adb exec-out screencap -p > "$REPORT_DIR/$NAME.png"
  test -s "$REPORT_DIR/$NAME.png"
}

tap_language () {
  local STATE="$1"
  local LANGUAGE="$2"
  local LABEL="$3"
  local COORDINATES X Y
  XML="$(dump_ui)"
  COORDINATES="$(printf '%s' "$XML" | python3 "$UI_CHECK" --state "$STATE" --language "$LANGUAGE" --tap-text "$LABEL")"
  read -r X Y <<< "$COORDINATES"
  [[ "$X" =~ ^[0-9]+$ && "$Y" =~ ^[0-9]+$ ]]
  adb shell input tap "$X" "$Y"
}

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" >/tmp/hiltech-android-start.out
cat /tmp/hiltech-android-start.out

# Fresh preferences are Arabic-first. Each capture checks the product header,
# localized subtitle/body/action, safe bounds and absence of the old ActionBar.
capture_shell android-signed-out signed-out ar
tap_language signed-out ar English
capture_shell android-signed-out-en signed-out en

CALLBACK_URI='com.hiltech.app:/oauth2redirect?code=smoke&state=smoke'
adb shell am force-stop "$PKG"
# Preserve the full URI through adb's remote shell, including its '&state='.
adb shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
  -d "'$CALLBACK_URI'" >/tmp/hiltech-android-callback.out
cat /tmp/hiltech-android-callback.out

grep -q "com.hiltech.android/.MainActivity" /tmp/hiltech-android-callback.out
capture_shell android-callback-failure-en callback-failure en
tap_language callback-failure en العربية
capture_shell android-callback-failure callback-failure ar

echo "HILTECH_PHASE1_ANDROID_SHELL_PASS signed_out=PASS private_callback_route=PASS missing_attempt_fail_closed=PASS header_bounds=PASS native_action_bar_absent=PASS language_switch=PASS render=PASS"

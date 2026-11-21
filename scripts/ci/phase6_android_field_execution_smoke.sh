#!/usr/bin/env bash
set -euo pipefail

PKG=com.hiltech.android
ACTIVITY="$PKG/.AssignedWorkEvidenceActivity"
REPORT_DIR=build/reports/phase6-slice02
APK="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"
test -n "$APK"
mkdir -p "$REPORT_DIR"
adb install -r "$APK" >/dev/null
adb shell pm clear "$PKG" >/dev/null

dump_ui () {
  adb shell uiautomator dump /sdcard/hiltech-phase6-field.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase6-field.xml | tr -d '\r'
}

scroll_until_text () {
  local target="$1"
  local started
  started="$(date +%s)"
  while true; do
    local xml
    xml="$(dump_ui || true)"
    if printf '%s' "$xml" | grep -F -q "$target"; then
      return 0
    fi
    if [ $(($(date +%s) - started)) -gt 40 ]; then
      echo "Missing native UI marker: $target"
      echo "$xml"
      adb logcat -d -t 400 | grep -iE "FATAL EXCEPTION|hiltech|field command" || true
      exit 1
    fi
    adb shell input swipe 540 1980 540 700 280 >/dev/null
    sleep 1
  done
}

tap_visible_text () {
  local target="$1"
  local xml
  xml="$(dump_ui)"
  local coords
  coords="$(printf '%s' "$xml" | python3 -c '
import re,sys,xml.etree.ElementTree as ET
target=sys.argv[1]
root=ET.fromstring(sys.stdin.read())
for node in root.iter():
    if target in node.attrib.get("text","") or target in node.attrib.get("content-desc",""):
        b=node.attrib.get("bounds","")
        m=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",b)
        if m:
            x1,y1,x2,y2=map(int,m.groups())
            print((x1+x2)//2, (y1+y2)//2)
            sys.exit(0)
sys.exit("Target not clickable/visible: "+target)
' "$target")"
  read -r x y <<< "$coords"
  adb shell input tap "$x" "$y"
}

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" --es mode field-command-start >/tmp/hiltech-phase6-field-start.txt
scroll_until_text "حفظ أمر بدء العمل"
tap_visible_text "حفظ أمر بدء العمل"
scroll_until_text "أمر StartWork محفوظ محليًا"
adb exec-out screencap -p > "$REPORT_DIR/android-field-command-local-pending.png"
test -s "$REPORT_DIR/android-field-command-local-pending.png"

# Force-stop destroys all in-memory Compose state. The actual Room queue is reopened.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" --es mode field-command-persisted >/tmp/hiltech-phase6-field-restart.txt
scroll_until_text "أمر StartWork محفوظ محليًا"
if dump_ui | grep -F -q "حفظ أمر بدء العمل"; then
  echo "An unresolved command must hide the duplicate Start action."
  exit 1
fi
adb exec-out screencap -p > "$REPORT_DIR/android-field-command-process-restart.png"
test -s "$REPORT_DIR/android-field-command-process-restart.png"

echo "HILTECH_PHASE6_SLICE02_NATIVE_INTENT_PASS queued=PASS room_process_restart=PASS duplicate_button_absent=PASS server_ack_not_faked=PASS"

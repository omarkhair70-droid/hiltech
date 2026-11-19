#!/usr/bin/env bash
set -euo pipefail
package=com.hiltech.android
report_dir=build/reports/phase5-asset-android
mkdir -p "$report_dir"
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.StocktakeEvidenceActivity"

dump_ui() {
  adb shell uiautomator dump /sdcard/hiltech-phase5-stocktake.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase5-stocktake.xml | tr -d '\r'
}
position_contains() {
  python3 -c '
import re, sys
from xml.etree import ElementTree as ET
root=ET.fromstring(sys.stdin.read())
matches=[n for n in root.iter("node") if sys.argv[1] in n.attrib.get("text","") and n.attrib.get("enabled")=="true"]
if not matches: sys.exit(1)
x1,y1,x2,y2=map(int,re.findall(r"\d+",matches[0].attrib["bounds"]))
print((x1+x2)//2,(y1+y2)//2)
' "$1"
}
position_clickable_exact() {
  python3 -c '
import re, sys
from xml.etree import ElementTree as ET
root=ET.fromstring(sys.stdin.read())
parent={child:p for p in root.iter() for child in p}
for node in root.iter("node"):
    if node.attrib.get("text")==sys.argv[1] and node.attrib.get("enabled")=="true":
        current=node
        while current is not None:
            if current.attrib.get("clickable")=="true" and current.attrib.get("enabled")=="true":
                x1,y1,x2,y2=map(int,re.findall(r"\d+",current.attrib["bounds"]))
                print((x1+x2)//2,(y1+y2)//2)
                sys.exit(0)
            current=parent.get(current)
sys.exit(1)
' "$1"
}
focus_text() {
  local expected="$1" xy x y xml
  for ((attempt=0; attempt<20; attempt++)); do
    xml="$(dump_ui || true)"
    xy="$(printf '%s' "$xml" | position_contains "$expected" 2>/dev/null || true)"
    if [[ -n "$xy" ]]; then
      read -r x y <<< "$xy"
      adb shell input tap "$x" "$y"
      sleep 1
      return 0
    fi
    adb shell input swipe 500 1750 500 700 350
    sleep 1
  done
  printf 'Missing enabled field: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}
tap_clickable_text() {
  local expected="$1" xy x y xml
  for ((attempt=0; attempt<20; attempt++)); do
    xml="$(dump_ui || true)"
    xy="$(printf '%s' "$xml" | position_clickable_exact "$expected" 2>/dev/null || true)"
    if [[ -n "$xy" ]]; then
      read -r x y <<< "$xy"
      adb shell input tap "$x" "$y"
      sleep 1
      return 0
    fi
    adb shell input swipe 500 1750 500 700 350
    sleep 1
  done
  printf 'Missing enabled clickable control: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}
wait_for_text() {
  local expected="$1" xml
  for ((attempt=0; attempt<20; attempt++)); do
    xml="$(dump_ui || true)"
    if [[ "$xml" == *"$expected"* ]]; then printf '%s' "$xml"; return 0; fi
    sleep 1
  done
  printf 'Missing evidence: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}

wait_for_text 'SYNTH-STOCKTAKE-READY' >/dev/null
wait_for_text 'SYNTH-COUNT-CABLE · SYNTH-BIN-01' >/dev/null
wait_for_text 'Expected 6 EA · balance v4 · item v2' >/dev/null

focus_text 'Counted quantity'
adb shell input text '7'
adb shell input keyevent KEYCODE_BACK
focus_text 'Scan / source reference'
adb shell input text 'SCAN-01'
adb shell input keyevent KEYCODE_BACK
tap_clickable_text 'Record count'
wait_for_text 'SYNTH-STOCKTAKE-COUNT-RECEIPT' >/dev/null
wait_for_text 'Count history · 1 record' >/dev/null
adb exec-out screencap -p > "$report_dir/android-stocktake-count.png"

focus_text 'Counted quantity'
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keyevent KEYCODE_DEL
adb shell input text '5'
adb shell input keyevent KEYCODE_BACK
focus_text 'Scan / source reference'
adb shell input text 'SCAN-02'
adb shell input keyevent KEYCODE_BACK
tap_clickable_text 'Record recount'
wait_for_text 'SYNTH-STOCKTAKE-RECOUNT-RECEIPT' >/dev/null
wait_for_text 'Count history · 2 records' >/dev/null
wait_for_text 'recount' >/dev/null
adb exec-out screencap -p > "$report_dir/android-stocktake-recount.png"

tap_clickable_text 'Submit physical count'
wait_for_text 'SYNTH-STOCKTAKE-SUBMIT-RECEIPT' >/dev/null
wait_for_text 'variance -1' >/dev/null
adb exec-out screencap -p > "$report_dir/android-stocktake-variance.png"

focus_text 'Adjustment reason'
adb shell input text 'Verified%sshortage'
adb shell input keyevent KEYCODE_BACK
tap_clickable_text 'Request adjustment'
wait_for_text 'SYNTH-STOCKTAKE-ADJUSTMENT-RECEIPT' >/dev/null
wait_for_text 'Adjustment requested · -1 EA' >/dev/null
wait_for_text 'Approval · no approval reference recorded' >/dev/null
adb exec-out screencap -p > "$report_dir/android-stocktake-adjustment.png"

test -s "$report_dir/android-stocktake-count.png"
test -s "$report_dir/android-stocktake-recount.png"
test -s "$report_dir/android-stocktake-variance.png"
test -s "$report_dir/android-stocktake-adjustment.png"
echo 'HILTECH_PHASE5_ANDROID_STOCKTAKE_PASS synthetic=true count=PASS recount=PASS variance=PASS adjustment_request=PASS approval_fake_state=ABSENT'

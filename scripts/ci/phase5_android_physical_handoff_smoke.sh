#!/usr/bin/env bash
set -euo pipefail
package=com.hiltech.android
report_dir=build/reports/phase5-asset-android
mkdir -p "$report_dir"
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.PhysicalHandoffEvidenceActivity"
dump_ui() {
  adb shell uiautomator dump /sdcard/hiltech-phase5-handoff.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase5-handoff.xml | tr -d '\r'
}
position() {
  python3 -c '
import re, sys
from xml.etree import ElementTree as ET
root = ET.fromstring(sys.stdin.read())
matches = [n for n in root.iter("node") if n.attrib.get("text") == sys.argv[1] and n.attrib.get("enabled") == "true"]
if not matches: sys.exit(1)
x1,y1,x2,y2 = map(int,re.findall(r"\d+",matches[0].attrib["bounds"]))
if x2 <= x1 or y2 <= y1: sys.exit(1)
print((x1+x2)//2,(y1+y2)//2)
' "$1"
}
tap_text() {
  local expected="$1" xy x y xml
  for ((attempt=0; attempt<14; attempt++)); do
    xml="$(dump_ui || true)"
    if xy="$(printf '%s' "$xml" | position "$expected" 2>/dev/null)"; then
      read -r x y <<< "$xy"
      adb shell input tap "$x" "$y"
      sleep 1
      return 0
    fi
    adb shell input swipe 500 1750 500 700 350
    sleep 1
  done
  printf 'Missing enabled control: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}
wait_for_text() {
  local expected="$1" xml
  for ((attempt=0; attempt<14; attempt++)); do
    xml="$(dump_ui || true)"
    if [[ "$xml" == *"$expected"* ]]; then printf '%s' "$xml"; return 0; fi
    sleep 1
  done
  printf 'Missing evidence: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}
tap_text 'Reservation'
tap_text 'SYNTH-WORK · SYNTH-CABLE · 8 · EA'
tap_text 'Find eligible employees'
tap_text 'Accountable employee'
tap_text 'SYNTH-EMP · Synthetic recipient'
tap_text 'Review physical handoff'
review="$(wait_for_text 'Confirm actual physical handoff to Synthetic recipient.')"
[[ "$review" != *'SYNTH-HANDOFF-RECEIPT'* ]]
adb exec-out screencap -p > "$report_dir/android-handoff-review.png"
tap_text 'Record stock issue'
recorded="$(wait_for_text 'SYNTH-HANDOFF-RECEIPT')"
[[ "$recorded" == *'No current reserved resources awaiting handoff.'* ]]
adb exec-out screencap -p > "$report_dir/android-handoff-receipt.png"
echo 'HILTECH_PHASE5_ANDROID_HANDOFF_PASS synthetic_port=true selection=PASS confirmation=PASS receipt=PASS active_reservation_cleared=PASS'

# Separate debug-only synthetic return/inspection journey using the shared production controls.
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.PhysicalReturnEvidenceActivity"
tap_text 'Destination storage'
tap_text 'SYNTH-BIN · Synthetic return bin'
tap_text 'Observed return condition · UNKNOWN'
tap_text 'GOOD'
wait_for_text 'Observed return condition · GOOD' >/dev/null
tap_text 'Review · Record physical return'
review="$(wait_for_text 'Confirm the actual physical operation for SYNTH-RETURN-TOOL.')"
[[ "$review" != *'SYNTH-RETURN-RECEIPT'* ]]
adb exec-out screencap -p > "$report_dir/android-return-review.png"
tap_text 'Record physical return'
wait_for_text 'SYNTH-RETURN-RECEIPT' >/dev/null
wait_for_text 'BLOCKED_INSPECTION' >/dev/null
tap_text 'Observed condition'
tap_text 'GOOD'
tap_text 'Review · Record inspection'
inspection_review="$(wait_for_text 'Confirm the actual physical operation for SYNTH-RETURN-TOOL.')"
[[ "$inspection_review" != *'SYNTH-INSPECTION-RECEIPT'* ]]
[[ "$inspection_review" == *'BLOCKED_INSPECTION'* ]]
adb exec-out screencap -p > "$report_dir/android-inspection-review.png"
tap_text 'Record inspection'
wait_for_text 'SYNTH-INSPECTION-RECEIPT' >/dev/null
wait_for_text 'Inspection complete · AVAILABLE' >/dev/null
adb exec-out screencap -p > "$report_dir/android-inspection-receipt.png"
echo 'HILTECH_PHASE5_ANDROID_RETURN_PASS synthetic_port=true return=PASS inspection_gate=PASS inspection=PASS receipt=PASS'

#!/usr/bin/env bash
set -euo pipefail
package=com.hiltech.android
report_dir=build/reports/phase5-asset-android
mkdir -p "$report_dir"
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.ReceivingEvidenceActivity"

dump_ui() {
  adb shell uiautomator dump /sdcard/hiltech-phase5-receiving.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase5-receiving.xml | tr -d '\r'
}
position_exact() {
  python3 -c '
import re, sys
from xml.etree import ElementTree as ET
root=ET.fromstring(sys.stdin.read())
matches=[n for n in root.iter("node") if n.attrib.get("text")==sys.argv[1] and n.attrib.get("enabled")=="true"]
if not matches: sys.exit(1)
x1,y1,x2,y2=map(int,re.findall(r"\d+",matches[0].attrib["bounds"]))
print((x1+x2)//2,(y1+y2)//2)
' "$1"
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
tap_match() {
  local mode="$1" expected="$2" xy x y xml
  for ((attempt=0; attempt<18; attempt++)); do
    xml="$(dump_ui || true)"
    if [[ "$mode" == exact ]]; then
      xy="$(printf '%s' "$xml" | position_exact "$expected" 2>/dev/null || true)"
    else
      xy="$(printf '%s' "$xml" | position_contains "$expected" 2>/dev/null || true)"
    fi
    if [[ -n "$xy" ]]; then
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
tap_text() { tap_match exact "$1"; }
focus_text() { tap_match contains "$1"; }
wait_for_text() {
  local expected="$1" xml
  for ((attempt=0; attempt<18; attempt++)); do
    xml="$(dump_ui || true)"
    if [[ "$xml" == *"$expected"* ]]; then printf '%s' "$xml"; return 0; fi
    sleep 1
  done
  printf 'Missing evidence: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}

wait_for_text 'SYNTH-RECEIVING-READY' >/dev/null
focus_text 'Receiving search / scan'
adb shell input text 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
tap_text 'Open scanned Receiving receipt'
wait_for_text 'SYNTH-RECEIVING-SEARCH-PASS' >/dev/null
adb exec-out screencap -p > "$report_dir/android-receiving-search.png"

tap_text 'Condition · unknown'
tap_text 'good'
focus_text 'Serials · comma or new line separated'
adb shell input text 'SER-001,SER-002'
tap_text 'Record Stock receipt'
wait_for_text 'SYNTH-RECEIVING-STOCK-RECEIPT' >/dev/null
wait_for_text 'Serial evidence · SER-001, SER-002' >/dev/null
adb exec-out screencap -p > "$report_dir/android-receiving-stock-receipt.png"

focus_text 'Physical discrepancy description'
adb shell input text 'Short%sdelivery'
tap_text 'Record discrepancy'
wait_for_text 'SYNTH-RECEIVING-DISCREPANCY-RECEIPT' >/dev/null
wait_for_text 'SHORT QTY · Short delivery' >/dev/null
adb exec-out screencap -p > "$report_dir/android-receiving-discrepancy.png"

tap_text 'Complete Receiving'
wait_for_text 'SYNTH-RECEIVING-FINAL-RECEIPT · RECEIVED_WITH_DISCREPANCY' >/dev/null
wait_for_text 'OTHER · SYNTH-MOBILE · RECEIVED_WITH_DISCREPANCY' >/dev/null
adb exec-out screencap -p > "$report_dir/android-receiving-final.png"
echo 'HILTECH_PHASE5_ANDROID_RECEIVING_PASS synthetic=true search=PASS serial=PASS condition=PASS discrepancy=PASS final_receipt=PASS'

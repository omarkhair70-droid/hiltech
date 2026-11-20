#!/usr/bin/env bash
set -euo pipefail

package=com.hiltech.android
report_dir=build/reports/phase5-asset-android
mkdir -p "$report_dir"
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.WarehouseQuickScanEvidenceActivity"

dump_ui() {
  adb shell uiautomator dump /sdcard/hiltech-phase5-quick-scan.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase5-quick-scan.xml | tr -d '\r'
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
    adb shell input swipe 500 1700 500 650 300
    sleep 1
  done
  printf 'Missing field: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}
tap_exact() {
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
    adb shell input swipe 500 1700 500 650 300
    sleep 1
  done
  printf 'Missing control: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}

wait_for_text 'SYNTH-QUICK-SCAN-READY' >/dev/null
focus_text 'AssetTag'
adb shell input text 'synthetic-current-tag'
adb shell input keyevent KEYCODE_BACK
tap_exact 'حل AssetTag / Resolve AssetTag'
wait_for_text 'SYNTH-QUICK-SCAN-TAG-RECEIPT' >/dev/null
wait_for_text 'SYNTH-PASSPORT' >/dev/null
wait_for_text 'Asset canonical · 11111111-2222-4333-8444-555555555555' >/dev/null
adb exec-out screencap -p > "$report_dir/android-quick-scan-tag.png"

focus_text 'Asset search'
adb shell input text 'SYNTH-PASSPORT'
adb shell input keyevent KEYCODE_BACK
tap_exact 'بحث Asset / Search Asset'
wait_for_text 'SYNTH-QUICK-SCAN-ASSET-SEARCH-RECEIPT' >/dev/null
wait_for_text 'Scan/search canonical match · PASS' >/dev/null
adb exec-out screencap -p > "$report_dir/android-quick-scan-asset-match.png"

focus_text 'Stock code / part number'
adb shell input text 'SYNTH-PART-01'
adb shell input keyevent KEYCODE_BACK
tap_exact 'بحث Stock / Search Stock'
wait_for_text 'SYNTH-QUICK-SCAN-STOCK-SEARCH-RECEIPT' >/dev/null
wait_for_text 'SYNTH-STOCK-01' >/dev/null
wait_for_text 'Supplier barcode mapping · unavailable' >/dev/null
adb exec-out screencap -p > "$report_dir/android-quick-scan-stock.png"

test -s "$report_dir/android-quick-scan-tag.png"
test -s "$report_dir/android-quick-scan-asset-match.png"
test -s "$report_dir/android-quick-scan-stock.png"
echo 'HILTECH_PHASE5_ANDROID_QUICK_SCAN_PASS synthetic=true asset_tag=PASS same_object=PASS stock_code_part=PASS no_barcode_invention=PASS'

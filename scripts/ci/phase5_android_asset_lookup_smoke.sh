#!/usr/bin/env bash
set -euo pipefail

package=com.hiltech.android
report_dir=build/reports/phase5-asset-android
apk="$(find apps/androidApp/build/outputs/apk/debug -name '*-debug.apk' -print -quit)"
test -n "$apk"
mkdir -p "$report_dir"
adb install -r "$apk" >/dev/null
adb shell am start -W -n "$package/.AssetLookupEvidenceActivity"

dump_ui() {
  adb shell uiautomator dump /sdcard/hiltech-phase5-asset.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase5-asset.xml | tr -d '\r'
}

wait_for_text() {
  local expected="$1" xml
  for ((attempt=0; attempt<15; attempt++)); do
    xml="$(dump_ui || true)"
    if [[ "$xml" == *"$expected"* ]]; then
      printf '%s' "$xml"
      return 0
    fi
    sleep 1
  done
  printf 'Expected text missing: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}

submit_tag() {
  local position
  position="$(wait_for_text 'Scan or enter opaque tag code' | python3 -c '
import re
import sys
from xml.etree import ElementTree as ET

field = next(node for node in ET.fromstring(sys.stdin.read()).iter("node") if node.attrib.get("class") == "android.widget.EditText")
x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.attrib["bounds"]))
print((x1+x2)//2, (y1+y2)//2)
')"
  read -r x y <<< "$position"
  adb shell input tap "$x" "$y"
  adb shell input text "$1"
  adb shell input keyevent KEYCODE_ENTER
}

submit_tag synthetic-current-tag
current="$(wait_for_text 'SYNTH-PASSPORT')"
[[ "$current" == *'SYNTH-ASSET-BIN'* && "$current" != *'Replace primary tag'* && "$current" != *'Acquisition'* ]]
adb exec-out screencap -p > "$report_dir/android-current-asset.png"
submit_tag synthetic-retired-tag
unavailable="$(wait_for_text 'Asset tag unavailable.' )"
[[ "$unavailable" != *'SYNTH-PASSPORT'* ]]
adb exec-out screencap -p > "$report_dir/android-tag-unavailable.png"
echo 'HILTECH_PHASE5_ASSET_ANDROID_PASS synthetic=true scanner_enter=PASS passport=PASS unavailable=PASS previous_result_cleared=PASS'

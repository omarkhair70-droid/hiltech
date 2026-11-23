#!/usr/bin/env bash
set -euo pipefail

package=com.hiltech.android
report_dir=build/reports/phase5-asset-android
mkdir -p "$report_dir"
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.WarehouseClosureEvidenceActivity"

dump_ui() {
  adb shell uiautomator dump /sdcard/hiltech-phase5-closure.xml >/dev/null
  adb shell cat /sdcard/hiltech-phase5-closure.xml | tr -d '\r'
}
wait_visible() {
  local expected="$1" xml
  for ((attempt=0; attempt<18; attempt++)); do
    xml="$(dump_ui || true)"
    if [[ "$xml" == *"$expected"* ]]; then return 0; fi
    adb shell input swipe 500 1700 500 650 300
    sleep 1
  done
  printf 'Missing closure evidence: %s\n%s\n' "$expected" "$xml" >&2
  return 1
}

wait_visible 'SYNTH-SAFE-ASSET'
all_xml=""
for ((page=0; page<7; page++)); do
  xml="$(dump_ui || true)"
  all_xml+="$xml"
  adb shell input swipe 500 1700 500 650 300
  sleep 1
done
[[ "$all_xml" == *'البحث السريع / Quick Scan'* ]]
# The shared authority gate intentionally renders one Arabic blocked status,
# not two English labels. Assert the exact product-visible denial, never
# weaken the revoked actor or require inaccessible controls to appear.
for expected in \
  'التسليم الفعلي غير متاح لهذه الهوية' \
  'سلطة تعديل المخزن مرفوضة'; do
  if [[ "$all_xml" != *"$expected"* ]]; then
    printf 'Missing revoked-authority denial: %s\n' "$expected" >&2
    exit 1
  fi
done
[[ "$all_xml" != *'UNEXPECTED AUTHORIZED MUTATION'* ]]
for forbidden in 'Acquisition cost' 'acquisitionCost' 'candidateValue' 'Purchase price' 'Unit cost' 'Currency code' 'Payroll' 'Invoice total'; do
  if [[ "$all_xml" == *"$forbidden"* ]]; then
    printf 'Restricted value leaked into Android Warehouse surface: %s\n' "$forbidden" >&2
    exit 1
  fi
done
adb exec-out screencap -p > "$report_dir/android-slice09-revoked-safe.png"
test -s "$report_dir/android-slice09-revoked-safe.png"
echo 'HILTECH_PHASE5_ANDROID_SLICE09_CLOSURE_PASS revoked_authority=DENIED restricted_value=REDACTED safe_lookup=PASS'

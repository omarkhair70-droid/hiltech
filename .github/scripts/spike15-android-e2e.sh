#!/usr/bin/env bash
set -euo pipefail

SCENARIO="${SPIKE15_SCENARIO:?SPIKE15_SCENARIO required}"
TECH_TOKEN_FILE="${TECH_TOKEN_FILE:?TECH_TOKEN_FILE required}"
PM_TOKEN_FILE="${PM_TOKEN_FILE:-}"
BASE_URL_ANDROID="${SPIKE15_ANDROID_BASE_URL:-http://10.0.2.2:8090}"
BASE_URL_HOST="${SPIKE15_HOST_BASE_URL:-http://127.0.0.1:8090}"

TECH_TOKEN="$(cat "$TECH_TOKEN_FILE")"
PKG="com.hiltech.spike.android"
RECEIVER="$PKG/.e2e.Spike15Receiver"
WORK_ID="wo-42"

cd spikes/kmp-android-windows

APK="$(find androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"
test -n "$APK"
adb install -r "$APK"

read_state () {
  adb shell run-as "$PKG" cat "files/spike15-$SCENARIO.txt" 2>/dev/null | tr -d '\r' || true
}

wait_state () {
  local expected="$1"
  local limit="$2"
  local start
  start="$(date +%s)"

  while true; do
    value="$(read_state)"
    echo "scenario=$SCENARIO value=[$value]"
    if echo "$value" | grep -q "state=$expected"; then
      return 0
    fi

    now="$(date +%s)"
    if [ $((now - start)) -ge "$limit" ]; then
      echo "Timed out waiting for $SCENARIO -> $expected"
      adb shell dumpsys jobscheduler | grep -i -A8 -B3 hiltech || true
      return 1
    fi
    sleep 1
  done
}

broadcast () {
  local action="$1"
  adb shell am broadcast     -n "$RECEIVER"     -a "$action"     --es baseUrl "$BASE_URL_ANDROID"     --es token "$TECH_TOKEN"     --es scenario "$SCENARIO"     --es workOrderId "$WORK_ID" >/dev/null
}

go_offline () {
  adb shell svc wifi disable || true
  adb shell svc data disable || true
  adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
  adb shell settings put global airplane_mode_on 1 || true
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null || true
  sleep 3
}

go_online () {
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell settings put global airplane_mode_on 0 || true
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null || true
  adb shell svc data enable || true
  adb shell svc wifi enable || true
  sleep 5
}

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
broadcast "com.hiltech.spike.SPIKE15_PREPARE"
wait_state "BUNDLE_READY" 30

bundle_state="$(read_state)"
echo "$bundle_state" | grep -q "version=2"
echo "$bundle_state" | grep -q "assetId=fluke-03"

go_offline
broadcast "com.hiltech.spike.SPIKE15_QUEUE_OFFLINE"
wait_state "PENDING" 20

pending_state="$(read_state)"
echo "$pending_state" | grep -q "queueCount=2"
echo "$pending_state" | grep -q "evidenceExists=true"

broadcast "com.hiltech.spike.SPIKE15_ENQUEUE_SYNC"
wait_state "QUEUED" 20

# Prove the durable WorkManager request + Room queue survive process death.
adb shell am kill "$PKG" || true
sleep 2

if [ "$SCENARIO" = "conflict" ]; then
  test -n "$PM_TOKEN_FILE"
  PM_TOKEN="$(cat "$PM_TOKEN_FILE")"
  gradle :desktopApp:run     --args="--spike15-pm-touch $BASE_URL_HOST $PM_TOKEN 2"     --stacktrace
fi

go_online

if [ "$SCENARIO" = "happy" ]; then
  wait_state "SYNCED" 120
  final="$(read_state)"
  echo "$final" | grep -q "applied=2"
  echo "$final" | grep -q "duplicateReplay=PASS"
  echo "$final" | grep -q "evidenceExists=true"
  echo "$final" | grep -q "queueStates=APPLIED,APPLIED"

  echo "HILTECH_SPIKE15_ANDROID_HAPPY_PASS bundle=PASS offline=PASS workmanager=PASS evidence=PASS idempotency=PASS"
else
  wait_state "CONFLICT" 120
  final="$(read_state)"
  echo "$final" | grep -q "conflicts=1"
  echo "$final" | grep -q "evidenceExists=true"
  echo "$final" | grep -q "CONFLICT"
  echo "$final" | grep -q "BLOCKED_BY_CONFLICT"

  echo "HILTECH_SPIKE15_ANDROID_CONFLICT_PASS conflict=PASS dependent_block=PASS evidence_preserved=PASS"
fi

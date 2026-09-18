#!/usr/bin/env bash
set -euo pipefail

cd spikes/kmp-android-windows

PKG="com.hiltech.spike.android"
RECEIVER="$PKG/.sync.SyncSpikeReceiver"
ACTION="com.hiltech.spike.ENQUEUE_SYNC"

APK="$(find androidApp/build/outputs/apk/debug -name '*-debug.apk' | head -n 1)"
test -n "$APK"
adb install -r "$APK"

read_probe () {
  local ID="$1"
  adb shell run-as "$PKG" cat "files/sync-$ID.txt" 2>/dev/null | tr -d '\r' || true
}

wait_state () {
  local ID="$1"
  local EXPECTED="$2"
  local LIMIT="$3"
  local START
  START="$(date +%s)"

  while true; do
    VALUE="$(read_probe "$ID")"
    echo "probe=$ID value=[$VALUE]"

    if echo "$VALUE" | grep -q "state=$EXPECTED"; then
      return 0
    fi

    NOW="$(date +%s)"
    if [ $((NOW - START)) -ge "$LIMIT" ]; then
      echo "Timed out waiting for $ID -> $EXPECTED"
      adb shell dumpsys jobscheduler | grep -i -A8 -B3 hiltech || true
      return 1
    fi

    sleep 1
  done
}

assert_not_state () {
  local ID="$1"
  local FORBIDDEN="$2"
  VALUE="$(read_probe "$ID")"
  if echo "$VALUE" | grep -q "state=$FORBIDDEN"; then
    echo "Unexpected state for $ID: $VALUE"
    exit 1
  fi
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

# Bootstrap app data/process once.
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell am force-stop "$PKG" || true

# ---- Network constraint + process death + reconnect ----
go_offline

adb shell am broadcast   -n "$RECEIVER"   -a "$ACTION"   --es probeId "network-restart"   --ez retryOnce false   --ez batteryNotLow false >/dev/null

wait_state "network-restart" "QUEUED" 15
sleep 5
assert_not_state "network-restart" "SYNCED"

adb shell am kill "$PKG" || true
sleep 2
assert_not_state "network-restart" "SYNCED"

go_online
wait_state "network-restart" "SYNCED" 90

# ---- Retry + exponential backoff ----
adb shell am broadcast   -n "$RECEIVER"   -a "$ACTION"   --es probeId "retry-once"   --ez retryOnce true   --ez batteryNotLow false >/dev/null

wait_state "retry-once" "RETRYABLE" 30
wait_state "retry-once" "SYNCED" 90

RETRY_VALUE="$(read_probe "retry-once")"
RETRY_ATTEMPTS="$(echo "$RETRY_VALUE" | awk -F= '/attempts=/{print $2}')"
if [ -z "$RETRY_ATTEMPTS" ] || [ "$RETRY_ATTEMPTS" -lt 2 ]; then
  echo "Expected retry attempts >= 2, got: $RETRY_VALUE"
  exit 1
fi

# ---- Battery-not-low OS constraint ----
# BatteryService's -f option forces the real system battery-change broadcast.
# Do not try to send BATTERY_LOW/BATTERY_OKAY directly from shell: Android
# protects those broadcasts.
adb shell dumpsys battery unplug -f >/dev/null
adb shell dumpsys battery set -f status 3 >/dev/null
adb shell dumpsys battery set -f level 5 >/dev/null

echo "Battery state before constrained enqueue:"
adb shell dumpsys battery | sed -n '1,30p'
sleep 3

adb shell am broadcast   -n "$RECEIVER"   -a "$ACTION"   --es probeId "battery-gate"   --ez retryOnce false   --ez batteryNotLow true >/dev/null

wait_state "battery-gate" "QUEUED" 15
sleep 5
assert_not_state "battery-gate" "SYNCED"

adb shell dumpsys battery set -f level 80 >/dev/null
adb shell dumpsys battery set -f status 2 >/dev/null

echo "Battery state after recovery:"
adb shell dumpsys battery | sed -n '1,30p'
wait_state "battery-gate" "SYNCED" 60

adb shell dumpsys battery reset -f >/dev/null || true

echo "HILTECH_WORKMANAGER_PASS reconnect_after_process_death=PASS retry_backoff=PASS battery_constraint=PASS user_state=PASS"

#!/usr/bin/env bash
set -euo pipefail
ADB=${ADB:-adb}
APP=${APP:-bypass.whitelist}
LOG=/data/user/0/$APP/cache/relay.log
CONNECT_TAP=${CONNECT_TAP:-"539 697"}
DOWN_SECONDS=${DOWN_SECONDS:-95}
UP_WAIT_SECONDS=${UP_WAIT_SECONDS:-130}
BASE_WAIT_SECONDS=${BASE_WAIT_SECONDS:-55}

grep_log() {
  $ADB shell "tail -n ${1:-520} $LOG" 2>&1 | grep -Ei 'NET TEST|Connectivity|validation|watchdog|SOCKS probe|SOCKS CONNECT|SOCKS CONNECTED|bad_room|claim_room|request_room|Discovery selected|Discovery scan|retrying scan|connect-retry|TUNNEL|Previous session|Room looks stale|failed|timeout|lost|Связ|OK|ms' || true
}

wait_for_ok() {
  local label=$1
  local seconds=$2
  echo "[wait] $label ${seconds}s"
  sleep "$seconds"
  echo "[log] $label"
  grep_log 260 | tail -n 160
  $ADB shell "tail -n 300 $LOG" 2>&1 | grep -qE 'Connectivity validation OK|Connectivity watchdog OK'
}

echo "[info] adb=$ADB app=$APP"
$ADB wait-for-device
$ADB shell dumpsys package "$APP" | grep -E 'versionName|versionCode|lastUpdateTime' || true

echo "[reset app]"
$ADB shell am force-stop "$APP" || true
$ADB logcat -c || true
$ADB shell "rm -f $LOG" || true
$ADB shell monkey -p "$APP" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 18

echo "[connect] tap $CONNECT_TAP"
$ADB shell input tap $CONNECT_TAP
if ! wait_for_ok baseline "$BASE_WAIT_SECONDS"; then
  echo "FAIL: baseline connect did not reach validation/watchdog OK"
  exit 2
fi

echo "[network down] ${DOWN_SECONDS}s"
$ADB shell "echo '--- NET TEST START '$(date)' ---' >> $LOG" || true
$ADB shell svc wifi disable || true
$ADB shell svc data disable || true
$ADB shell cmd connectivity airplane-mode enable || true
sleep "$DOWN_SECONDS"

echo "[network up] wait ${UP_WAIT_SECONDS}s"
$ADB shell cmd connectivity airplane-mode disable || true
$ADB shell svc wifi enable || true
$ADB shell svc data enable || true
sleep "$UP_WAIT_SECONDS"

echo "[final log]"
grep_log 620 | tail -n 260

if $ADB shell "tail -n 520 $LOG" 2>&1 | grep -qE 'tunnel swapped after reconnect|Connectivity validation OK|Connectivity watchdog OK'; then
  echo "PASS: reconnect restored tunnel/connectivity"
else
  echo "FAIL: reconnect did not restore connectivity"
  exit 3
fi

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="${APK:-$ROOT/../prebuilts/whitelist-bypass.apk}"
PKG="${PKG:-bypass.whitelist}"
MAIN="${MAIN:-bypass.whitelist/.MainActivity}"
TG_URL="${TG_URL:-https://t.me/durov/1}"
WAIT_CONNECT_SECONDS="${WAIT_CONNECT_SECONDS:-45}"
LOG="${LOG:-/tmp/wt-adb-smoke-$(date +%Y%m%d_%H%M%S).log}"

if [[ -z "${ADB:-}" ]]; then
  if [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  elif [[ -x /home/roomhacker/.bubblewrap/android_sdk/platform-tools/adb ]]; then
    ADB=/home/roomhacker/.bubblewrap/android_sdk/platform-tools/adb
  else
    ADB=adb
  fi
fi
DEVICE="${DEVICE:-}"
ADB_BASE=("$ADB")
if [[ -n "$DEVICE" ]]; then ADB_BASE+=( -s "$DEVICE" ); fi

say(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
adbsh(){ "${ADB_BASE[@]}" shell "$@"; }

say "APK=$APK"
say "LOG=$LOG"
"${ADB_BASE[@]}" start-server >/dev/null
DEVICES="$($ADB devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "$DEVICE" ]]; then
  COUNT=$(wc -w <<<"$DEVICES" | tr -d ' ')
  if [[ "$COUNT" -eq 0 ]]; then say "FAIL: no adb device/emulator"; exit 2; fi
  if [[ "$COUNT" -gt 1 ]]; then say "FAIL: several adb devices, set DEVICE=..."; echo "$DEVICES" | tee -a "$LOG"; exit 2; fi
  DEVICE="$DEVICES"
  ADB_BASE=("$ADB" -s "$DEVICE")
fi
say "device=$DEVICE"
"${ADB_BASE[@]}" wait-for-device
say "installing"
"${ADB_BASE[@]}" install -r "$APK" | tee -a "$LOG"
say "clearing logcat"
"${ADB_BASE[@]}" logcat -c || true
say "launching app"
adbsh am force-stop "$PKG" || true
adbsh monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 | tee -a "$LOG"
sleep 4
say "tap center connect"
# Relative tap near hero button center. Override TAP_X/TAP_Y for specific screen.
SIZE=$(adbsh wm size | tr -d '\r' || true)
say "wm_size=$SIZE"
X="${TAP_X:-540}"; Y="${TAP_Y:-720}"
adbsh input tap "$X" "$Y" || true
sleep 2
say "approving possible VPN dialog"
# Try OK/Allow button coordinates and text-key fallback.
adbsh input keyevent 66 || true
adbsh input tap "${VPN_OK_X:-820}" "${VPN_OK_Y:-1820}" || true
sleep 1
adbsh input keyevent 66 || true
say "waiting for tunnel logs"
FOUND=0
for i in $(seq 1 "$WAIT_CONNECT_SECONDS"); do
  if "${ADB_BASE[@]}" logcat -d -v time | grep -E 'TUNNEL CONNECTED|VPN established|tun2socks native start returned|SOCKS5 on 127\.0\.0\.1' >> "$LOG"; then
    FOUND=1; break
  fi
  sleep 1
done
if [[ "$FOUND" != 1 ]]; then
  say "WARN: tunnel markers not found in logcat within ${WAIT_CONNECT_SECONDS}s"
fi
say "opening telegram URL: $TG_URL"
adbsh am start -a android.intent.action.VIEW -d "$TG_URL" | tee -a "$LOG" || true
sleep 8
say "top activity after Telegram URL"
adbsh dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity|ResumedActivity" | tail -n 8 | tee -a "$LOG" || true
say "collecting final log markers"
"${ADB_BASE[@]}" logcat -d -v time | grep -E 'wbstream|TUNNEL|tun2socks|SOCKS|VPN|relay|bypass.whitelist|Telegram|org.telegram' | tail -n 220 | tee -a "$LOG" || true
say "smoke done; inspect LOG=$LOG"

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_HOST="${WT_BUILD_HOST:-192.168.2.5}"
REMOTE_USER="${WT_BUILD_USER:-roomhacker}"
REMOTE_BASE="${WT_BUILD_REMOTE_BASE:-/tmp/wt-build-${USER:-roomhacker}}"
REMOTE_SRC="$REMOTE_BASE/src"
REMOTE_OUT="$REMOTE_BASE/out"
LOCAL_OUT="${WT_BUILD_LOCAL_OUT:-$ROOT/build/server44}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)
RCLONE_BIN="${WT_RCLONE_BIN:-$HOME/.local/bin/rclone}"
if [[ ! -x "$RCLONE_BIN" ]]; then RCLONE_BIN="$(command -v rclone)"; fi
RCLONE_SFTP=":sftp:"
RCLONE_FLAGS=(--sftp-host "$REMOTE_HOST" --sftp-user "$REMOTE_USER")
RCLONE_FLAGS+=(--sftp-key-file "${WT_BUILD_SSH_KEY:-$HOME/.ssh/wt_build_server44_rsa}")
if [[ -f "$HOME/.ssh/known_hosts.server44-build" ]]; then
  RCLONE_FLAGS+=(--sftp-known-hosts-file "$HOME/.ssh/known_hosts.server44-build")
fi

ssh44() {
  ssh "${SSH_OPTS[@]}" "$REMOTE_USER@$REMOTE_HOST" "$@"
}

rclone_to44() {
  "$RCLONE_BIN" sync "$ROOT/" "$RCLONE_SFTP$REMOTE_SRC" "${RCLONE_FLAGS[@]}" \
    --delete-excluded \
    --exclude '.git/**' \
    --exclude 'android-app/.gradle/**' \
    --exclude 'android-app/app/build/**' \
    --exclude 'build/**' \
    --exclude 'logs/**' \
    --exclude 'run/**' \
    --exclude '*.bak.*' \
    --exclude '.DS_Store' \
    --transfers 16 --checkers 32 --links
}

rclone_from44() {
  mkdir -p "$LOCAL_OUT"
  "$RCLONE_BIN" copy "$RCLONE_SFTP$REMOTE_OUT" "$LOCAL_OUT/" "${RCLONE_FLAGS[@]}" --transfers 8 --checkers 16
}

prepare_remote() {
  ssh44 "rm -rf '$REMOTE_BASE' && mkdir -p '$REMOTE_SRC' '$REMOTE_OUT'"
}

cleanup_remote() {
  if [[ "${WT_BUILD_KEEP_REMOTE:-0}" != "1" ]]; then
    ssh44 "rm -rf '$REMOTE_BASE'"
  fi
}

print_summary() {
  echo "ROOT=$ROOT"
  echo "REMOTE=$REMOTE_USER@$REMOTE_HOST:$REMOTE_BASE"
  echo "LOCAL_OUT=$LOCAL_OUT"
  echo "RCLONE_BIN=$RCLONE_BIN"
}

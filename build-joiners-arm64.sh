#!/bin/sh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
HEADLESS_DIR="$ROOT/headless"
PREBUILTS="$ROOT/prebuilts"

mkdir -p "$PREBUILTS"

echo "=== Building headless-wbstream-joiner (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/wbstream-joiner" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-wbstream-joiner-linux-arm64" .

echo "=== Building headless-telemost-joiner (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/telemost-joiner" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-telemost-joiner-linux-arm64" .

echo "=== Building headless-dion-joiner (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/dion-joiner" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-dion-joiner-linux-arm64" .

echo ""
echo "=== Done ==="
ls -lh "$PREBUILTS"/headless-*-joiner-linux-arm64

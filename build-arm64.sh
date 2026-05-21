#!/bin/sh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
HEADLESS_DIR="$ROOT/headless"
PREBUILTS="$ROOT/prebuilts"

mkdir -p "$PREBUILTS"

echo "=== Building headless-vk-bot (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/vk-bot" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-vk-bot-linux-arm64" .

echo "=== Building headless-vk-creator (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/vk" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-vk-creator-linux-arm64" .

echo "=== Building headless-telemost-creator (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/telemost" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-telemost-creator-linux-arm64" .

echo "=== Building headless-wbstream-creator (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/wbstream" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-wbstream-creator-linux-arm64" .

echo "=== Building headless-dion-creator (Linux ARM64) ==="
GOOS=linux GOARCH=arm64 go -C "$HEADLESS_DIR/dion" build -trimpath -ldflags="-s -w" -o "$PREBUILTS/headless-dion-creator-linux-arm64" .

echo ""
echo "=== Done ==="
ls -lh "$PREBUILTS"/*-linux-arm64

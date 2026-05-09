#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "Building headless-vk-creator..."
go -C "$ROOT/headless/vk" build -ldflags="-s -w" -o headless-vk-creator .

echo "Building headless-telemost-creator..."
go -C "$ROOT/headless/telemost" build -ldflags="-s -w" -o headless-telemost-creator .

echo "Building headless-wbstream-creator..."
go -C "$ROOT/headless/wbstream" build -ldflags="-s -w" -o headless-wbstream-creator .

echo "Building headless-wbstream-joiner..."
go -C "$ROOT/headless/wbstream-joiner" build -ldflags="-s -w" -o headless-wbstream-joiner .

echo "Building headless-telemost-joiner..."
go -C "$ROOT/headless/telemost-joiner" build -ldflags="-s -w" -o headless-telemost-joiner .

echo "Done."
ls -lh "$ROOT/headless/vk/headless-vk-creator" "$ROOT/headless/telemost/headless-telemost-creator" "$ROOT/headless/wbstream/headless-wbstream-creator" "$ROOT/headless/wbstream-joiner/headless-wbstream-joiner" "$ROOT/headless/telemost-joiner/headless-telemost-joiner"

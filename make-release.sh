#!/bin/sh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
PREBUILTS="$ROOT/prebuilts"

mkdir -p "$PREBUILTS"

echo "=== Building Android app ==="
"$ROOT/build-go.sh"
"$ROOT/build-android.sh"

echo ""
echo "=== Building creator-app + headless creators ==="
"$ROOT/build-creator.sh"

echo ""
echo "=== Building headless CLI zips per architecture ==="
"$ROOT/build-cli.sh"

echo ""
echo "=== Building desktop joiner Electron app (Windows + Linux + macOS) ==="
"$ROOT/build-joiner-app.sh"

if [ "$(uname)" = "Darwin" ]; then
    echo ""
    echo "=== Building iOS app ==="
    "$ROOT/build-ios.sh"
else
    echo ""
    echo "=== Skipping iOS build (requires macOS) ==="
fi

"$ROOT/clean-prebuilts.sh"

echo ""
echo "=== Release complete ==="
ls -lh "$PREBUILTS/"

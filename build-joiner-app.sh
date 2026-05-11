#!/bin/sh
set -e

# Builds the user-facing Electron joiner app for Windows (portable .exe)
# and Linux (AppImage), following the same per-arch bundle pattern as
# build-creator.sh. Output ends up in prebuilts/ via electron-builder's
# directories.output.

ROOT="$(cd "$(dirname "$0")" && pwd)"
JOINER_GO_DIR="$ROOT/joiner-desktop-app/desktop-joiner"
ELECTRON_DIR="$ROOT/joiner-desktop-app"

echo "=== Building Go backend ==="
"$ROOT/build-desktop-joiner.sh"

cd "$ELECTRON_DIR"
if [ ! -d node_modules/typescript ]; then
    echo "[npm] installing dev deps"
    npm install
fi
npx tsc

cleanup_artifacts() {
    rm -f "$JOINER_GO_DIR"/desktop-joiner-windows-*.exe \
          "$JOINER_GO_DIR"/desktop-joiner-linux-* \
          "$JOINER_GO_DIR"/desktop-joiner-bundle \
          "$JOINER_GO_DIR"/desktop-joiner-bundle.exe \
          "$JOINER_GO_DIR"/wintun-*.dll
}
trap cleanup_artifacts EXIT

for pair in "x64 --x64" "ia32 --ia32" "arm64 --arm64"; do
    arch="${pair% *}"
    flag="${pair#* }"
    echo ""
    echo "--- Windows $arch ---"
    cp "$JOINER_GO_DIR/desktop-joiner-windows-$arch.exe" "$JOINER_GO_DIR/desktop-joiner-bundle.exe"
    cp "$JOINER_GO_DIR/wintun-$arch.dll" "$JOINER_GO_DIR/wintun-bundle.dll"
    npx electron-builder --win $flag --publish never
done

for pair in "x64 --x64" "arm64 --arm64"; do
    arch="${pair% *}"
    flag="${pair#* }"
    echo ""
    echo "--- Linux $arch ---"
    cp "$JOINER_GO_DIR/desktop-joiner-linux-$arch" "$JOINER_GO_DIR/desktop-joiner-bundle"
    chmod +x "$JOINER_GO_DIR/desktop-joiner-bundle"
    npx electron-builder --linux $flag --publish never
done

"$ROOT/clean-prebuilts.sh"

echo ""
echo "=== Done ==="
ls -lh "$ROOT/prebuilts"/WhitelistBypass* 2>/dev/null || true

#!/bin/sh
set -e

# Builds the standalone headless creators, joiners and vk-bot for every supported
# Linux CPU architecture and packages each architecture into one zip under
# prebuilts. Binaries inside a zip keep their plain names, so a user unzips and
# runs ./headless-vk-creator directly.

ROOT="$(cd "$(dirname "$0")" && pwd)"
HEADLESS="$ROOT/headless"
PREBUILTS="$ROOT/prebuilts"
mkdir -p "$PREBUILTS"

# entry format is sourceDir:outputBinaryName
COMPONENTS="\
vk:headless-vk-creator \
telemost:headless-telemost-creator \
wbstream:headless-wbstream-creator \
dion:headless-dion-creator \
telemost-joiner:headless-telemost-joiner \
wbstream-joiner:headless-wbstream-joiner \
dion-joiner:headless-dion-joiner \
vk-bot:headless-vk-bot"

build_arch() {
    arch_label="$1"
    goarch="$2"
    stage="$PREBUILTS/stage-linux-$arch_label"
    zip_path="$PREBUILTS/whitelist-bypass-cli-linux-$arch_label.zip"
    rm -rf "$stage" "$zip_path"
    mkdir -p "$stage"

    echo "=== linux/$goarch $arch_label ==="
    for entry in $COMPONENTS; do
        dir=${entry%%:*}
        bin=${entry##*:}
        echo "  $bin"
        GOOS=linux GOARCH="$goarch" go -C "$HEADLESS/$dir" build -trimpath -ldflags="-s -w" -o "$stage/$bin" .
    done

    ( cd "$stage" && zip -q -j "$zip_path" ./* )
    rm -rf "$stage"
    echo "  -> $(basename "$zip_path")"
}

build_arch x64 amd64
build_arch ia32 386
build_arch arm64 arm64

echo ""
echo "=== Done ==="
ls -lh "$PREBUILTS"/whitelist-bypass-cli-linux-*.zip

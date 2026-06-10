#!/bin/sh
set -e

# Builds the standalone headless creators, joiners and vk-bot for every supported
# OS and CPU architecture and packages each target into one zip under prebuilts.
# Binaries inside a zip keep their plain names, so a user unzips and runs
# ./headless-vk-creator directly.

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

build_target() {
    goos="$1"
    arch_label="$2"
    goarch="$3"
    stage="$PREBUILTS/stage-$goos-$arch_label"
    zip_path="$PREBUILTS/whitelist-bypass-cli-$goos-$arch_label.zip"
    rm -rf "$stage" "$zip_path"
    mkdir -p "$stage"

    echo "=== $goos/$goarch $arch_label ==="
    for entry in $COMPONENTS; do
        dir=${entry%%:*}
        bin=${entry##*:}
        echo "  $bin"
        GOOS="$goos" GOARCH="$goarch" go -C "$HEADLESS/$dir" build -trimpath -ldflags="-s -w" -o "$stage/$bin" .
    done

    ( cd "$stage" && zip -q -j "$zip_path" ./* )
    rm -rf "$stage"
    echo "  -> $(basename "$zip_path")"
}

build_target linux x64 amd64
build_target linux ia32 386
build_target linux arm64 arm64
build_target freebsd x64 amd64
build_target freebsd arm64 arm64

echo ""
echo "=== Done ==="
ls -lh "$PREBUILTS"/whitelist-bypass-cli-*.zip

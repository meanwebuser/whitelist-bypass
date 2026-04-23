#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_PATH="$SCRIPT_DIR/ios-proxy-app/build/Debug-iphoneos/whitelist-bypass-proxy.app"
IPA_PATH="$SCRIPT_DIR/prebuilts/whitelist-bypass-proxy.ipa"
TEMP_DIR=$(mktemp -d)

if [ ! -d "$APP_PATH" ]; then
    echo "Error: .app not found at $APP_PATH"
    echo "Build the project in Xcode first"
    exit 1
fi

echo "Creating unsigned IPA..."

mkdir -p "$TEMP_DIR/Payload"
cp -r "$APP_PATH" "$TEMP_DIR/Payload/"

codesign --remove-signature "$TEMP_DIR/Payload/whitelist-bypass-proxy.app/whitelist-bypass-proxy" 2>/dev/null || true
find "$TEMP_DIR/Payload/whitelist-bypass-proxy.app/Frameworks" -mindepth 2 -maxdepth 2 -type f ! -name "Info.plist" -exec codesign --remove-signature {} \; 2>/dev/null || true

cd "$TEMP_DIR"
zip -r "$IPA_PATH" Payload/ -x "*.DS_Store"

rm -rf "$TEMP_DIR"

echo "Created: $IPA_PATH"
echo "Size: $(du -h "$IPA_PATH" | cut -f1)"

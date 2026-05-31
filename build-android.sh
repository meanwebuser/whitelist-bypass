#!/bin/sh
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT/android-app"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Using JAVA_HOME=$JAVA_HOME"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -d /home/roomhacker/.bubblewrap/android_sdk ]; then
    export ANDROID_HOME=/home/roomhacker/.bubblewrap/android_sdk
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
    echo "Using ANDROID_HOME=$ANDROID_HOME"
fi

if [ -f .env ]; then
    BRAND_LINE="$(grep -E '^(APP_BRAND|BRANDING|BRAND_NAME|APP_NAME)=' .env 2>/dev/null | tail -1 || true)"
    if [ -n "$BRAND_LINE" ]; then
        echo "Using Android build branding from .env: ${BRAND_LINE%%=*}=<set>"
    fi
fi

[ -f "./gradlew" ] || { echo "gradlew not found"; exit 1; }

echo "Cleaning..."
find app/build -name .DS_Store -delete 2>/dev/null || true # dont fail trying to clean last build!
./gradlew clean 2>&1 | tail -3

echo "Building release APK..."
./gradlew assembleRelease 2>&1 | tail -5

APK="app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
    mkdir -p "$ROOT/prebuilts"
    cp "$APK" "$ROOT/prebuilts/whitelist-bypass.apk"
    echo "APK ready: prebuilts/whitelist-bypass.apk ($(du -h "$ROOT/prebuilts/whitelist-bypass.apk" | cut -f1))"
else
    echo "Build failed, APK not found"
    exit 1
fi

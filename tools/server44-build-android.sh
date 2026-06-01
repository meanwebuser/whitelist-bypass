#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/server44-common.sh"
print_summary
prepare_remote
rclone_to44
ssh44 "set -euo pipefail
cd '$REMOTE_SRC'
chmod +x android-app/gradlew 2>/dev/null || true
export ANDROID_HOME=\${ANDROID_HOME:-/home/roomhacker/Android/Sdk}
export ANDROID_SDK_ROOT=\$ANDROID_HOME
printf "sdk.dir=/home/roomhacker/Android/Sdk\n" > android-app/local.properties
export PATH=/home/roomhacker/.local/go/bin:\$ANDROID_HOME/platform-tools:/usr/local/go/bin:/usr/bin:/bin:\$PATH
mkdir -p '$REMOTE_OUT'
printf 'HOST='; hostname; printf ' GO='; go version; printf ' JAVA='; java -version 2>&1 | head -n1
cd relay
go test ./tunnel ./common | tee '$REMOTE_OUT/go-test.txt'
cd ../android-app
./gradlew assembleRelease --no-daemon | tee '$REMOTE_OUT/gradle-assembleRelease.txt'
APK=app/build/outputs/apk/release/app-release.apk
sha256sum \$APK | tee '$REMOTE_OUT/app-release.apk.sha256'
cp -a \$APK '$REMOTE_OUT/app-release.apk'
if [[ -f app/libs/mobile.aar ]]; then sha256sum app/libs/mobile.aar > '$REMOTE_OUT/mobile.aar.sha256'; cp -a app/libs/mobile.aar '$REMOTE_OUT/mobile.aar'; fi
find app/src/main/jniLibs -type f -name 'librelay.so' -print0 2>/dev/null | tar --null -T - -czf '$REMOTE_OUT/jniLibs-librelay.tgz' || true
"
rclone_from44
cleanup_remote
echo "Artifacts copied to $LOCAL_OUT"

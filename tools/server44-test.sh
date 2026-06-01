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
./gradlew :app:compileReleaseKotlin --no-daemon | tee '$REMOTE_OUT/gradle-compileReleaseKotlin.txt'
"
rclone_from44
cleanup_remote
echo "Artifacts copied to $LOCAL_OUT"

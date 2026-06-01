#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOWNLOAD_DIR="${WT_DOWNLOAD_DIR:-/var/www/white-transport}"
BASE_URL="${WT_DOWNLOAD_BASE_URL:-https://download.bezrabotnyi.com/white-transport}"
APP_NAME="BEZabotny-NET"
ANDROID_OUT="$ROOT/build/server44/app-release.apk"
ANDROID_SHA_OUT="$ROOT/build/server44/app-release.apk.sha256"

require_file() {
  local p="$1"
  [[ -s "$p" ]] || { echo "ERROR: required file missing or empty: $p" >&2; exit 66; }
}

json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1], ensure_ascii=False))' "${1:-}"
}

android_version() {
  python3 - <<'PY'
from pathlib import Path
s=Path('android-app/app/build.gradle.kts').read_text()
vals={}
for name in ('versionMajor','versionMinor','versionPatch'):
    for line in s.splitlines():
        line=line.strip()
        if line.startswith(f'val {name} ='):
            vals[name]=line.split('=',1)[1].strip()
            break
print(f"{vals.get('versionMajor','0')}.{vals.get('versionMinor','0')}.{vals.get('versionPatch','0')}")
PY
}

ios_version_from_files() {
  local latest
  latest=$(ls -1t "$DOWNLOAD_DIR"/${APP_NAME}-ios-[0-9]*.ipa 2>/dev/null | head -n1 || true)
  if [[ -n "$latest" ]]; then
    basename "$latest" | sed -E "s/^${APP_NAME}-ios-([0-9.]+)\.ipa$/\1/"
  else
    echo "0.0.0"
  fi
}

validate_mobile_secrets() {
  echo "Validating iOS/Android secrets are present..."
  (cd "$ROOT" && bash scripts/generate-ios-secrets.sh >/tmp/wt-ios-secrets-validate.log)
  (cd "$ROOT/android-app" && ./gradlew --no-daemon --console=plain :app:help >/tmp/wt-android-secrets-validate.log)
}

build_android_on_server44() {
  echo "Building Android release on server-44..."
  (cd "$ROOT" && bash tools/server44-build-android.sh)
  require_file "$ANDROID_OUT"
}

publish_android() {
  local version sha size now tmp
  version="$(android_version)"
  mkdir -p "$DOWNLOAD_DIR"
  tmp="$DOWNLOAD_DIR/.${APP_NAME}.apk.$$"
  install -m 0644 "$ANDROID_OUT" "$tmp"
  if [[ -f "$DOWNLOAD_DIR/${APP_NAME}.apk" ]]; then
    cp -a "$DOWNLOAD_DIR/${APP_NAME}.apk" "$DOWNLOAD_DIR/${APP_NAME}.apk.bak.$(date +%Y%m%d_%H%M%S)"
  fi
  mv -f "$tmp" "$DOWNLOAD_DIR/${APP_NAME}.apk"
  cp -a "$DOWNLOAD_DIR/${APP_NAME}.apk" "$DOWNLOAD_DIR/${APP_NAME}-${version}.apk"
  sha=$(sha256sum "$DOWNLOAD_DIR/${APP_NAME}.apk" | awk '{print $1}')
  size=$(stat -c '%s' "$DOWNLOAD_DIR/${APP_NAME}.apk")
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '%s  %s\n' "$sha" "${APP_NAME}.apk" > "$DOWNLOAD_DIR/${APP_NAME}.apk.sha256"
  cat > "$DOWNLOAD_DIR/update.json" <<JSON
{
  "platform": "android",
  "name": "$APP_NAME",
  "version": "$version",
  "versionCode": $((1000000*${version%%.*} + 1000*$(echo "$version" | cut -d. -f2) + $(echo "$version" | cut -d. -f3))),
  "updatedAt": "$now",
  "url": "$BASE_URL/${APP_NAME}.apk",
  "sha256": "$sha",
  "size": $size,
  "mandatory": false,
  "notes": "Android release built with required embedded WTBUS/VK secrets. Empty mobile builds are blocked unless ALLOW_EMPTY_MOBILE_SECRETS=1."
}
JSON
  echo "Published Android: $BASE_URL/${APP_NAME}.apk version=$version size=$size sha256=$sha"
}

pick_ios_ipa() {
  local src=""
  if [[ -n "${WT_IOS_IPA:-}" ]]; then src="$WT_IOS_IPA"; fi
  if [[ -z "$src" && -s "$ROOT/prebuilts/whitelist-bypass-proxy.ipa" ]]; then src="$ROOT/prebuilts/whitelist-bypass-proxy.ipa"; fi
  if [[ -z "$src" && -s "$ROOT/prebuilts/whitelist-bypass-proxy-only-unsigned.ipa" ]]; then src="$ROOT/prebuilts/whitelist-bypass-proxy-only-unsigned.ipa"; fi
  if [[ -z "$src" && -s "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" ]]; then src="$DOWNLOAD_DIR/${APP_NAME}-ios.ipa"; fi
  if [[ -z "$src" ]]; then src=$(ls -1t "$DOWNLOAD_DIR"/${APP_NAME}-ios-[0-9]*.ipa 2>/dev/null | head -n1 || true); fi
  [[ -n "$src" && -s "$src" ]] || { echo "WARN: no iOS IPA found, skipping iOS publish" >&2; return 1; }
  echo "$src"
}

publish_ios_and_manifests() {
  local src version sha size now
  src=$(pick_ios_ipa) || return 0
  version="$(ios_version_from_files)"
  [[ "$version" != "0.0.0" ]] || version="$(android_version)"
  mkdir -p "$DOWNLOAD_DIR"
  if [[ "$(readlink -f "$src")" != "$(readlink -f "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" 2>/dev/null || true)" ]]; then
    install -m 0644 "$src" "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa"
  fi
  cp -a "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" "$DOWNLOAD_DIR/${APP_NAME}.ipa"
  cp -a "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" "$DOWNLOAD_DIR/whitelist-bypass-proxy-only-unsigned.ipa"
  cp -a "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" "$DOWNLOAD_DIR/${APP_NAME}-ios-${version}.ipa"
  sha=$(sha256sum "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" | awk '{print $1}')
  size=$(stat -c '%s' "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa")
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '%s  %s\n' "$sha" "${APP_NAME}-ios.ipa" > "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa.sha256"
  printf '%s  %s\n' "$sha" "${APP_NAME}.ipa" > "$DOWNLOAD_DIR/${APP_NAME}.ipa.sha256"
  cat > "$DOWNLOAD_DIR/manifest.json" <<JSON
{
  "name": "BEZаботный-NET",
  "updatedAt": "$now",
  "android": {
    "version": "$(android_version)",
    "url": "$BASE_URL/${APP_NAME}.apk",
    "sha256": "$(sha256sum "$DOWNLOAD_DIR/${APP_NAME}.apk" | awk '{print $1}')",
    "size": $(stat -c '%s' "$DOWNLOAD_DIR/${APP_NAME}.apk")
  },
  "ios": {
    "version": "$version",
    "url": "$BASE_URL/${APP_NAME}.ipa",
    "altstoreSourceURL": "$BASE_URL/altstore-source.json",
    "sha256": "$sha",
    "size": $size
  }
}
JSON
  cat > "$DOWNLOAD_DIR/altstore-source.json" <<JSON
{
  "name": "BEZаботный-NET",
  "subtitle": "iOS builds for BEZаботный-NET",
  "description": "Unsigned iOS builds for BEZаботный-NET / WhiteTransport. Mobile builds require embedded WTBUS/VK secrets by default.",
  "iconURL": "$BASE_URL/ios-icon-android-match.png",
  "headerURL": "$BASE_URL/ios-icon-android-match.png",
  "website": "https://download.bezrabotnyi.com/white-transport/",
  "tintColor": "#6D5DF6",
  "featuredApps": ["bypass.whitelist.whitelist-bypass-proxy"],
  "apps": [
    {
      "name": "BEZаботный-NET",
      "bundleIdentifier": "bypass.whitelist.whitelist-bypass-proxy",
      "developerName": "Roomhacker",
      "subtitle": "WhiteTransport iOS proxy",
      "localizedDescription": "BEZаботный-NET / WhiteTransport iOS proxy build.",
      "iconURL": "$BASE_URL/ios-icon-android-match.png",
      "tintColor": "#6D5DF6",
      "category": "utilities",
      "versions": [
        {
          "version": "$version",
          "date": "$now",
          "localizedDescription": "Latest published iOS IPA.",
          "downloadURL": "$BASE_URL/${APP_NAME}.ipa",
          "size": $size
        }
      ],
      "appPermissions": {}
    }
  ]
}
JSON
  echo "Published iOS aliases/manifests: $BASE_URL/${APP_NAME}.ipa size=$size sha256=$sha"
}

main() {
  validate_mobile_secrets
  build_android_on_server44
  publish_android
  publish_ios_and_manifests
  chmod -R a+rX "$DOWNLOAD_DIR"
  echo "Done. Files:"
  ls -lh "$DOWNLOAD_DIR/${APP_NAME}.apk" "$DOWNLOAD_DIR/${APP_NAME}.apk.sha256" "$DOWNLOAD_DIR/${APP_NAME}.ipa" "$DOWNLOAD_DIR/${APP_NAME}-ios.ipa" "$DOWNLOAD_DIR/manifest.json" "$DOWNLOAD_DIR/altstore-source.json" "$DOWNLOAD_DIR/update.json" 2>/dev/null || true
}

main "$@"

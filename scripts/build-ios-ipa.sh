#!/usr/bin/env bash
set -euo pipefail
MODE="${1:-proxy}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$ROOT/ios-proxy-app/whitelist-bypass-proxy.xcodeproj"
SCHEME="whitelist-bypass-proxy"
CONFIG="${CONFIGURATION:-Debug}"
OUTDIR="${OUTDIR:-$ROOT/dist/ios}"
mkdir -p "$OUTDIR"
case "$MODE" in
  proxy|proxy-only) MODE="proxy" ;;
  vpn|packet-tunnel|extended) MODE="vpn" ;;
  *) echo "usage: $0 [proxy|vpn]" >&2; exit 2 ;;
esac
xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration "$CONFIG" -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
APP="$(find "$HOME/Library/Developer/Xcode/DerivedData" /var/root/Library/Developer/Xcode/DerivedData -path "*/Build/Products/${CONFIG}-iphoneos/whitelist-bypass-proxy.app" -type d 2>/dev/null | head -n 1)"
if [[ -z "$APP" ]]; then echo "app product not found" >&2; exit 1; fi
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Payload"
cp -R "$APP" "$WORK/Payload/"
if [[ "$MODE" == "proxy" ]]; then
  rm -rf "$WORK/Payload/whitelist-bypass-proxy.app/PlugIns/PacketTunnel.appex"
  IPA="$OUTDIR/whitelist-bypass-proxy-only-unsigned.ipa"
else
  IPA="$OUTDIR/whitelist-bypass-proxy-packet-tunnel-unsigned.ipa"
fi
(cd "$WORK" && /usr/bin/zip -qry "$IPA" Payload)
shasum -a 256 "$IPA"
ls -lh "$IPA"

#!/bin/sh
# End-to-end test for creator --upstream-socks, on Telemost.
#
# A telemost creator JOINS an existing conference (your call link, never created
# here) and a telemost joiner joins the same conference to form the tunnel. We run
# the pair twice - once with direct egress, once with -upstream-socks - and assert
# the joiner's public egress IP CHANGES. A changed IP proves the joiner's traffic
# left through the upstream SOCKS5 (your VPN client -> VPS) instead of this host.
#
# Needs: a real upstream SOCKS5 proxy whose exit IP differs from this machine
# (an actual VPN client pointing at a VPS), plus Yandex cookies and a live call link.
#
# Usage:
#   UPSTREAM_SOCKS=127.0.0.1:1080 ./test-upstream-socks.sh <cookies-yandex.json> <tm-link>
#   UPSTREAM_SOCKS=127.0.0.1:1080 UPSTREAM_USER=u UPSTREAM_PASS=p \
#       ./test-upstream-socks.sh <cookies-yandex.json> https://telemost.yandex.ru/j/<id>

set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CREATOR="$ROOT/headless/telemost/headless-telemost-creator"
JOINER="$ROOT/headless/telemost-joiner/headless-telemost-joiner"
SOCKS_PORT=11080
IP_URL="https://api.ipify.org"
SETTLE_TIMEOUT=45
PROBE_TIMEOUT=15

if [ $# -lt 2 ]; then
    echo "Usage: UPSTREAM_SOCKS=host:port $0 <cookies-yandex.json> <tm-link>" >&2
    exit 2
fi
COOKIES="$1"
TM_LINK="$2"

if [ -z "${UPSTREAM_SOCKS:-}" ]; then
    echo "FAIL: set UPSTREAM_SOCKS=host:port (a real SOCKS5 proxy / VPN client whose exit IP differs from this host)" >&2
    exit 2
fi
[ -x "$CREATOR" ] || { echo "FAIL: missing $CREATOR (run ./build-headless.sh first)" >&2; exit 2; }
[ -x "$JOINER" ]  || { echo "FAIL: missing $JOINER (run ./build-headless.sh first)" >&2; exit 2; }
[ -f "$COOKIES" ] || { echo "FAIL: cookies not found at: $COOKIES" >&2; exit 2; }
echo "cookies=$COOKIES tm-link=$TM_LINK upstream=$UPSTREAM_SOCKS"

CREATOR_PID=""
JOINER_PID=""
kill_pair() {
    [ -n "$JOINER_PID" ] && kill "$JOINER_PID" 2>/dev/null
    [ -n "$CREATOR_PID" ] && kill "$CREATOR_PID" 2>/dev/null
    wait 2>/dev/null
    JOINER_PID=""
    CREATOR_PID=""
}
trap kill_pair EXIT INT TERM

# run_chain <label> [extra creator args...] -> prints the joiner's egress IP on stdout
run_chain() {
    label="$1"
    shift
    clog=$(mktemp -t up-c.XXXXXX.log)
    jlog=$(mktemp -t up-j.XXXXXX.log)

    "$CREATOR" -cookies "$COOKIES" -tm-link "$TM_LINK" "$@" >"$clog" 2>&1 &
    CREATOR_PID=$!

    w=0
    while [ "$w" -lt "$SETTLE_TIMEOUT" ]; do
        grep -q "media_server=" "$clog" && break
        if ! kill -0 "$CREATOR_PID" 2>/dev/null; then
            echo "FAIL[$label]: creator died before joining the conference" >&2
            tail -20 "$clog" >&2
            kill_pair
            return 1
        fi
        sleep 1
        w=$((w + 1))
    done
    if ! grep -q "media_server=" "$clog"; then
        echo "FAIL[$label]: creator did not join the conference in ${SETTLE_TIMEOUT}s" >&2
        tail -20 "$clog" >&2
        kill_pair
        return 1
    fi
    sleep 3

    "$JOINER" -tm-link "$TM_LINK" -socks-port "$SOCKS_PORT" >"$jlog" 2>&1 &
    JOINER_PID=$!

    w=0
    while [ "$w" -lt "$SETTLE_TIMEOUT" ]; do
        grep -q "TUNNEL CONNECTED" "$jlog" && break
        if ! kill -0 "$JOINER_PID" 2>/dev/null; then
            break
        fi
        sleep 1
        w=$((w + 1))
    done
    if ! grep -q "TUNNEL CONNECTED" "$jlog"; then
        echo "FAIL[$label]: joiner never reached TUNNEL CONNECTED in ${SETTLE_TIMEOUT}s" >&2
        tail -20 "$jlog" >&2
        kill_pair
        return 1
    fi

    w=0
    while [ "$w" -lt 10 ]; do
        nc -z 127.0.0.1 "$SOCKS_PORT" 2>/dev/null && break
        sleep 1
        w=$((w + 1))
    done

    ip=$(curl --socks5-hostname "127.0.0.1:$SOCKS_PORT" -m "$PROBE_TIMEOUT" -s "$IP_URL")
    kill_pair
    echo "$ip"
}

echo "=== baseline: direct egress ==="
DIRECT_IP=$(run_chain direct) || exit 1
echo "direct_ip=$DIRECT_IP"

# Let the fixed SOCKS port free up before the second pair.
sleep 2

echo "=== upstream: -upstream-socks $UPSTREAM_SOCKS ==="
set -- -upstream-socks "$UPSTREAM_SOCKS"
[ -n "${UPSTREAM_USER:-}" ] && set -- "$@" -upstream-user "$UPSTREAM_USER"
[ -n "${UPSTREAM_PASS:-}" ] && set -- "$@" -upstream-pass "$UPSTREAM_PASS"
UPSTREAM_IP=$(run_chain upstream "$@") || exit 1
echo "upstream_ip=$UPSTREAM_IP"

case "$DIRECT_IP" in
    *.*) ;;
    *)
        echo "FAIL: no direct egress IP (baseline chain broken)" >&2
        exit 1
        ;;
esac
case "$UPSTREAM_IP" in
    *.*) ;;
    *)
        echo "FAIL: no egress IP via upstream - is $UPSTREAM_SOCKS reachable and does its SOCKS5 inbound work?" >&2
        exit 1
        ;;
esac
if [ "$DIRECT_IP" = "$UPSTREAM_IP" ]; then
    echo "FAIL: egress IP unchanged ($UPSTREAM_IP) - joiner traffic did NOT go through the upstream" >&2
    exit 1
fi

echo "PASS: egress moved from $DIRECT_IP (this host) to $UPSTREAM_IP (via $UPSTREAM_SOCKS)"

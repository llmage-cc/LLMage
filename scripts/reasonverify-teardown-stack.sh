#!/usr/bin/env bash
# Teardown for the ask-reason verification stack (ports 17261/17263/8161/18261).
# PID-scoped kill only — never pattern-wide (sibling agents run their own rigs).
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Artifact names carry the Maven project version — read the single source of
# truth (<mage-version> in the root pom) instead of hardcoding it.
VERSION="$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' "$REPO/pom.xml" 2>/dev/null || true)"
[ -n "$VERSION" ] || { echo "FAIL: cannot read <mage-version> from $REPO/pom.xml" >&2; exit 1; }
STAGE=/tmp/reasonverify-stage
HOST=127.0.0.1
SERVER_PORT=17261
SECONDARY_PORT=17263
PROXY_PORT=8161
MOCK_PORT=18261

kill_pid() {
    local pid="$1" name="$2"
    [ -n "$pid" ] || return 0
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        for _ in $(seq 1 40); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
        if kill -0 "$pid" 2>/dev/null; then
            echo "WARN: $name pid $pid still alive after SIGTERM — SIGKILL" >&2
            kill -9 "$pid" 2>/dev/null
            sleep 0.5
        fi
        echo "killed $name pid $pid"
    fi
}

SPID=""; PPID_=""; MPID=""
if [ -r "$STAGE/pids.env" ]; then
    # shellcheck disable=SC1091
    . "$STAGE/pids.env"
    SPID="${SERVER_PID:-}"; PPID_="${PROXY_PID:-}"; MPID="${MOCK_PID:-}"
fi

if [ -z "$SPID" ]; then
    SPID="$(for p in $(pgrep -f 'mage.server.Main' 2>/dev/null); do
        cwd="$(readlink /proc/$p/cwd 2>/dev/null || true)"
        [ "$cwd" = "$STAGE/server" ] && echo "$p"
    done | head -1)"
fi
if [ -z "$PPID_" ]; then
    PPID_="$(for p in $(pgrep -f "mage-web-proxy-$VERSION.jar" 2>/dev/null); do
        cmd="$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null || true)"
        case "$cmd" in *"--port $PROXY_PORT"*) echo "$p" ;; esac
    done | head -1)"
fi
if [ -z "$MPID" ]; then
    MPID="$(pgrep -f "mock-llm-sideboard.py $MOCK_PORT" 2>/dev/null | head -1)"
fi

kill_pid "${SPID:-}" server
kill_pid "${PPID_:-}" proxy
kill_pid "${MPID:-}" mock

sleep 1
for p in $SERVER_PORT $SECONDARY_PORT $PROXY_PORT $MOCK_PORT; do
    if ss -tln | grep -q ":$p "; then echo "STILL LISTENING: $p" >&2; else echo "port $p free"; fi
done
if pgrep -f "mock-llm-sideboard.py $MOCK_PORT" >/dev/null 2>&1; then
    echo "WARN: mock still running" >&2
fi

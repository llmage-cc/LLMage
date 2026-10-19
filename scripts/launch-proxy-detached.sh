#!/usr/bin/env bash
# Detached web-proxy launcher — the proxy half of launch-rig-detached.sh.
#
# Why this exists: a proxy started through the terminal tool's
# background mode is a child of the owning shell session's process tree and is
# SIGTERM'd when that session is torn down (exit 143), killing the connection
# while a detached server survives. start-proxy.sh must be launched with setsid
# so the JVM re-parents to init and outlives the session.
#
# Usage: ./launch-proxy-detached.sh [--bind IP] [--port N]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Match the proxy JVM by its actual command-line name. NOTE: do NOT copy the server's
# `mage[.]server[.]Main` style here — that idiom escapes real dots; the proxy jar is
# `mage-web-proxy-*.jar` with HYPHENS, so `mage[.]web[-]proxy` matches nothing, the
# "already running" check silently always passes, and repeated launches stack multiple
# JVMs that fight over the port (measured 2026-09-17: two PIDs, 997265 + 997972).
PROXY_PAT='[m]age-web-proxy'

# Host-level proxies only: the public stack's proxy runs INSIDE the xmage-web
# container, and its JVM process is visible on the host with the same cmdline
# (same jar name, --port 8090) while living in a docker cgroup. Matching it made
# this guard false-positive ("a web proxy is already running") on any host that
# also runs the public stack, even though the HOST port was free.
host_proxies() {
    local p
    for p in $(pgrep -f "$PROXY_PAT" 2>/dev/null); do
        case "$(head -1 /proc/$p/cgroup 2>/dev/null)" in
            *docker*) continue ;;
        esac
        # Skip shells whose command line merely contains the pattern (this
        # script's own pgrep/awk invocations, shell-session wrappers).
        case "$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)" in
            *bash*) continue ;;
        esac
        echo "$p"
    done
}

HOST_PROXIES="$(host_proxies)"
if [ -n "$HOST_PROXIES" ]; then
    echo "FAIL: a web proxy is already running:" >&2
    for p in $HOST_PROXIES; do
        echo "  PID $p: $(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-160)" >&2
    done
    exit 1
fi

BIND=127.0.0.1
PORT=8090
while [ $# -gt 0 ]; do
    case "$1" in
        --bind) BIND="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

LOG=/tmp/xmage-proxy-live.log
setsid nohup "$HERE/start-proxy.sh" --bind "$BIND" --port "$PORT" \
    >> "$LOG" 2>&1 < /dev/null &
disown

for _ in $(seq 1 20); do
    sleep 1
    if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
        # Report the HOST proxy (same filter as the pre-flight guard: the public
        # stack's containerized proxy carries an identical cmdline and must not
        # be named here).
        PID="$(host_proxies | head -1)"
        echo "Web proxy detached, PID $PID (PPid $(awk '/^PPid/{print $2}' /proc/$PID/status 2>/dev/null))"
        echo "Listening on $BIND:$PORT — log: $LOG"
        exit 0
    fi
done

echo "WARN: proxy did not bind $BIND:$PORT within 20s — check $LOG" >&2
tail -5 "$LOG" >&2 || true
exit 1

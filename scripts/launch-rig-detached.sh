#!/usr/bin/env bash
# Detached XMage rig launcher — survives terminal-session teardown.
#
# Why this exists: a server started as a plain child of a shell session dies
# when that session is torn down (no shutdown trace; the proxy survived only
# because it had been detached, PPid 1). This wrapper double-forks via setsid so
# the JVM re-parents to init and outlives the session.
#
# Usage: ./launch-rig-detached.sh            (charm endpoint, both seats)
#        ./launch-rig-detached.sh --plain    (plain start-server.sh defaults)
#        ./launch-rig-detached.sh --plain --no-proxy
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# ── Host-level process filters ──────────────────────────────────────────────
#
# The public stack's xmage-server / xmage-web run INSIDE docker containers whose
# JVMs are visible on the host with the SAME cmdline as the rig's. A bare
# `pgrep -f ... | head -1` therefore can (and did) name the CONTAINER process —
# an operator acting on that PID would restart or destroy the public
# stack. This bit the success line on 2026-09-23: it reported the containerized
# server's PID while the rig server it had just started was a different PID.
# The proxy launcher grew the same filter first (2026-09-18); keep both in step.
#
# Skip: processes in a docker cgroup, and shells whose cmdline merely CONTAINS
# the pattern (this script's own pgrep/awk invocations, shell-session wrappers).
host_pids() {
    local pat="$1" p cmd
    for p in $(pgrep -f "$pat" 2>/dev/null); do
        case "$(head -1 /proc/$p/cgroup 2>/dev/null)" in
            *docker*) continue ;;
        esac
        # A PID can exit between pgrep and here (transient shells whose cmdline
        # merely matches the pattern) — reading a vanished /proc entry must not
        # fall through and emit the PID as if it were a live host process.
        cmd="$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)" || continue
        [ -n "$cmd" ] || continue
        case "$cmd" in
            *bash*) continue ;;
        esac
        echo "$p"
    done
}

SERVER_PAT='mage[.]server[.]Main'
PROXY_PAT='mage-web-proxy'

# ── Pre-flight: refuse if the rig server is already up ──
EXISTING="$(host_pids "$SERVER_PAT" | head -1)"
if [ -n "$EXISTING" ]; then
    echo "FAIL: an XMage server is already running:" >&2
    ps -o pid,args -p "$EXISTING" >&2
    exit 1
fi

LOG=/tmp/xmage-server-console.log
PROXY_LOG=/tmp/xmage-proxy-live.log
export XMAGE_CONFIG_PATH=Mage.Server/config/config.rig.xml
if [ "${1:-}" = "--plain" ]; then
    setsid nohup "$HERE/start-server.sh" >> "$LOG" 2>&1 < /dev/null &
else
    setsid nohup "$HERE/start-server-charm.sh" >> "$LOG" 2>&1 < /dev/null &
fi
disown
sleep 1
NEW_PID="$(host_pids "$SERVER_PAT" | head -1)"
if [ -n "$NEW_PID" ]; then
    echo "XMage server detached, PID $NEW_PID (PPid $(awk '/^PPid/{print $2}' /proc/$NEW_PID/status))"
    echo "Console log: $LOG"
else
    echo "WARN: java not up yet after 1s — check $LOG" >&2
    exit 1
fi

# The proxy has the same teardown problem as the server: started via the terminal
# tool's background mode it dies (SIGTERM) with the owning shell session's process
# tree, killing an in-flight connection mid-run while the server survived. Run it
# detached too, unless a HOST proxy is already listening (the containerized one
# does not hold the host port).
if [ "${2:-}" != "--no-proxy" ] && [ -z "$(host_pids "$PROXY_PAT" | head -1)" ]; then
    setsid nohup "$HERE/start-proxy.sh" --bind "${WEBPROXY_BIND:-127.0.0.1}" --port "${WEBPROXY_PORT:-8090}" \
        >> "$PROXY_LOG" 2>&1 < /dev/null &
    disown
    sleep 4
    PROXY_PID="$(host_pids "$PROXY_PAT" | head -1)"
    if [ -n "$PROXY_PID" ]; then
        echo "Web proxy detached, PID $PROXY_PID (PPid $(awk '/^PPid/{print $2}' /proc/$PROXY_PID/status))"
        echo "Proxy log: $PROXY_LOG"
    else
        echo "WARN: web proxy not up after 4s — check $PROXY_LOG" >&2
    fi
fi

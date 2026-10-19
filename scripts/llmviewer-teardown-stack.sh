#!/usr/bin/env bash
# llmviewer-teardown-stack — stop the throwaway replay-viewer stack.
# PID-scoped (never pattern-wide): reads /tmp/llmviewer-stage/pids.env.
set -uo pipefail
STAGE=/tmp/llmviewer-stage
[ -r "$STAGE/pids.env" ] || { echo "no pids.env at $STAGE — nothing recorded"; exit 0; }
# shellcheck disable=SC1090
. "$STAGE/pids.env"
for name in SERVER_PID PROXY_PID MOCK_PID; do
    pid="${!name:-}"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null && echo "killed $name=$pid"
    else
        echo "$name not running (${pid:-unset})"
    fi
done
sleep 1
for name in SERVER_PID PROXY_PID MOCK_PID; do
    pid="${!name:-}"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && { kill -9 "$pid" 2>/dev/null; echo "SIGKILL $name=$pid"; }
done
echo "teardown done"

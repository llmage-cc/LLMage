#!/usr/bin/env bash
# Full automated pipeline: start server (if needed), run bot game, analyze postgame.
# Usage: ./pipeline.sh
# Exit: 0 = no bugs found, 1 = potential bugs found, 2 = pipeline error
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LOG_FILE="pipeline.log"
echo "=== Pipeline start: $(date) ===" | tee -a "$LOG_FILE"

# ── 1. Ensure server is running ────────────────────────────────────────
if ! pgrep -f "mage.server.Main" > /dev/null; then
    echo "[$(date)] Starting XMage server..." | tee -a "$LOG_FILE"
    "$ROOT/scripts/start-server.sh" &
    SERVER_PID=$!
    # Wait for server to be ready (up to 10 minutes for card loading).
    # mageserver.log only exists when the server's own log4j config
    # wins the root logger (a stray mage-web-proxy-*.jar in release/lib/ shadows
    # it with rootLogger=INFO,stdout → no file). If the file never appears while
    # the server is up, that is an explicit failure — do not spin 20 minutes
    # grepping a file that does not exist and then blame the server.
    READY=0
    for i in $(seq 1 120); do
        if [ ! -f mageserver.log ] && [ "$i" -ge 6 ]; then
            echo "[$(date)] ERROR: mageserver.log absent ~60s after server start." | tee -a "$LOG_FILE"
            echo "        Live server log: /tmp/xmage-server-console.log (launcher stdout redirect)." | tee -a "$LOG_FILE"
            echo "        Check for a stray mage-web-proxy-*.jar in Mage.Server/release/lib/ —" | tee -a "$LOG_FILE"
            echo "        its log4j.properties shadows the server's config (boot line '- log appender:')." | tee -a "$LOG_FILE"
            exit 2
        fi
        if grep -q "Started MAGE server" mageserver.log 2>/dev/null; then
            echo "[$(date)] Server ready after ${i}0s" | tee -a "$LOG_FILE"
            READY=1
            break
        fi
        sleep 10
    done
    if [ "$READY" -ne 1 ]; then
        echo "[$(date)] ERROR: Server failed to start (no 'Started MAGE server' in mageserver.log within 20 min)" | tee -a "$LOG_FILE"
        exit 2
    fi
else
    echo "[$(date)] Server already running" | tee -a "$LOG_FILE"
    if [ ! -f mageserver.log ]; then
        echo "[$(date)] ERROR: server is running but mageserver.log is MISSING." | tee -a "$LOG_FILE"
        echo "        Live server log: /tmp/xmage-server-console.log (launcher stdout redirect)." | tee -a "$LOG_FILE"
        echo "        Check for a stray mage-web-proxy-*.jar in Mage.Server/release/lib/ —" | tee -a "$LOG_FILE"
        echo "        its log4j.properties shadows the server's config (boot line '- log appender:')." | tee -a "$LOG_FILE"
        exit 2
    fi
fi

# ── 2. Run bot game ────────────────────────────────────────────────────
echo "[$(date)] Launching bot game..." | tee -a "$LOG_FILE"
./scripts/run-bot-game.sh --timeout-minutes 45 2>&1 | tee -a "$LOG_FILE"
GAME_EXIT=${PIPESTATUS[0]}
echo "[$(date)] Bot game exit code: $GAME_EXIT" | tee -a "$LOG_FILE"

if [ "$GAME_EXIT" -ne 0 ]; then
    echo "[$(date)] ERROR: Bot game failed (exit $GAME_EXIT)" | tee -a "$LOG_FILE"
    exit 2
fi

# ── 3. Analyze postgame ────────────────────────────────────────────────
echo "[$(date)] Analyzing postgame reviews..." | tee -a "$LOG_FILE"
python3 "$ROOT/scripts/analyze-postgame.py" 2>&1 | tee -a "$LOG_FILE"
ANALYSIS_EXIT=${PIPESTATUS[0]}
echo "[$(date)] Analysis exit code: $ANALYSIS_EXIT" | tee -a "$LOG_FILE"

echo "=== Pipeline end: $(date) ===" | tee -a "$LOG_FILE"
exit $ANALYSIS_EXIT

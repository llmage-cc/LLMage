#!/usr/bin/env bash
# Background watcher for 3 parallel Bo3 bot matches.
# Polls every 300s; exits 0 when all BotGameRunner processes are gone.
# Logs stalls (transcript mtime > 15min while runner alive) and new error lines.
# Logs/logs dirs live at the repo root — resolve relative to this script so the
# watcher works from any checkout location.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
STATE=/tmp/monitor-watch.state
LOG=/tmp/monitor-watch.log
echo "watcher started $(date)" > "$LOG"
echo "0" > "$STATE"   # last mageserver.log line count processed

# ── mageserver.log presence (proxy-jar guard) ─────────────────────────────────
# Fail loud if the file is missing while runners are live: the server only
# writes it when ITS log4j config wins (a stray mage-web-proxy-*.jar in
# release/lib/ shadows it with rootLogger=INFO,stdout). Silent fallbacks would
# let the watcher report a clean run from a file that does not exist.
SERVER_LOG_PRESENT=1
if [ ! -f mageserver.log ]; then
    SERVER_LOG_PRESENT=0
    echo "ERROR: mageserver.log is MISSING while the server is running." >> "$LOG"
    echo "       The live server log is /tmp/xmage-server-console.log (launcher stdout redirect)." >> "$LOG"
    echo "       Check for a stray mage-web-proxy-*.jar in Mage.Server/release/lib/ —" >> "$LOG"
    echo "       its log4j.properties shadows the server's config." >> "$LOG"
fi

while true; do
    RUNNERS=$(pgrep -f BotGameRunner | wc -l)
    NOW=$(date '+%F %T')
    echo "--- $NOW runners=$RUNNERS ---" >> "$LOG"

    if [ "$RUNNERS" -eq 0 ]; then
        echo "ALL RUNNERS EXITED at $NOW" >> "$LOG"
        exit 0
    fi

    # Stall check per game dir
    for d in gamelogs/game_*/; do
        [ -d "$d" ] || continue
        GID=$(basename "$d")
        LATEST=$(ls -t "$d"/*transcript*.txt 2>/dev/null | head -1)
        if [ -n "$LATEST" ]; then
            AGE=$(( ($(date +%s) - $(stat --format='%Y' "$LATEST")) / 60 ))
            SIZE=$(stat --format='%s' "$LATEST")
            echo "  $GID: newest=${LATEST##*/} age=${AGE}min size=${SIZE}" >> "$LOG"
            if [ "$AGE" -ge 15 ]; then
                echo "  *** STALL? $GID transcript age ${AGE}min >= 15min while runners alive" >> "$LOG"
            fi
        fi
    done

    # New error lines in mageserver.log since last check (skip entirely + warn
    # once per pass when the file is absent — grep of a missing file is a
    # silent empty set, which the previous version reported as "no errors").
    if [ "$SERVER_LOG_PRESENT" -eq 1 ]; then
        LAST=$(cat "$STATE")
        TOTAL=$(wc -l < mageserver.log)
        if [ "$TOTAL" -gt "$LAST" ]; then
            tail -n +$((LAST+1)) mageserver.log | grep -E "FATAL|LLM returned empty answer|invalid action index|Too many errors|priorityErrorsCount|game will be end" >> "$LOG" 2>/dev/null
            echo "$TOTAL" > "$STATE"
        fi
    else
        echo "  (error scanning SKIPPED — mageserver.log absent; live log: /tmp/xmage-server-console.log)" >> "$LOG"
    fi

    sleep 300
done

#!/usr/bin/env bash
# Monitor running bot games — check for stalls, loops, and progress.
# Usage: ./monitor-games.sh
# Run in a loop or as a one-shot check.
cd "$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Game Monitor: $(date) ==="

# Check server is running
if ! pgrep -f "mage.server.Main" > /dev/null; then
    echo "⚠ XMage server is NOT running!"
    exit 1
fi

# Check BotGameRunners
RUNNERS=$(ps aux | grep BotGameRunner | grep -v grep | wc -l)
echo "BotGameRunners active: $RUNNERS"

# ── mageserver.log presence (proxy-jar guard) ────────────────────────────────
# The server only writes mageserver.log when ITS log4j config wins the root
# logger. If a stray mage-web-proxy-*.jar sits in Mage.Server/release/lib/ the
# proxy's rootLogger=INFO,stdout config shadows it and NO file is written —
# greps below would report false zeros. Fail loud; name the live console log
# (the rig launcher redirects JVM stdout there).
if [ ! -f mageserver.log ]; then
    echo "ERROR: mageserver.log is MISSING while the server is running." >&2
    echo "       The live server log is /tmp/xmage-server-console.log (launcher stdout redirect)." >&2
    echo "       Check for a stray mage-web-proxy-*.jar in Mage.Server/release/lib/ —" >&2
    echo "       its log4j.properties shadows the server's config." >&2
    echo "       (Also check the boot line '- log appender:' in the console log.)" >&2
    exit 3
fi

# Check for FATAL/error patterns in recent log (file presence proven above —
# no `|| echo 0` fallbacks: grep -c already prints 0 on no match, and the
# fallback masked the missing-file case that the guard above now catches).
FATAL_COUNT=$(grep -c "FATAL" mageserver.log)
RESTORE_COUNT=$(grep -c "Auto-restored" mageserver.log)
INVALID_COUNT=$(grep -c "invalid action index" mageserver.log)
HEALTH_COUNT=$(grep -c "Removing unhealthy" mageserver.log)
echo "Errors: FATAL=$FATAL_COUNT AutoRestored=$RESTORE_COUNT InvalidAction=$INVALID_COUNT HealthRemovals=$HEALTH_COUNT"

# Check each game directory
for gamedir in gamelogs/game_*/; do
    [ -d "$gamedir" ] || continue
    GAME_ID=$(basename "$gamedir" | sed 's/game_//')
    
    # Get transcript files
    latest_transcript=$(ls -t "$gamedir"/*transcript*.txt 2>/dev/null | head -1)
    postgame_count=$(ls "$gamedir"/*postgame*.txt 2>/dev/null | wc -l)
    
    # Get latest transcript modification time
    if [ -n "$latest_transcript" ]; then
        last_modified=$(stat --format='%Y' "$latest_transcript" 2>/dev/null || echo 0)
        now=$(date +%s)
        age_seconds=$((now - last_modified))
        age_minutes=$((age_seconds / 60))
        
        # Get file size
        size=$(stat --format='%s' "$latest_transcript" 2>/dev/null || echo 0)
        size_kb=$((size / 1024))
        
        # Get latest turn from transcript
        latest_turn=$(grep -oP "Turn \d+" "$latest_transcript" 2>/dev/null | tail -1 | sed 's/Turn //')
        
        echo "Game $GAME_ID: turn=$latest_turn transcript=${size_kb}KB last_modified=${age_minutes}min ago postgames=$postgame_count"
        
        # Warn if transcript hasn't been modified in 15+ minutes
        if [ $age_minutes -ge 15 ]; then
            echo "  ⚠ Game $GAME_ID may be STALLED — no transcript update in ${age_minutes} minutes"
        fi
    fi
done

# Check latest LLM request
last_request=$(grep "LLM request body" mageserver.log 2>/dev/null | tail -1)
if [ -n "$last_request" ]; then
    echo "Latest LLM request: $last_request"
fi

echo "=== Monitor complete ==="
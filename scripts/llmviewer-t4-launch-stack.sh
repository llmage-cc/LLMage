#!/usr/bin/env bash
# llmviewer-launch-stack — throwaway XMage stack for the replay-viewer legs
# (T1/T2/T3 legs). Two COMPUTER_LLM seats pointed at the committed strict mock
# (scripts/mock-llm-sideboard.py), which makes the game deterministic and gives
# us the exact between-games section texts to grep in the replay wire frames.
#
# Reserved ports:
#   server 127.0.0.1:17281 (secondary 17283)
#   proxy  127.0.0.1:8181
#   mock   127.0.0.1:18281 (seat 0/1 both dial this; 18283 spare)
#
# Everything runs from /tmp/llmviewer-t4; the repo is only READ (jars, web
# root, decks) except for the saved/ dir the server writes (kept in the stage).
#
# Usage: bash scripts/llmviewer-launch-stack.sh
# Teardown: bash scripts/llmviewer-teardown-stack.sh
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Artifact names carry the Maven project version — read the single source of
# truth (<mage-version> in the root pom) instead of hardcoding it.
VERSION="$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' "$REPO/pom.xml" 2>/dev/null || true)"
[ -n "$VERSION" ] || { echo "FAIL: cannot read <mage-version> from $REPO/pom.xml" >&2; exit 1; }
STAGE=/tmp/llmviewer-t4
HOST=127.0.0.1
SERVER_PORT=17291
SECONDARY_PORT=17293
PROXY_PORT=8191
MOCK_PORT=18301
MOCK_PORT2=18303
# Warm card DB (pre-scanned). The first boot after a fresh build may rescan the
# card table (~40s); the launcher waits for that.
DB_SRC="$REPO/db/cards.h2.mv.db"

PLUGIN_JAR="$REPO/Mage.Server/release/plugins/mage-player-llm-$VERSION.jar"
PROXY_JAR="$REPO/Mage.WebProxy/target/mage-web-proxy-$VERSION.jar"
WEB_ROOT="$REPO/Mage.WebProxy/web"

for f in "$PLUGIN_JAR" "$PROXY_JAR" "$DB_SRC"; do
    [ -r "$f" ] || { echo "FAIL: missing $f" >&2; exit 1; }
done
[ -d "$WEB_ROOT/frontend" ] || { echo "FAIL: frontend not built ($WEB_ROOT/frontend)" >&2; exit 1; }

echo "=== STAGE ASSEMBLY ==="
for p in $SERVER_PORT $SECONDARY_PORT $PROXY_PORT $MOCK_PORT $MOCK_PORT2; do
    if ss -tln | grep -q ":$p "; then
        echo "FAIL: port $p in use (teardown first)" >&2; exit 1
    fi
done

rm -rf "$STAGE"
mkdir -p "$STAGE/server/db" "$STAGE/server/saved" "$STAGE/webproxy/db" "$STAGE/gamelogs" "$STAGE/evidence"

# ── server: release dir + stage ports ──
cp -r "$REPO/Mage.Server/release/." "$STAGE/server/"
cp "$REPO/Mage.Server/config/config.xml" "$STAGE/server/config/config.xml"
sed -i -e "s/port=\"17171\"/port=\"$SERVER_PORT\"/" \
       -e "s/secondaryBindPort=\"17179\"/secondaryBindPort=\"$SECONDARY_PORT\"/" \
       -e "s/serverAddress=\"127.0.0.1\"/serverAddress=\"$HOST\"/" \
    "$STAGE/server/config/config.xml"
grep -q "port=\"$SERVER_PORT\"" "$STAGE/server/config/config.xml" \
    || { echo "FAIL: server port sed did not apply" >&2; exit 1; }
grep -q "secondaryBindPort=\"$SECONDARY_PORT\"" "$STAGE/server/config/config.xml" \
    || { echo "FAIL: secondary port sed did not apply" >&2; exit 1; }

# plugins/ must hold UNVERSIONED symlinks (PluginUtil resolves CWD-relative
# plugins/<jar> by config.xml jar= names) — bootstrap-plugins.sh equivalent.
(
  cd "$STAGE/server" && mkdir -p plugins
  for jar in lib/mage-player-*.jar lib/mage-game-*.jar lib/mage-tournament-*.jar \
             lib/mage-deck-*.jar plugins/mage-player-*.jar; do
      [ -e "$jar" ] || continue
      base="$(basename "$jar")"
      name="$(printf '%s' "$base" | sed -E 's/-[0-9][0-9.]*\.jar$//; s/\.jar$//')"
      rm -f "plugins/$name.jar"
      ln -s "../$jar" "plugins/$name.jar"
  done
)
dangling=0
for link in "$STAGE"/server/plugins/*.jar; do
    [ -L "$link" ] || continue
    [ -e "$link" ] || { echo "DANGLING: $link -> $(readlink "$link")" >&2; dangling=$((dangling+1)); }
done
[ "$dangling" = 0 ] || { echo "FAIL: $dangling dangling plugin symlinks" >&2; exit 1; }
echo "plugins/: $(ls "$STAGE"/server/plugins/*.jar | wc -l) links materialized, all resolve"

# ── cards DB: BOTH server and proxy (CardsDbGuard refuses an empty proxy DB) ──
cp "$DB_SRC" "$STAGE/server/db/cards.h2.mv.db"
cp "$DB_SRC" "$STAGE/webproxy/db/cards.h2.mv.db"

# ── proxy stage: jar + deps + web root + saved/ (replay dir) ──
cp "$PROXY_JAR" "$STAGE/webproxy/"
cp -r "$REPO/Mage.WebProxy/target/lib" "$STAGE/webproxy/lib"
# Jetty 12 rejects a SYMLINKED docroot as an alias → HTTP 404 on /. Copy for real.
rm -rf "$STAGE/webproxy/web"
cp -r "$WEB_ROOT" "$STAGE/webproxy/web"
ln -sfn "$STAGE/server/saved" "$STAGE/webproxy/saved"
# Replay file listing is read from `-Dwebproxy.replayDir` (default "saved")
# relative to the PROXY's CWD; GameReplay.loadGame resolves `saved/<uuid>.game`
# relative to the SERVER's CWD. Different CWDs here -> symlink them together.
ln -sfn "$STAGE/server/saved" "$STAGE/webproxy/saved"
[ -d "$STAGE/webproxy/web/frontend" ] || { echo "FAIL: no web/frontend in proxy stage" >&2; exit 1; }

# ── hostile mock (two ports, one process) ──
# MOCK_REASON_SHAPES=h2 makes the FIRST attempt at each in-game decision carry a
# REASON label on line 1 instead of the bare answer (format-invalid); the mock
# answers correction prompts properly — exactly the T4 format-correction path.
rm -f /tmp/xmage-scratch/mock-strict-$MOCK_PORT.log
MOCK_REASON_SHAPES="${MOCK_REASON_SHAPES:-h2}" \
setsid nohup python3 "$REPO/scripts/mock-llm-sideboard.py" "$MOCK_PORT" "$MOCK_PORT2" \
    > "$STAGE/mock.log" 2>&1 < /dev/null &
MOCK_PID=$!
sleep 1
curl -s -m 5 "http://$HOST:$MOCK_PORT/v1/models" | grep -q stub-model \
    || { echo "FAIL: mock not up"; tail "$STAGE/mock.log" >&2; exit 1; }
echo "mock up on $MOCK_PORT/$MOCK_PORT2 (pid $MOCK_PID)"

# ── server ──
( cd "$STAGE/server" && setsid nohup java -Xmx4g \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.text=ALL-UNNAMED \
    --add-opens java.base/java.net=ALL-UNNAMED \
    --add-opens java.base/java.security=ALL-UNNAMED \
    --add-opens java.base/sun.security.action=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/sun.reflect=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.access=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED \
    -cp "lib/*:plugins/*" \
    -Dxmage.config.path=config/config.xml \
    -Dxmage.llm.model="stub-model" \
    -Dxmage.llm.endpoint="http://$HOST:$MOCK_PORT/v1/chat/completions" \
    -Dxmage.llm.reasoningeffort="default" \
    -Dxmage.llm.thinking_dialect="effort" \
    -Dxmage.llm.skip_thinking_probe=true \
    -Dxmage.llm.personality_enabled=false \
    -Dxmage.llm.transcript_dir="$STAGE/gamelogs" \
    -Dxmage.llm.verbose="true" \
    -Dxmage.llm.model_context_window=131072 \
    -Dxmage.llm2.model="stub-model" \
    -Dxmage.llm2.endpoint="http://$HOST:$MOCK_PORT2/v1/chat/completions" \
    -Dxmage.llm2.reasoningeffort="default" \
    -Dxmage.llm2.thinking_dialect="effort" \
    -Dxmage.llm2.skip_thinking_probe=true \
    -Dxmage.llm2.personality_enabled=false \
    -Dxmage.llm2.transcript_dir="$STAGE/gamelogs" \
    -Dxmage.llm2.verbose="true" \
    -Dxmage.llm2.model_context_window=131072 \
    mage.server.Main >> "$STAGE/server.log" 2>&1 < /dev/null & disown )
echo "server starting (log: $STAGE/server.log)…"
SERVER_UP=0
for i in $(seq 1 300); do
    grep -q 'Started MAGE server' "$STAGE/server.log" 2>/dev/null && { SERVER_UP=1; echo "SERVER UP after ${i}s"; break; }
    grep -qi 'Address already in use' "$STAGE/server.log" 2>/dev/null && \
        { echo "FAIL: server port conflict"; tail -25 "$STAGE/server.log" >&2; exit 1; }
    sleep 1
done
[ "$SERVER_UP" = 1 ] || { echo "FAIL: server not up in 300s"; tail -30 "$STAGE/server.log" >&2; exit 1; }

BAD="$(grep -c "Can.t load plugin" "$STAGE/server.log" 2>/dev/null || true)"
if [ "${BAD:-0}" != "0" ]; then
    echo "FAIL: $BAD plugin load errors on server boot" >&2
    grep -m5 "Can.t load plugin" "$STAGE/server.log" >&2
    exit 1
fi
echo "server boot: 0 plugin load errors"

# ── proxy ──
( cd "$STAGE/webproxy" && setsid nohup java \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.text=ALL-UNNAMED \
    --add-opens java.base/java.net=ALL-UNNAMED \
    --add-opens java.base/java.security=ALL-UNNAMED \
    --add-opens java.base/sun.security.action=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/sun.reflect=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.access=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED \
    -Djava.net.preferIPv4Stack=true \
    -jar mage-web-proxy-$VERSION.jar --bind "$HOST" --port "$PROXY_PORT" \
    >> "$STAGE/proxy.log" 2>&1 < /dev/null & disown )
echo "proxy starting (log: $STAGE/proxy.log)…"
PROXY_UP=0
for i in $(seq 1 60); do
    if (exec 3<>"/dev/tcp/$HOST/$PROXY_PORT") 2>/dev/null; then PROXY_UP=1; echo "PROXY UP after ${i}s"; break; fi
    sleep 1
done
[ "$PROXY_UP" = 1 ] || { echo "FAIL: proxy not accepting"; tail -30 "$STAGE/proxy.log" >&2; exit 1; }
sleep 2
pgrep -f "mage-web-proxy-$VERSION.jar" >/dev/null 2>&1 \
    || { echo "FAIL: proxy exited after start"; tail -30 "$STAGE/proxy.log" >&2; exit 1; }

# ── record PIDs for teardown (cwd-scoped, never pattern-wide) ──
SERVER_PID="$(for p in $(pgrep -f 'mage.server.Main' 2>/dev/null); do
    cwd="$(readlink /proc/$p/cwd 2>/dev/null || true)"
    [ "$cwd" = "$STAGE/server" ] && echo "$p"
done | head -1)"
PROXY_PID="$(for p in $(pgrep -f "mage-web-proxy-$VERSION.jar" 2>/dev/null); do
    cmd="$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null || true)"
    case "$cmd" in *"--port $PROXY_PORT"*) echo "$p" ;; esac
done | head -1)"
{
    echo "SERVER_PID=${SERVER_PID:-}"
    echo "PROXY_PID=${PROXY_PID:-}"
    echo "MOCK_PID=${MOCK_PID:-}"
    echo "SERVER_PORT=$SERVER_PORT"
    echo "PROXY_PORT=$PROXY_PORT"
    echo "MOCK_PORT=$MOCK_PORT"
} > "$STAGE/pids.env"

echo
echo "STAGE READY: server $HOST:$SERVER_PORT | proxy $HOST:$PROXY_PORT | mock $HOST:$MOCK_PORT"
echo "gamelogs: $STAGE/gamelogs   saved: $STAGE/server/saved"
